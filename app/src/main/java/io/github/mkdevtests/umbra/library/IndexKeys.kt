package io.github.mkdevtests.umbra.library

import io.github.mkdevtests.umbra.browse.naturalCompare
import java.text.Normalizer

/** "Élite" → "elite": accents off, lower case, so that É sorts and indexes with E. */
fun folded(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase()

private val MARKS = Regex("""\p{Mn}+""")

/** The letters of the index bar, in order. */
val LETTERS: List<String> = listOf("#") + ('A'..'Z').map(Char::toString)

/** The letter of [title] in the index bar: "A"…"Z", "#" for a digit or anything else. */
fun indexLetter(title: String): String {
    val first = folded(title).firstOrNull { it.isLetterOrDigit() } ?: return "#"
    return if (first in 'a'..'z') first.uppercaseChar().toString() else "#"
}

/** [items] in title order, accents ignored ("Élite" with the E), numbers by value. */
fun <T> byTitle(items: List<T>, title: (T) -> String): List<T> =
    items.map { it to folded(title(it)) }.sortedWith { a, b -> naturalCompare(a.second, b.second) }.map { it.first }

/** The first position of each letter in [titles] (in title order). */
fun letterPositions(titles: List<String>): Map<String, Int> = LinkedHashMap<String, Int>().apply {
    titles.forEachIndexed { index, title -> putIfAbsent(indexLetter(title), index) }
}

/** The first position of each decade ("1990") in [years] (newest first); titles without a year have none. */
fun decadePositions(years: List<Int?>): LinkedHashMap<String, Int> = LinkedHashMap<String, Int>().apply {
    years.forEachIndexed { index, year -> if (year != null) putIfAbsent((year / 10 * 10).toString(), index) }
}
