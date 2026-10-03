package io.github.mkdevtests.umbra.player

import io.github.mkdevtests.umbra.trakt.TraktTarget
import kotlinx.serialization.Serializable

/** One video of the player's queue: a film, or an episode followed by the next ones. */
@Serializable
data class PlayItem(
    val url: String,
    val title: String,
    /** Second line under the title ("S02E03 · Titre"), null for films. */
    val subtitle: String? = null,
    /** External subtitle files. */
    val subtitles: List<String> = emptyList(),
    /** NAS path, the key of the watch history; null for a file picked on the tablet. */
    val file: String? = null,
    /** Where to start, in seconds (resuming). */
    val start: Double = 0.0,
    /** What it is on Trakt; null when it isn't matched surely enough to be scrobbled. */
    val trakt: TraktTarget? = null,
    /** The NAS file actually read when it isn't [file]: another version of the title, whose progress stays [file]'s. */
    val stream: String? = null,
)

/** One audio or subtitle track of the playing file, as the side panel shows it. */
data class PlayerTrack(
    val id: Int,
    val label: String,
    val detail: String,
    val selected: Boolean,
    /** As the file says it ("fre", "en"), null when it doesn't. */
    val language: String? = null,
)

/** What the player measured of one file; see [MpvPlayer.figures]. */
data class PlayerFigures(
    /** From the request to the first frame. */
    val openMs: Long?,
    /** From the request to the file probed (tracks known): the rest of [openMs] is decoding and display. */
    val loadedMs: Long?,
    val seeksMs: List<Long>,
    /** Waits for the network while playing, outside openings and seeks. */
    val stalls: Int,
    val stalledMs: Long,
    /** Time since the first frame. */
    val watchedS: Long,
    /** "hevc 3840x2160 mediacodec". */
    val video: String?,
    val droppedFrames: Int?,
    /** Seconds. */
    val duration: Double,
)
