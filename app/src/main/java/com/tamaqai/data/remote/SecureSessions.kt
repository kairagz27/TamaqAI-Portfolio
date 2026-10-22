package com.tamaqai.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SessionIdentity(val owner:String,val hasAccess:Boolean,val hasRefresh:Boolean,val hasGuest:Boolean,val pendingLink:Boolean)

/** Session material only: passwords never enter storage, Room, saved state or logs. */
@android.annotation.SuppressLint("UseKtx") // Session writes must be durable and checked before any request.
class SecureSessions(context: Context, private val origin: String) {
    private val prefs = context.getSharedPreferences("encrypted_sessions", Context.MODE_PRIVATE)
    private val alias = context.packageName + ".session.aes"
    private val slot = java.security.MessageDigest.getInstance("SHA-256").digest(origin.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun slotFor(address:String)=java.security.MessageDigest.getInstance("SHA-256").digest(address.toByteArray()).joinToString("") {"%02x".format(it)}
    private val key: SecretKey
        get() = synchronized(KEY_LOCK) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
        }
    private var value: JSONObject = read()
    private fun identity(data:JSONObject)=SessionIdentity(data.optString("user").ifBlank {"guest"},
        data.optString("access").isNotBlank(),data.optString("refresh").isNotBlank(),data.optString("guest").isNotBlank(),data.optString("link_user").isNotBlank())
    private val sessionChanges=kotlinx.coroutines.flow.MutableStateFlow(identity(value))
    val changes=sessionChanges.asStateFlow()
    init {
        val old = context.getSharedPreferences("backend_guest",Context.MODE_PRIVATE)
        old.all.forEach { (address,raw) ->
            if(raw is String) {
                if(address==origin) {
                    if (guestToken.isBlank()) change { put("guest",raw) }
                    check(guestToken==raw)
                } else {
                    val target=slotFor(address);val migrated=read(target)
                    if(migrated.optString("guest").isBlank()) persist(migrated.put("guest",raw),target)
                    check(read(target).optString("guest")==raw)
                }
                check(old.edit().remove(address).commit())
            }
        }
    }
    private fun read(target:String=slot): JSONObject {
        val stored=prefs.getString(target,null) ?: return JSONObject()
        // A lost keystore key is surfaced; do not silently discard identity or diary ownership.
        val packed=Base64.decode(stored,Base64.NO_WRAP)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,packed.copyOfRange(0,12)))
        return JSONObject(String(cipher.doFinal(packed.copyOfRange(12,packed.size)),Charsets.UTF_8))
    }
    @Synchronized fun snapshot() = JSONObject(value.toString())
    @Synchronized fun change(block: JSONObject.() -> Unit) {
        val next=JSONObject(value.toString()).apply(block)
        persist(next,slot)
        value=next
        sessionChanges.value=identity(next)
    }
    private fun persist(next:JSONObject,target:String) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key) }
        val payload=cipher.iv+cipher.doFinal(next.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(target,Base64.encodeToString(payload,Base64.NO_WRAP)).commit())
    }
    val owner get()=snapshot().optString("user").ifBlank { "guest" }
    val guestToken get()=snapshot().optString("guest")
    val access get()=snapshot().optString("access")
    val refresh get()=snapshot().optString("refresh")
    val expires get()=snapshot().optLong("expires")
    fun login(response: JSONObject) = change {
        val incoming=response.getString("user_id")
        // An unresolved linkage belongs to one authenticated account; keep it for recovery.
        put("user",incoming); put("access",response.getString("access_token")); put("refresh",response.getString("refresh_token"))
        put("expires",System.currentTimeMillis()/1000+response.getLong("expires_in"))
    }
    fun expire() = change { remove("access"); remove("refresh"); remove("expires") }
    fun logout() = change { remove("access"); remove("refresh"); remove("expires"); remove("user") }
    private companion object {val KEY_LOCK=Any()}
}
