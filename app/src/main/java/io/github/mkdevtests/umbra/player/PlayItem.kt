package io.github.mkdevtests.umbra.player

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
)

/** One audio or subtitle track of the playing file, as the side panel shows it. */
data class PlayerTrack(
    val id: Int,
    val label: String,
    val detail: String,
    val selected: Boolean,
)
