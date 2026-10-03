package io.github.mkdevtests.umbra.player

/** A chapter of the playing file, from its start in seconds. */
data class Chapter(val title: String, val start: Double)

enum class ChapterKind { Intro, Credits, Other }

/** What a chapter is, by its name: "Opening", "Générique de fin", "ED"… A bare "Générique" is an intro in the first half. */
fun chapterKind(chapter: Chapter, duration: Double): ChapterKind {
    val title = chapter.title.trim()
    return when {
        BARE_CREDITS.matches(title) -> if (duration > 0 && chapter.start < duration / 2) ChapterKind.Intro else ChapterKind.Credits
        INTRO.matches(title) -> ChapterKind.Intro
        CREDITS.matches(title) -> ChapterKind.Credits
        else -> ChapterKind.Other
    }
}

/** An intro or credits chapter under way: where it ends (the next chapter, or the end of the file). */
data class Skippable(val index: Int, val kind: ChapterKind, val end: Double, val last: Boolean)

fun skippableAt(chapters: List<Chapter>, position: Double, duration: Double): Skippable? {
    val index = chapters.indexOfLast { it.start <= position + 0.5 }.takeIf { it >= 0 } ?: return null
    val kind = chapterKind(chapters[index], duration)
    if (kind == ChapterKind.Other) return null
    val next = chapters.getOrNull(index + 1)
    return Skippable(index, kind, next?.start ?: duration, last = next == null)
}

/** The file has a credits chapter: the next episode is offered there rather than in the last seconds. */
fun hasCredits(chapters: List<Chapter>, duration: Double) = chapters.any { chapterKind(it, duration) == ChapterKind.Credits }

private val BARE_CREDITS = Regex("""g[ée]n[ée]rique""", RegexOption.IGNORE_CASE)
private val INTRO = Regex(
    """(intro(duction)?|opening( credits| theme| song)?|op ?\d{0,2}|ouverture|g[ée]n[ée]rique (de |du )?d[ée]but|g[ée]n[ée]rique d'ouverture|recap|r[ée]capitulatif|r[ée]sum[ée]|previously.*|pr[ée]c[ée]demment.*|main titles?|title sequence)""",
    RegexOption.IGNORE_CASE,
)
private val CREDITS = Regex(
    """(credits|end credits|closing credits|ending( credits| theme| song)?|ed ?\d{0,2}|outro|g[ée]n[ée]rique (de )?fin|preview|next episode.*|aper[çc]u|prochain [ée]pisode.*|[àa] suivre)""",
    RegexOption.IGNORE_CASE,
)
