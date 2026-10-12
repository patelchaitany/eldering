package com.eldering.safety.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eldering.safety.data.session.SessionStore
import com.eldering.safety.testing.FakeTime
import com.eldering.safety.testing.ServerSamples
import com.eldering.safety.testing.authJson
import com.eldering.safety.testing.errorJson
import com.eldering.safety.testing.json
import com.eldering.safety.testing.session
import com.eldering.safety.testing.testCipher
import com.eldering.safety.testing.testDataStore
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TokenRefreshTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val backend = FakeAuthBackend()
    private val time = FakeTime()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var sessions: SessionStore
    private lateinit var api: ElderingApi

    @Before
    fun setUp() {
        server.dispatcher = backend
        server.start()
        sessions = SessionStore(testDataStore(tmp.root, scope), testCipher())
        api = ElderingNetwork(server.url("/").toString(), sessions, time).api
    }

    @After
    fun tearDown() {
        server.close()
        scope.cancel()
    }

    private fun signIn(access: String = "stale-access", refresh: String = "refresh-1", expiresAt: Long = time.nowMillis() + 15 * 60_000) =
        runBlocking { sessions.save(session(accessToken = access, refreshToken = refresh, accessExpiresAtMs = expiresAt)) }

    @Test
    fun `401 rotates the tokens once and retries the request`() = runBlocking {
        signIn() // the server no longer accepts "stale-access"

        val result = apiCall { api.me() }

        assertTrue(result.isSuccess)
        assertEquals(1, backend.refreshCalls.get())
        val rotated = sessions.current()!!
        assertEquals("access-2", rotated.accessToken)
        assertEquals("refresh-2", rotated.refreshToken)
        assertEquals(time.nowMillis() + 900_000, rotated.accessExpiresAtMs)
        assertEquals(listOf("Bearer stale-access", "Bearer access-2"), backend.meAuthHeaders)
    }

    @Test
    fun `concurrent 401s share a single refresh`() = runBlocking {
        signIn()
        backend.refreshDelayMs = 300

        val results = withContext(Dispatchers.IO) { (1..8).map { async { apiCall { api.me() } } }.awaitAll() }

        assertTrue(results.all { it.isSuccess })
        assertEquals("refresh-token rotation must be single-flight", 1, backend.refreshCalls.get())
        assertEquals("refresh-2", sessions.current()!!.refreshToken)
    }

    @Test
    fun `token about to expire is refreshed before the request`() = runBlocking {
        backend.validAccess.set("access-1")
        signIn(access = "access-1", expiresAt = time.nowMillis() + 10_000) // inside the 30 s margin

        assertTrue(apiCall { api.me() }.isSuccess)

        assertEquals(1, backend.refreshCalls.get())
        assertEquals("no 401 round trip", listOf("Bearer access-2"), backend.meAuthHeaders)
    }

    @Test
    fun `rejected refresh token signs the user out`() = runBlocking {
        signIn(refresh = "revoked")

        val result = apiCall { api.me() }

        assertTrue(result.errorOrNull() is ApiError.SignedOut)
        assertNull(sessions.current())
        assertEquals(1, backend.refreshCalls.get())
    }

    @Test
    fun `transient refresh failure keeps the session`() = runBlocking {
        signIn()
        backend.refreshOverride = errorJson(503, "unavailable")

        val result = apiCall { api.me() }

        assertTrue(result.errorOrNull() is ApiError.Network)
        assertEquals("refresh-1", sessions.current()?.refreshToken)
    }

    @Test
    fun `a second 401 after refreshing is final`() = runBlocking {
        signIn()
        backend.rejectAllAccessTokens = true

        val error = apiCall { api.me() }.errorOrNull()

        assertTrue(error is ApiError.Http && error.isUnauthorized)
        assertEquals(1, backend.refreshCalls.get())
        assertEquals(2, backend.meAuthHeaders.size)
        assertNotNull("session itself is still valid", sessions.current())
    }

    @Test
    fun `auth endpoints carry no token and a 401 there never refreshes`() = runBlocking {
        signIn(access = "access-1")

        val error = apiCall { api.login(LoginRequest("asha@example.com", "wrong")) }.errorOrNull()

        assertTrue(error is ApiError.Http && error.code == "invalid_credentials")
        assertEquals(0, backend.refreshCalls.get())
        assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.headers["Authorization"])
    }

    @Test
    fun `no session fails fast without touching the network`() = runBlocking {
        val result = apiCall { api.me() }
        assertTrue(result.errorOrNull() is ApiError.SignedOut)
        assertEquals(0, server.requestCount)
    }
}

/** A tiny auth server: accepts one access token at a time and rotates refresh tokens like the real one. */
private class FakeAuthBackend : Dispatcher() {
    val validAccess = AtomicReference("access-1")
    private val validRefresh = AtomicReference("refresh-1")
    val refreshCalls = AtomicInteger()
    val meAuthHeaders: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    @Volatile var refreshDelayMs = 0L
    @Volatile var refreshOverride: MockResponse? = null
    @Volatile var rejectAllAccessTokens = false

    override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
        "/v1/auth/refresh" -> refresh(request)
        "/v1/me" -> {
            val auth = request.headers["Authorization"]
            meAuthHeaders += auth
            if (!rejectAllAccessTokens && auth == "Bearer ${validAccess.get()}") json(200, ServerSamples.ME) else errorJson(401, "unauthorized")
        }
        "/v1/auth/login" -> errorJson(401, "invalid_credentials")
        else -> errorJson(404, "not_found")
    }

    @Synchronized
    private fun refresh(request: RecordedRequest): MockResponse {
        val n = refreshCalls.incrementAndGet()
        refreshOverride?.let { return it }
        if (refreshDelayMs > 0) Thread.sleep(refreshDelayMs)
        val presented = Json.parseToJsonElement(request.body!!.utf8()).jsonObject["refresh_token"]!!.jsonPrimitive.content
        if (presented != validRefresh.get()) return errorJson(401, "invalid_refresh_token")
        val access = "access-${n + 1}"
        val refresh = "refresh-${n + 1}"
        validAccess.set(access)
        validRefresh.set(refresh)
        return json(200, authJson(access, refresh))
    }
}
