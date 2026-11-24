package app.cameraderie.gallery.protocol

import java.util.UUID

/** Separate from Android bonds and the R-SDK identity. Never include in diagnostics. */
class GalleryIdentity private constructor(private val identifier: String) {
    internal fun bytes() = identifier.toByteArray(Charsets.US_ASCII)
    override fun toString() = "GalleryIdentity(redacted)"
    interface Store {
        fun read(): String?
        /** Return only after durable local persistence succeeds. */
        fun save(value: String): Boolean
    }
    companion object {
        @Synchronized fun load(store: Store, mint: () -> String = { UUID.randomUUID().toString().replace("-","") }): GalleryIdentity {
            val saved=store.read()
            if(saved != null) {
                check(saved.matches(Regex("[0-9a-fA-F]{32}"))) { "Gallery pairing identity is invalid; keep it for recovery" }
                return GalleryIdentity(saved)
            }
            val value=mint()
            check(value.matches(Regex("[0-9a-fA-F]{32}"))) { "Cannot create gallery pairing identity" }
            check(store.save(value)) { "Cannot save gallery pairing identity" }
            return GalleryIdentity(value)
        }
    }
}

/** Secret-bearing values are transient and never have a generated data-class toString. */
class CameraWifiCredentials private constructor(private val ssid: String, private val password: String) {
    fun <T> use(block: (ssid: String, password: String) -> T): T = block(ssid,password)
    override fun toString() = "CameraWifiCredentials(redacted)"
    companion object {
        internal fun create(ssid: String, password: String): CameraWifiCredentials {
            require(ssid.toByteArray(Charsets.UTF_8).size in 1..32 && ssid.none { it == '\u0000' }) { "Invalid camera network name" }
            // WifiNetworkSpecifier's WPA2 API requires a printable ASCII passphrase, 8..63 bytes.
            // Raw 64-character PSKs require another API and are intentionally not silently reinterpreted.
            require(password.length in 8..63 && password.all { it.code in 32..126 }) { "Invalid camera Wi-Fi passphrase" }
            return CameraWifiCredentials(ssid,password)
        }
    }
}
