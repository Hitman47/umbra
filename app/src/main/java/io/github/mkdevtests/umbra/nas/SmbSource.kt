package io.github.mkdevtests.umbra.nas

import java.util.UUID

/** SMB shares of one NAS. [host] may carry a port ("nas.local:4450"). */
data class SmbSource(
    val host: String,
    val shares: List<String>,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    /** Stable identity of the source, kept when it is edited. */
    val id: String = "",
    /** Name chosen by the user; the address when blank. */
    val name: String = "",
    /** Name of a share in the app's paths when it isn't the share's own ("Media (Zima 2)"): see [withRoots]. */
    val roots: Map<String, String> = emptyMap(),
) {
    val label get() = name.ifBlank { host }

    /** First folder of the app's paths for [share]. */
    fun rootOf(share: String) = roots[share] ?: share
}

/**
 * [this] with a root name for each share that no other source uses, so that
 * paths stay unique across NAS: a share keeps its root (its name for a new
 * one), unless another source has it already: then "Media (Zima 2)".
 * Share names are compared as SMB does, ignoring case.
 */
fun SmbSource.withRoots(others: List<SmbSource>): SmbSource {
    val taken = others.flatMap { other -> other.shares.map { other.rootOf(it).lowercase() } }.toMutableSet()
    val suffix = label.replace('\\', '/')
    val roots = shares.associateWith { share ->
        val root = sequenceOf(rootOf(share), share, "$share ($suffix)")
            .plus(generateSequence(2) { it + 1 }.map { "$share ($suffix $it)" })
            .first { it.lowercase() !in taken }
        taken += root.lowercase()
        root
    }
    return copy(roots = roots.filter { (share, root) -> share != root })
}

fun newSourceId(): String = UUID.randomUUID().toString().take(8)
