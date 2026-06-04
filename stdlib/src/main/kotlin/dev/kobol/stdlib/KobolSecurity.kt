package dev.kobol.stdlib

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Security helpers for Kobol programs.
 *
 * Kobol syntax (all methods are @JvmStatic — called via CALL kobol.security.*):
 *   CALL kobol.security.sha256    USING input GIVING hash
 *   CALL kobol.security.md5       USING input GIVING hash
 *   CALL kobol.security.encrypt   USING plaintext, key GIVING ciphertext
 *   CALL kobol.security.decrypt   USING ciphertext, key GIVING plaintext
 *   CALL kobol.security.base64enc USING input GIVING encoded
 *   CALL kobol.security.base64dec USING encoded GIVING decoded
 */
object KobolSecurity {

    /** SHA-256 hash of [input], returned as lowercase hex string. */
    @JvmStatic fun sha256(input: String): String =
        digest("SHA-256", input)

    /** MD5 hash of [input], returned as lowercase hex string. */
    @JvmStatic fun md5(input: String): String =
        digest("MD5", input)

    private fun digest(algorithm: String, input: String): String {
        val bytes = MessageDigest.getInstance(algorithm).digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * AES/ECB/PKCS5Padding encryption.
     * [key] must be exactly 16 characters (128-bit AES).
     * Returns Base64-encoded ciphertext.
     */
    @JvmStatic fun encrypt(plaintext: String, key: String): String {
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    /**
     * AES/ECB/PKCS5Padding decryption.
     * [key] must be exactly 16 characters (128-bit AES).
     * [ciphertext] must be Base64-encoded (from [encrypt]).
     */
    @JvmStatic fun decrypt(ciphertext: String, key: String): String {
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey)
        return String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), Charsets.UTF_8)
    }

    /** Base64-encode [input]. */
    @JvmStatic fun base64enc(input: String): String =
        Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))

    /** Base64-decode [encoded] to plain text. */
    @JvmStatic fun base64dec(encoded: String): String =
        String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
}
