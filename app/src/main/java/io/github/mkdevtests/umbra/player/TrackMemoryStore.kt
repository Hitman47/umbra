package io.github.mkdevtests.umbra.player

import android.content.Context
import androidx.core.content.edit
import io.github.mkdevtests.umbra.settings.TrackMemory
import kotlinx.serialization.json.Json

/** The tracks chosen by hand for each series (see [TrackMemory]), by the series' key. */
class TrackMemoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("series_tracks", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun get(show: String): TrackMemory? =
        prefs.getString(show, null)?.let { runCatching { json.decodeFromString(TrackMemory.serializer(), it) }.getOrNull() }

    fun save(show: String, memory: TrackMemory) = prefs.edit { putString(show, json.encodeToString(TrackMemory.serializer(), memory)) }
}
