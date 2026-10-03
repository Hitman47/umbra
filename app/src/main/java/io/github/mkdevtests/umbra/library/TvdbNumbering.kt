package io.github.mkdevtests.umbra.library

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** A regular TMDB episode, paired with TheTVDB's by air date. */
data class TmdbSlot(val season: Int, val number: Int, val airDate: String?)

/** An episode as TheTVDB numbers it ([season], [number], [absolute]) and where TMDB has it. */
@Serializable
data class NumberingEntry(val season: Int, val number: Int, val absolute: Int, val tmdbSeason: Int, val tmdbNumber: Int)

/** TheTVDB's numbering of a show, translated to TMDB's, which Trakt and the episode details follow. */
@Serializable
data class Numbering(val fetchedAt: Long, val entries: List<NumberingEntry>) {
    private val byPlace by lazy { entries.associateBy { it.season to it.number } }
    private val byAbsolute by lazy { entries.associateBy { it.absolute } }

    /**
     * TMDB's season and number for a file numbered [number]: in TheTVDB's
     * [season] if it has one there ("Saison 3\E05"), else as an absolute
     * number ("Show - 54", "Saison 3\54").
     */
    fun place(season: Int, number: Int, seasonKnown: Boolean): Pair<Int, Int>? {
        val entry = (if (seasonKnown) byPlace[season to number] else null) ?: byAbsolute[number]
        return entry?.let { it.tmdbSeason to it.tmdbNumber }
    }
}

/**
 * Pairs TheTVDB's regular episodes with TMDB's: by air date when both list
 * as many episodes that day, the others by order, shifted like the last pair
 * found by date (an episode one of them splits or leaves out).
 */
fun pairEpisodes(tvdb: List<TvdbEpisode>, tmdb: List<TmdbSlot>): List<NumberingEntry> {
    val theirs = tvdb.filter { (it.seasonNumber ?: 0) >= 1 && (it.number ?: 0) >= 1 }.sortedWith(compareBy({ it.seasonNumber }, { it.number }))
    val ours = tmdb.filter { it.season >= 1 }.sortedWith(compareBy({ it.season }, { it.number }))
    val theirsByDate = theirs.indices.filter { theirs[it].aired != null }.groupBy { theirs[it].aired!! }
    val oursByDate = ours.indices.filter { ours[it].airDate != null }.groupBy { ours[it].airDate!! }

    val used = HashSet<Int>()
    val dated = HashMap<Int, Int>()
    theirs.forEachIndexed { i, episode ->
        pairByDate(i, episode.aired, theirsByDate, oursByDate)?.takeIf(used::add)?.let { dated[i] = it }
    }
    var shift = 0
    return theirs.mapIndexedNotNull { i, episode ->
        val j = dated[i]?.also { shift = it - i }
            ?: (i + shift).takeIf { it in ours.indices && used.add(it) }
            ?: return@mapIndexedNotNull null
        val absolute = episode.absoluteNumber?.takeIf { it > 0 } ?: (i + 1)
        NumberingEntry(episode.seasonNumber!!, episode.number!!, absolute, ours[j].season, ours[j].number)
    }
}

/** Index in TMDB's list of TheTVDB's [i]-th episode, aired the same day or the day before or after (time zones). */
private fun pairByDate(i: Int, aired: String?, theirs: Map<String, List<Int>>, ours: Map<String, List<Int>>): Int? {
    val day = aired?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val sameDay = theirs.getValue(aired)
    for (date in listOf(day, day.minusDays(1), day.plusDays(1))) {
        val candidates = ours[date.toString()] ?: continue
        if (candidates.size == sameDay.size) return candidates[sameDay.indexOf(i)]
        if (date == day) return null // a double episode on one side: left to the order
    }
    return null
}

/**
 * The numberings by TMDB show, kept between scans: TheTVDB is asked once a
 * week per anime, not at every scan.
 */
class NumberingCache(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val tables = ConcurrentHashMap<Int, Numbering>()

    init {
        if (file.exists()) {
            runCatching { json.decodeFromString<Map<Int, Numbering>>(file.readText()) }
                .onSuccess(tables::putAll)
                .onFailure { Log.w(TAG, "numbering cache unreadable", it) }
        }
    }

    operator fun get(tmdbId: Int): Numbering? = tables[tmdbId]

    operator fun set(tmdbId: Int, numbering: Numbering) {
        tables[tmdbId] = numbering
    }

    fun save() {
        runCatching { file.writeText(json.encodeToString<Map<Int, Numbering>>(tables.toMap())) }
            .onFailure { Log.w(TAG, "numbering cache not saved", it) }
    }

    private companion object {
        const val TAG = "NumberingCache"
    }
}
