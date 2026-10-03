package io.github.mkdevtests.umbra.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An audio language the user ranks. [Original] is the film's own language, whatever it is. */
enum class AudioLanguage(val label: String, val language: Language?) {
    English("Anglais", Language.English),
    French("Français", Language.French),
    Original("VO", null),
}

enum class SubtitleSize(val label: String, val scale: Double) {
    Small("Petit", 0.8),
    Medium("Moyen", 1.0),
    Large("Grand", 1.25),
}

data class Settings(
    /** Tried in this order; a missing language falls through to the next. */
    val audioOrder: List<AudioLanguage> = AudioLanguage.entries,
    /** Full (not forced) subtitles in this language, null for none. */
    val subtitles: Language? = Language.French,
    val subtitleSize: SubtitleSize = SubtitleSize.Medium,
    /** Looks for new files at each launch (only new or changed files are analysed). */
    val rescanAtLaunch: Boolean = true,
)

/** User preferences, kept in SharedPreferences and observed by the screens. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(change: (Settings) -> Settings) {
        val updated = change(_settings.value)
        _settings.value = updated
        prefs.edit {
            putString(KEY_AUDIO, updated.audioOrder.joinToString(",") { it.name })
            putString(KEY_SUBTITLES, updated.subtitles?.name ?: NONE)
            putString(KEY_SUBTITLE_SIZE, updated.subtitleSize.name)
            putBoolean(KEY_RESCAN, updated.rescanAtLaunch)
        }
    }

    private fun load(): Settings {
        val defaults = Settings()
        val audio = prefs.getString(KEY_AUDIO, null)?.split(',')
            ?.mapNotNull { name -> AudioLanguage.entries.firstOrNull { it.name == name } }
            // A language added in a later version goes last.
            ?.let { saved -> (saved + AudioLanguage.entries).distinct() }
        return Settings(
            audioOrder = audio ?: defaults.audioOrder,
            subtitles = when (val saved = prefs.getString(KEY_SUBTITLES, null)) {
                null -> defaults.subtitles
                NONE -> null
                else -> Language.entries.firstOrNull { it.name == saved } ?: defaults.subtitles
            },
            subtitleSize = SubtitleSize.entries.firstOrNull { it.name == prefs.getString(KEY_SUBTITLE_SIZE, null) }
                ?: defaults.subtitleSize,
            rescanAtLaunch = prefs.getBoolean(KEY_RESCAN, defaults.rescanAtLaunch),
        )
    }

    private companion object {
        const val KEY_AUDIO = "audio_order"
        const val KEY_SUBTITLES = "subtitles"
        const val KEY_SUBTITLE_SIZE = "subtitle_size"
        const val KEY_RESCAN = "rescan_at_launch"
        const val NONE = "none"
    }
}
