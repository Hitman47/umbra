package io.github.mkdevtests.umbra.bench

import io.github.mkdevtests.umbra.library.Library
import kotlinx.serialization.Serializable
import kotlin.random.Random

/** Protocols compared by the test; SMB is what the app uses. */
enum class Protocol(val label: String) { Smb("SMB"), WebDav("WebDAV"), Nfs("NFS") }

/**
 * Where the same folder is found in each protocol: [smbFolder] as an app
 * path ("Vidéos\Films"), [webdavUrl] the folder's URL, [nfsExport] the
 * folder exported by [nfsServer].
 */
@Serializable
data class BenchConfig(
    val smbFolder: String = "",
    val webdavUrl: String = "",
    /** Blank: the SMB account. */
    val webdavUser: String = "",
    val nfsServer: String = "",
    val nfsExport: String = "",
    val files: Int = 3,
)

/** A video of the test, with its title for the screens. */
data class BenchFile(val path: String, val title: String)

/** [count] videos of the library under [folder], drawn by [seed]. */
fun benchFiles(library: Library, folder: String, count: Int, seed: Long): List<BenchFile> {
    val prefix = folder.trimEnd('\\') + "\\"
    val movies = library.movies.map { BenchFile(it.file, it.title) }
    val episodes = library.shows.flatMap { show ->
        show.seasons.flatMap { it.episodes }.map { BenchFile(it.file, "${show.title} S%02dE%02d".format(it.season, it.number)) }
    }
    return (movies + episodes).filter { it.path.startsWith(prefix, ignoreCase = true) }.shuffled(Random(seed)).take(count)
}

/** [file] below [folder]: "Dune (2021)\Dune.mkv". */
fun relativePath(file: String, folder: String): String = file.substring(folder.trimEnd('\\').length).trimStart('\\')

/** Folders holding videos, two levels deep ("Vidéos\Films"), with how many: to pick the test folder. */
fun videoFolders(library: Library): List<Pair<String, Int>> {
    val files = library.movies.map { it.file } + library.shows.flatMap { show -> show.seasons.flatMap { it.episodes }.map { it.file } }
    return files.flatMap { file ->
        val parts = file.split('\\').dropLast(1)
        (1..minOf(parts.size, 3)).map { parts.take(it).joinToString("\\") }
    }.groupingBy { it }.eachCount().toList().filter { it.second >= 3 }.sortedBy { it.first.lowercase() }
}

/**
 * The URL mpv reads [relative] at over WebDAV, with the account in it
 * (mpv's HTTP client answers Basic and Digest authentication from it).
 */
fun webdavUrl(folderUrl: String, relative: String, user: String, password: String): String {
    val base = folderUrl.trim().trimEnd('/')
    val scheme = base.substringBefore("://", "http")
    val rest = base.substringAfter("://")
    val host = rest.substringBefore('/')
    // The folder as typed ("/media/sdb1/Vidéos/Films") or already encoded ("Vid%C3%A9os"): encoded once.
    val folder = rest.substringAfter('/', "").split('/').filter { it.isNotEmpty() }.joinToString("") { "/" + encode(it, PATH + "%") }
    val credentials = if (user.isNotEmpty()) "${encode(user, USERINFO)}:${encode(password, USERINFO)}@" else ""
    val path = relative.split('\\', '/').filter { it.isNotEmpty() }.joinToString("/") { encode(it, PATH) }
    return "$scheme://$credentials$host$folder/$path"
}

/** The same URL without the account, for display and for a client that authenticates by header. */
fun webdavUrlWithoutAccount(folderUrl: String, relative: String) = webdavUrl(folderUrl, relative, "", "")

/**
 * The runs: every file in each protocol, the order turning from one file to
 * the next so that no protocol always finds the file in the NAS's cache.
 */
fun benchOrder(files: List<BenchFile>, protocols: List<Protocol>): List<Pair<BenchFile, Protocol>> =
    files.flatMapIndexed { i, file -> protocols.indices.map { j -> file to protocols[(i + j) % protocols.size] } }

/** Where each run seeks, as fractions of the duration: the same for every protocol. */
val BENCH_SEEKS = listOf(0.25, 0.6, 0.1)

private const val PATH = "-._~!$&'()*+,;=:@"
private const val USERINFO = "-._~!$&'()*+,;="

/** Percent-encoding of [text] in UTF-8, [allowed] characters and letters/digits kept. */
private fun encode(text: String, allowed: String): String = buildString {
    text.toByteArray(Charsets.UTF_8).forEach { byte ->
        val c = byte.toInt() and 0xFF
        val ch = c.toChar()
        if (c < 0x80 && (ch.isLetterOrDigit() || ch in allowed)) append(ch) else append("%%%02X".format(c))
    }
}

/**
 * Where [file] might be below the WebDAV or NFS folder: its path without
 * the first folders, from the longest ("Vidéos\Films\Dune\Dune.mkv"…) to
 * the file name alone, each with the SMB folder it implies.
 */
fun pathCandidates(file: String): List<Pair<String, String>> {
    val parts = file.split('\\').filter { it.isNotEmpty() }
    return (0 until parts.size).map { k -> parts.drop(k).joinToString("\\") to parts.take(k).joinToString("\\") }
}

/** [text] and its decomposed form ("é" as e + accent), as some NAS store names; once if the same. */
fun unicodeForms(text: String): List<String> =
    listOf(text, java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD), java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)).distinct()

/** Names in a WebDAV folder listing (PROPFIND answer), the folder itself left out. */
fun propfindNames(xml: String): List<String> {
    val hrefs = Regex("""<(?:[A-Za-z0-9]+:)?href>([^<]+)</(?:[A-Za-z0-9]+:)?href>""").findAll(xml).map { it.groupValues[1].trim() }.toList()
    return hrefs.drop(1).mapNotNull { href ->
        runCatching { java.net.URLDecoder.decode(href.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8") }.getOrNull()
    }.filter { it.isNotEmpty() }
}
