package com.eldering.safety.data.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Symmetric authenticated encryption for secrets at rest. */
interface TokenCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    /** @throws GeneralSecurityException if the data was tampered with or the key changed. */
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a fresh random 96-bit IV per message (chosen by the provider, as the Android Keystore
 * requires) and fixed associated data that binds ciphertexts to their purpose.
 *
 * Format: `version(1) | ivLength(1) | iv | ciphertext+tag(128 bit)`.
 */
class AesGcmCipher(
    keyProvider: () -> SecretKey,
    private val associatedData: ByteArray,
) : TokenCipher {

    private val key by lazy(keyProvider)

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData)
        val sealed = cipher.doFinal(plaintext)
        val iv = cipher.iv ?: throw GeneralSecurityException("Cipher provided no IV")
        return byteArrayOf(FORMAT_VERSION, iv.size.toByte()) + iv + sealed
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < 2 || ciphertext[0] != FORMAT_VERSION) {
            throw GeneralSecurityException("Unknown ciphertext format")
        }
        val ivLength = ciphertext[1].toInt() and 0xFF
        if (ivLength !in 12..16 || ciphertext.size < 2 + ivLength + TAG_BITS / 8) {
            throw GeneralSecurityException("Truncated ciphertext")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ciphertext, 2, ivLength))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext, 2 + ivLength, ciphertext.size - 2 - ivLength)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val FORMAT_VERSION: Byte = 1
    }
}

/** Non-exportable AES keys in the Android Keystore (hardware-backed where available). */
object AndroidKeystoreKeys {
    private const val PROVIDER = "AndroidKeyStore"

    /**
     * Returns the AES-256-GCM key [alias], creating it on first use.
     *
     * Deliberately no user-authentication or unlocked-device requirement: SafetyService must reach the API while
     * the phone is locked in a pocket, which is exactly when accidents happen. Backups are disabled for the app,
     * so the encrypted blob never leaves the device without its key either.
     */
    @Synchronized
    fun getOrCreateAesKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    /** Drops the key (after which everything encrypted with it is unreadable). */
    @Synchronized
    fun delete(alias: String) {
        KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
    }
}
