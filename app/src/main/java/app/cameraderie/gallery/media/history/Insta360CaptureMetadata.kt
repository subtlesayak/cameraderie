package app.cameraderie.gallery.media.history

import app.cameraderie.gallery.media.CameraMetadataProto
import app.cameraderie.sony.CameraCaptureTime
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object Insta360CaptureMetadata {
    private const val FOOTER_BYTES = 78
    private const val RECORD_HEADER_BYTES = 6
    private const val MAKER_NOTES = 0x101
    private const val MAX_MAKER_NOTES = 64 * 1024
    private const val MAX_RECORDS = 64
    private val magic = "8db42d694ccc418790edff439fe026bf".toByteArray(Charsets.US_ASCII)

    // Native INSV and INSP share a trailer. Seek past gyro, GPS, and preview data.
    fun read(file: File): ImportCaptureMetadata? = runCatching {
        val notes = RandomAccessFile(file, "r").use(::readNotes) ?: return@runCatching null
        val fields = CameraMetadataProto(notes)
        val model = fields.bytes(2)?.toString(Charsets.UTF_8)?.trim()
            ?.takeIf { it.length in 1..100 && it.all { char -> char in ' '..'~' } }
        // The date field uses a decimal camera clock, YYYYMMDDhhmmss, without an offset.
        val time = fields.number(7)?.toString()?.takeIf { it.length == 14 }
            ?.let { CameraCaptureTime.parse(it.take(8) + "T" + it.drop(8)) }
        if (time == null && model == null) null
        else ImportCaptureMetadata(time, time?.let { "INSTA360_TRAILER" }, cameraModel = model)
    }.getOrNull()

    private fun readNotes(input: RandomAccessFile): ByteArray? {
        val end = input.length()
        var position = end - FOOTER_BYTES
        if (position < 0) return null
        val footer = ByteArray(FOOTER_BYTES)
        input.seek(position)
        input.readFully(footer)
        if (!magic.indices.all { footer[FOOTER_BYTES - magic.size + it] == magic[it] }) return null
        val trailerSize = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN).getInt(38).toLong() and 0xffff_ffffL
        if (trailerSize !in FOOTER_BYTES.toLong()..end) return null
        val trailerStart = end - trailerSize
        val record = ByteBuffer.allocate(RECORD_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        repeat(MAX_RECORDS) {
            if (position < trailerStart) return null
            input.seek(position)
            input.readFully(record.array())
            val id = record.getShort(0).toInt() and 0xffff
            val size = record.getInt(2).toLong() and 0xffff_ffffL
            if (size > position - trailerStart) return null
            val start = position - size
            if (id == MAKER_NOTES) {
                if (size !in 1..MAX_MAKER_NOTES.toLong()) return null
                input.seek(start)
                return ByteArray(size.toInt()).also(input::readFully)
            }
            position = start - RECORD_HEADER_BYTES
        }
        return null
    }
}
