package io.github.mkdevtests.umbra.nas

import io.github.mkdevtests.umbra.browse.naturalCompare
import java.io.Closeable
import java.io.IOException

/**
 * Every NAS source behind one tree: the root lists the shares of all of
 * them, each under its root name (see [withRoots]). Paths keep the form they
 * had with a single NAS, "Root\folder\file", so the history and the match
 * corrections made before survive adding a NAS.
 *
 * Blocking API, like [SmbNas]: call from a background thread.
 */
class NasRouter(val connections: List<NasClient>) : Closeable {

    val sources: List<NasSource> get() = connections.map { it.source }

    /** Root name → its NAS and the share's real name. */
    private val byRoot: Map<String, Pair<NasClient, String>> =
        connections.flatMap { nas -> nas.source.shares.map { share -> nas.source.rootOf(share).lowercase() to (nas to share) } }.toMap()

    /** The roots of [source], in name order. */
    fun rootsOf(source: NasSource): List<String> = source.shares.map(source::rootOf).sortedWith(::naturalCompare)

    /** The source holding [path]. */
    fun sourceOf(path: String): NasSource? = byRoot[path.substringBefore('\\').lowercase()]?.first?.source

    /** The address the NAS of [path] is reached at now (local or Tailscale). */
    fun hostOf(path: String): String? = byRoot[path.substringBefore('\\').lowercase()]?.first?.currentHost

    /** [path] is in a folder the user left out. */
    fun isExcluded(path: String) = sourceOf(path)?.isExcluded(path) == true

    fun list(path: String): List<NasEntry> {
        if (path.isEmpty()) {
            return sources.flatMap(::rootsOf).sortedWith(::naturalCompare).map { NasEntry(it, it, isDirectory = true, size = 0) }
        }
        val root = path.substringBefore('\\')
        val (nas, share) = route(root)
        if (nas.source.isExcluded(path)) return emptyList()
        val prefix = "$share\\"
        return nas.list(share + path.substring(root.length))
            .map { entry -> entry.copy(path = root + "\\" + entry.path.removePrefix(prefix)) }
            .filterNot { nas.source.isExcluded(it.path) }
    }

    /** Opens [path] read-only; the caller closes the returned file. */
    fun open(path: String): RemoteFile {
        val root = path.substringBefore('\\')
        val (nas, share) = route(root)
        if (nas.source.isExcluded(path)) throw IOException("Dossier exclu de la bibliothèque")
        return nas.open(share + path.substring(root.length))
    }

    /** A URL the player reads [path] at by itself (WebDAV), or null: through the local server. */
    fun directUrl(path: String): String? {
        val root = path.substringBefore('\\')
        val (nas, share) = byRoot[root.lowercase()] ?: return null
        if (nas.source.isExcluded(path)) return null
        return nas.directUrl(share + path.substring(root.length))
    }

    override fun close() = connections.forEach { it.close() }

    private fun route(root: String) = byRoot[root.lowercase()] ?: throw IOException("Partage inconnu : $root")
}
