package io.github.mkdevtests.umbra.nas

/** What a folder of a NAS is for. */
enum class FolderRole(val label: String) {
    Library("Bibliothèque"),
    Documentaries("Documentaires"),
    Personal("Perso"),
    Off("Non suivi"),
}

/** The role of [sub] ("" for the whole share) of [share] on [source], with the [documentaries] folders (app paths). */
fun roleOf(source: NasSource, share: String, sub: String, documentaries: List<String>): FolderRole {
    val followed = source.shares.firstOrNull { it.equals(share, ignoreCase = true) } ?: return FolderRole.Off
    val root = source.rootOf(followed)
    val path = if (sub.isEmpty()) root else "$root\\$sub"
    return when {
        source.isPersonal(path) -> FolderRole.Personal
        source.isExcluded(path) -> FolderRole.Off
        documentaries.any { path.within(it) } -> FolderRole.Documentaries
        else -> FolderRole.Library
    }
}

/** A NAS and the documentary folders once a folder changed role. */
data class RoleChange(val source: NasSource, val documentaries: List<String>)

/**
 * [source] and [documentaries] once [sub] of [share] takes [role]; its
 * subfolders follow it. A share not read yet is added; then [siblings] (subs
 * of the share, the folders beside the way down to [sub]) are left out, so
 * that only the folder chosen comes in. [others]: the other NAS, for the
 * share's root name. Null when nothing changes or the NAS would be left
 * without a share (removed from Réglages › Sources instead).
 */
fun withRole(
    source: NasSource,
    others: List<NasSource>,
    share: String,
    sub: String,
    role: FolderRole,
    documentaries: List<String>,
    siblings: List<String> = emptyList(),
): RoleChange? {
    val followed = source.shares.firstOrNull { it.equals(share, ignoreCase = true) }
    if (followed == null && role == FolderRole.Off) return null
    var updated = if (followed == null) source.copy(shares = source.shares + share).withRoots(others) else source
    val name = updated.shares.first { it.equals(share, ignoreCase = true) }
    val root = updated.rootOf(name)
    val path = if (sub.isEmpty()) root else "$root\\$sub"
    updated = updated.copy(
        personal = updated.personal.filterNot { it.within(path) },
        excluded = updated.excluded.filterNot { it.within(path) } +
            if (followed == null) siblings.map { "$root\\$it" } else emptyList(),
    )
    // The folder and those inside take its role; a documentary folder around it keeps its own.
    var docs = documentaries.filterNot { it.within(path) }
    when (role) {
        FolderRole.Library -> Unit
        FolderRole.Documentaries -> if (docs.none { path.within(it) }) docs = docs + path
        FolderRole.Personal -> updated = updated.copy(personal = updated.personal + path)
        FolderRole.Off -> updated = if (sub.isEmpty()) {
            updated.copy(shares = updated.shares - name, excluded = updated.excluded.filterNot { it.within(root) }, personal = updated.personal.filterNot { it.within(root) })
        } else {
            updated.copy(excluded = updated.excluded + path)
        }
    }
    if (updated.shares.isEmpty()) return null
    if (updated == source && docs == documentaries) return null
    return RoleChange(updated, docs)
}

/** A folder that looks like documentaries: "Documentaires", "Docus", "Documentary"… */
fun looksLikeDocumentaries(name: String): Boolean =
    Regex("""^(documentaires?|docus?|documentar(y|ies|ios?)|dokus?|dokumentationen?)$""", RegexOption.IGNORE_CASE).matches(name.trim())
