package app.cameraderie.gallery.media

/** Reads scalar fields from a bounded camera metadata packet. */
internal class CameraMetadataProto(private val data: ByteArray) {
    private data class Field(val start: Int, val end: Int, val number: Long?)

    fun bytes(field: Int): ByteArray? = find(field, 2)?.let { data.copyOfRange(it.start, it.end) }

    fun number(field: Int): Long? = find(field, 0)?.number

    private fun find(field: Int, kind: Int): Field? {
        var offset = 0
        fun varint(): Long? {
            var value = 0L
            for (shift in 0..63 step 7) {
                if (offset >= data.size) return null
                val byte = data[offset++].toInt() and 255
                if (shift == 63 && byte > 1) return null
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value
            }
            return null
        }
        while (offset < data.size) {
            val tag = varint()?.takeIf { it in 8..0xffff_ffffL } ?: return null
            val wire = (tag and 7).toInt()
            val number = if (wire == 0) varint() ?: return null else null
            val size = when (wire) {
                0 -> 0L
                1 -> 8L
                2 -> varint() ?: return null
                5 -> 4L
                else -> return null
            }
            if (size !in 0..(data.size - offset).toLong()) return null
            val end = offset + size.toInt()
            if (wire == kind && tag ushr 3 == field.toLong()) return Field(offset, end, number)
            offset = end
        }
        return null
    }
}
