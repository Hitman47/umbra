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

/** A video of a Perso folder and the subtitle files next to it. */
data class PersoVideo(val path: String, val subtitles: List<String>)

/** Folders listed at once while a Perso folder is walked: a deep tree comes quickly, the NAS isn't flooded. */
private const val PARALLEL_LISTINGS = 4

/**
 * Every video below [folder], subfolders included, in the order the tab
 * shows them: each folder's subfolders first, then its videos, by name. A
 * subfolder that can't be read is skipped.
 */
suspend fun videosUnder(nas: NasRouter, folder: String): List<PersoVideo> = withContext(Dispatchers.IO) {
    val gate = Semaphore(PARALLEL_LISTINGS)
    suspend fun walk(path: String, top: Boolean): List<PersoVideo> {
        val listing: List<NasEntry> = try {
            gate.withPermit { nas.list(path, withPersonal = true) }
        } catch (e: Exception) {
            if (top) throw e else return emptyList()
        }
        val shown = sortForDisplay(listing)
        val below = coroutineScope { shown.filter { it.isDirectory }.map { async { walk(it.path, top = false) } }.awaitAll() }
        val here = shown.filter { it.isVideo }.map { video -> PersoVideo(video.path, subtitlesFor(video, listing).map { it.path }) }
        return below.flatten() + here
    }
    walk(folder, top = true)
}
