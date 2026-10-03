package io.github.mkdevtests.umbra.history

import kotlinx.serialization.Serializable

/**
 * What a backup keeps of the user's own data, as a JSON file the user saves
 * anywhere (never on the NAS): the history, the corrections, the hidden
 * titles, the preferences and the sources (without their passwords, which
 * only this device's Keystore can read).
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
)

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
)

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
