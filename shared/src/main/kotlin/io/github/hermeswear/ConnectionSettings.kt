package io.github.hermeswear

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.net.URL

/** Device credentials are encrypted with a non-exportable Android Keystore key. */
class ConnectionSettings(private val context: Context) {
    private val prefs=context.getSharedPreferences("hermes_connection",Context.MODE_PRIVATE)
    companion object { private val KEY_LOCK=Any();const val DEFAULT_URL="https://relay.example.com/hermes-wear" }
    private fun key():SecretKey = synchronized(KEY_LOCK) {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("hermes-relay",null) as? SecretKey)?.let { return@synchronized it }
        val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder("hermes-relay",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        generator.generateKey()
    }
    var url:String
        get()=prefs.getString("url",DEFAULT_URL) ?: DEFAULT_URL
        set(value){require(validUrl(value));check(prefs.edit().putString("url",value.trimEnd('/')).commit())}
    var token:String
        get() {
            val stored=prefs.getString("credential",null) ?: return ""
            return try {
                val bits=stored.split(":")
                val cipher=Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(bits[0],Base64.NO_WRAP)))
                String(cipher.doFinal(Base64.decode(bits[1],Base64.NO_WRAP)),Charsets.UTF_8)
            }catch(_:Exception){""} // Keystore reset: visibly unpaired, never fall back to plaintext.
        }
        set(value) {
            require(value.length in 24..512)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,key())
            val stored=Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(value.toByteArray()),Base64.NO_WRAP)
            check(prefs.edit().putString("credential",stored).commit())
        }
    fun validUrl(value:String):Boolean=try {
        val u=URL(value)
        u.protocol=="https" && u.userInfo==null && u.host.isNotBlank() && u.query==null && u.ref==null &&
            (u.path.isEmpty() || u.path=="/" || u.path=="/hermes-wear")
    }catch(_:Exception){false}
    fun configured()=validUrl(url) && token.length>=24
}
