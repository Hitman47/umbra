package io.github.mkdevtests.umbra.requests

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A release Prowlarr found: what the list shows, and what is needed to send it. */
data class Release(
    val title: String,
    val size: Long,
    val seeders: Int?,
    val indexer: String?,
    /** A magnet when the indexer gives one. */
    val magnet: String? = null,
    /** Prowlarr's link to the .torrent (or to a magnet it redirects to). */
    val download: String? = null,
    val published: String? = null,
    val guid: String = "",
    val indexerId: Int = 0,
    val leechers: Int? = null,
    val ageHours: Double? = null,
    val protocol: String = "torrent",
    /** The release's page on its indexer. */
    val infoUrl: String? = null,
    val infoHash: String? = null,
) {
    val parsed: ParsedTitle by lazy { ReleaseTitle.parse(title) }

    /** "1080p", "2160p", "720p"…; null when the name doesn't say. */
    val quality: String? get() = parsed.tag(TagKind.Resolution)?.lowercase()

    /** "MULTI", "VFF", "VOSTFR"…; null when the name doesn't say. */
    val language: String? get() = parsed.tag(TagKind.Language)

    /** The same torrent on several indexers: one line. */
    val dedupKey: String
        get() = infoHash?.takeIf { it.isNotBlank() }?.lowercase() ?: (title.lowercase().replace(Regex("[^a-z0-9]"), "") + "|" + size)

    /** The link qBittorrent can take as it is. */
    val link: String? get() = magnet ?: download
}

/** The languages put first, in this order (a French household); the others still show after. */
val PREFERRED_LANGUAGES = listOf("MULTI", "VFF", "TRUEFRENCH", "VF2", "FRENCH", "VFQ", "VF", "VFI")

/** Prowlarr's search answer → its releases (the same torrent on several indexers kept apart: see [grouped]). */
fun parseReleases(json: JsonArray): List<Release> = json.mapNotNull { element ->
    val o = element as? JsonObject ?: return@mapNotNull null
    fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    val magnet = text("magnetUrl")
    val download = text("downloadUrl")
    if (magnet == null && download == null && text("guid") == null) return@mapNotNull null
    Release(
        title = text("title") ?: return@mapNotNull null,
        size = text("size")?.toLongOrNull() ?: 0,
        seeders = text("seeders")?.toIntOrNull(),
        indexer = text("indexer"),
        magnet = magnet,
        download = download,
        published = text("publishDate")?.take(10),
        guid = text("guid").orEmpty(),
        indexerId = text("indexerId")?.toIntOrNull() ?: 0,
        leechers = text("leechers")?.toIntOrNull(),
        ageHours = text("ageHours")?.toDoubleOrNull(),
        protocol = text("protocol") ?: "torrent",
        infoUrl = text("infoUrl"),
        infoHash = text("infoHash"),
    )
}

/** How the list is ordered. */
enum class ReleaseSort(val label: String) { Best("Pertinence"), Seeders("Sources"), Size("Taille"), Date("Date") }

/**
 * [releases] ordered: by default the preferred quality, then the preferred
 * languages (MULTI, VFF…), then the most seeded; the others follow, never hidden.
 */
fun ranked(releases: List<Release>, sort: ReleaseSort = ReleaseSort.Best, quality: String = "1080p"): List<Release> = when (sort) {
    ReleaseSort.Best -> releases.sortedWith(
        compareByDescending<Release> { it.quality == quality }
            .thenBy { release -> release.language?.let { PREFERRED_LANGUAGES.indexOf(it.uppercase()).takeIf { i -> i >= 0 } } ?: PREFERRED_LANGUAGES.size }
            .thenByDescending { it.seeders ?: -1 },
    )
    ReleaseSort.Seeders -> releases.sortedByDescending { it.seeders ?: -1 }
    ReleaseSort.Size -> releases.sortedByDescending { it.size }
    ReleaseSort.Date -> releases.sortedBy { it.ageHours ?: Double.MAX_VALUE }
}

/** What the list is narrowed to; null: any. */
data class ReleaseFilter(
    val quality: String? = null,
    val language: String? = null,
    val indexer: String? = null,
    val season: Int? = null,
    /** Without the releases nobody shares any more (0 sources). */
    val withSeeders: Boolean = false,
) {
    fun apply(releases: List<Release>) = releases.filter { r ->
        (quality == null || r.quality == quality) &&
            (language == null || r.language.equals(language, ignoreCase = true)) &&
            (indexer == null || r.indexer == indexer) &&
            (season == null || r.parsed.season == season) &&
            (!withSeeders || r.protocol != "torrent" || (r.seeders ?: 0) > 0)
    }
}

/** The languages found, the preferred first. */
fun languagesOf(releases: List<Release>): List<String> = releases.mapNotNull { it.language?.uppercase() }.distinct()
    .sortedWith(compareBy<String> { PREFERRED_LANGUAGES.indexOf(it).takeIf { i -> i >= 0 } ?: PREFERRED_LANGUAGES.size }.thenBy { it })

/** The qualities found, the highest first. */
fun qualitiesOf(releases: List<Release>): List<String> = releases.mapNotNull { it.quality }.distinct()
    .sortedByDescending { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

/** "3 h", "5 j", "2 mois", "3 ans". */
fun ageLabel(hours: Double?): String? = when {
    hours == null || hours < 0 -> null
    hours < 1 -> "< 1 h"
    hours < 48 -> "${hours.toInt()} h"
    hours < 24 * 60 -> "${(hours / 24).toInt()} j"
    hours < 24 * 365 * 2 -> "${(hours / 24 / 30).toInt()} mois"
    else -> "${(hours / 24 / 365).toInt()} ans"
}

/** The same torrent found on several indexers, once: the first in [ordered] order, with the others as "aussi sur". */
fun grouped(ordered: List<Release>): List<Pair<Release, List<Release>>> =
    ordered.groupBy { it.dedupKey }.values.map { it.first() to it.drop(1) }

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
