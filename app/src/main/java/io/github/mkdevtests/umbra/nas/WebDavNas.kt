package io.github.mkdevtests.umbra.nas

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.FileNotFoundException
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * WebDAV access to a NAS: [NasSource.host] is the server's URL, each share a
 * folder below it. The player reads files over HTTP by itself ([directUrl]);
 * the app only lists folders. Read only: GET, HEAD and PROPFIND, nothing else
 * (ReadOnlyTest keeps it that way).
 *
 * Blocking API: call from a background thread.
 */
class WebDavNas(override val source: NasSource, private val http: OkHttpClient = HTTP) : NasClient {

    private val base: HttpUrl = source.host.trim().let { if ("://" in it) it else "http://$it" }.toHttpUrl()
    private val auth = source.username.takeIf { it.isNotBlank() }?.let { Credentials.basic(it, source.password, Charsets.UTF_8) }

    /** The URL of [path] ("Films\Dune (2021)\Dune.mkv", a share being one or more folders below [base]). */
    fun urlOf(path: String): HttpUrl = base.newBuilder().apply {
        // Without the trailing empty segment of "http://nas:5005/".
        if (base.pathSegments.lastOrNull() == "") removePathSegment(base.pathSize - 1)
        path.split('\\', '/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
    }.build()

    override fun list(path: String): List<NasEntry> {
        if (path.isEmpty()) return source.shares.map { NasEntry(it, it, isDirectory = true, size = 0) }
        val folder = urlOf(path).newBuilder().addPathSegment("").build() // trailing "/": a folder
        return propfind(folder).map { (name, isDirectory, size, modified) -> NasEntry(name, "$path\\$name", isDirectory, size, modified) }
    }

    override fun open(path: String): RemoteFile = WebDavFile(urlOf(path))

    override fun directUrl(path: String): String = urlOf(path).newBuilder().apply {
        if (source.username.isNotBlank()) username(source.username).password(source.password)
    }.build().toString()

    /** The folders at the server's URL. */
    override fun availableShares(): List<String> = propfind(base).filter { it.isDirectory }.map { it.name }.sorted()

    override fun close() {}

    private fun propfind(url: HttpUrl): List<DavEntry> {
        val request = request(url).method("PROPFIND", PROPFIND_BODY.toRequestBody(null)).header("Depth", "1").build()
        return http.newCall(request).execute().use { response ->
            check(response, url)
            parsePropfind(response.body!!.string(), url.encodedPath)
        }
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url).apply { auth?.let { header("Authorization", it) } }

    private fun check(response: Response, url: HttpUrl) {
        when {
            response.code == 207 || response.isSuccessful -> Unit
            response.code == 401 -> throw IOException(
                "WebDAV : compte refusé" + if (response.header("WWW-Authenticate")?.startsWith("Digest", true) == true) " (Digest non géré)" else "",
            )
            response.code == 403 -> throw RefusedException("WebDAV : accès refusé à ${url.encodedPath}")
            response.code == 404 -> throw FileNotFoundException("WebDAV : ${url.encodedPath} introuvable")
            else -> throw IOException("WebDAV : HTTP ${response.code} sur ${url.encodedPath}")
        }
    }

    /** A file read by ranges: one request per read. Used when the player can't read the URL itself. */
    private inner class WebDavFile(private val url: HttpUrl) : RemoteFile {
        override val size: Long by lazy {
            http.newCall(request(url).head().build()).execute().use { response ->
                check(response, url)
                response.header("Content-Length")?.toLongOrNull() ?: throw IOException("WebDAV : taille inconnue")
            }
        }

        override fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int {
            if (length == 0) return 0
            val request = request(url).header("Range", "bytes=$fileOffset-${fileOffset + length - 1}").build()
            return http.newCall(request).execute().use { response ->
                if (response.code == 416) return -1 // past the end
                check(response, url)
                val stream = response.body!!.byteStream()
                var done = 0
                while (done < length) {
                    val count = stream.read(buffer, bufferOffset + done, length - done)
                    if (count < 0) break
                    done += count
                }
                if (done == 0) -1 else done
            }
        }

        override fun close() {}
    }

    companion object {
        private val HTTP = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
    }
}

/** An entry of a WebDAV listing. */
data class DavEntry(val name: String, val isDirectory: Boolean, val size: Long, val modified: Long)

/**
 * The entries of a PROPFIND answer for the folder at [folderPath] (encoded),
 * the folder itself left out. Read with patterns: no XML parser to depend on.
 */
fun parsePropfind(xml: String, folderPath: String): List<DavEntry> {
    val responses = Regex("""<(?:[\w-]+:)?response\b[^>]*>(.*?)</(?:[\w-]+:)?response>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)
    fun tag(block: String, name: String) =
        Regex("""<(?:[\w-]+:)?$name\b[^>]*>(.*?)</(?:[\w-]+:)?$name>""", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim()
    val folder = decodePath(folderPath).trimEnd('/')
    return responses.mapNotNull { match ->
        val block = match.groupValues[1]
        val href = tag(block, "href") ?: return@mapNotNull null
        val path = decodePath(href.substringAfter("://").let { if (href.contains("://")) "/" + it.substringAfter('/') else it }).trimEnd('/')
        if (path == folder) return@mapNotNull null
        val name = path.substringAfterLast('/')
        if (name.isEmpty() || name.startsWith('.')) return@mapNotNull null
        val isDirectory = Regex("""<(?:[\w-]+:)?collection\b""").containsMatchIn(block)
        val size = tag(block, "getcontentlength")?.toLongOrNull() ?: 0
        val modified = tag(block, "getlastmodified")?.let { date ->
            runCatching { ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        } ?: 0
        DavEntry(name, isDirectory, if (isDirectory) 0 else size, modified)
    }.toList()
}

/** %XX sequences of a URL path as UTF-8 text; "+" stays a plus. */
private fun decodePath(path: String): String = java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
