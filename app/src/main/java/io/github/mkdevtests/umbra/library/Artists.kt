package io.github.mkdevtests.umbra.library

import io.github.mkdevtests.umbra.nas.within

/**
 * The artist of a spectacle or a concert at [file] (an app path), in one of
 * the [roots] (the Spectacles or Concerts folders): the folder right below
 * the root ("Spectacles\Artiste\Titre.mkv"), else the start of a file named
 * "Artiste - Titre"; null when neither says.
 */
fun artistOf(file: String, roots: List<String>): String? {
    val root = roots.filter { file.within(it) }.maxByOrNull { it.length } ?: return null
    val parts = file.substring(root.length).trimStart('\\').split('\\')
    if (parts.size >= 2) return parts.first().trim().takeIf { it.isNotEmpty() }
    val name = parts.first().substringBeforeLast('.')
    if (" - " !in name) return null
    return name.substringBefore(" - ").trim().takeIf { part -> part.count { it.isLetter() } >= 2 && !YEAR.matches(part) }
}

private val YEAR = Regex("""^\(?\d{4}\)?$""")

/** The [titles] of a section grouped by artist (in title order); the titles with no artist on their own, keyed by null. */
fun <T> byArtist(titles: List<T>, file: (T) -> String, roots: List<String>): Map<String?, List<T>> =
    titles.groupBy { artistOf(file(it), roots) }
