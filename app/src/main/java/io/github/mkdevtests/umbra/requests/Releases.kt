package io.github.mkdevtests.umbra.requests

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A release Prowlarr found: what the list shows, and the link qBittorrent is given. */
data class Release(
    val title: String,
    val size: Long,
    val seeders: Int?,
    val indexer: String?,
    /** A magnet when the indexer gives one, else Prowlarr's download link. */
    val link: String,
    val published: String? = null,
) {
    /** "1080p", "2160p", "720p"… from the release name; null when it doesn't say. */
    val quality: String? get() = QUALITY.find(title)?.value?.lowercase()?.let { if (it == "4k" || it == "uhd") "2160p" else it }
}

private val QUALITY = Regex("""(?<![0-9])(2160p|1080p|720p|576p|480p|4k|uhd)(?![a-z0-9])""", RegexOption.IGNORE_CASE)

/** Prowlarr's search answer → releases with a link, the preferred quality first, then the most seeded. */
fun parseReleases(json: JsonArray, preferred: String = "1080p"): List<Release> = json.mapNotNull { element ->
    val o = element as? JsonObject ?: return@mapNotNull null
    fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    val link = text("magnetUrl") ?: text("downloadUrl") ?: return@mapNotNull null
    Release(
        title = text("title") ?: return@mapNotNull null,
        size = text("size")?.toLongOrNull() ?: 0,
        seeders = text("seeders")?.toIntOrNull(),
        indexer = text("indexer"),
        link = link,
        published = text("publishDate")?.take(10),
    )
}.sortedWith(compareByDescending<Release> { it.quality == preferred }.thenByDescending { it.seeders ?: -1 })

/** The search of a title: "Dune 2021" for a film, the name alone for a show. */
fun searchQuery(title: String, year: Int?, isShow: Boolean) = if (isShow || year == null) title else "$title $year"

/** A title sent to qBittorrent: "Demandé le …" on its page until it is in the library, or for [REQUEST_KEEP_MS]. */
@Serializable
data class Requested(val tmdbId: Int, val isShow: Boolean, val title: String, val release: String, val at: Long)

/** A month. */
const val REQUEST_KEEP_MS = 30 * 24 * 3600_000L

/** [list] without the titles in the library and those asked for more than [REQUEST_KEEP_MS] ago. */
fun pruned(list: List<Requested>, owned: (Int, Boolean) -> Boolean, now: Long) =
    list.filter { now - it.at < REQUEST_KEEP_MS && !owned(it.tmdbId, it.isShow) }
