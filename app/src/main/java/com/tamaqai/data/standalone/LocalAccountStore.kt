package com.tamaqai.data.standalone

import android.content.Context
import com.tamaqai.data.contracts.AccountStore
import com.tamaqai.data.remote.AccountProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Local profile, never an authenticated server identity. No session or network client is created. */
@android.annotation.SuppressLint("UseKtx")
class LocalAccountStore(context: Context, private val owner: String) : AccountStore {
    private val prefs = context.getSharedPreferences("profile_$owner", Context.MODE_PRIVATE)
    private val avatar = File(context.filesDir, "standalone_avatar.jpg")
    private val lock = Mutex()
    private fun read() = AccountProfile(
        name = prefs.getString("name", "") ?: "",
        height = prefs.getString("height", "") ?: "",
        currentWeight = prefs.getString("current", "") ?: "",
        targetWeight = prefs.getString("target", "") ?: "",
        avatarVersion = prefs.getString("avatar", "") ?: "",
        avatarFile = avatar.takeIf(File::isFile)?.absolutePath ?: "",
    )
    private val changes = MutableStateFlow(read())
    override val profileState = changes.asStateFlow()
    override val deliveryFailed = false
    override fun localProfile() = changes.value
    override suspend fun load(): AccountProfile = changes.value

    private fun persist(value: AccountProfile): AccountProfile {
        val local = value.copy(email = "", phone = "", verified = false, phoneVerified = false, pilotLinked = false)
        check(prefs.edit().putString("name", local.name).putString("height", local.height)
            .putString("current", local.currentWeight).putString("target", local.targetWeight)
            .putString("avatar", local.avatarVersion).commit())
        changes.value = local
        return local
    }

    override suspend fun save(profile: AccountProfile): AccountProfile = withContext(Dispatchers.IO) {
        lock.withLock {
            fun number(value: String) = value.replace(',', '.').toDoubleOrNull()
            require(profile.name.length <= 80)
            require(profile.height.isBlank() || number(profile.height)?.let { it.isFinite() && it in 50.0..250.0 } == true)
            require(listOf(profile.currentWeight, profile.targetWeight).all {
                it.isBlank() || number(it)?.let { n -> n.isFinite() && n in 20.0..500.0 } == true
            })
            persist(profile)
        }
    }

    internal suspend fun seed() = lock.withLock {
        if (!prefs.contains("name")) persist(AccountProfile(name = "Demo", height = "172", currentWeight = "72", targetWeight = "70"))
    }
    internal suspend fun clear() = lock.withLock {
        check(prefs.edit().clear().commit())
        if (avatar.exists()) check(avatar.delete())
        changes.value = AccountProfile()
    }

    // Infrastructure-only actions stay explicit even if a caller accidentally invokes one.
    override suspend fun login(email: String, password: String): Unit = unavailable()
    override suspend fun register(email: String, password: String, name: String): Unit = unavailable()
    override suspend fun methods(): JSONObject = unavailable()
    override suspend fun requestPhone(phone: String, region: String): JSONObject = unavailable()
    override suspend fun verifyPhone(challenge: String, code: String, name: String): Unit = unavailable()
    override suspend fun verify(token: String): JSONObject = unavailable()
    override suspend fun resend(): JSONObject = unavailable()
    override suspend fun forgot(email: String): JSONObject = unavailable()
    override suspend fun reset(token: String, password: String): JSONObject = unavailable()
    override suspend fun logout(): Boolean = unavailable()
    override suspend fun delete(password: String): Unit = unavailable()
    override suspend fun linkGuest(invite: String): AccountProfile = unavailable()

    override suspend fun uploadAvatar(bytes: ByteArray): AccountProfile = withContext(Dispatchers.IO) {
        lock.withLock {
            require(bytes.isNotEmpty() && bytes.size <= 2 * 1024 * 1024)
            val temporary = File(avatar.parentFile, "standalone_avatar.tmp")
            try {
                temporary.writeBytes(bytes)
                check(temporary.renameTo(avatar))
            } finally { temporary.delete() }
            persist(changes.value.copy(avatarFile = avatar.absolutePath, avatarVersion = java.util.UUID.randomUUID().toString()))
        }
    }
    override suspend fun removeAvatar(): AccountProfile = withContext(Dispatchers.IO) {
        lock.withLock {
            if (avatar.exists()) check(avatar.delete())
            persist(changes.value.copy(avatarFile = "", avatarVersion = ""))
        }
    }
}

/** Deliberately not an HTTP failure: this build has no server dependency to retry. */
class StandaloneInfrastructureException : IllegalStateException("standalone_infrastructure_unavailable")
internal fun unavailable(): Nothing = throw StandaloneInfrastructureException()
