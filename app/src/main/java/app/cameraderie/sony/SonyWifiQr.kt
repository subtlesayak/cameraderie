package app.cameraderie.sony

/** Transient QR credentials. Never log raw QR text, credentials or this payload. */
class SonyWifiQr private constructor(val ssid: String,val password: String,val cameraName:String?=null) {
    override fun toString()="Sony camera Wi-Fi credentials [redacted]"
    companion object {
        fun parse(raw: String): SonyWifiQr? {
            if(raw.length !in 1..2048) return null
            if(raw.startsWith("W01:")) {
                if(!raw.endsWith(";")) return null
                val fields=fields(raw.substring(4)) ?: return null
                if(fields.keys!=setOf("S","P","C","M")) return null
                val prefix=fields.getValue("S");val model=fields.getValue("C")
                if(!Regex("[A-Za-z0-9]{4}").matches(prefix) || SonyCameraIdentity.label(model)!=model) return null
                if(!Regex("[A-Fa-f0-9]{12}").matches(fields.getValue("M"))) return null
                // Sony QrDataUtil.getSsid: DIRECT- + prefix + colon + friendly name.
                // M is structurally checked but is not retained or used as trust.
                return credentials("DIRECT-$prefix:$model",fields.getValue("P"),model)
            }
            if(!raw.startsWith("WIFI:") || !raw.endsWith(";;")) return null
            val fields=fields(raw.substring(5)) ?: return null
            if(fields.keys.any { it !in setOf("S","P","T","H") }) return null
            if(fields["T"] !in setOf("WPA","WPA2") || fields["H"]?.lowercase() !in setOf(null,"false")) return null
            val ssid=fields["S"] ?: return null;val pass=fields["P"] ?: return null
            return credentials(ssid,pass)
        }
        private fun credentials(ssid: String,pass: String,cameraName:String?=null): SonyWifiQr? {
            if(!ssid.startsWith("DIRECT-") || ssid.toByteArray(Charsets.UTF_8).size !in 8..32 || ssid.any { it.isISOControl() }) return null
            if(pass.length !in 8..63 || pass.any { it.code !in 32..126 }) return null
            return SonyWifiQr(ssid,pass,cameraName ?: SonyCameraIdentity.expectedFromSsid(ssid))
        }
        private fun fields(body: String): Map<String,String>? {
            val fields=mutableMapOf<String,String>()
            var escaped=false;val field=StringBuilder()
            for(c in body) {
                if(escaped) { field.append(c);escaped=false }
                else if(c=='\\') escaped=true
                else if(c==';') {
                    if(field.isNotEmpty()) {
                        val colon=field.indexOf(":");if(colon<=0) return null
                        val key=field.substring(0,colon);if(key in fields) return null
                        fields[key]=field.substring(colon+1);field.clear()
                    }
                } else field.append(c)
            }
            return if(escaped || field.isNotEmpty()) null else fields
        }
    }
}
