package com.reverseplan.app.domain

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-based encryption for private task-instance records. No password is persisted. */
internal object SecretRecordCipher {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun newSalt(): String = ByteArray(16).also(random::nextBytes).toBase64()

    fun verifier(key: String, salt: String): String = MessageDigest.getInstance("SHA-256")
        .digest(deriveKey(key, salt).encoded)
        .toBase64()

    fun verifies(key: String, salt: String, expectedVerifier: String): Boolean =
        salt.isNotBlank() && expectedVerifier.isNotBlank() && MessageDigest.isEqual(
            verifier(key, salt).fromBase64(), expectedVerifier.fromBase64()
        )

    fun encrypt(plainText: String, key: String, salt: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(key, salt), GCMParameterSpec(TAG_BITS, iv))
        }
        return "v1:${iv.toBase64()}:${cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)).toBase64()}"
    }

    fun decrypt(payload: String, key: String, salt: String): String {
        if (payload.isBlank()) return ""
        val parts = payload.split(':')
        require(parts.size == 3 && parts[0] == "v1") { "秘密紀錄格式無法讀取" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, deriveKey(key, salt), GCMParameterSpec(TAG_BITS, parts[1].fromBase64()))
        }
        return cipher.doFinal(parts[2].fromBase64()).toString(Charsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: String): SecretKeySpec {
        require(password.isNotBlank()) { "金鑰不可空白" }
        val spec = PBEKeySpec(password.toCharArray(), salt.fromBase64(), ITERATIONS, KEY_BITS)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}
