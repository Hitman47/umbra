package io.github.mkdevtests.umbra.nas

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** SMB shares of one NAS. [host] may carry a port ("nas.local:4450"). */
data class SmbSource(
    val host: String,
    val shares: List<String>,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
)

/**
 * Persists the NAS source. The password is encrypted with an AES key that
 * lives in the Android Keystore and never leaves it.
 */
class SourceStore(context: Context) {

    private val prefs = context.getSharedPreferences("sources", Context.MODE_PRIVATE)

    fun load(): SmbSource? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        return SmbSource(
            host = host,
            shares = prefs.getString(KEY_SHARES, null)?.split('\n')
                ?: listOfNotNull(prefs.getString(KEY_SHARE, null)), // single-share format of v0.1
            username = prefs.getString(KEY_USER, "").orEmpty(),
            password = prefs.getString(KEY_PASSWORD, null)?.let(::decrypt).orEmpty(),
            domain = prefs.getString(KEY_DOMAIN, "").orEmpty(),
        )
    }

    fun save(source: SmbSource) = prefs.edit {
        putString(KEY_HOST, source.host)
        putString(KEY_SHARES, source.shares.joinToString("\n"))
        remove(KEY_SHARE)
        putString(KEY_USER, source.username)
        putString(KEY_PASSWORD, encrypt(source.password))
        putString(KEY_DOMAIN, source.domain)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** Returns null if the key is gone (e.g. app data restored elsewhere). */
    private fun decrypt(stored: String): String? = runCatching {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        }
        String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE))
    }.getOrNull()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "umbra_sources"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val KEY_HOST = "host"
        const val KEY_SHARE = "share"
        const val KEY_SHARES = "shares"
        const val KEY_USER = "user"
        const val KEY_PASSWORD = "password"
        const val KEY_DOMAIN = "domain"
    }
}
