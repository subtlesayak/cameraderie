package app.gripbridge.core

/** Independently implemented DJI R SDK wire format. See docs/RESEARCH.md. */
object DjiProtocol {
    data class Frame(val type: Int, val sequence: Int, val set: Int, val id: Int, val payload: ByteArray) {
        val response get() = type and 0x20 != 0
    }
    fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 255) or ((b[i + 1].toInt() and 255) shl 8)
    fun u32(b: ByteArray, i: Int): Long = (0..3).fold(0L) { n, j -> n or ((b[i+j].toLong() and 255) shl (8*j)) }
    fun put(b: ByteArray, i: Int, value: Long, size: Int) { repeat(size) { b[i+it] = (value ushr (8*it)).toByte() } }
    fun crc(bytes: ByteArray, count: Int, bits: Int): Long {
        var value = 0x3AA3L
        val polynomial = if (bits == 16) 0xA001L else 0xEDB88320L
        repeat(count) { index ->
            value = value xor (bytes[index].toLong() and 255)
            repeat(8) { value = (value ushr 1) xor (if (value and 1L != 0L) polynomial else 0L) }
        }
        return value
    }
    fun encode(f: Frame): ByteArray {
        require(f.payload.size <= 1005)
        require(f.type and 0xC0 == 0 && f.sequence in 0..65535 && f.set in 0..255 && f.id in 0..255)
        val b = ByteArray(18 + f.payload.size)
        b[0] = 0xAA.toByte(); put(b, 1, b.size.toLong(), 2); b[3] = f.type.toByte()
        put(b, 8, f.sequence.toLong(), 2); put(b, 10, crc(b, 10, 16), 2)
        b[12] = f.set.toByte(); b[13] = f.id.toByte(); f.payload.copyInto(b, 14)
        put(b, b.size-4, crc(b, b.size-4, 32), 4)
        return b
    }
    fun decode(b: ByteArray): Frame? {
        if (b.size !in 18..1023 || b[0] != 0xAA.toByte()) return null
        if (u16(b, 1) != b.size || b[4] != 0.toByte() || (b[3].toInt() and 0xC0) != 0) return null
        if (u16(b, 10).toLong() != crc(b, 10, 16) || u32(b, b.size-4) != crc(b, b.size-4, 32)) return null
        return Frame(b[3].toInt() and 255, u16(b, 8), b[12].toInt() and 255, b[13].toInt() and 255, b.copyOfRange(14, b.size-4))
    }
    fun connection(identity: Long, address: ByteArray, verify: Int, code: Int): ByteArray {
        require(address.size == 6 && verify in 0..2 && code in 0..65535)
        return ByteArray(33).also { put(it, 0, identity, 4); it[4] = 6; address.copyInto(it, 5); it[26] = verify.toByte(); put(it,27,code.toLong(),2) }
    }
    fun authReply(identity: Long): ByteArray = ByteArray(9).also { put(it,0,identity,4) }
    fun mode(device: Long, mode: Int): ByteArray {
        require(mode in setOf(1,5)) { "Only verified Photo/Video mode requests are enabled" }
        return ByteArray(9).also { put(it,0,device,4); it[4] = mode.toByte() }
    }
    fun record(device: Long, start: Boolean): ByteArray = ByteArray(9).also { put(it,0,device,4); it[4] = if (start) 0 else 1 }
}

/** Bounded reassembler; validates header CRC before trusting length, rescans after CRC failure. */
class DjiFramer(private val onError: (String) -> Unit = {}) {
    private var buffer = byteArrayOf()
    fun reset() { buffer = byteArrayOf() }
    fun accept(input: ByteArray): List<DjiProtocol.Frame> {
        val frames = mutableListOf<DjiProtocol.Frame>()
        // Android notifications are at most MTU-3; bound hostile inputs too.
        input.asList().chunked(512).forEach { chunk ->
            buffer += chunk.toByteArray()
            while (buffer.isNotEmpty()) {
                if (buffer[0] != 0xAA.toByte()) { buffer = buffer.drop(1).toByteArray(); continue }
                if (buffer.size < 12) break
                val len = DjiProtocol.u16(buffer, 1)
                if (len !in 18..1023 || DjiProtocol.u16(buffer,10).toLong() != DjiProtocol.crc(buffer,10,16)) {
                    onError("Invalid DJI header/CRC16"); buffer = buffer.drop(1).toByteArray(); continue
                }
                if (buffer.size < len) break
                val frame = DjiProtocol.decode(buffer.copyOfRange(0,len))
                if (frame == null) { onError("Invalid DJI frame/CRC32"); buffer = buffer.drop(1).toByteArray() }
                else { frames += frame; buffer = buffer.drop(len).toByteArray() }
            }
        }
        return frames
    }
}
