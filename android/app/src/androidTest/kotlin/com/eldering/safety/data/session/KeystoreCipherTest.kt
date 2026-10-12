package com.eldering.safety.data.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.GeneralSecurityException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device check of the Android Keystore path used for session tokens (Robolectric has no AndroidKeyStore).
 * Uses its own key alias, so it never touches the real session key. Run with
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.eldering.safety.data.session.KeystoreCipherTest`
 * (note: connected tests reinstall the app).
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCipherTest {

    private val alias = "eldering.test.keystore-cipher"

    @After
    fun deleteKey() = AndroidKeystoreKeys.delete(alias)

    @Test
    fun roundTripsWithAKeystoreKeyAndRejectsTampering() {
        val cipher = AesGcmCipher({ AndroidKeystoreKeys.getOrCreateAesKey(alias) }, SessionStore.ASSOCIATED_DATA)
        val secret = "refresh-token-value".encodeToByteArray()

        val sealed = cipher.encrypt(secret)
        assertFalse(sealed.decodeToString(throwOnInvalidSequence = false).contains("refresh-token-value"))
        assertArrayEquals(secret, cipher.decrypt(sealed))

        // Same alias from a fresh cipher (as after a process restart) still decrypts.
        val again = AesGcmCipher({ AndroidKeystoreKeys.getOrCreateAesKey(alias) }, SessionStore.ASSOCIATED_DATA)
        assertArrayEquals(secret, again.decrypt(sealed))

        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(sealed) }
    }
}
