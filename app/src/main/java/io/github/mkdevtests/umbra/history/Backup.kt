package io.github.mkdevtests.umbra.history

import io.github.mkdevtests.umbra.nas.NasSource
import io.github.mkdevtests.umbra.nas.Protocol
import kotlinx.serialization.Serializable

/**
 * What a backup keeps of the user's own data, as a JSON file the user saves
 * anywhere (never on the NAS): the history, the corrections, the hidden
 * titles, the preferences, the sources and the services' addresses — never
 * a password or a key, which only this device's Keystore can read. A
 * transfer to another device (Réglages › Sauvegarde) carries those too,
 * sealed with the code shown on the receiving screen.
 */
@Serializable
data class Backup(
    val app: String = "Nyxara",
    val format: Int = 1,
    val createdAt: Long,
    val progress: List<BackupProgress> = emptyList(),
    val fixes: List<BackupFix> = emptyList(),
    val hidden: List<String> = emptyList(),
    /** Preference files by name, each value typed: "b:true", "i:10", "l:5", "f:1.0", "s:text", "t:a\nb" (a set). */
    val prefs: Map<String, Map<String, String>> = emptyMap(),
    val sources: List<BackupSource> = emptyList(),
    val services: BackupServices? = null,
)

/** The requests' and the external catalogue's settings; the secret ones only in a transfer. */
@Serializable
data class BackupServices(
    val prowlarrUrl: String = "",
    val qbitUrl: String = "",
    val qbitUser: String = "",
    val catalogHome: String = "",
    val catalogAway: String = "",
    val catalogUser: String = "",
    val catalogMode: String = "",
    val catalogIgnored: List<String> = emptyList(),
    val prowlarrKey: String = "",
    val qbitPassword: String = "",
    val qbitKey: String = "",
    val catalogPassword: String = "",
) {
    /** Without the secrets: what a backup file keeps. */
    fun public() = copy(prowlarrKey = "", qbitPassword = "", qbitKey = "", catalogPassword = "")
}

@Serializable
data class BackupProgress(val file: String, val position: Double, val duration: Double, val updatedAt: Long)

@Serializable
data class BackupFix(val group: String, val tmdbId: Int? = null, val season: Int? = null, val firstEpisode: Int = 1)

@Serializable
data class BackupSource(
    val id: String,
    val name: String = "",
    val host: String,
    val shares: List<String>,
    val username: String = "",
    val domain: String = "",
    val roots: Map<String, String> = emptyMap(),
    val excluded: List<String> = emptyList(),
    val protocol: String = "Smb",
    val fallbackHost: String = "",
    val personal: List<String> = emptyList(),
    /** Only in a transfer, never in a file. */
    val password: String = "",
)

/** What a backup's sources change here: the ones to add, and known ones it brings the password of. */
data class SourceMerge(val added: List<NasSource>, val updated: List<NasSource>)

/**
 * [incoming] against this device's [local] sources: one is known by its id or
 * by the same address and protocol (set up on both devices by hand); known,
 * it only lends its password when this device has none.
 */
fun mergeSources(local: List<NasSource>, incoming: List<BackupSource>): SourceMerge {
    fun same(a: String, b: String) = a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)
    val added = mutableListOf<NasSource>()
    val updated = mutableListOf<NasSource>()
    incoming.forEach { source ->
        val protocol = Protocol.entries.firstOrNull { it.name == source.protocol } ?: Protocol.Smb
        val known = local.firstOrNull { it.id == source.id } ?: local.firstOrNull { it.protocol == protocol && same(it.host, source.host) }
        when {
            known == null && added.none { it.id == source.id } -> added += NasSource(
                source.host, source.shares, source.username, source.password, source.domain, source.id, source.name,
                source.roots, source.excluded, protocol, source.fallbackHost, source.personal,
            )
            known != null && known.password.isEmpty() && source.password.isNotEmpty() ->
                updated += known.copy(username = known.username.ifEmpty { source.username }, password = source.password)
        }
    }
    return SourceMerge(added, updated)
}

/** The entries of [incoming] newer than this device's (or unknown here): what an import writes. */
fun newerProgress(local: Map<String, Progress>, incoming: List<BackupProgress>): List<Progress> =
    incoming.filter { (local[it.file]?.updatedAt ?: Long.MIN_VALUE) < it.updatedAt }
        .map { Progress(it.file, it.position, it.duration, it.updatedAt) }

/** A preference value in a backup: its type, then its text. */
fun encodePref(value: Any?): String? = when (value) {
    is Boolean -> "b:$value"
    is Int -> "i:$value"
    is Long -> "l:$value"
    is Float -> "f:$value"
    is String -> "s:$value"
    is Set<*> -> "t:" + value.filterIsInstance<String>().joinToString("\n")
    else -> null
}

/** Back to the value [encodePref] wrote; null when unreadable. */
fun decodePref(text: String): Any? {
    val value = text.substringAfter(':', "")
    return when (text.substringBefore(':')) {
        "b" -> value.toBooleanStrictOrNull()
        "i" -> value.toIntOrNull()
        "l" -> value.toLongOrNull()
        "f" -> value.toFloatOrNull()
        "s" -> value
        "t" -> value.split('\n').filter { it.isNotEmpty() }.toSet()
        else -> null
    }
}
