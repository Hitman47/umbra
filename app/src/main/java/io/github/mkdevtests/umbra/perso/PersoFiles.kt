package io.github.mkdevtests.umbra.perso

import io.github.mkdevtests.umbra.browse.isVideo
import io.github.mkdevtests.umbra.browse.sortForDisplay
import io.github.mkdevtests.umbra.browse.subtitlesFor
import io.github.mkdevtests.umbra.nas.NasEntry
import io.github.mkdevtests.umbra.nas.NasRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** A video of a Perso folder and the subtitle files next to it. */
@Serializable
data class PersoVideo(val path: String, val subtitles: List<String> = emptyList())

/** Folders listed at once while a Perso folder is walked: a deep tree comes quickly, the NAS isn't flooded. */
private const val PARALLEL_LISTINGS = 4

/**
 * Every video below [folder], subfolders included, in the order the tab
 * shows them: each folder's subfolders first, then its videos, by name. A
 * subfolder that can't be read is skipped. [onFound] gets each folder's
 * videos as soon as it is listed, for the first one to play before the end.
 */
suspend fun videosUnder(
    nas: NasRouter,
    folder: String,
    /** Folders left out (Réglages › Perso): not walked. */
    skip: (String) -> Boolean = { false },
    onFound: (suspend (List<PersoVideo>) -> Unit)? = null,
): List<PersoVideo> = withContext(Dispatchers.IO) {
    val gate = Semaphore(PARALLEL_LISTINGS)
    suspend fun walk(path: String, top: Boolean): List<PersoVideo> {
        val listing: List<NasEntry> = try {
            gate.withPermit { nas.list(path, withPersonal = true) }
        } catch (e: Exception) {
            if (top) throw e else return emptyList()
        }
        val shown = sortForDisplay(listing)
        val found = shown.filter { it.isVideo }.map { video -> PersoVideo(video.path, subtitlesFor(video, listing).map { it.path }) }
        if (found.isNotEmpty()) onFound?.invoke(found)
        val below = coroutineScope { shown.filter { it.isDirectory && !skip(it.path) }.map { async { walk(it.path, top = false) } }.awaitAll() }
        return below.flatten() + found
    }
    walk(folder, top = true)
}

/**
 * The last walk of each Perso folder, on the device (no-backup folder): the
 * next shuffle draws from the whole folder at once, while a new walk
 * refreshes it. The [MAX] folders played last are kept.
 */
class PersoTrees(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PersoVideo.serializer())

    fun load(folder: String): List<PersoVideo>? = runCatching {
        fileOf(folder).takeIf { it.exists() }?.let { json.decodeFromString(serializer, it.readText()) }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun save(folder: String, videos: List<PersoVideo>) {
        runCatching {
            dir.mkdirs()
            val file = fileOf(folder)
            val temp = File(file.path + ".tmp")
            temp.writeText(json.encodeToString(serializer, videos))
            temp.renameTo(file)
            dir.listFiles { it: File -> it.name.endsWith(".json") }.orEmpty().sortedByDescending { it.lastModified() }.drop(MAX).forEach { it.delete() }
        }
    }

    private fun fileOf(folder: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(folder.lowercase().toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.json")
    }

    private companion object {
        const val MAX = 20
    }
}
