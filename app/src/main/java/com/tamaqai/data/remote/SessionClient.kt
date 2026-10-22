package com.tamaqai.data.remote

import com.tamaqai.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiFailure(val code: String, val status: Int, val fields: Map<String,String> = emptyMap()): Exception(code) {
    companion object {
        /** Do not expose server messages or submitted values to UI/logs. Supports old servers. */
        fun parse(status:Int,response:ByteArray):ApiFailure {
            val error=runCatching {JSONObject(response.toString(Charsets.UTF_8)).optJSONObject("error")}.getOrNull()
            val code=error?.optString("code")?.takeIf {it.matches(Regex("[a-z_]{1,64}"))}
                ?: if(status==422) "invalid_input" else "server_unavailable"
            val source=error?.optJSONObject("fields")
            val fields=if(status==422 && code=="invalid_input") listOf("name","email","password","token").mapNotNull {field ->
                source?.optString(field)?.takeIf {it in setOf("required","too_short","too_long","invalid")}?.let {field to it}
            }.toMap() else emptyMap()
            return ApiFailure(code,status,fields)
        }
    }
}

/** Shared by all request callers. Captured owner scopes prevent late responses changing users. */
class SessionClient(val sessions: SecureSessions, private val origin: String,private val connectMillis:Int=10000,private val readMillis:Int=40000) {
    init {require(connectMillis in 1..60000 && readMillis in 1..60000)}
    private val refreshLock=Mutex()
    private val guestLock=Mutex()
    fun hasAnalysisAccess(owner: String): Boolean = if (owner=="guest") sessions.guestToken.isNotBlank() else sessions.access.isNotBlank()
    fun requireOwner(owner: String) { check(sessions.owner==owner) { "account_changed" } }
    suspend fun raw(path: String, method: String="POST", body: JSONObject=JSONObject(), headers: Map<String,String> = emptyMap()): JSONObject =
        JSONObject(send(path,method,body.toString().toByteArray(),"application/json",headers))

    private suspend fun token(owner: String, failed: String?=null): String = refreshLock.withLock {
        requireOwner(owner)
        if (sessions.access.isNotBlank() && (failed!=null && sessions.access!=failed || failed==null && sessions.expires>System.currentTimeMillis()/1000+30)) return@withLock sessions.access
        if (sessions.refresh.isBlank()) throw ApiFailure("session_expired",401)
        val refresh=sessions.refresh
        val response=try { raw("/v1/auth/refresh",body=JSONObject().put("refresh_token",refresh)) }
        catch (e: Exception) {
            // No automatic replay of a refresh whose server outcome is unknown.
            requireOwner(owner); sessions.expire(); throw e
        }
        requireOwner(owner)
        check(response.getString("user_id")==owner)
        sessions.login(response)
        sessions.access
    }
    suspend fun authorized(path: String, owner: String, method: String="GET", body: JSONObject=JSONObject(),headers:Map<String,String> = emptyMap()): JSONObject {
        val access=token(owner)
        return try { raw(path,method,body,headers+mapOf("Authorization" to "Bearer $access")) }
        catch (e: ApiFailure) {
            if (e.status!=401 || e.code !in listOf("unauthorized","token_expired","session_expired")) throw e
            val fresh=token(owner,access)
            raw(path,method,body,headers+mapOf("Authorization" to "Bearer $fresh"))
        }.also { requireOwner(owner) }
    }
    suspend fun authorizedBytes(path:String,owner:String,method:String="GET",bytes:ByteArray=byteArrayOf(),type:String="image/jpeg"):ByteArray {
        val access=token(owner)
        val result=try {sendBytes(path,method,bytes,type,mapOf("Authorization" to "Bearer $access"))}
        catch(e:ApiFailure) {
            if(e.status!=401 || e.code !in listOf("unauthorized","token_expired","session_expired")) throw e
            val fresh=token(owner,access)
            sendBytes(path,method,bytes,type,mapOf("Authorization" to "Bearer $fresh"))
        }
        requireOwner(owner)
        return result
    }
    suspend fun guest(invite: String): String = guestLock.withLock {
        sessions.guestToken.takeIf { it.isNotBlank() } ?: run {
            require(invite.isNotBlank()) { "ui_error_017" }
            val response=raw("/v1/guests",headers=mapOf("X-Pilot-Invite" to invite))
            val token=response.getString("token")
            sessions.change { put("guest",token) }
            token
        }
    }
    suspend fun analyze(owner: String, image: ByteArray, id: String, invite: String): String {
        requireOwner(owner)
        val access=if (owner=="guest") guest(invite) else token(owner)
        requireOwner(owner)
        val headers=mapOf("Authorization" to "Bearer $access","Idempotency-Key" to id)
        val result=try { send("/v1/analyses","POST",image,"image/jpeg",headers) }
        catch (e: ApiFailure) {
            if (e.status!=401) throw e
            if (owner=="guest") { sessions.change { remove("guest") }; throw e }
            val fresh=token(owner,access)
            send("/v1/analyses","POST",image,"image/jpeg",headers+mapOf("Authorization" to "Bearer $fresh"))
        }
        requireOwner(owner)
        return result
    }
    private suspend fun send(path: String, method: String, bytes: ByteArray, type: String, headers: Map<String,String>): String =
        sendBytes(path,method,bytes,type,headers).toString(Charsets.UTF_8)
    private suspend fun sendBytes(path: String, method: String, bytes: ByteArray, type: String, headers: Map<String,String>): ByteArray = withContext(Dispatchers.IO) {
        if(origin.isBlank()) throw ApiFailure("backend_not_configured",0)
        val url=URL(origin)
        if(!(url.protocol=="https" || BuildConfig.DEBUG && url.protocol=="http" && url.host in listOf("127.0.0.1","localhost","10.0.2.2")))
            throw ApiFailure("backend_https_required",0)
        val connection=URL(origin.trimEnd('/')+path).openConnection() as HttpURLConnection
        coroutineScope {
        // HttpURLConnection reads do not observe coroutine cancellation. A child
        // on IO disconnects the socket when the caller is cancelled, instead of
        // holding the screen's job until the read timeout. No retry is implied.
        val disconnect=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {connection.disconnect()}
        }
        try {
            currentCoroutineContext().ensureActive()
            connection.requestMethod=method; connection.instanceFollowRedirects=false
            connection.connectTimeout=connectMillis; connection.readTimeout=readMillis
            headers.forEach { (key,value) -> connection.setRequestProperty(key,value) }
            if (method!="GET") {
                connection.doOutput=true; connection.setRequestProperty("Content-Type",type)
                connection.setFixedLengthStreamingMode(bytes.size); connection.outputStream.use { it.write(bytes) }
            }
            val status=connection.responseCode
            val stream=if (status in 200..299) connection.inputStream else connection.errorStream
            val response=stream?.use { input ->
                val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(4096)
                val maximum=if(path.startsWith("/v1/catalog/")) 4*1024*1024
                    else if(method=="GET" && path in listOf("/v1/users/me/avatar","/v1/plans","/v1/nutrition/goals")) 2*1024*1024 else 65536
                while (true) { val count=input.read(buffer); if (count<0) break; check(output.size()+count<=maximum); output.write(buffer,0,count) }
                output.toByteArray()
            } ?: "{}".toByteArray()
            if (status !in 200..299) throw ApiFailure.parse(status,response)
            response
        } catch(error:Exception) {
            // Socket-closed/timeout must not replace cancellation with a UI error.
            currentCoroutineContext().ensureActive()
            throw error
        } finally { withContext(NonCancellable) {disconnect.cancelAndJoin()} }
        }
    }
}
