package io.github.mkdevtests.umbra.library

/** An age rating as one country gives it ("FR", "12"; "US", "PG-13"). */
data class CountryRating(val country: String, val rating: String)

/**
 * The age a title is for, from its ratings: France's, else the United
 * States', else Germany's or the United Kingdom's; null when none is known
 * (the title then stays out of a child's profile). 0 means for everyone.
 */
fun ageOf(ratings: List<CountryRating>): Int? {
    for (country in listOf("FR", "US", "DE", "GB")) {
        ratings.filter { it.country == country }.firstNotNullOfOrNull { age(country, it.rating.trim()) }?.let { return it }
    }
    return null
}

private fun age(country: String, rating: String): Int? = when (country) {
    "FR" -> when (rating.uppercase().removePrefix("-")) {
        "U", "TP", "TOUS PUBLICS", "0" -> 0
        "10" -> 10
        "12" -> 12
        "16" -> 16
        "18" -> 18
        else -> null
    }
    "US" -> when (rating.uppercase()) {
        "G", "TV-Y", "TV-G" -> 0
        "TV-Y7", "TV-Y7-FV" -> 7
        "PG", "TV-PG" -> 10
        "PG-13" -> 13
        "TV-14" -> 14
        "R" -> 17
        "NC-17", "TV-MA" -> 18
        else -> null
    }
    "DE", "GB" -> when (rating.uppercase()) {
        "0", "U" -> 0
        "PG" -> 8
        "6" -> 6
        "12", "12A" -> 12
        "15" -> 15
        "16" -> 16
        "18" -> 18
        else -> null
    }
    else -> null
}

/** [this] for a child of [maxAge]: the titles TMDB rates for that age at most (unknown ones left out). */
fun Library.forAge(ages: Map<String, Int>, maxAge: Int): Library {
    fun allowed(key: String) = ages[key]?.let { it in 0..maxAge } == true
    return copy(
        movies = movies.filter { movie -> movie.tmdbId?.let { allowed("m:$it") } == true },
        shows = shows.filter { show -> show.tmdbId?.let { allowed("t:$it") } == true },
    )
}
