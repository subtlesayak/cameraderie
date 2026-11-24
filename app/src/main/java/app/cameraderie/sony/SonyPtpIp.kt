package app.cameraderie.sony

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Original object metadata returned by GetObjectInfo. No thumbnail conversion is performed. */
data class SonyPtpObject(val handle: Long, val storageId: Long, val format: Int, val size: Long, val name: String, val parent: Long, val captureDate:String?=null) {
    val isFolder get() = format == 0x3001
    val isRaw get() = name.endsWith(".arw", ignoreCase = true)
}

/**
 * Read-only standard PTP/IP media session. Sockets must already be connected and
 * bound to the selected camera network. All methods except close run on one worker.
 * Sony-specific control initialization is intentionally not guessed: cameras that
 * do not offer the standard object operations return an explicit failure.
 */
class SonyPtpIp(private val command: Socket, private val events: Socket,private val expectedModel:String?=null) : Closeable {
    private val input = command.getInputStream()
    private val output = command.getOutputStream()
    @Volatile private var closed = false
    @Volatile private var opened = false
    val isOpen get()=opened && !closed
    private var transaction = 1L
    private var operations = emptySet<Int>()
    var manufacturer: String = ""; private set
    var model: String = ""; private set

    fun open(friendlyName: String = "Cameraderie", uuid: UUID = UUID.randomUUID()) {
        check(!opened && !closed) { "Camera session is already open or closed" }
        require(friendlyName.length in 1..39 && !friendlyName.contains('\u0000'))
        try {
            val name = friendlyName.toByteArray(Charsets.UTF_16LE)
            send(output, 1, bytes(16 + name.size + 2 + 4).apply {
                putLong(uuid.mostSignificantBits); putLong(uuid.leastSignificantBits)
                put(name); putShort(0); putInt(0x10000)
            }.array())
            val ack = control(input, 2)
            if (ack.size < 26 || (ack.size - 24) % 2 != 0) fail("Malformed PTP command acknowledgment")
            if (little(ack.copyOfRange(ack.size-4,ack.size)).int ushr 16 != 1 || ack[ack.size-6] != 0.toByte() || ack[ack.size-5] != 0.toByte()) fail("Unsupported PTP connection version or name")
            val connection = little(ack).int
            send(events.getOutputStream(), 3, bytes(4).putInt(connection).array())
            if (control(events.getInputStream(), 4).isNotEmpty()) fail("Malformed PTP event acknowledgment")
            // Drain event/probe traffic independently during long original-file transfers.
            events.soTimeout = 0
            Thread({
                try {
                    val stream = events.getInputStream()
                    while (!closed) {
                        val header = readHeader(stream)
                        when (header.type) {
                            13 -> {
                                if (header.bodySize != 0) fail("Malformed PTP event probe")
                                send(events.getOutputStream(), 14, byteArrayOf())
                            }
                            8 -> {
                                if (header.bodySize !in 6..18 || (header.bodySize - 6) % 4 != 0) fail("Malformed PTP event")
                                readExact(stream, header.bodySize)
                            }
                            else -> fail("Unexpected PTP event packet")
                        }
                    }
                } catch (_: Exception) { if (!closed) close() }
            }, "Cameraderie-Sony-events").apply { isDaemon = true; start() }
            val info = collect(0x1001, emptyList(), 0)
            parseDeviceInfo(info)
            model=SonyCameraIdentity.ptp(manufacturer,model,expectedModel)
                ?: fail("Selected device is not a matching Sony camera")
            val required = setOf(0x1004, 0x1007, 0x1008, 0x1009)
            if (!operations.containsAll(required)) fail("Camera does not expose original-file browsing over this PTP session")
            transact(0x1002, listOf(1), 0, null, null, { false }, {})
            opened = true
        } catch (error: Exception) { close(); throw error }
    }

    fun storageIds(): List<Long> = guarded { checkOpen(); u32Array(collect(0x1004)) }

    fun handles(storageId: Long): List<Long> = guarded {
        checkOpen(); requireU32(storageId)
        u32Array(collect(0x1007, listOf(storageId, 0, 0)))
    }

    fun objectInfo(handle: Long): SonyPtpObject = guarded {
        checkOpen(); requireU32(handle)
        val r = Dataset(collect(0x1008, listOf(handle)))
        val storage = r.u32(); val format = r.u16(); r.u16(); val size = r.u32()
        r.skip(26); val parent = r.u32(); r.skip(10)
        val name = r.string()
        if (name.isBlank() || name.length > 255 || name.contains('\u0000')) fail("Camera returned an invalid object name")
        SonyPtpObject(handle, storage, format, size, name, parent,r.optionalString())
    }

    /** Writes original bytes and returns only after exact length and OK response validation. */
    fun download(handle: Long, output: OutputStream, expected: Long, cancelled: () -> Boolean, progress: (Long) -> Unit): Long = guarded {
        checkOpen(); requireU32(handle)
        if (expected < 0 || expected >= 0xFFFFFFFFL) fail("This object needs unsupported extended-size metadata")
        transact(0x1009, listOf(handle), nextTransaction(), output, expected, cancelled, progress)
    }

    private fun parseDeviceInfo(data: ByteArray) {
        val r = Dataset(data)
        r.u16(); r.u32(); r.u16(); r.string(); r.u16()
        operations = r.u16Array().toSet()
        repeat(4) { r.u16Array() }
        manufacturer = r.string(); model = r.string(); r.string(); r.string()
    }

    private fun collect(code: Int, params: List<Long> = emptyList(), id: Long = nextTransaction()): ByteArray {
        val result = ByteArrayOutputStream()
        transact(code, params, id, result, null, { false }, {})
        return result.toByteArray()
    }

    private fun transact(code: Int, params: List<Long>, id: Long, sink: OutputStream?, expected: Long?, cancelled: () -> Boolean, progress: (Long) -> Unit): Long {
        ensureActive(cancelled)
        require(params.size <= 5); params.forEach(::requireU32)
        // 1 means no data or incoming data, as in the inspected app's requester.
        send(output, 6, bytes(10 + params.size * 4).apply { putInt(1); putShort(code.toShort()); putInt(id.toInt()); params.forEach { putInt(it.toInt()) } }.array())
        var started = false
        var ended = false
        var announced = -1L
        var received = 0L
        var frames = 0
        while (true) {
            ensureActive(cancelled)
            if (++frames > MAX_FRAMES) fail("Too many PTP packets")
            val header = readHeader(input)
            when (header.type) {
                9 -> {
                    if (sink == null || started || ended || header.bodySize != 12) fail("Unexpected PTP data start")
                    val b = little(readExact(input, 12)); checkTransaction(b.int, id)
                    announced = b.long
                    if (announced < 0 || (expected != null && announced != expected)) fail("Camera object length does not match metadata")
                    if (expected == null && announced > MAX_METADATA) fail("Camera metadata is too large")
                    started = true
                }
                10, 12 -> {
                    if (!started || ended || sink == null || header.bodySize < 4) fail("Unexpected PTP data packet")
                    checkTransaction(little(readExact(input, 4)).int, id)
                    val count = header.bodySize - 4
                    if (received > announced - count) fail("Camera sent more bytes than announced")
                    val buffer = ByteArray(minOf(count, 64 * 1024))
                    var remaining = count
                    while (remaining > 0) {
                        ensureActive(cancelled)
                        val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (n < 0) throw EOFException("Camera file ended early")
                        if (n == 0) continue
                        sink.write(buffer, 0, n); remaining -= n; received += n
                        progress(received)
                    }
                    if (header.type == 12) {
                        if (received != announced) fail("Camera file ended before its declared length")
                        ended = true
                    }
                }
                7 -> {
                    if (header.bodySize !in 6..26 || (header.bodySize - 6) % 4 != 0) fail("Malformed PTP operation response")
                    val response = little(readExact(input, header.bodySize))
                    val responseCode = response.short.toInt() and 65535; checkTransaction(response.int, id)
                    if (responseCode != 0x2001) fail("Camera rejected PTP operation 0x${code.toString(16)} (0x${responseCode.toString(16)}). Use the camera's transfer mode or a card reader.")
                    if (sink != null && (!started || !ended)) fail("Camera omitted the original-file data phase")
                    if (sink == null && started) fail("Unexpected camera data")
                    return received
                }
                13 -> { if (header.bodySize != 0) fail("Malformed PTP probe"); send(output, 14, byteArrayOf()) }
                else -> fail("Unexpected PTP packet type ${header.type}")
            }
        }
    }

    private fun nextTransaction(): Long = transaction++.also { if (it > 0xFFFFFFFFL) fail("PTP transaction limit reached; reconnect") }
    private fun checkOpen() { if (!opened || closed) fail("Camera session is not connected") }
    private fun ensureActive(cancelled: () -> Boolean) {
        if (closed || Thread.currentThread().isInterrupted || cancelled()) throw InterruptedIOException("Camera transfer cancelled")
    }
    private fun checkTransaction(value: Int, expected: Long) { if ((value.toLong() and 0xFFFFFFFFL) != expected) fail("PTP transaction mismatch") }
    private fun requireU32(value: Long) { require(value in 0..0xFFFFFFFFL) }
    private inline fun <T> guarded(action: () -> T): T = try { action() } catch (error: Exception) { close(); throw error }
    override fun close() { closed = true; opened = false; runCatching { command.close() }; runCatching { events.close() } }

    private data class Header(val type: Int, val bodySize: Int)
    private fun readHeader(stream: InputStream): Header {
        val b = little(readExact(stream, 8)); val length = b.int.toLong() and 0xFFFFFFFFL; val type = b.int
        if (length !in 8..MAX_FRAME.toLong()) fail("Invalid PTP packet length")
        return Header(type, length.toInt() - 8)
    }
    private fun control(stream: InputStream, type: Int): ByteArray {
        val h = readHeader(stream)
        if (h.type == 5) fail("Camera declined the PTP connection; open transfer mode and approve the phone")
        if (h.type != type || h.bodySize > 1024) fail("Unexpected PTP connection response")
        return readExact(stream, h.bodySize)
    }
    private fun send(stream: OutputStream, type: Int, body: ByteArray) {
        stream.write(bytes(8).putInt(body.size + 8).putInt(type).array()); stream.write(body); stream.flush()
    }
    private fun u32Array(data: ByteArray): List<Long> {
        val r = Dataset(data); val count = r.u32()
        if (count > 100000 || data.size.toLong() != 4 + count * 4) fail("Invalid camera object list")
        return List(count.toInt()) { r.u32() }
    }
    private class Dataset(data: ByteArray) {
        private val buffer = little(data)
        private fun need(n: Int) { if (n < 0 || buffer.remaining() < n) fail("Truncated PTP metadata") }
        fun u16(): Int { need(2); return buffer.short.toInt() and 65535 }
        fun u32(): Long { need(4); return buffer.int.toLong() and 0xFFFFFFFFL }
        fun skip(n: Int) { need(n); buffer.position(buffer.position() + n) }
        fun optionalString():String?=if(buffer.hasRemaining()) string().takeIf { it.isNotBlank() } else null
        fun string(): String {
            need(1); val count = buffer.get().toInt() and 255
            if (count == 0) return ""
            need(count * 2); val raw = ByteArray(count * 2); buffer.get(raw)
            if (raw[raw.size-1] != 0.toByte() || raw[raw.size-2] != 0.toByte()) fail("Unterminated PTP string")
            return String(raw, 0, raw.size-2, Charsets.UTF_16LE)
        }
        fun u16Array(): List<Int> {
            val count = u32(); if (count > 65536) fail("Invalid PTP capability list")
            return List(count.toInt()) { u16() }
        }
    }
    companion object {
        private const val MAX_FRAME = 64 * 1024 * 1024
        private const val MAX_METADATA = 8 * 1024 * 1024
        private const val MAX_FRAMES = 1000000
        private fun bytes(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        private fun little(data: ByteArray) = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        private fun fail(message: String): Nothing = throw IOException(message)
        private fun readExact(input: InputStream, count: Int): ByteArray {
            val result = ByteArray(count); var done = 0
            while (done < count) {
                val n = input.read(result, done, count-done)
                if (n < 0) throw EOFException("Camera connection ended early")
                if (n == 0) continue
                done += n
            }
            return result
        }
    }
}
