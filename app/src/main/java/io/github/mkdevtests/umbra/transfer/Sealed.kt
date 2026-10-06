package io.github.mkdevtests.umbra.transfer

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The settings sent from one device to another on the home network, sealed
 * with the code the receiving screen shows: AES-GCM under a key drawn from
 * the code (PBKDF2), so that only who reads the screen can send, and what
 * travels says nothing to anyone else.
 */
object Sealed {
    private const val SALT = 16
    private const val IV = 12
    private const val ITERATIONS = 120_000
    private val random = SecureRandom()

    /** A fresh code to show: [CODE_DIGITS] digits. */
    fun newCode(): String = (1..CODE_DIGITS).map { random.nextInt(10) }.joinToString("")

    /** "12345678" shown as "1234 5678"; spaces typed in a code don't count. */
    fun spaced(code: String) = code.chunked(4).joinToString(" ")
    fun normalized(typed: String) = typed.filter { it.isDigit() }

    fun seal(code: String, plain: ByteArray): ByteArray {
        val salt = ByteArray(SALT).also(random::nextBytes)
        val iv = ByteArray(IV).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(code, salt), GCMParameterSpec(128, iv))
        return salt + iv + cipher.doFinal(plain)
    }

    /** [sealed]'s content; null when the code is not the one it was sealed with (or it was altered). */
    fun open(code: String, sealed: ByteArray): ByteArray? {
        if (sealed.size <= SALT + IV) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(code, sealed.copyOfRange(0, SALT)), GCMParameterSpec(128, sealed.copyOfRange(SALT, SALT + IV)))
            cipher.doFinal(sealed, SALT + IV, sealed.size - SALT - IV)
        }.getOrNull()
    }

    private fun key(code: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(normalized(code).toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    const val CODE_DIGITS = 8
}
