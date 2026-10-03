package io.github.mkdevtests.umbra.bench

import android.content.Context
import io.github.mkdevtests.umbra.NyxaraApp
import io.github.mkdevtests.umbra.nas.NfsNas
import io.github.mkdevtests.umbra.nas.toUserMessage
import io.github.mkdevtests.umbra.player.BenchStep
import io.github.mkdevtests.umbra.player.PlayItem
import io.github.mkdevtests.umbra.player.routeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The protocol test: the same few videos read in SMB, WebDAV and NFS by
 * the player itself, each run measured like any playback (Réglages ›
 * Mesures de lecture, one line per protocol).
 *
 * WebDAV is read by mpv directly over HTTP; NFS goes through the app's
 * local server, like SMB.
 */
class ProtocolBench(private val app: NyxaraApp) {

    private val prefs = app.getSharedPreferences("bench", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val http by lazy { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build() }

    fun load(): BenchConfig = prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString<BenchConfig>(it) }.getOrNull() } ?: defaults()

    fun save(config: BenchConfig) = prefs.edit().putString(KEY, json.encodeToString(config)).apply()

    /** The NAS of the first source, ZimaOS's WebDAV port, and the folder given by the user. */
    private fun defaults(): BenchConfig {
        val host = app.nas?.sources?.firstOrNull()?.host?.substringBefore(':').orEmpty()
        val folder = videoFolders(app.library.library.value).map { it.first }
            .firstOrNull { it.substringAfterLast('\\').equals("Films", ignoreCase = true) }.orEmpty()
        return BenchConfig(
            smbFolder = folder,
            webdavUrl = if (host.isEmpty()) "" else "http://$host:5005/media/sdb1/Vidéos/Films",
            nfsServer = host,
            nfsExport = "/media/sdb1/Vidéos/Films",
        )
    }

    /** One video of the folder read in each protocol: null when it works, else why not. */
    suspend fun check(config: BenchConfig, webdavPassword: String): Map<Protocol, String?> = withContext(Dispatchers.IO) {
        val file = benchFiles(app.library.library.value, config.smbFolder, 1, 0).firstOrNull()
            ?: return@withContext Protocol.entries.associateWith { "aucune vidéo de la bibliothèque dans ce dossier" }
        val relative = relativePath(file.path, config.smbFolder)
        Protocol.entries.associateWith { protocol ->
            runCatching {
                when (protocol) {
                    Protocol.Smb -> app.nas?.open(file.path)?.use { it.size } ?: error("aucun NAS")
                    Protocol.WebDav -> webdavCheck(config, webdavPassword, relative)
                    Protocol.Nfs -> NfsNas(config.nfsServer.trim(), config.nfsExport.trim()).open(relative).use { it.size }
                }
                null
            }.getOrElse { it.toUserMessage().ifBlank { it.toString() } }
        }
    }

    private fun webdavCheck(config: BenchConfig, password: String, relative: String) {
        val (user, pass) = webdavAccount(config, password)
        val request = Request.Builder()
            .url(webdavUrlWithoutAccount(config.webdavUrl, relative))
            .header("Range", "bytes=0-0")
            .apply { if (user.isNotEmpty()) header("Authorization", Credentials.basic(user, pass)) }
            .build()
        http.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> Unit
                response.code == 401 -> error("compte refusé (401)" + if (response.header("WWW-Authenticate")?.startsWith("Digest") == true) ", Digest : le lecteur essaiera quand même" else "")
                response.code == 404 -> error("fichier introuvable (404) : vérifie l'URL du dossier")
                else -> error("HTTP ${response.code}")
            }
        }
    }

    /** The WebDAV account: the one typed, else the SMB source's. */
    private fun webdavAccount(config: BenchConfig, password: String): Pair<String, String> {
        if (config.webdavUser.isNotBlank()) return config.webdavUser.trim() to password
        val source = app.nas?.sourceOf(config.smbFolder)
        return source?.username.orEmpty() to source?.password.orEmpty()
    }

    /** The player's queue: [BenchConfig.files] videos, each in every protocol. */
    fun queue(config: BenchConfig, webdavPassword: String): List<PlayItem> {
        val files = benchFiles(app.library.library.value, config.smbFolder, config.files, Random.nextLong())
        val source = app.nas?.sourceOf(config.smbFolder)
        val nfs = NfsNas(config.nfsServer.trim(), config.nfsExport.trim())
        val (user, pass) = webdavAccount(config, webdavPassword)
        val runs = benchOrder(files, Protocol.entries)
        return runs.mapIndexed { i, (file, protocol) ->
            val relative = relativePath(file.path, config.smbFolder)
            val name = file.path.substringAfterLast('\\')
            val (url, statsKey, host) = when (protocol) {
                Protocol.Smb -> Triple(app.streamServer.urlFor(file.path), file.path, source?.host.orEmpty())
                Protocol.WebDav -> Triple(webdavUrl(config.webdavUrl, relative, user, pass), null, config.webdavUrl.substringAfter("://").substringBefore('/'))
                Protocol.Nfs -> {
                    val key = "nfs:${file.path}"
                    Triple(app.streamServer.urlFor(key, name) { nfs.open(relative) }, key, config.nfsServer)
                }
            }
            PlayItem(
                url = url,
                title = file.title,
                subtitle = "Test des protocoles · ${protocol.label} · ${i + 1}/${runs.size}",
                statsKey = statsKey,
                bench = BenchStep(protocol.label, source?.label ?: host, routeOf(host)),
            )
        }
    }

    private companion object {
        const val KEY = "config"
    }
}
