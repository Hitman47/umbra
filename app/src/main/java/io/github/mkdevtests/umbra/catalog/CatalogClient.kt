package io.github.mkdevtests.umbra.catalog

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where the catalogue answers: at home, away (Tailscale), and its optional login. */
data class CatalogAddress(val home: String, val away: String = "", val user: String = "", val password: String = "") {
    val configured get() = home.isNotBlank()

    fun bases() = listOf(home, away).map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
}

/**
 * Reads the external catalogue's JSON API, and nothing else: only GET
 * requests (CatalogReadOnlyTest checks it), nothing is ever changed there.
 * Tries the address that answered last, then the other one.
 */
class CatalogClient(http: OkHttpClient) {
    private val http = http.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var last: String? = null

    /** The body of [path] ("/api/v1/…"), as text. */
    fun text(address: CatalogAddress, path: String): String = fetch(address, path) { it.string() }

    /** The bytes of [path]; null when the catalogue has none (404). */
    fun bytes(address: CatalogAddress, path: String): ByteArray? = try {
        fetch(address, path) { it.bytes() }
    } catch (e: NotFound) {
        null
    }

    private class NotFound : IOException("not found")

    private fun <T> fetch(address: CatalogAddress, path: String, read: (okhttp3.ResponseBody) -> T): T {
        val bases = address.bases().sortedByDescending { it == last }
        if (bases.isEmpty()) throw IOException("Adresse non réglée")
        var failure: IOException? = null
        for (base in bases) {
            val request = Request.Builder().url(base + path).get().apply {
                if (address.user.isNotEmpty()) header("Authorization", Credentials.basic(address.user, address.password))
            }.build()
            try {
                http.newCall(request).execute().use { response ->
                    if (response.code == 404) throw NotFound()
                    if (response.code == 401) throw IOException("Identifiant ou mot de passe refusé")
                    if (!response.isSuccessful) throw IOException("Erreur ${response.code}")
                    last = base
                    return read(response.body ?: throw IOException("Réponse vide"))
                }
            } catch (e: NotFound) {
                last = base
                throw e
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("Injoignable")
    }
}
