package com.eldering.safety.data.session

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.eldering.safety.network.ApiJson
import com.eldering.safety.network.Session
import com.eldering.safety.network.SessionProvider
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The signed-in [Session], persisted in Preferences DataStore as one AES-GCM blob encrypted with an Android
 * Keystore key ([TokenCipher]), so neither token is ever stored in plaintext.
 *
 * The session is cached in memory after the first read: OkHttp threads read it on every request through the
 * blocking [SessionProvider] methods. Writes go to memory first and then to disk, so a failing disk or Keystore
 * never breaks the running process (it only costs a re-login after a restart).
 *
 * If the stored blob cannot be decrypted (Keystore key lost or invalidated, data corrupted) the session is
 * dropped and the user signs in again.
 */
class SessionStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
    private val json: Json = ApiJson,
) : SessionProvider {

    /** Null until loaded from disk; then holds the (possibly null) session. */
    private val state = MutableStateFlow<Loaded?>(null)
    private val mutex = Mutex()

    private class Loaded(val session: Session?)

    /** The current session; emits null when signed out (including when a refresh token is rejected). */
    val session: Flow<Session?> = flow {
        ensureLoaded()
        emitAll(state.filterNotNull().map { it.session })
    }.distinctUntilChanged()

    suspend fun current(): Session? = ensureLoaded()

    suspend fun save(session: Session) {
        mutex.withLock { writeLocked(session) }
    }

    suspend fun clear() {
        mutex.withLock { writeLocked(null) }
    }

    /** See [SessionProvider.replaceSessionBlocking]. */
    suspend fun replaceIfCurrent(expectedRefreshToken: String, session: Session): Boolean = mutex.withLock {
        if (loadedLocked()?.refreshToken != expectedRefreshToken) return@withLock false
        writeLocked(session)
        true
    }

    /** See [SessionProvider.clearSessionBlocking]. */
    suspend fun clearIfCurrent(expectedRefreshToken: String): Boolean = mutex.withLock {
        if (loadedLocked()?.refreshToken != expectedRefreshToken) return@withLock false
        writeLocked(null)
        true
    }

    // --- SessionProvider: blocking, for OkHttp threads only (never the main thread) ------------------------------

    override fun currentSessionBlocking(): Session? {
        state.value?.let { return it.session }
        return runBlocking { ensureLoaded() }
    }

    override fun replaceSessionBlocking(expectedRefreshToken: String, session: Session): Boolean =
        runBlocking { replaceIfCurrent(expectedRefreshToken, session) }

    override fun clearSessionBlocking(expectedRefreshToken: String): Boolean =
        runBlocking { clearIfCurrent(expectedRefreshToken) }

    // --- internals --------------------------------------------------------------------------------------------

    private suspend fun ensureLoaded(): Session? {
        state.value?.let { return it.session }
        return mutex.withLock { loadedLocked() }
    }

    /** Must hold [mutex]. */
    private suspend fun loadedLocked(): Session? {
        state.value?.let { return it.session }
        val session = readFromDisk()
        state.value = Loaded(session)
        return session
    }

    private suspend fun readFromDisk(): Session? {
        val blob = try {
            dataStore.data.first()[KEY_SESSION]
        } catch (e: IOException) {
            Log.w(TAG, "Session storage unreadable", e)
            return null
        } ?: return null
        return try {
            val plaintext = cipher.decrypt(Base64.getDecoder().decode(blob))
            json.decodeFromString(Session.serializer(), plaintext.decodeToString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // GeneralSecurityException (key gone/invalidated, tampering), IllegalArgumentException (bad Base64),
            // SerializationException (format change): the session is unusable either way.
            Log.w(TAG, "Stored session cannot be decrypted; signing out (${e.javaClass.simpleName})")
            runCatching { dataStore.edit { it.remove(KEY_SESSION) } }
            null
        }
    }

    /** Must hold [mutex]. */
    private suspend fun writeLocked(session: Session?) {
        state.value = Loaded(session)
        try {
            val blob = session?.let(::encode)
            dataStore.edit { prefs ->
                if (blob == null) prefs.remove(KEY_SESSION) else prefs[KEY_SESSION] = blob
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not persist session; kept in memory only", e)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Could not encrypt session; kept in memory only", e)
        } catch (e: SerializationException) {
            Log.e(TAG, "Could not encode session; kept in memory only", e)
        }
    }

    private fun encode(session: Session): String {
        val plaintext = json.encodeToString(Session.serializer(), session).encodeToByteArray()
        return Base64.getEncoder().encodeToString(cipher.encrypt(plaintext))
    }

    companion object {
        private const val TAG = "SessionStore"
        private val KEY_SESSION = stringPreferencesKey("session_v1")

        /** Keystore alias of the session key. */
        const val KEY_ALIAS = "eldering.session.v1"

        /** AES-GCM associated data: ciphertexts from other purposes never decrypt as a session. */
        val ASSOCIATED_DATA: ByteArray = "eldering/session/v1".encodeToByteArray()
    }
}
