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
    /** A run of the protocol test: played and measured on its own, then the next one. */
    val bench: BenchStep? = null,
    /** Name of the file in the stream server's stats when it isn't [file] ("nfs:Films\Dune.mkv"). */
    val statsKey: String? = null,
)

/** What a run of the protocol test is, for its measure. */
@Serializable
data class BenchStep(val protocol: String, val source: String, val route: String)

/** One audio or subtitle track of the playing file, as the side panel shows it. */
data class PlayerTrack(
    val id: Int,
    val label: String,
    val detail: String,
    val selected: Boolean,
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
