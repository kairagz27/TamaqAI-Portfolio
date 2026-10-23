package com.tamaqai.data.remote

import android.content.Context
import androidx.room.withTransaction
import com.tamaqai.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AccountProfile(val name: String="", val email: String="", val height: String="", val currentWeight: String="",
    val targetWeight: String="", val verified: Boolean=false, val pilotLinked: Boolean=false,
    val phone:String="",val phoneVerified:Boolean=false,val avatarVersion:String="",val avatarFile:String="") {
    val contactVerified get()=verified || phoneVerified
}

@android.annotation.SuppressLint("UseKtx") // Commit results are checked before publishing profile/linkage state.
class AccountRepository(private val context: Context, val client: SessionClient) : com.tamaqai.data.contracts.AccountStore {
    private val owner=client.sessions.owner
    private val profilePrefs=context.getSharedPreferences("profile_$owner",Context.MODE_PRIVATE)
    private val avatarFile=File(context.filesDir,"profile_avatars/"+java.security.MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") {"%02x".format(it)}+".jpg")
    override fun localProfile(): AccountProfile = AccountProfile(profilePrefs.getString("name","") ?: "",profilePrefs.getString("email","") ?: "",
        profilePrefs.getString("height","") ?: "",profilePrefs.getString("current","") ?: "",profilePrefs.getString("target","") ?: "",
        profilePrefs.getBoolean("verified",false),profilePrefs.getBoolean("pilot",false),profilePrefs.getString("phone","") ?: "",profilePrefs.getBoolean("phone_verified",false),
        profilePrefs.getString("avatar","") ?: "",if(avatarFile.isFile) avatarFile.absolutePath else "")
    private val profileChanges=MutableStateFlow(localProfile())
    override val profileState=profileChanges.asStateFlow()
    private val profileMutex=Mutex()
    private fun persist(p: AccountProfile): AccountProfile {
        client.requireOwner(owner)
        check(profilePrefs.edit().putString("name",p.name).putString("email",p.email).putString("height",p.height)
            .putString("current",p.currentWeight).putString("target",p.targetWeight).putBoolean("verified",p.verified).putBoolean("pilot",p.pilotLinked)
            .putString("phone",p.phone).putBoolean("phone_verified",p.phoneVerified).putString("avatar",p.avatarVersion).commit())
        profileChanges.value=p
        return p
    }
    private fun parse(data: JSONObject): AccountProfile {
        fun number(key: String) = if (data.isNull(key)) "" else data.getDouble(key).toString()
        fun nullable(key:String)=if(data.isNull(key)) "" else data.optString(key,"")
        return AccountProfile(data.getString("name"),nullable("email"),number("height_cm"),number("current_weight_kg"),number("target_weight_kg"),data.getBoolean("email_verified"),data.getBoolean("pilot_linked"),nullable("phone"),data.optBoolean("phone_verified"),nullable("avatar_version"))
    }
    private suspend fun publish(data:JSONObject):AccountProfile {
        var profile=parse(data)
        if(profile.avatarVersion.isBlank()) {avatarFile.delete()}
        else if(profile.avatarVersion!=localProfile().avatarVersion || !avatarFile.isFile) {
            val image=client.authorizedBytes("/v1/users/me/avatar",owner)
            client.requireOwner(owner)
            check(avatarFile.parentFile!!.mkdirs() || avatarFile.parentFile!!.isDirectory)
            val temporary=File.createTempFile("avatar_",".tmp",avatarFile.parentFile)
            try {temporary.writeBytes(image);check(temporary.renameTo(avatarFile))} finally {temporary.delete()}
        }
        profile=profile.copy(avatarFile=if(avatarFile.isFile) avatarFile.absolutePath else "")
        return persist(profile)
    }
    override suspend fun load(): AccountProfile = withContext(Dispatchers.IO) {
        profileMutex.withLock {
        if (owner=="guest") localProfile() else publish(client.authorized("/v1/users/me",owner))
        }
    }
    override suspend fun save(profile: AccountProfile): AccountProfile = withContext(Dispatchers.IO) {
        profileMutex.withLock {
        fun number(value: String) = value.replace(',','.').toDoubleOrNull()
        require(profile.name.length<=80)
        require(profile.height.isBlank() || number(profile.height)?.let { it.isFinite() && it in 50.0..250.0 }==true)
        require(listOf(profile.currentWeight,profile.targetWeight).all { it.isBlank() || number(it)?.let { n -> n.isFinite() && n in 20.0..500.0 }==true })
        if (owner=="guest") persist(profile.copy(email="",verified=false,pilotLinked=false))
        else publish(client.authorized("/v1/users/me",owner,"PATCH",JSONObject().put("name",profile.name)
            .put("height_cm",number(profile.height) ?: JSONObject.NULL).put("current_weight_kg",number(profile.currentWeight) ?: JSONObject.NULL)
            .put("target_weight_kg",number(profile.targetWeight) ?: JSONObject.NULL)))
        }
    }
    override suspend fun login(email: String,password: String) {
        val response=client.raw("/v1/auth/login",body=JSONObject().put("email",email.trim()).put("password",password))
        client.sessions.login(response)
    }
    override suspend fun register(email: String,password: String,name: String) {
        var deliveryFailed=false
        try {client.raw("/v1/auth/register",body=JSONObject().put("email",email.trim()).put("password",password).put("name",name.trim()))}
        catch(e:ApiFailure) {if(e.code!="email_delivery_unavailable") throw e;deliveryFailed=true}
        // Generic registration prevents email enumeration. Prove credentials via
        // login, including recovery when creation committed but SMTP failed.
        try {login(email,password)} catch(e:ApiFailure) {if(e.code=="invalid_credentials") throw ApiFailure("registration_credentials_conflict",409);throw e}
        if(deliveryFailed) context.getSharedPreferences("nutrition_${client.sessions.owner}",Context.MODE_PRIVATE).edit().putBoolean("delivery_failed",true).apply()
    }
    override suspend fun methods()=client.raw("/v1/auth/methods",method="GET")
    override suspend fun requestPhone(phone:String,region:String)=client.raw("/v1/auth/phone/request",body=JSONObject().put("phone",phone).put("region",region))
    override suspend fun verifyPhone(challenge:String,code:String,name:String) {
        val response=client.raw("/v1/auth/phone/verify",body=JSONObject().put("challenge_id",challenge).put("code",code).put("name",name.trim()))
        client.sessions.login(response)
    }
    override suspend fun uploadAvatar(bytes:ByteArray):AccountProfile=withContext(Dispatchers.IO) {
        profileMutex.withLock {
        check(owner!="guest")
        publish(JSONObject(client.authorizedBytes("/v1/users/me/avatar",owner,"PUT",bytes).toString(Charsets.UTF_8)))
        }
    }
    override suspend fun removeAvatar():AccountProfile=withContext(Dispatchers.IO) {profileMutex.withLock {publish(client.authorized("/v1/users/me/avatar",owner,"DELETE"))}}
    override suspend fun verify(token: String) = client.raw("/v1/auth/verify-email",body=JSONObject().put("token",token.trim()))
    override val deliveryFailed get()=context.getSharedPreferences("nutrition_$owner",Context.MODE_PRIVATE).getBoolean("delivery_failed",false)
    override suspend fun resend():JSONObject {
        val response=client.authorized("/v1/auth/resend-verification",owner,"POST")
        check(context.getSharedPreferences("nutrition_$owner",Context.MODE_PRIVATE).edit().putBoolean("delivery_failed",false).commit())
        return response
    }
    override suspend fun forgot(email: String) = client.raw("/v1/auth/forgot-password",body=JSONObject().put("email",email.trim()))
    override suspend fun reset(token: String,password: String) = client.raw("/v1/auth/reset-password",body=JSONObject().put("token",token.trim()).put("password",password))
    override suspend fun logout(): Boolean {
        val revoked=try { client.authorized("/v1/auth/logout",owner,"POST"); true } catch (_:Exception) { false }
        client.sessions.logout()
        return revoked
    }
    override suspend fun delete(password: String) {
        client.authorized("/v1/users/me",owner,"DELETE",JSONObject().put("password",password).put("phone_confirmation",localProfile().phoneVerified))
        avatarFile.delete()
        check(profilePrefs.edit().clear().commit())
        client.sessions.logout()
    }
    override suspend fun linkGuest(invite: String) = withContext(Dispatchers.IO) {
        check(owner!="guest"); client.requireOwner(owner)
        val database=AppDatabase.getInstance(context)
        val pending=client.sessions.snapshot()
        val pendingOwner=pending.optString("link_user")
        if (pendingOwner.isNotBlank() && pendingOwner!=owner) error("link_conflict")
        val operation=pending.optString("link_operation").ifBlank { UUID.randomUUID().toString() }
        val moved=database.openHelper.writableDatabase.query("SELECT owner FROM local_links WHERE operation=?",arrayOf(operation)).use { it.moveToFirst() && it.getString(0)==owner }
        // Avoid a server claim if local data would require an ambiguous merge.
        database.withTransaction {
            if (!moved) listOf("meals","favorite_meals","weight_entries").forEach { table ->
                database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table WHERE owner=?",arrayOf(owner)).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0)==0) { "local_link_conflict" }
                }
            }
        }
        val oldSettings=context.getSharedPreferences("daily_settings",Context.MODE_PRIVATE)
        val guestPlans=context.getSharedPreferences("plans_guest",Context.MODE_PRIVATE)
        val ownedPlans=context.getSharedPreferences("plans_$owner",Context.MODE_PRIVATE)
        if(pending.optString("link_plans").isBlank() && org.json.JSONArray(guestPlans.getString("plans","[]")).length()>0 && org.json.JSONArray(ownedPlans.getString("plans","[]")).length()>0) error("local_link_conflict")
        val guestProfile=context.getSharedPreferences("profile_guest",Context.MODE_PRIVATE)
        client.sessions.change {
            put("link_user",owner); put("link_operation",operation)
            if (!has("link_goals")) put("link_goals",JSONObject().apply { listOf("calories","protein","fat","carbs").forEach { put(it,oldSettings.getInt(it,0)) } })
            if (!has("link_profile")) put("link_profile",JSONObject().apply { listOf("name","height","current","target").forEach {put(it,guestProfile.getString(it,"") ?: "")} })
            if (!has("link_history")) put("link_history",oldSettings.getString("goal_history","") ?: "")
            if (!has("link_plans")) put("link_plans",guestPlans.getString("plans","[]") ?: "[]")
        }
        val profile=client.authorized("/v1/users/me",owner)
        val capturedProfile=client.sessions.snapshot().getJSONObject("link_profile")
        val merged=JSONObject().put("name",profile.getString("name").ifBlank {capturedProfile.optString("name")})
        listOf("height" to "height_cm","current" to "current_weight_kg","target" to "target_weight_kg").forEach { (local,remote) ->
            val value=capturedProfile.optString(local).replace(',','.').toDoubleOrNull()
            if(value!=null && !profile.isNull(remote) && profile.getDouble(remote)!=value) error("local_link_conflict")
            merged.put(remote,if(!profile.isNull(remote)) profile.getDouble(remote) else value ?: JSONObject.NULL)
        }
        if (profile.optString("linked_operation")!=operation) {
            val guest=client.guest(invite)
            client.authorized("/v1/auth/link-guest",owner,"POST",JSONObject().put("guest_token",guest).put("operation",operation))
        }
        client.authorized("/v1/users/me",owner,"PATCH",merged)
        client.requireOwner(owner)
        database.withTransaction {
            if (!moved) listOf("meals","favorite_meals","weight_entries").forEach { table ->
                database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table WHERE owner=?",arrayOf(owner)).use { check(it.moveToFirst() && it.getInt(0)==0) { "local_link_conflict" } }
                database.openHelper.writableDatabase.execSQL("UPDATE $table SET owner=? WHERE owner='guest'",arrayOf(owner))
            }
            database.openHelper.writableDatabase.execSQL("INSERT OR IGNORE INTO local_links(operation,owner) VALUES(?,?)",arrayOf(operation,owner))
        }
        // The operation receipt is durable. A restart can finish moving the same local rows.
        val newSettings=context.getSharedPreferences("daily_settings_$owner",Context.MODE_PRIVATE)
        val savedGoals=client.sessions.snapshot().getJSONObject("link_goals")
        val guestHistory=client.sessions.snapshot().optString("link_history")
        val ownHistory=newSettings.getString("goal_history",null)
        // Account targets already confirmed in onboarding retain their effective
        // dates; older guest goals remain the historical baseline.
        val history=org.json.JSONArray()
        val own=ownHistory?.let {org.json.JSONArray(it)} ?: org.json.JSONArray()
        val guest=guestHistory.takeIf {it.isNotBlank()}?.let {org.json.JSONArray(it)} ?: org.json.JSONArray().put(JSONObject().put("effective_date",java.time.LocalDate.MIN.toString()).put("origin","manual").put("targets",JSONObject().apply {listOf("calories","protein","fat","carbs").forEach {put(it,savedGoals.getInt(it).takeIf {v->v>0} ?: JSONObject.NULL)}}))
        val retained=(0 until own.length()).map(own::getJSONObject).filterNot {it.getString("effective_date")==java.time.LocalDate.MIN.toString() && targets(it.getJSONObject("targets")).isEmpty}
        val dates=retained.map {it.getString("effective_date")}.toSet()
        (0 until guest.length()).forEach {if(guest.getJSONObject(it).getString("effective_date") !in dates) history.put(guest.getJSONObject(it))}
        retained.forEach(history::put)
        check(newSettings.edit().putString("goal_history",history.toString()).apply { if(own.length()==0) listOf("calories","protein","fat","carbs").forEach {putInt(it,savedGoals.getInt(it))} }.commit())
        val linkedPlans=org.json.JSONArray(client.sessions.snapshot().getString("link_plans"))
        // Server ownership starts only after this explicit linkage; moved plans
        // are pending local edits and sync through the regular owner-checked API.
        for(i in 0 until linkedPlans.length()) linkedPlans.getJSONObject(i).put("dirty",true)
        if(linkedPlans.length()>0) check(ownedPlans.edit().putString("plans",linkedPlans.toString()).commit())
        check(guestPlans.edit().clear().commit())
        check(oldSettings.edit().clear().commit())
        check(profilePrefs.edit().putString("linked_guest_profile",capturedProfile.toString()).commit())
        check(guestProfile.edit().clear().commit())
        client.sessions.change { remove("guest"); remove("link_user"); remove("link_operation"); remove("link_goals"); remove("link_profile");remove("link_history");remove("link_plans") }
        load()
    }
}
