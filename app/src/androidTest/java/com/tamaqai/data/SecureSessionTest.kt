package com.tamaqai.data

import androidx.test.platform.app.InstrumentationRegistry
import com.tamaqai.data.remote.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SecureSessionTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun legacyGuestMigrationEncryptsAndPersists() {
        val origin="http://127.0.0.1:19001"+System.nanoTime()
        val legacy=context.getSharedPreferences("backend_guest",0)
        val secret="private-guest-fixture-value"
        val otherOrigin=origin+"-other"
        val otherSecret="private-other-origin-fixture-value"
        assertTrue(legacy.edit().putString(origin,secret).putString(otherOrigin,otherSecret).commit())
        val store=SecureSessions(context,origin)
        assertEquals(secret,store.guestToken);assertNull(legacy.getString(origin,null))
        assertNull(legacy.getString(otherOrigin,null))
        assertEquals(otherSecret,SecureSessions(context,otherOrigin).guestToken)
        assertEquals(secret,SecureSessions(context,origin).guestToken)
        val encrypted=context.getSharedPreferences("encrypted_sessions",0).all.values.joinToString()
        assertFalse(encrypted.contains(secret))
        assertFalse(encrypted.contains(otherSecret))
    }
    @Test fun parallelRequestsCoordinateExactlyOneRefreshAndLogoutKeepsOwnerCacheIsolated() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val origin="http://127.0.0.1:${server.localPort}";val refreshes=AtomicInteger();val requests=AtomicInteger()
            val worker=Executors.newSingleThreadExecutor()
            val task=worker.submit {
                repeat(9) {
                    server.accept().use { socket ->
                        socket.soTimeout=5000
                        val reader=socket.getInputStream().bufferedReader();val path=reader.readLine();var length=0
                        while(true) {val line=reader.readLine() ?: break;if(line.isEmpty())break;if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt()}
                        repeat(length) {reader.read()}
                        val response=if(path.contains("/refresh")) {
                            refreshes.incrementAndGet();Thread.sleep(100)
                            JSONObject().put("user_id","fixture-user").put("access_token","new-access-fixture").put("refresh_token","new-refresh-fixture").put("expires_in",600).toString()
                        } else {requests.incrementAndGet();"{\"name\":\"Test\"}"}
                        val bytes=response.toByteArray()
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()+bytes)
                    }
                }
            }
            try {
                val store=SecureSessions(context,origin)
                store.login(JSONObject().put("user_id","fixture-user").put("access_token","old-access-fixture").put("refresh_token","old-refresh-fixture").put("expires_in",0))
                val client=SessionClient(store,origin)
                coroutineScope { (0..7).map {async(Dispatchers.IO) {client.authorized("/v1/users/me","fixture-user")}}.awaitAll() }
                task.get();assertEquals(1,refreshes.get());assertEquals(8,requests.get())
                assertEquals("new-refresh-fixture",SecureSessions(context,origin).refresh)
                store.expire();assertEquals("fixture-user",store.owner);assertTrue(store.access.isBlank())
                store.logout();assertEquals("guest",store.owner)
                assertTrue(runCatching {client.authorized("/v1/users/me","fixture-user")}.isFailure)
            } finally {worker.shutdownNow()}
        }
    }
}
