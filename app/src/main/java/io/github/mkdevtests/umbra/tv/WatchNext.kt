package io.github.mkdevtests.umbra.tv

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.util.Log
import io.github.mkdevtests.umbra.MainActivity
import io.github.mkdevtests.umbra.home.Resume
import io.github.mkdevtests.umbra.library.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android TV's "Continuer à regarder" row (Watch Next), on the TV's home
 * screen: the films and episodes under way, OK playing them straight away.
 * Only the library: Perso never goes there. Kept in step with the home
 * screen's own row: added, moved, removed.
 */
object WatchNext {
    private const val TAG = "WatchNext"
    private const val MAX = 10

    /** The intent a card opens: [MainActivity] plays [EXTRA_FILE]. */
    const val ACTION_PLAY = "io.github.mkdevtests.umbra.PLAY"
    const val EXTRA_FILE = "file"

    fun supported(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    suspend fun publish(context: Context, resume: List<Resume>) = withContext(Dispatchers.IO) {
        if (!supported(context)) return@withContext
        runCatching { sync(context, resume.filter { it.progress != null }.take(MAX)) }
            .onFailure { Log.w(TAG, "watch next not updated", it) }
    }

    private fun sync(context: Context, items: List<Resume>) {
        val resolver = context.contentResolver
        // This app's own cards: Android only shows an app its own.
        val existing = mutableMapOf<String, Long>()
        resolver.query(
            TvContract.WatchNextPrograms.CONTENT_URI,
            arrayOf(TvContract.WatchNextPrograms._ID, TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(1) ?: continue
                existing[key] = cursor.getLong(0)
            }
        }
        val wanted = items.associateBy { it.file }
        existing.filterKeys { it !in wanted }.values.forEach { id ->
            resolver.delete(TvContract.buildWatchNextProgramUri(id), null, null)
        }
        items.forEach { item ->
            val values = valuesOf(context, item)
            val id = existing[item.file]
            if (id != null) resolver.update(TvContract.buildWatchNextProgramUri(id), values, null, null)
            else resolver.insert(TvContract.WatchNextPrograms.CONTENT_URI, values)
        }
    }

    private fun valuesOf(context: Context, item: Resume): ContentValues = ContentValues().apply {
        val progress = item.progress!!
        val movie = item.movie
        val show = item.show
        val episode = item.episode
        put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, item.file)
        put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
        put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, progress.updatedAt)
        put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, (progress.position * 1000).toLong())
        put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, (progress.duration * 1000).toLong())
        if (movie != null) {
            put(TvContract.WatchNextPrograms.COLUMN_TYPE, TvContract.WatchNextPrograms.TYPE_MOVIE)
            put(TvContract.WatchNextPrograms.COLUMN_TITLE, movie.title)
            (movie.backdrop ?: movie.poster)?.let { put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, Tmdb.image(it, "w780")) }
            put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.WatchNextPrograms.ASPECT_RATIO_16_9)
        } else if (show != null && episode != null) {
            put(TvContract.WatchNextPrograms.COLUMN_TYPE, TvContract.WatchNextPrograms.TYPE_TV_EPISODE)
            put(TvContract.WatchNextPrograms.COLUMN_TITLE, show.title)
            put(TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, episode.season.toString())
            put(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, episode.number.toString())
            episode.title?.let { put(TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, it) }
            (episode.still ?: show.backdrop ?: show.poster)?.let { put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, Tmdb.image(it, "w780")) }
            put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.WatchNextPrograms.ASPECT_RATIO_16_9)
        }
        val open = Intent(context, MainActivity::class.java).setAction(ACTION_PLAY).putExtra(EXTRA_FILE, item.file)
        put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, open.toUri(Intent.URI_INTENT_SCHEME))
    }

    /** Takes the cards away (Perso unlocked, another profile…). */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        if (!supported(context)) return@withContext
        runCatching { context.contentResolver.delete(TvContract.WatchNextPrograms.CONTENT_URI, null, null) }
    }
}
