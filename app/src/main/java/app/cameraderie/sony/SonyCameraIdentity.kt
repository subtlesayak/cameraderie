package app.cameraderie.sony

import java.util.Locale

/** Model-independent Sony media identity checks; capability support is probed by each transport.
 * A vendor description is not cryptographic identity or a hardware-acceptance claim. */
internal object SonyCameraIdentity {
    fun label(value:String?):String?=value?.trim()?.takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size<=80 && it.none(Char::isISOControl) }
    fun sony(manufacturer:String)=manufacturer.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit) in
        setOf("sony","sonycorporation","sonycorporationinc","sonyimagingproductsandsolutionsinc")
    fun modelCode(value:String?)=label(value)?.takeIf { Regex("[A-Z0-9]{2,12}-[A-Z0-9][A-Z0-9._ -]{0,48}").matches(it.uppercase(Locale.ROOT)) }?.uppercase(Locale.ROOT)
    fun expectedFromSsid(ssid:String)=modelCode(ssid.substringAfter(':',""))
    fun matchesExpected(actual:String,expected:String?):Boolean {
        val required=modelCode(expected) ?: return true // A custom Wi-Fi name is not a model ID.
        return modelCode(actual)==required
    }
    fun ptp(manufacturer:String,model:String,expected:String?):String? =
        label(model)?.takeIf { sony(manufacturer) && matchesExpected(it,expected) }
    fun description(manufacturer:String,name:String,number:String,friendly:String,type:String,expected:String?):String? {
        if(!sony(manufacturer)) return null
        val codes=listOfNotNull(modelCode(name),modelCode(number)).distinct()
        if(codes.size>1) return null
        val actual=codes.singleOrNull() ?: label(friendly)?.takeIf {
            name=="SonyImagingDevice" && type=="urn:schemas-upnp-org:device:MediaServer:1"
        } ?: return null
        return actual.takeIf { matchesExpected(it,expected) }
    }
}
