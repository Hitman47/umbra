package io.github.mkdevtests.umbra.nas

import java.util.UUID

/** How a NAS is read. */
enum class Protocol(val label: String) {
    Smb("SMB"),
    Nfs("NFS"),
    WebDav("WebDAV"),
}

/**
 * One NAS and the folders read from it ([shares]):
 * - SMB: [host] may carry a port ("nas.local:4450"), shares are SMB share names;
 * - NFS: [host] is the NAS, shares are export paths ("/media/sdb1/Vidéos/Films");
 * - WebDAV: [host] is the server's URL ("http://nas:5005/"), shares are folders below it.
 */
data class NasSource(
    val host: String,
    val shares: List<String>,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    /** Stable identity of the source, kept when it is edited. */
    val id: String = "",
    /** Name chosen by the user; the address when blank. */
    val name: String = "",
    /** Name of a share in the app's paths when it isn't its default one ("Media (Zima 2)"): see [withRoots]. */
    val roots: Map<String, String> = emptyMap(),
    /** Folders left out, as app paths ("Media\Photos"): not scanned, browsed nor played. */
    val excluded: List<String> = emptyList(),
    val protocol: Protocol = Protocol.Smb,
    /** Where the NAS is reached when [host] doesn't answer: its Tailscale address, away from home (SMB, NFS). */
    val fallbackHost: String = "",
    /** Folders of the Perso tab, as app paths: played as plain videos, out of the library. */
    val personal: List<String> = emptyList(),
) {
    /** [host], then [fallbackHost] if any. */
    fun hosts(): List<String> = listOf(host, fallbackHost).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** The name, else the NAS's address ("nas:5005" for a WebDAV URL). */
    val label get() = name.ifBlank { host.substringAfter("://").substringBefore('/') }

    /** [path] is an excluded folder or inside one; a Perso folder never is. */
    fun isExcluded(path: String) = !isPersonal(path) && excluded.any { path.within(it) }

    /** [path] is a Perso folder or inside one. */
    fun isPersonal(path: String) = personal.any { path.within(it) }

    /** First folder of the app's paths for [share]. */
    fun rootOf(share: String) = roots[share] ?: defaultRoot(share)
}

/** [this] is [folder] or inside it. */
fun String.within(folder: String) = equals(folder, ignoreCase = true) || startsWith("$folder\\", ignoreCase = true)

/** A share's name in the app's paths: its own for SMB, the last folder of an NFS export or a WebDAV folder ("Films"). */
fun defaultRoot(share: String): String =
    share.trimEnd('/').substringAfterLast('/').ifEmpty { share }.replace('\\', '/')

/**
 * [this] with a root name for each share that no other source uses, so that
 * paths stay unique across NAS: a share keeps its root (its default name for a
 * new one), unless another source has it already: then "Media (Zima 2)".
 * Names are compared ignoring case, as SMB does.
 */
fun NasSource.withRoots(others: List<NasSource>): NasSource {
    val taken = others.flatMap { other -> other.shares.map { other.rootOf(it).lowercase() } }.toMutableSet()
    val suffix = label.replace('\\', '/')
    val roots = shares.associateWith { share ->
        val base = defaultRoot(share)
        val root = sequenceOf(rootOf(share), base, "$base ($suffix)")
            .plus(generateSequence(2) { it + 1 }.map { "$base ($suffix $it)" })
            .first { it.lowercase() !in taken }
        taken += root.lowercase()
        root
    }
    return copy(roots = roots.filter { (share, root) -> defaultRoot(share) != root })
}

fun newSourceId(): String = UUID.randomUUID().toString().take(8)
