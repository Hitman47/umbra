package io.github.mkdevtests.umbra.nas

/** What a folder of a NAS is for. */
enum class FolderRole(val label: String) {
    Library("Bibliothèque"),
    Documentaries("Documentaires"),
    Spectacles("Spectacles"),
    Concerts("Concerts"),
    Personal("Perso"),
    Off("Non suivi"),
}

/** The folders (app paths) whose titles leave Films and Séries for a tab of their own. */
data class Sections(
    val documentaries: List<String> = emptyList(),
    val spectacles: List<String> = emptyList(),
    val concerts: List<String> = emptyList(),
) {
    fun of(role: FolderRole): List<String> = when (role) {
        FolderRole.Documentaries -> documentaries
        FolderRole.Spectacles -> spectacles
        FolderRole.Concerts -> concerts
        else -> emptyList()
    }

    fun with(role: FolderRole, folders: List<String>): Sections = when (role) {
        FolderRole.Documentaries -> copy(documentaries = folders)
        FolderRole.Spectacles -> copy(spectacles = folders)
        FolderRole.Concerts -> copy(concerts = folders)
        else -> this
    }

    val all get() = documentaries + spectacles + concerts

    /** The section [path] is in (the innermost folder wins), null for none. */
    fun roleOf(path: String): FolderRole? = SECTION_ROLES
        .flatMap { role -> of(role).filter { path.within(it) }.map { role to it } }
        .maxByOrNull { it.second.length }?.first

    companion object {
        val SECTION_ROLES = listOf(FolderRole.Documentaries, FolderRole.Spectacles, FolderRole.Concerts)
    }
}

/** The role of [sub] ("" for the whole share) of [share] on [source], with the [documentaries] folders (app paths). */
fun roleOf(source: NasSource, share: String, sub: String, documentaries: List<String>): FolderRole =
    roleOf(source, share, sub, Sections(documentaries))

/** The role of [sub] ("" for the whole share) of [share] on [source], with the [sections]. */
fun roleOf(source: NasSource, share: String, sub: String, sections: Sections): FolderRole {
    val followed = source.shares.firstOrNull { it.equals(share, ignoreCase = true) } ?: return FolderRole.Off
    val root = source.rootOf(followed)
    val path = if (sub.isEmpty()) root else "$root\\$sub"
    return when {
        source.isPersonal(path) -> FolderRole.Personal
        source.isExcluded(path) -> FolderRole.Off
        else -> sections.roleOf(path) ?: FolderRole.Library
    }
}

/** A NAS and the section folders once a folder changed role. */
data class RoleChange(val source: NasSource, val sections: Sections) {
    val documentaries get() = sections.documentaries
}

fun withRole(
    source: NasSource,
    others: List<NasSource>,
    share: String,
    sub: String,
    role: FolderRole,
    documentaries: List<String>,
    siblings: List<String> = emptyList(),
): RoleChange? = withRole(source, others, share, sub, role, Sections(documentaries), siblings)

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
    sections: Sections,
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
    // The folder and those inside take its role; a section folder around it keeps its own.
    var kept = Sections.SECTION_ROLES.fold(sections) { result, kind -> result.with(kind, sections.of(kind).filterNot { it.within(path) }) }
    when (role) {
        FolderRole.Library -> Unit
        FolderRole.Documentaries, FolderRole.Spectacles, FolderRole.Concerts ->
            if (kept.of(role).none { path.within(it) }) kept = kept.with(role, kept.of(role) + path)
        FolderRole.Personal -> updated = updated.copy(personal = updated.personal + path)
        FolderRole.Off -> updated = if (sub.isEmpty()) {
            updated.copy(shares = updated.shares - name, excluded = updated.excluded.filterNot { it.within(root) }, personal = updated.personal.filterNot { it.within(root) })
        } else {
            updated.copy(excluded = updated.excluded + path)
        }
    }
    if (updated.shares.isEmpty()) return null
    if (updated == source && kept == sections) return null
    return RoleChange(updated, kept)
}

/** A folder that looks like documentaries: "Documentaires", "Docus", "Documentary"… */
fun looksLikeDocumentaries(name: String): Boolean =
    Regex("""^(documentaires?|docus?|documentar(y|ies|ios?)|dokus?|dokumentationen?)$""", RegexOption.IGNORE_CASE).matches(name.trim())

/** The section a folder's name suggests ("Spectacles", "Stand-up", "Concerts", "Live"…), null for none. */
fun sectionOfName(name: String): FolderRole? {
    val folded = java.text.Normalizer.normalize(name.trim(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
    return when {
        looksLikeDocumentaries(name) -> FolderRole.Documentaries
        Regex("""^(spectacles?|stand[ -]?ups?|one[ -]man[ -]shows?|one[ -]woman[ -]shows?|humou?rs?|comedie|comedy|sketch(es)?)$""").matches(folded) -> FolderRole.Spectacles
        Regex("""^(concerts?|lives?|live concerts?|musique live)$""").matches(folded) -> FolderRole.Concerts
        else -> null
    }
}
