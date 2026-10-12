package com.eldering.safety.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eldering.safety.testing.FakeTime
import com.eldering.safety.testing.INCIDENT_ID
import com.eldering.safety.testing.errorJson
import com.eldering.safety.testing.json
import com.eldering.safety.testing.session
import java.time.Duration
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ErrorMappingTest {

    private val server = MockWebServer()
    private lateinit var api: ElderingApi

    @Before
    fun setUp() {
        server.start()
        api = ElderingNetwork(server.url("/").toString(), InMemorySessions(session()), FakeTime()).api
    }

    @After
    fun tearDown() = server.close()

    private suspend fun failureOf(response: MockResponse): ApiError {
        server.enqueue(response)
        return apiCall { api.incident(INCIDENT_ID) }.errorOrNull() ?: error("expected a failure")
    }

    @Test
    fun `server error envelope maps to code and message`() = runTest {
        val e = failureOf(errorJson(404, "not_found", "resource not found")) as ApiError.Http
        assertEquals(404, e.status)
        assertEquals("not_found", e.code)
        assertEquals("resource not found", e.serverMessage)
        assertTrue(e.isNotFound)
        assertFalse(e.isRetryable)
    }

    @Test
    fun `conflict codes are preserved`() = runTest {
        val e = failureOf(errorJson(409, "incident_closed")) as ApiError.Http
        assertTrue(e.isConflict)
        assertEquals("incident_closed", e.code)
    }

    @Test
    fun `rate limit carries retry-after and is retryable`() = runTest {
        val e = failureOf(
            MockResponse.Builder()
                .code(429)
                .addHeader("Content-Type", "application/json")
                .addHeader("Retry-After", "7")
                .body("""{"error":{"code":"rate_limited","message":"too many requests, retry later"}}""")
                .build(),
        ) as ApiError.Http
        assertTrue(e.isRateLimited)
        assertTrue(e.isRetryable)
        assertEquals(Duration.ofSeconds(7), e.retryAfter)
    }

    @Test
    fun `non-json gateway errors still map`() = runTest {
        val e = failureOf(MockResponse.Builder().code(502).body("<html>Bad Gateway</html>").build()) as ApiError.Http
        assertEquals(502, e.status)
        assertEquals("http_502", e.code)
        assertTrue(e.isRetryable)
    }

    @Test
    fun `malformed success body is unexpected, not a crash`() = runTest {
        assertTrue(failureOf(json(200, """{"id":42""")) is ApiError.Unexpected)
        assertTrue(failureOf(json(200, """{"unrelated":true}""")) is ApiError.Unexpected)
    }

    @Test
    fun `unreachable server is a retryable network error`() = runTest {
        val url = server.url("/").toString()
        server.close()
        val offline = ElderingNetwork(url, InMemorySessions(session()), FakeTime()).api
        val e = apiCall { offline.health() }.errorOrNull()
        assertTrue(e is ApiError.Network)
        assertTrue(e!!.isRetryable)
    }
}
