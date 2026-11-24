package app.gripbridge.core

/** 0000 response: uint16 result, 16-byte product, then device-supplied version text. */
data class DjiFirmwareInfo(val product: String?, val sdk: String?, val firmware: String?, val reportedName: String?, val versionText: String, val rawTailHex: String) {
    companion object {
        fun parse(bytes: ByteArray): DjiFirmwareInfo? {
            if(bytes.size !in 18..1000 || DjiProtocol.u16(bytes,0) != 0) return null
            fun ascii(part: ByteArray): String? {
                val value = part.takeWhile { it != 0.toByte() }
                if(value.any { (it.toInt() and 255) !in 32..126 }) return null
                return value.toByteArray().toString(Charsets.US_ASCII).trim().takeIf { it.isNotEmpty() }
            }
            val product = ascii(bytes.copyOfRange(2,18))
            val tail = bytes.copyOfRange(18,bytes.size)
            val parts = tail.toString(Charsets.ISO_8859_1).split('\u0000').filter { it.isNotBlank() }
            val safe = tail.map { b -> val c = b.toInt() and 255; if(c in 32..126) c.toChar() else ' ' }.joinToString("").trim().take(256)
            val sdk = Regex("(?i)SDK[- _]?[Vv]?[0-9]+(?:[.][0-9]+)*").find(safe)?.value
            val firmware = Regex("(?<![0-9.])[0-9]{1,3}(?:[.][0-9]{1,3}){3}(?![0-9.])").find(safe)?.value
            // Only name unambiguously separated fields. Unspecified/binary layouts stay visible as diagnostics.
            val name = if(parts.size == 3 && parts[0].startsWith("SDK",true) && parts[2].trim() == firmware)
                parts[1].takeIf { it.all { c -> c.code in 32..126 } }?.trim()?.take(64) else null
            return DjiFirmwareInfo(product,sdk,firmware,name,safe,tail.take(96).toByteArray().hex())
        }
    }
}
