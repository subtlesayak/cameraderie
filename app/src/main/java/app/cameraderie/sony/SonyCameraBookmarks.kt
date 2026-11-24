package app.cameraderie.sony

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM ciphertext in app-private preferences; key stays in Android Keystore. */
internal class SonyCameraBookmarks(context:Context,private val namespace:String="sony_saved_wifi") {
    init { require(namespace in setOf("sony_saved_wifi","sony_saved_wifi_native_test")) }
    private val prefs=context.getSharedPreferences(namespace,Context.MODE_PRIVATE)
    private val alias="app.cameraderie.$namespace.v1"
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized fun list():List<SonySavedCamera> = prefs.all.map { (id,raw) ->
        val parts=(raw as? String ?: error("Saved camera is unreadable")).split(':')
        check(parts.size==2)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
        cipher.updateAAD(namespace.toByteArray(Charsets.UTF_8))
        val json=JSONObject(String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8))
        SonySavedCamera(id,json.getString("model"),json.getString("ssid"),json.getString("password"),json.getLong("connected"))
    }.sortedByDescending { it.lastConnected }
    @Synchronized fun remember(model:String,ssid:String,password:String):SonySavedCamera {
        val existing=list()
        val previous=existing.firstOrNull { it.ssid==ssid && it.model==model }
        check(previous!=null || existing.size<10) { "Saved camera limit reached" }
        val camera=SonySavedCamera(previous?.id ?: java.util.UUID.randomUUID().toString(),model,ssid,password,System.currentTimeMillis())
        val json=JSONObject().put("model",model).put("ssid",ssid).put("password",password).put("connected",camera.lastConnected).toString()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(namespace.toByteArray(Charsets.UTF_8))
        val encrypted=cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        val value=Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(encrypted,Base64.NO_WRAP)
        check(prefs.edit().putString(camera.id,value).commit()) { "Saved camera could not be stored" }
        return camera
    }
    @Synchronized fun forget(id:String) { java.util.UUID.fromString(id);check(prefs.edit().remove(id).commit()) }
    internal fun clearTestKey() {
        check(namespace=="sony_saved_wifi_native_test")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(alias) }
    }
}
