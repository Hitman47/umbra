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
    /** Folders (share roots) offered at the top of the home page; null: every root, when there are several. */
    val homeShortcuts: List<String>? = null,
    /** What the player's ⏪ ⏩ buttons and double taps jump, in seconds. */
    val seekStep: Int = 10,
    /** Skips an intro or credits chapter by itself; otherwise a button offers to. */
    val autoSkip: Boolean = false,
    /** Counts down to the next episode at the credits, instead of waiting for the end. */
    val nextEpisodeCountdown: Boolean = true,
    /** Leaving the player keeps the video in a small window. */
    val pictureInPicture: Boolean = true,
    /** Screen off or in another app without the small window: the sound goes on. */
    val backgroundAudio: Boolean = false,
    /** Languages searched for subtitles online (ISO 639-1), in this order. */
    val onlineSubtitleLanguages: List<String> = listOf("fr", "en"),
)

/** Jumps offered in the player, in seconds. */
val SEEK_STEPS = listOf(10, 30, 60, 300)

/** "10 s", "1 min", "5 min". */
fun seekStepLabel(seconds: Int) = if (seconds < 60) "$seconds s" else "${seconds / 60} min"

/** Languages offered for online subtitles: code → name. */
val SUBTITLE_LANGUAGES = linkedMapOf(
    "fr" to "Français", "en" to "Anglais", "es" to "Espagnol", "de" to "Allemand", "it" to "Italien",
    "pt-PT" to "Portugais", "pt-BR" to "Portugais (Brésil)", "nl" to "Néerlandais", "ar" to "Arabe", "ja" to "Japonais",
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
            putString(KEY_SHORTCUTS, updated.homeShortcuts?.joinToString("\n"))
            putInt(KEY_SEEK_STEP, updated.seekStep)
            putBoolean(KEY_AUTO_SKIP, updated.autoSkip)
            putBoolean(KEY_COUNTDOWN, updated.nextEpisodeCountdown)
            putBoolean(KEY_PIP, updated.pictureInPicture)
            putBoolean(KEY_BACKGROUND, updated.backgroundAudio)
            putString(KEY_ONLINE_LANGUAGES, updated.onlineSubtitleLanguages.joinToString(","))
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
            homeShortcuts = prefs.getString(KEY_SHORTCUTS, null)?.split('\n')?.filter { it.isNotEmpty() },
            seekStep = prefs.getInt(KEY_SEEK_STEP, defaults.seekStep).takeIf { it in SEEK_STEPS } ?: defaults.seekStep,
            autoSkip = prefs.getBoolean(KEY_AUTO_SKIP, defaults.autoSkip),
            nextEpisodeCountdown = prefs.getBoolean(KEY_COUNTDOWN, defaults.nextEpisodeCountdown),
            pictureInPicture = prefs.getBoolean(KEY_PIP, defaults.pictureInPicture),
            backgroundAudio = prefs.getBoolean(KEY_BACKGROUND, defaults.backgroundAudio),
            onlineSubtitleLanguages = prefs.getString(KEY_ONLINE_LANGUAGES, null)?.split(',')?.filter { it in SUBTITLE_LANGUAGES }
                ?: defaults.onlineSubtitleLanguages,
        )
    }

    private companion object {
        const val KEY_AUDIO = "audio_order"
        const val KEY_SUBTITLES = "subtitles"
        const val KEY_SUBTITLE_SIZE = "subtitle_size"
        const val KEY_RESCAN = "rescan_at_launch"
        const val KEY_SHORTCUTS = "home_shortcuts"
        const val KEY_SEEK_STEP = "seek_step"
        const val KEY_AUTO_SKIP = "auto_skip"
        const val KEY_COUNTDOWN = "next_episode_countdown"
        const val KEY_PIP = "picture_in_picture"
        const val KEY_BACKGROUND = "background_audio"
        const val KEY_ONLINE_LANGUAGES = "online_subtitle_languages"
        const val NONE = "none"
    }
}
