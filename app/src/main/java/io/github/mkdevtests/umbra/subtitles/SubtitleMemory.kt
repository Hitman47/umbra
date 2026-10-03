package io.github.mkdevtests.umbra.subtitles

import android.content.Context
import androidx.core.content.edit
import java.io.File

/** A subtitle file downloaded for a video, added again each time it plays. */
data class OnlineTrack(val path: String, val language: String) {
    val title get() = "${subtitleLanguageName(language)} · en ligne"
}

/** The subtitles downloaded for each video (its NAS path), one per language. */
class SubtitleMemory(context: Context) {
    private val prefs = context.getSharedPreferences("online-subtitles", Context.MODE_PRIVATE)

    /** Those still on the device. */
    fun of(file: String): List<OnlineTrack> =
        prefs.getStringSet(file, emptySet()).orEmpty()
            .map { OnlineTrack(it.substringAfter('|'), it.substringBefore('|')) }
            .filter { File(it.path).exists() }
            .sortedBy { it.language }

    /** [path] is now the [language] subtitle of [file], instead of the one chosen before. */
    fun remember(file: String, language: String, path: String) {
        val kept = prefs.getStringSet(file, emptySet()).orEmpty().filterNot { it.substringBefore('|') == language }
        prefs.edit { putStringSet(file, (kept + "$language|$path").toSet()) }
    }
}
