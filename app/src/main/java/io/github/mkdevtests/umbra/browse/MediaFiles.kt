package io.github.mkdevtests.umbra.browse

import io.github.mkdevtests.umbra.nas.NasEntry

private val VIDEO_EXTENSIONS = setOf(
    "mkv", "mp4", "m4v", "avi", "mov", "wmv", "flv", "webm",
    "ts", "m2ts", "mts", "mpg", "mpeg", "vob", "ogv", "3gp",
)
private val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub")

/** NAS housekeeping folders (Synology, Windows, macOS) nobody wants to browse. */
private val JUNK_FOLDERS = setOf("@eadir", "#recycle", "\$recycle.bin", "system volume information", "lost+found")

val NasEntry.extension get() = name.substringAfterLast('.', "").lowercase()
val NasEntry.isVideo get() = !isDirectory && extension in VIDEO_EXTENSIONS

/** Folders first, then videos; other files and junk hidden; natural order ("Saison 2" < "Saison 10"). */
fun sortForDisplay(entries: List<NasEntry>): List<NasEntry> = entries
    .filter { if (it.isDirectory) it.name.lowercase() !in JUNK_FOLDERS && !it.name.startsWith('.') else it.isVideo }
    .sortedWith(compareBy<NasEntry> { !it.isDirectory }.then { a, b -> naturalCompare(a.name, b.name) })

/**
 * Subtitle files for [video] among the original listing of its folder:
 * "Film.srt", "Film.fr.srt", "Film.forced.fr.ass" all match "Film.mkv".
 * [siblings] must be the unfiltered listing, as [sortForDisplay] hides subtitles.
 */
fun subtitlesFor(video: NasEntry, siblings: List<NasEntry>): List<NasEntry> {
    val base = video.name.substringBeforeLast('.')
    return siblings.filter {
        !it.isDirectory && it.extension in SUBTITLE_EXTENSIONS &&
            (it.name.substringBeforeLast('.') == base || it.name.startsWith("$base."))
    }
}

/** Compares runs of digits by value, the rest case-insensitively. */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (a[i].isDigit() && b[j].isDigit()) {
            val startA = i
            val startB = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val numA = a.substring(startA, i).trimStart('0')
            val numB = b.substring(startB, j).trimStart('0')
            val cmp = if (numA.length != numB.length) numA.length - numB.length else numA.compareTo(numB)
            if (cmp != 0) return cmp
        } else {
            val cmp = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
            if (cmp != 0) return cmp
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}
