package com.eldering.safety.data.session

import app.cash.turbine.test
import com.eldering.safety.testing.session
import com.eldering.safety.testing.softwareAesKey
import com.eldering.safety.testing.testCipher
import com.eldering.safety.testing.testDataStore
import java.io.File
import java.security.GeneralSecurityException
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val key = softwareAesKey()

    /** A fresh store over the same file, as after a process restart. */
    private fun newStore(dir: File = tmp.root, cipher: TokenCipher = testCipher(key)): SessionStore {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also(scopes::add)
        return SessionStore(testDataStore(dir, scope), cipher)
    }

    /** Closes every open store (DataStore allows one active instance per file). */
    private suspend fun closeStores() {
        scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
        scopes.clear()
    }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    @Test
    fun `persists across restarts`() = runTest {
        newStore().save(session())
        // A second DataStore on the same file is only legal once the first is gone.
        closeStores()
        assertEquals(session(), newStore().current())
    }

    @Test
    fun `tokens are never stored in plaintext`() = runTest {
        val store = newStore()
        store.save(session(accessToken = "access-PLAINTEXT-CANARY", refreshToken = "refresh-PLAINTEXT-CANARY"))
        val onDisk = tmp.root.walk().filter { it.isFile }.map { it.readBytes().decodeToString() }.joinToString()
        assertFalse(onDisk.contains("PLAINTEXT-CANARY"))
        assertFalse(onDisk.contains("asha@example.com"))
    }

    @Test
    fun `undecryptable blob signs the user out instead of crashing`() = runTest {
        newStore().save(session())
        closeStores()

        val otherKey = newStore(cipher = testCipher(softwareAesKey())) // e.g. Keystore key invalidated
        assertNull(otherKey.current())
        assertNull(otherKey.session.first())
    }

    @Test
    fun `compare-and-set guards against racing logout and login`() = runTest {
        val store = newStore()
        store.save(session(refreshToken = "r1"))

        assertTrue(store.replaceIfCurrent("r1", session(accessToken = "a2", refreshToken = "r2")))
        assertFalse("stale rotation must not clobber", store.replaceIfCurrent("r1", session(refreshToken = "r3")))
        assertFalse(store.clearIfCurrent("r1"))
        assertEquals("r2", store.current()?.refreshToken)

        store.clear()
        assertFalse("must not resurrect a logged-out session", store.replaceIfCurrent("r2", session(refreshToken = "r4")))
        assertNull(store.current())
    }

    @Test
    fun `blocking accessors see the same state`() = runTest {
        val store = newStore()
        assertNull(store.currentSessionBlocking())
        store.save(session(refreshToken = "r1"))
        assertEquals("r1", store.currentSessionBlocking()?.refreshToken)
        assertTrue(store.clearSessionBlocking("r1"))
        assertNull(store.current())
    }

    @Test
    fun `session flow emits sign-in and sign-out`() = runTest {
        val store = newStore()
        store.session.test {
            assertNull(awaitItem())
            store.save(session())
            assertEquals(session(), awaitItem())
            store.clear()
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `aes-gcm uses a fresh iv and detects tampering`() {
        val cipher = testCipher(key)
        val plaintext = "secret".encodeToByteArray()
        val a = cipher.encrypt(plaintext)
        val b = cipher.encrypt(plaintext)
        assertNotEquals(Base64.getEncoder().encodeToString(a), Base64.getEncoder().encodeToString(b))
        assertArrayEquals(plaintext, cipher.decrypt(a))

        val tampered = a.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(tampered) }

        val otherPurpose = AesGcmCipher({ key }, "something-else".encodeToByteArray())
        assertThrows(GeneralSecurityException::class.java) { otherPurpose.decrypt(a) }
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(byteArrayOf(9, 12)) }
    }
}
