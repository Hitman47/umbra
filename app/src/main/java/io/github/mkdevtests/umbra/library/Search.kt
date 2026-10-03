package io.github.mkdevtests.umbra.library

import io.github.mkdevtests.umbra.browse.naturalCompare
import io.github.mkdevtests.umbra.history.hideKey

enum class SearchKind(val label: String) { All("Tout"), Movies("Films"), Shows("Séries") }

data class SearchFilters(
    val kind: SearchKind = SearchKind.All,
    val genre: String? = null,
    /** First year of a decade: 1990 for the 1990s. */
    val decade: Int? = null,
    /** Only the hidden titles, to show one again. */
    val hidden: Boolean = false,
)

/** What the index found: rows by title, then rows by actor or director. */
data class SearchHits(val titles: List<SearchHit>, val people: List<SearchHit>)

/** A film or a show in the results; [note] says why it's there when the title doesn't. */
data class Found(
    val key: String,
    val isShow: Boolean,
    val title: String,
    val year: Int?,
    val poster: String?,
    val note: String? = null,
)

/**
 * The results for [query]: titles first (those starting with it before the
 * others), then shows by an episode title, then actors and directors.
 * Without a query, every title, so the filters alone can browse.
 */
fun searchResults(library: Library, hidden: Set<String>, query: String, hits: SearchHits?, filters: SearchFilters): List<Found> {
    val movies = library.movies.associateBy { it.file }
    val shows = library.shows.associateBy { it.key }
    val showOfEpisode by lazy { library.shows.flatMap { show -> show.seasons.flatMap { it.episodes }.map { it.file to show } }.toMap() }
    val words = searchWords(query).map { it.withoutAccents().lowercase() }

    val found = LinkedHashMap<String, Pair<Any, String?>>() // key → film or show, note
    fun add(item: Any?, note: String? = null) {
        val key = when (item) {
            is Movie -> item.file
            is Show -> item.key
            else -> return
        }
        found.putIfAbsent(key, item to note)
    }
    if (words.isEmpty()) {
        (library.movies + library.shows).sortedWith { a, b -> naturalCompare(titleOf(a), titleOf(b)) }.forEach { add(it) }
    } else if (hits != null) {
        val (direct, episodes) = hits.titles.partition { it.kind != "episode" }
        direct.mapNotNull { if (it.kind == "movie") movies[it.ref] else shows[it.ref] }
            .sortedWith(compareBy<Any> { !startsWith(titleOf(it), words.first()) }.thenComparator { a, b -> naturalCompare(titleOf(a), titleOf(b)) })
            .forEach { add(it) }
        episodes.forEach { hit -> add(showOfEpisode[hit.ref], "Épisode") }
        hits.people.forEach { hit ->
            val item = if (hit.kind == "movie") movies[hit.ref] else shows[hit.ref]
            val people = when (item) {
                is Movie -> item.directors + item.cast
                is Show -> item.directors + item.cast
                else -> emptyList()
            }
            add(item, people.firstOrNull { matches(it, words) })
        }
    }

    return found.values.mapNotNull { (item, note) ->
        when (item) {
            is Movie -> item.takeIf { filters.kind != SearchKind.Shows && keep(it.hideKey, it.genres, it.year, hidden, filters) }
                ?.let { Found(it.file, isShow = false, it.title, it.year, it.poster, note) }
            is Show -> item.takeIf { filters.kind != SearchKind.Movies && keep(it.hideKey, it.genres, it.year, hidden, filters) }
                ?.let { Found(it.key, isShow = true, it.title, it.year, it.poster, note) }
            else -> null
        }
    }
}

/** Genres of the library, the most common first. */
fun genresOf(library: Library): List<String> =
    (library.movies.flatMap { it.genres } + library.shows.flatMap { it.genres })
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

/** Decades of the library, the latest first. */
fun decadesOf(library: Library): List<Int> =
    (library.movies.mapNotNull { it.year } + library.shows.mapNotNull { it.year }).map { it / 10 * 10 }.distinct().sortedDescending()

private fun keep(hideKey: String, genres: List<String>, year: Int?, hidden: Set<String>, filters: SearchFilters): Boolean =
    (hideKey in hidden) == filters.hidden &&
        (filters.genre == null || filters.genre in genres) &&
        (filters.decade == null || (year != null && year / 10 * 10 == filters.decade))

private fun titleOf(item: Any) = when (item) {
    is Movie -> item.title
    is Show -> item.title
    else -> ""
}

private fun startsWith(title: String, word: String) = title.withoutAccents().lowercase().startsWith(word)

/** Every word typed starts a word of [name]: "denis vil" is Denis Villeneuve. */
private fun matches(name: String, words: List<String>): Boolean {
    val parts = searchWords(name).map { it.withoutAccents().lowercase() }
    return words.all { word -> parts.any { it.startsWith(word) } }
}
