package io.github.mkdevtests.umbra.nas

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What is saved of a source: the password encrypted. */
@Serializable
private data class StoredSource(
    val id: String,
    val name: String = "",
    val host: String,
    val shares: List<String>,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val roots: Map<String, String> = emptyMap(),
    val excluded: List<String> = emptyList(),
)

/**
 * Persists the NAS sources. Passwords are encrypted with an AES key that
 * lives in the Android Keystore and never leaves it.
 */
class SourceStore(context: Context) {

    private val prefs = context.getSharedPreferences("sources", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<SmbSource> {
        val stored = prefs.getString(KEY_LIST, null)
            ?: return listOfNotNull(loadSingle()).also { if (it.isNotEmpty()) save(it) } // keeps its new id
        return runCatching { json.decodeFromString<List<StoredSource>>(stored) }
            .onFailure { Log.w(TAG, "sources unreadable", it) }
            .getOrDefault(emptyList())
            .map { SmbSource(it.host, it.shares, it.username, it.password.let(::decrypt).orEmpty(), it.domain, it.id, it.name, it.roots, it.excluded) }
    }

    fun save(sources: List<SmbSource>) = prefs.edit {
        val stored = sources.map { StoredSource(it.id, it.name, it.host, it.shares, it.username, encrypt(it.password), it.domain, it.roots, it.excluded) }
        putString(KEY_LIST, json.encodeToString(stored))
        listOf(KEY_HOST, KEY_SHARE, KEY_SHARES, KEY_USER, KEY_PASSWORD, KEY_DOMAIN).forEach(::remove)
    }

    /** The single source of earlier versions. */
    private fun loadSingle(): SmbSource? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        return SmbSource(
            host = host,
            shares = prefs.getString(KEY_SHARES, null)?.split('\n')
                ?: listOfNotNull(prefs.getString(KEY_SHARE, null)), // single-share format of v0.1
            username = prefs.getString(KEY_USER, "").orEmpty(),
            password = prefs.getString(KEY_PASSWORD, null)?.let(::decrypt).orEmpty(),
            domain = prefs.getString(KEY_DOMAIN, "").orEmpty(),
            id = newSourceId(),
        )
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
        const val TAG = "SourceStore"
        const val KEY_LIST = "list"
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
