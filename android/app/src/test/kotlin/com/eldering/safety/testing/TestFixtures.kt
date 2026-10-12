package com.eldering.safety.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.eldering.safety.core.TimeSource
import com.eldering.safety.data.db.ElderingDatabase
import com.eldering.safety.data.session.AesGcmCipher
import com.eldering.safety.data.session.SessionStore
import com.eldering.safety.network.Session
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mockwebserver3.MockResponse

/** A clock tests move by hand. */
class FakeTime(start: Long = T0) : TimeSource {
    private val now = AtomicLong(start)
    override fun nowMillis(): Long = now.get()
    fun advance(ms: Long) {
        now.addAndGet(ms)
    }
    fun set(ms: Long) = now.set(ms)

    companion object {
        /** 2026-10-13T08:00:00Z */
        const val T0: Long = 1_791_878_400_000L
    }
}

/** Software AES key (Robolectric has no AndroidKeyStore provider); same cipher code path as production. */
fun softwareAesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

fun testCipher(key: SecretKey = softwareAesKey()) = AesGcmCipher({ key }, SessionStore.ASSOCIATED_DATA)

fun testDataStore(dir: File, scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "session.preferences_pb") })

fun inMemoryDatabase(): ElderingDatabase = Room.inMemoryDatabaseBuilder(
    ApplicationProvider.getApplicationContext(),
    ElderingDatabase::class.java,
).allowMainThreadQueries().build()

fun session(
    accessToken: String = "access-1",
    refreshToken: String = "refresh-1",
    accessExpiresAtMs: Long = FakeTime.T0 + 15 * 60_000,
    userId: String = USER_ID,
) = Session(
    userId = userId,
    email = "asha@example.com",
    displayName = "Asha",
    accessToken = accessToken,
    accessExpiresAtMs = accessExpiresAtMs,
    refreshToken = refreshToken,
)

fun json(code: Int, body: String): MockResponse = MockResponse.Builder()
    .code(code)
    .addHeader("Content-Type", "application/json; charset=utf-8")
    .body(body)
    .build()

fun noContent(): MockResponse = MockResponse.Builder().code(204).build()

fun errorJson(code: Int, errorCode: String, message: String = "nope"): MockResponse =
    json(code, """{"error":{"code":"$errorCode","message":"$message"}}""")

fun authJson(access: String, refresh: String, userId: String = USER_ID): String = """
    {"access_token":"$access","token_type":"Bearer","expires_in":900,
     "access_expires_at":"2026-10-13T08:15:00.123456789Z","refresh_token":"$refresh",
     "refresh_expires_at":"2026-11-12T08:00:00Z",
     "user":{"id":"$userId","email":"asha@example.com","display_name":"Asha","created_at":"2026-10-01T10:00:00+02:00"}}
""".trimIndent()

const val USER_ID = "7c1e9a52-5d1b-4a8e-9a43-0f6f8f1d2b3c"
const val OTHER_USER_ID = "1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f9"
const val FAMILY_ID = "0e7a7f1c-2b4d-4c55-8a66-7b8c9d0e1f20"
const val INCIDENT_ID = "9f8e7d6c-5b4a-4392-8170-6f5e4d3c2b1a"
