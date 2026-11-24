package app.cameraderie.camera

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One command, optional inbound data, and a response on a claimed USB still-image interface. */
internal class UsbPtpTransport(
    private val write: (ByteArray) -> Int,
    private val read: (ByteArray, Int) -> Int,
    packetSize: Int,
) {
    private val input = ByteArray(16 * 1024)
    private val packetSize = packetSize.also { require(it in HEADER_SIZE..input.size) }
    private var nextTransaction = 1
    private var sessionOpen = false

    data class Response(val code: Int, val data: ByteArray?) {
        fun requireOk(operation: Int): Response {
            if (code != 0x2001) throw IOException("Camera rejected operation 0x${operation.toString(16)} (0x${code.toString(16)}).")
            return this
        }
    }

    fun open() {
        check(!sessionOpen)
        command(OPEN_SESSION, 1).requireOk(OPEN_SESSION)
        sessionOpen = true
    }

    fun close() {
        if (!sessionOpen) return
        try {
            command(CLOSE_SESSION).requireOk(CLOSE_SESSION)
        } finally {
            sessionOpen = false
        }
    }

    fun command(operation: Int, vararg params: Int, maxData: Int = 0): Response {
        require(params.size <= 5 && maxData >= 0)
        check(sessionOpen || operation == GET_DEVICE_INFO || operation == OPEN_SESSION)
        val transaction = if (sessionOpen) nextTransaction++ else 0
        val length = HEADER_SIZE + params.size * 4
        val request = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(length).putShort(1).putShort(operation.toShort()).putInt(transaction)
        for (param in params) request.putInt(param)
        if (write(request.array()) != length) throw IOException("USB camera did not receive the complete request.")

        val first = readContainer(transaction, maxData)
        if (first.type == RESPONSE) return Response(first.code, null)
        if (first.type != DATA || first.code != operation) throw IOException("Unexpected USB camera data.")
        val response = readContainer(transaction, 0)
        if (response.type != RESPONSE) throw IOException("USB camera omitted the operation response.")
        return Response(response.code, first.payload)
    }

    private data class Container(val type: Int, val code: Int, val payload: ByteArray?)

    private fun readContainer(transaction: Int, maxData: Int): Container {
        var received = read(input, packetSize)
        // A full-sized final USB packet can be followed by one terminating zero-length packet.
        if (received == 0) received = read(input, packetSize)
        if (received !in HEADER_SIZE..packetSize) throw IOException("USB camera timed out or sent an incomplete header.")
        val header = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
        val length = header.int
        val type = header.short.toInt() and 0xffff
        val code = header.short.toInt() and 0xffff
        if (header.int != transaction || length < received) throw IOException("Invalid USB camera transaction.")
        val remainingLimit = when (type) {
            DATA -> maxData.takeIf { it > 0 } ?: throw IOException("Unexpected USB camera data phase.")
            RESPONSE -> 20
            else -> throw IOException("Unexpected USB camera packet.")
        }
        val size = length - HEADER_SIZE
        if (size !in 0..remainingLimit || (type == RESPONSE && size % 4 != 0)) {
            throw IOException("Invalid USB camera packet length.")
        }
        val payload = ByteArray(size)
        input.copyInto(payload, startIndex = HEADER_SIZE, endIndex = received)
        var copied = received - HEADER_SIZE
        while (copied < size) {
            val wanted = minOf(input.size, size - copied)
            received = read(input, wanted)
            if (received !in 1..wanted) throw IOException("USB camera data ended early.")
            input.copyInto(payload, destinationOffset = copied, endIndex = received)
            copied += received
        }
        return Container(type, code, payload.takeIf { type == DATA })
    }

    companion object {
        const val GET_DEVICE_INFO = 0x1001
        private const val OPEN_SESSION = 0x1002
        private const val CLOSE_SESSION = 0x1003
        private const val HEADER_SIZE = 12
        private const val DATA = 2
        private const val RESPONSE = 3
    }
}
