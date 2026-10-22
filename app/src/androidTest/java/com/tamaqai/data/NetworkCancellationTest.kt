package com.tamaqai.data

import androidx.test.platform.app.InstrumentationRegistry
import com.tamaqai.BuildConfig
import com.tamaqai.data.remote.SecureSessions
import com.tamaqai.data.remote.SessionClient
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NetworkCancellationTest {
    @Test fun cancellingBlockedReadDisconnectsWithoutWaitingForReadTimeout() = runBlocking<Unit> {
        check(BuildConfig.APPLICATION_ID.endsWith(".demo") || BuildConfig.APPLICATION_ID.endsWith(".visual"))
        ServerSocket(0).use { server ->
            val accepted=CountDownLatch(1)
            val executor=Executors.newSingleThreadExecutor()
            val socketTask=executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout=10_000
                    val reader=socket.getInputStream().bufferedReader()
                    while(!reader.readLine().isNullOrEmpty()) { /* consume only fixture headers */ }
                    accepted.countDown()
                    // Withhold a response; the client must close the socket on cancellation.
                    reader.read()
                }
            }
            try {
                val context=InstrumentationRegistry.getInstrumentation().targetContext
                val origin="http://127.0.0.1:${server.localPort}"
                val client=SessionClient(SecureSessions(context,origin),origin,readMillis=6000)
                val request=launch(Dispatchers.IO) {client.raw("/fixture","GET")}
                assertTrue(withContext(Dispatchers.IO) {accepted.await(5,TimeUnit.SECONDS)})
                val start=System.nanoTime()
                request.cancelAndJoin()
                assertTrue("Cancellation must not wait for the network read timeout",(System.nanoTime()-start)/1_000_000<1500)
                withContext(Dispatchers.IO) {socketTask.get(2,TimeUnit.SECONDS)}
            } finally {executor.shutdownNow()}
        }
    }
}
