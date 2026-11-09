package com.tamaqai.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.tamaqai.BuildConfig
import com.tamaqai.data.local.AppDatabase
import com.tamaqai.data.local.PhotoStore
import com.tamaqai.data.remote.BackendFoodAnalysisRepository
import com.tamaqai.data.remote.SecureSessions
import com.tamaqai.data.remote.SessionClient
import com.tamaqai.data.repository.MealRepositoryImpl
import com.tamaqai.domain.model.*
import com.tamaqai.domain.usecase.AnalyzeMealPhotoUseCase
import com.tamaqai.ui.screens.MealEditorViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual HTTP adapter + validation + ViewModel + Room, with loopback-only synthetic responses. */
class LocalHttpPhotoFlowTest {
    @Test fun validEstimateAutomaticallyConfirmsOnceAndSurvivesReopen() = runBlocking {
        verifySuccess(isDemo = false)
    }

    @Test fun fakeServerResultRetainsDemoProvenance() = runBlocking {
        verifySuccess(isDemo = true)
    }

    private suspend fun verifySuccess(isDemo: Boolean) {
        HttpFixture(listOf({ request -> Reply(200, result(request.key, isDemo)) })).use { server ->
            val fixture = Pipeline(server)
            try {
                fixture.prepare()
                fixture.startTwice()
                fixture.awaitStatus(MealStatus.Confirmed)
                val saved = fixture.repository.getAllMeals().first().single()
                assertEquals(fixture.draft.uid, saved.uid)
                assertEquals(200, saved.nutrition.calories)
                assertEquals(if (isDemo) NutritionSource.Demo else NutritionSource.AiEstimate, saved.source)
                assertNull(saved.details.photoName)
                assertNull(fixture.photos.availableFile(fixture.photoName))
                server.finished()
                assertEquals(1, server.requests.size)
                fixture.assertRequest(server.requests.single())
                fixture.reopenDatabase()
                val persisted = fixture.repository.getAllMeals().first().single()
                assertEquals(saved.uid, persisted.uid)
                assertEquals(MealStatus.Confirmed, persisted.status)
                assertEquals(saved.source, persisted.source)
            } finally { fixture.dispose() }
        }
    }

    @Test fun invalidResponsePreservesPhotoAndNeverConfirms() = runBlocking {
        HttpFixture(listOf({ _ -> Reply(200, result("wrong-analysis-id", false)) })).use { server ->
            val fixture = Pipeline(server)
            try {
                fixture.prepare(); fixture.startTwice(); fixture.awaitStatus(MealStatus.Failed)
                val saved = fixture.repository.getAllMeals().first().single()
                assertEquals(fixture.draft.details.analysisId, saved.details.analysisId)
                assertEquals(fixture.photoName, saved.details.photoName)
                assertArrayEquals(fixture.jpeg, fixture.photos.read(fixture.photoName))
                assertNotNull(fixture.vm.state.value.error)
                assertTrue(fixture.repository.getAllMeals().first().none { it.status == MealStatus.Confirmed })
                server.finished(); assertEquals(1, server.requests.size)
                fixture.assertRequest(server.requests.single())
            } finally { fixture.dispose() }
        }
    }

    @Test fun unavailableResponseThenExplicitRetryReusesKeyAndPhoto() = runBlocking {
        HttpFixture(listOf(
            { _ -> Reply(503, "{\"error\":{\"code\":\"provider_unavailable\"}}") },
            { request -> Reply(200, result(request.key, false)) },
        )).use { server ->
            val fixture = Pipeline(server)
            try {
                fixture.prepare(); fixture.startTwice(); fixture.awaitStatus(MealStatus.Failed)
                assertEquals(1, server.requests.size) // no hidden paid/provider retry
                assertArrayEquals(fixture.jpeg, fixture.photos.read(fixture.photoName))
                withContext(Dispatchers.Main) { fixture.vm.startPhotoAnalysisIfReady(true) }
                delay(150)
                assertEquals(1, server.requests.size) // failed drafts do not auto-restart
                withContext(Dispatchers.Main) { fixture.vm.analyze("") }
                fixture.awaitStatus(MealStatus.Confirmed)
                server.finished()
                assertEquals(2, server.requests.size)
                server.requests.forEach(fixture::assertRequest)
                assertEquals(1, fixture.repository.getAllMeals().first().size)
            } finally { fixture.dispose() }
        }
    }

    private class Pipeline(server: HttpFixture) {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val scope = "http-photo-fixture-${UUID.randomUUID()}"
        private val prefs = mutableSetOf<String>()
        private val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "$scope-$name"
                prefs.add(isolated)
                return base.getSharedPreferences(isolated, mode)
            }
        }
        private val databaseName = "$scope.db"
        private val photoDirectory = File(base.cacheDir, scope)
        private var database: AppDatabase
        var repository: MealRepositoryImpl
            private set
        val photos: PhotoStore
        val jpeg: ByteArray
        val photoName: String
        val draft: Meal
        lateinit var vm: MealEditorViewModel
            private set
        private val sessions: SecureSessions
        private val provider: BackendFoodAnalysisRepository

        init {
            check(BuildConfig.DEBUG && (BuildConfig.APPLICATION_ID.endsWith(".demo") || BuildConfig.APPLICATION_ID.endsWith(".visual")))
            database = openDatabase()
            repository = MealRepositoryImpl(database.mealDao(), "guest")
            photos = PhotoStore(photoDirectory)
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            jpeg = try { ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)); out.toByteArray()
            } } finally { bitmap.recycle() }
            val uid = UUID.randomUUID().toString()
            photoName = photos.save(uid, jpeg)
            draft = Meal(uid = uid, nutrition = Nutrition("Synthetic upload", 0, 0, 0, 0),
                status = MealStatus.PendingUpload,
                details = MealDetails(emptyList(), photoName = photoName, analysisId = UUID.randomUUID().toString()))
            sessions = SecureSessions(context, server.origin)
            sessions.change { put("guest", "loopback-only-synthetic-token") }
            val client = SessionClient(sessions, server.origin, connectMillis = 2000, readMillis = 3000)
            provider = BackendFoodAnalysisRepository(context, server.origin, client, "guest")
        }
        private fun openDatabase() = Room.databaseBuilder(base, AppDatabase::class.java, databaseName).build()
        suspend fun prepare() {
            repository.saveDraft(draft)
            withContext(Dispatchers.Main) { vm = MealEditorViewModel(draft.uid, repository, photos, AnalyzeMealPhotoUseCase(provider)) }
            withTimeout(5000) { vm.state.first { !it.loading } }
        }
        suspend fun startTwice() = withContext(Dispatchers.Main) {
            vm.startPhotoAnalysisIfReady(true); vm.startPhotoAnalysisIfReady(true)
        }
        suspend fun awaitStatus(expected: MealStatus) = withTimeout(8000) {
            if (expected == MealStatus.Confirmed) {
                // The close event follows both the Room commit and best-effort photo cleanup.
                vm.close.first()
                assertEquals(expected, repository.getMeal(draft.uid)?.status)
            } else while (repository.getMeal(draft.uid)?.status != expected || vm.state.value.busy) {
                delay(20)
            }
        }
        fun assertRequest(request: Request) {
            assertEquals("POST /v1/analyses HTTP/1.1", request.line)
            assertEquals("image/jpeg", request.headers["content-type"])
            assertEquals("Bearer loopback-only-synthetic-token", request.headers["authorization"])
            assertEquals(draft.details.analysisId, request.key)
            assertArrayEquals(jpeg, request.body)
        }
        private suspend fun stopVm() {
            if (::vm.isInitialized) withContext(Dispatchers.Main) { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        }
        suspend fun reopenDatabase() {
            stopVm(); database.close(); database = openDatabase()
            repository = MealRepositoryImpl(database.mealDao(), "guest")
        }
        suspend fun dispose() {
            stopVm(); database.close()
            base.deleteDatabase(databaseName)
            prefs.forEach { base.deleteSharedPreferences(it) }
            // Only this test's unique, app-private photo directory is removed.
            photoDirectory.listFiles()?.forEach { check(it.delete()) }
            check(photoDirectory.delete())
        }
    }

    private data class Request(val line: String, val headers: Map<String, String>, val body: ByteArray) {
        val key get() = headers["idempotency-key"] ?: ""
    }
    private data class Reply(val status: Int, val body: String)
    private class HttpFixture(replies: List<(Request) -> Reply>) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val origin = "http://127.0.0.1:${server.localPort}"
        val requests = ConcurrentLinkedQueue<Request>()
        private val executor = Executors.newSingleThreadExecutor()
        private val task = executor.submit {
            replies.forEach { reply ->
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().buffered()
                    fun line(): String {
                        val output = ByteArrayOutputStream()
                        while (true) {
                            val byte = input.read(); check(byte >= 0)
                            if (byte == 10) break
                            if (byte != 13) output.write(byte)
                            check(output.size() <= 8192)
                        }
                        return output.toString("UTF-8")
                    }
                    val first = line()
                    val headers = mutableMapOf<String, String>()
                    while (true) { val header = line(); if (header.isEmpty()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                    }
                    val length = headers.getValue("content-length").toInt(); check(length in 1..262144)
                    val body = ByteArray(length); var read = 0
                    while (read < length) { val n = input.read(body, read, length - read); check(n > 0); read += n }
                    val request = Request(first, headers, body); requests.add(request)
                    val response = reply(request); val bytes = response.body.toByteArray(Charsets.UTF_8)
                    val status = if (response.status == 200) "200 OK" else "503 Service Unavailable"
                    socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
                }
            }
        }
        fun finished() { task.get(5, TimeUnit.SECONDS) }
        override fun close() { server.close(); executor.shutdownNow(); executor.awaitTermination(2, TimeUnit.SECONDS) }
    }

    private companion object {
        fun result(id: String, demo: Boolean): String = JSONObject()
            .put("analysis_id", id).put("schema_version", "1").put("prompt_version", "fixture-v1")
            .put("model", "loopback-fixture-not-an-ai-call").put("is_demo", demo)
            .put("result", JSONObject().put("status", "food").put("name", "Synthetic food")
                .put("explanation", JSONObject.NULL).put("question", JSONObject.NULL)
                .put("components", JSONArray().put(JSONObject().put("name", "Synthetic food")
                    .put("mass_grams", 100.0).put("calories", 200.0).put("protein", 10.0)
                    .put("fat", 8.0).put("carbs", 22.0)))).toString()
    }
}
