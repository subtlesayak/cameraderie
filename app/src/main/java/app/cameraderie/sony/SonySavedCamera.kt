package app.cameraderie.sony

import java.util.UUID

/** Only validated camera Wi-Fi details are remembered; this is separate from BLE bonds. */
internal class SonySavedCamera(val id:String,val model:String,val ssid:String,val password:String,val lastConnected:Long) {
    init {
        UUID.fromString(id)
        require(SonyCameraIdentity.label(model)==model && ssid.startsWith("DIRECT-",true) && ssid.toByteArray(Charsets.UTF_8).size<=32 && password.length in 8..63 && lastConnected>0)
    }
    override fun toString()="SonySavedCamera(id=<local>,model=<redacted>,ssid=<redacted>,password=<redacted>)"
}
