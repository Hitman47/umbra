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
import okhttp3.RequestBody.Companion.toRequestBody
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

    /**
     * One video of the folder looked for in each protocol. When WebDAV or NFS
     * doesn't have it where expected, its path is tried without its first
     * folders: the one that matches tells which SMB folder the WebDAV or NFS
     * folder really is ([BenchCheck.folder]). Otherwise, what the folder holds.
     */
    suspend fun check(config: BenchConfig, webdavPassword: String): BenchCheck = withContext(Dispatchers.IO) {
        val file = benchFiles(app.library.library.value, config.smbFolder, 1, 0).firstOrNull()
            ?: return@withContext BenchCheck(Protocol.entries.associateWith { "aucune vidéo de la bibliothèque dans ce dossier" })
        val relative = relativePath(file.path, config.smbFolder)
        val folders = mutableMapOf<Protocol, String>()
        val problems = Protocol.entries.associateWith { protocol ->
            runCatching {
                when (protocol) {
                    Protocol.Smb -> {
                        app.nas?.open(file.path)?.use { it.size } ?: error("aucun NAS")
                        null
                    }
                    Protocol.WebDav -> webdavCheck(config, webdavPassword, file.path, relative)?.let { (folder, problem) ->
                        folder?.let { folders[protocol] = it }
                        problem
                    }
                    Protocol.Nfs -> nfsCheck(config, file.path, relative)?.let { (folder, problem) ->
                        folder?.let { folders[protocol] = it }
                        problem
                    }
                }
            }.getOrElse { it.toUserMessage().ifBlank { it.toString() } }
        }
        BenchCheck(problems, folders.values.firstOrNull(), file.path)
    }

    /** Null when found where expected; else the SMB folder that matches, or why it fails. */
    private fun webdavCheck(config: BenchConfig, password: String, file: String, relative: String): Pair<String?, String?>? {
        val (user, pass) = webdavAccount(config, password)
        val auth = if (user.isNotEmpty()) Credentials.basic(user, pass) else null
        fun status(url: String): Int {
            val request = Request.Builder().url(url).header("Range", "bytes=0-0").apply { auth?.let { header("Authorization", it) } }.build()
            return http.newCall(request).execute().use { it.code }
        }
        val first = status(webdavUrlWithoutAccount(config.webdavUrl, relative))
        when {
            first in 200..299 -> return null
            first == 401 -> error("compte refusé (401) : renseigne le compte WebDAV de ZimaOS")
            first != 404 -> error("HTTP $first")
        }
        for ((candidate, folder) in pathCandidates(file)) {
            for (form in unicodeForms(candidate)) {
                if (status(webdavUrlWithoutAccount(config.webdavUrl, form)) in 200..299) {
                    return folder to "trouvé, mais l'URL correspond au dossier SMB « $folder »"
                }
            }
        }
        // Not found anywhere: what the folder (or the first parent that answers) holds.
        val base = config.webdavUrl.trim().trimEnd('/')
        val host = base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/')
        val segments = base.removePrefix(host).split('/').filter { it.isNotEmpty() }
        for (depth in segments.size downTo 0) {
            val url = host + segments.take(depth).joinToString("") { "/$it" } + "/"
            val names = webdavList(url, auth) ?: continue
            val where = url.removePrefix(host).ifEmpty { "/" }
            val shown = names.take(10).joinToString(", ").ifEmpty { "(vide)" }
            return null to (if (depth == segments.size) "« ${file.substringAfterLast('\\')} » introuvable. " else "dossier introuvable. ") +
                "Contenu de « $where » : $shown"
        }
        return null to "fichier introuvable (404), et le serveur ne liste aucun dossier : vérifie l'URL"
    }

    /** Names in a WebDAV folder, or null if it doesn't answer as a folder. */
    private fun webdavList(url: String, auth: String?): List<String>? {
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", ByteArray(0).toRequestBody(null))
            .header("Depth", "1")
            .apply { auth?.let { header("Authorization", it) } }
            .build()
        return runCatching {
            http.newCall(request).execute().use { response -> if (response.code == 207) propfindNames(response.body?.string().orEmpty()) else null }
        }.getOrNull()
    }

    /** Like [webdavCheck], in the NFS export. */
    private fun nfsCheck(config: BenchConfig, file: String, relative: String): Pair<String?, String?>? {
        val nfs = NfsNas(config.nfsServer.trim(), config.nfsExport.trim())
        if (unicodeForms(relative).any(nfs::exists)) return null
        for ((candidate, folder) in pathCandidates(file)) {
            if (unicodeForms(candidate).any(nfs::exists)) return folder to "trouvé, mais l'export correspond au dossier SMB « $folder »"
        }
        val shown = nfs.list("").take(10).joinToString(", ").ifEmpty { "(vide)" }
        return null to "« ${file.substringAfterLast('\\')} » introuvable. Contenu de l'export : $shown"
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

/**
 * The access check: [problems] per protocol (null: works); [folder], the SMB
 * folder WebDAV or NFS really match when it isn't the one chosen; [file], the video looked for.
 */
data class BenchCheck(val problems: Map<Protocol, String?>, val folder: String? = null, val file: String? = null)
