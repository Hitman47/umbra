package io.github.mkdevtests.umbra.media

/** "4K", "1080p", "720p", "SD". */
fun resolutionLabel(width: Int?, height: Int?): String? {
    val w = width ?: 0
    val h = height ?: 0
    return when {
        w == 0 && h == 0 -> null
        w >= 3200 || h >= 2000 -> "4K"
        w >= 1800 || h >= 1000 -> "1080p"
        w >= 1200 || h >= 700 -> "720p"
        else -> "SD"
    }
}

/** "Stéréo", "5.1", "7.1". */
fun channelsLabel(channels: Int?): String? = when (channels) {
    null, 0 -> null
    1 -> "Mono"
    2 -> "Stéréo"
    3 -> "2.1"
    6 -> "5.1"
    7 -> "6.1"
    8 -> "7.1"
    else -> "$channels canaux"
}

/** An audio track's language in short: "FR", "EN", "VFQ" for a Quebec dub. */
fun audioLanguageLabel(track: MediaTrack): String? {
    val name = track.name.orEmpty()
    return when {
        name.contains("VFQ", ignoreCase = true) || name.contains("québ", ignoreCase = true) || name.contains("canad", ignoreCase = true) -> "VFQ"
        name.contains("VFF", ignoreCase = true) || name.contains("truefrench", ignoreCase = true) -> "VFF"
        else -> track.language?.uppercase()
    }
}

/** What a file is, in badges: "4K", "HDR10", "HEVC", "E-AC3 5.1". */
fun MediaInfo.badges(): List<String> {
    val video = video
    val audio = audio.firstOrNull { it.default } ?: audio.firstOrNull()
    return listOfNotNull(
        resolutionLabel(video?.width, video?.height),
        video?.hdr,
        video?.codec,
        audio?.let { listOfNotNull(it.codec, channelsLabel(it.channels)).joinToString(" ") },
    )
}

/** The audio languages, then the subtitles': "FR, EN", "FR, EN (forcés : FR)". */
fun MediaInfo.audioLanguages(): List<String> = audio.mapNotNull(::audioLanguageLabel).distinct()

fun MediaInfo.subtitleLanguages(): List<String> {
    val full = subtitles.filter { !it.forced }.mapNotNull { it.language?.uppercase() }.distinct()
    val forced = subtitles.filter { it.forced }.mapNotNull { it.language?.uppercase() }.distinct().filter { it !in full }
    return full + forced.map { "$it forcés" }
}

/** One line for a list: "1080p · HEVC · FR, EN · ST FR". */
fun MediaInfo.compactLine(): String = listOfNotNull(
    resolutionLabel(video?.width, video?.height)?.let { listOfNotNull(it, video?.hdr).joinToString(" ") },
    audioLanguages().takeIf { it.isNotEmpty() }?.joinToString(", "),
    subtitleLanguages().takeIf { it.isNotEmpty() }?.let { "ST " + it.joinToString(", ") },
).joinToString(" · ")

/** Codecs and languages, the resolution aside: "HEVC · E-AC3 5.1 · FR, EN · ST FR". */
fun MediaInfo.detailLine(): String {
    val audio = audio.firstOrNull { it.default } ?: audio.firstOrNull()
    return listOfNotNull(
        video?.codec,
        audio?.let { listOfNotNull(it.codec, channelsLabel(it.channels)).joinToString(" ") },
        audioLanguages().takeIf { it.isNotEmpty() }?.joinToString(", "),
        subtitleLanguages().takeIf { it.isNotEmpty() }?.let { "ST " + it.joinToString(", ") },
    ).joinToString(" · ")
}
