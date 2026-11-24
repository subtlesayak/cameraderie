package app.cameraderie.core

fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it.toInt() and 255) }
fun hexBytes(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
