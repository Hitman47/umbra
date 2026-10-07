package io.github.mkdevtests.umbra.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.mkdevtests.umbra.subtitles.SUBTITLE_LANGUAGES

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
    /** Skips an intro or credits chapter by itself; otherwise a button offers to. */
    val autoSkip: Boolean = false,
    /** Counts down to the next episode at the credits, instead of waiting for the end. */
    val nextEpisodeCountdown: Boolean = true,
    /** Leaving the player keeps the video in a small window. */
    val pictureInPicture: Boolean = true,
    /** Screen off or in another app without the small window: the sound goes on. */
    val backgroundAudio: Boolean = false,
    /** Away from home (Tailscale, mobile data): the version up to 1080p rather than a 4K one. */
    val lighterAway: Boolean = true,
    /** Subtitles fetched online by themselves when the file has none in the profile's language. */
    val autoOnlineSubtitles: Boolean = false,
    /** Louder dialogue, softer explosions: for late evenings. */
    val nightAudio: Boolean = false,
    /** Anime4K shaders on the picture: sharper lines for anime, more work for the GPU. */
    val animeUpscale: Boolean = false,
    /** Scan at launch away from home too: through Tailscale it is slower. */
    val scanAway: Boolean = false,
    /** Languages searched for subtitles online (ISO 639-1), in this order. */
    val onlineSubtitleLanguages: List<String> = listOf("fr", "en"),
    /** Folders of documentaries (app paths): their titles leave Films and Séries for Documentaires. */
    val documentaryFolders: List<String> = emptyList(),
    /** The titles of a NAS that doesn't answer leave the lists (downloaded ones stay). */
    val hideUnavailable: Boolean = false,
    /** Every card's size against the usual one (0.8 to 1.3). */
    val cardScale: Float = 1f,
    /** The sound sent as it is (Dolby, DTS, Atmos) to a sound bar or an amplifier that takes it; else decoded. */
    val audioPassthrough: Boolean = true,
    /** The whole interface's size; 0: automatic (smaller on a TV, whose screen is drawn as 960 × 540). */
    val uiScale: Float = 0f,
    /** On a TV: a thicker, brighter frame around what the remote is on. */
    val strongFocus: Boolean = true,
    /** TV: HDR drawn by mpv's full renderer, never sent straight to the screen (if the direct way misbehaves). */
    val fullRender: Boolean = false,
)


/** User preferences, kept in SharedPreferences and observed by the screens. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /** Read again from the preferences: a backup was just restored. */
    fun reload() {
        _settings.value = load()
    }

    fun update(change: (Settings) -> Settings) {
        val updated = change(_settings.value)
        _settings.value = updated
        prefs.edit {
            putString(KEY_AUDIO, updated.audioOrder.joinToString(",") { it.name })
            putString(KEY_SUBTITLES, updated.subtitles?.name ?: NONE)
            putString(KEY_SUBTITLE_SIZE, updated.subtitleSize.name)
            putBoolean(KEY_RESCAN, updated.rescanAtLaunch)
            putString(KEY_SHORTCUTS, updated.homeShortcuts?.joinToString("\n"))
            putBoolean(KEY_AUTO_SKIP, updated.autoSkip)
            putBoolean(KEY_COUNTDOWN, updated.nextEpisodeCountdown)
            putBoolean(KEY_PIP, updated.pictureInPicture)
            putBoolean(KEY_BACKGROUND, updated.backgroundAudio)
            putString(KEY_ONLINE_LANGUAGES, updated.onlineSubtitleLanguages.joinToString(","))
            putBoolean(KEY_LIGHTER_AWAY, updated.lighterAway)
            putBoolean(KEY_AUTO_ONLINE, updated.autoOnlineSubtitles)
            putBoolean(KEY_NIGHT_AUDIO, updated.nightAudio)
            putBoolean(KEY_ANIME_UPSCALE, updated.animeUpscale)
            putBoolean(KEY_SCAN_AWAY, updated.scanAway)
            putString(KEY_DOCUMENTARIES, updated.documentaryFolders.joinToString("\n"))
            putBoolean(KEY_HIDE_UNAVAILABLE, updated.hideUnavailable)
            putFloat(KEY_CARD_SCALE, updated.cardScale)
            putBoolean(KEY_PASSTHROUGH, updated.audioPassthrough)
            putFloat(KEY_UI_SCALE, updated.uiScale)
            putBoolean(KEY_STRONG_FOCUS, updated.strongFocus)
            putBoolean(KEY_FULL_RENDER, updated.fullRender)
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
            autoSkip = prefs.getBoolean(KEY_AUTO_SKIP, defaults.autoSkip),
            nextEpisodeCountdown = prefs.getBoolean(KEY_COUNTDOWN, defaults.nextEpisodeCountdown),
            pictureInPicture = prefs.getBoolean(KEY_PIP, defaults.pictureInPicture),
            backgroundAudio = prefs.getBoolean(KEY_BACKGROUND, defaults.backgroundAudio),
            onlineSubtitleLanguages = prefs.getString(KEY_ONLINE_LANGUAGES, null)?.split(',')?.filter { it in SUBTITLE_LANGUAGES }
                ?: defaults.onlineSubtitleLanguages,
            lighterAway = prefs.getBoolean(KEY_LIGHTER_AWAY, defaults.lighterAway),
            autoOnlineSubtitles = prefs.getBoolean(KEY_AUTO_ONLINE, defaults.autoOnlineSubtitles),
            nightAudio = prefs.getBoolean(KEY_NIGHT_AUDIO, defaults.nightAudio),
            animeUpscale = prefs.getBoolean(KEY_ANIME_UPSCALE, defaults.animeUpscale),
            scanAway = prefs.getBoolean(KEY_SCAN_AWAY, defaults.scanAway),
            documentaryFolders = prefs.getString(KEY_DOCUMENTARIES, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
            hideUnavailable = prefs.getBoolean(KEY_HIDE_UNAVAILABLE, defaults.hideUnavailable),
            cardScale = prefs.getFloat(KEY_CARD_SCALE, defaults.cardScale).coerceIn(0.8f, 1.3f),
            audioPassthrough = prefs.getBoolean(KEY_PASSTHROUGH, defaults.audioPassthrough),
            uiScale = prefs.getFloat(KEY_UI_SCALE, defaults.uiScale),
            strongFocus = prefs.getBoolean(KEY_STRONG_FOCUS, defaults.strongFocus),
            fullRender = prefs.getBoolean(KEY_FULL_RENDER, defaults.fullRender),
        )
    }

    companion object {
        private const val KEY_AUDIO = "audio_order"
        private const val KEY_SUBTITLES = "subtitles"
        private const val KEY_SUBTITLE_SIZE = "subtitle_size"
        private const val KEY_RESCAN = "rescan_at_launch"
        private const val KEY_SHORTCUTS = "home_shortcuts"
        private const val KEY_AUTO_SKIP = "auto_skip"
        private const val KEY_COUNTDOWN = "next_episode_countdown"
        private const val KEY_PIP = "picture_in_picture"
        private const val KEY_BACKGROUND = "background_audio"
        private const val KEY_ONLINE_LANGUAGES = "online_subtitle_languages"
        private const val KEY_LIGHTER_AWAY = "lighter_away"
        private const val KEY_AUTO_ONLINE = "auto_online_subtitles"
        private const val KEY_NIGHT_AUDIO = "night_audio"
        private const val KEY_ANIME_UPSCALE = "anime_upscale"
        private const val KEY_SCAN_AWAY = "scan_away"
        private const val KEY_DOCUMENTARIES = "documentary_folders"
        private const val KEY_HIDE_UNAVAILABLE = "hide_unavailable"
        const val KEY_CARD_SCALE = "card_scale"
        const val KEY_PASSTHROUGH = "audio_passthrough"
        const val KEY_UI_SCALE = "ui_scale"
        const val KEY_STRONG_FOCUS = "strong_focus"
        const val KEY_FULL_RENDER = "full_render"
        private const val NONE = "none"
    }
}
