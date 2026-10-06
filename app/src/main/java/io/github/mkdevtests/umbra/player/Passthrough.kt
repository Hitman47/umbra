package io.github.mkdevtests.umbra.player

/** android.media.AudioFormat's encodings, as HDMI outputs list them. */
private const val ENCODING_AC3 = 5
private const val ENCODING_E_AC3 = 6
private const val ENCODING_DTS = 7
private const val ENCODING_DTS_HD = 8
private const val ENCODING_DOLBY_TRUEHD = 14
private const val ENCODING_E_AC3_JOC = 18

/**
 * mpv's "audio-spdif" for an output taking [encodings]: the formats sent as
 * they are ("ac3,eac3,dts,dts-hd,truehd"); "" when none (a TV's speakers, a
 * tablet): everything is decoded, as before.
 */
fun passthroughFor(encodings: Set<Int>): String = buildList {
    if (ENCODING_AC3 in encodings) add("ac3")
    // E-AC3 carries Dolby Atmos on streaming releases (JOC).
    if (ENCODING_E_AC3 in encodings || ENCODING_E_AC3_JOC in encodings) add("eac3")
    if (ENCODING_DTS in encodings) add("dts")
    if (ENCODING_DTS_HD in encodings) add("dts-hd")
    if (ENCODING_DOLBY_TRUEHD in encodings) add("truehd")
}.joinToString(",")
