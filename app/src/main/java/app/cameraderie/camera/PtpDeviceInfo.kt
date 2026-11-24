package app.cameraderie.camera

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class PtpDeviceInfo(val operations: Set<Int>, val manufacturer: String, val model: String, val version: String) {
    companion object {
        fun parse(data: ByteArray): PtpDeviceInfo {
            val values = PtpDataset(data)
            values.skip(8)
            values.string()
            values.skip(2)
            val operations = values.u16Array().toSet()
            repeat(4) { values.skipU16Array() }
            return PtpDeviceInfo(operations, values.string(), values.string(), values.string()).also {
                values.string() // Serial number is not needed by the app.
            }
        }
    }
}

internal class PtpDataset(data: ByteArray) {
    private val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    private fun need(size: Int) {
        if (size < 0 || buffer.remaining() < size) throw IOException("Truncated camera metadata.")
    }

    val remaining: Int get() = buffer.remaining()

    fun u8(): Int {
        need(1)
        return buffer.get().toInt() and 255
    }

    fun u16(): Int {
        need(2)
        return buffer.short.toInt() and 65535
    }

    fun u32(): Long {
        need(4)
        return buffer.int.toLong() and 0xFFFFFFFFL
    }

    fun skip(size: Int) {
        need(size)
        buffer.position(buffer.position() + size)
    }

    fun optionalString(): String? = if (buffer.hasRemaining()) string().takeIf { it.isNotBlank() } else null

    fun string(): String {
        val count = u8()
        if (count == 0) return ""
        need(count * 2)
        val raw = ByteArray(count * 2)
        buffer.get(raw)
        if (raw[raw.size - 1] != 0.toByte() || raw[raw.size - 2] != 0.toByte()) {
            throw IOException("Unterminated camera metadata string.")
        }
        return String(raw, 0, raw.size - 2, Charsets.UTF_16LE)
    }

    private fun u16Count(): Int {
        val count = u32()
        if (count > 65536) throw IOException("Invalid camera capability list.")
        need(count.toInt() * 2)
        return count.toInt()
    }

    fun u16Array(): List<Int> = List(u16Count()) { u16() }

    fun skipU16Array() = skip(u16Count() * 2)
}
