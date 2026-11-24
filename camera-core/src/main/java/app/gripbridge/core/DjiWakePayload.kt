package app.gripbridge.core

/** DJI's documented FF manufacturer AD: ASCII WKP followed by target MAC in reverse order. */
object DjiWakePayload {
    const val manufacturerId = 0x4B57 // Android emits the two-byte company field little endian: W K.
    fun manufacturerData(address: String): ByteArray {
        val target = requireNotNull(CameraBookmark.create(address,"")) { "Invalid saved camera address" }
        val reversed = target.address.split(':').map { it.toInt(16).toByte() }.reversed().toByteArray()
        return byteArrayOf(0x50) + reversed
    }
}