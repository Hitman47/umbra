package io.github.mkdevtests.umbra.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.core.content.ContextCompat

/**
 * The player as Android sees it: the remote's and headphones' media keys,
 * the voice assistant ("pause"), the controls of the notification and of the
 * lock screen all reach it, wherever the focus is. Headphones unplugged (or
 * a Bluetooth speaker gone): pause, as the sound would otherwise come out
 * loud of the device.
 */
class PlayerSession(
    private val context: Context,
    private val controls: Controls,
) {
    interface Controls {
        fun play()
        fun pause()
        fun seekTo(seconds: Double)
        fun jump(seconds: Int)
        fun next()
        fun stop()
    }

    val session = MediaSession(context, "Nyxara").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = controls.play()
            override fun onPause() = controls.pause()
            override fun onSeekTo(position: Long) = controls.seekTo(position / 1000.0)
            override fun onFastForward() = controls.jump(JUMP_SECONDS)
            override fun onRewind() = controls.jump(-JUMP_SECONDS)
            override fun onSkipToNext() = controls.next()
            override fun onStop() = controls.stop()
        })
        isActive = true
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) controls.pause()
        }
    }

    init {
        ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED) // system broadcasts still arrive
    }

    /** What plays: shown on the lock screen and in the system's media controls. */
    fun describe(title: String, subtitle: String?, durationSeconds: Double) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .apply { subtitle?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it) } }
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (durationSeconds * 1000).toLong())
                .build(),
        )
    }

    fun update(playing: Boolean, positionSeconds: Double, hasNext: Boolean) {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_STOP or (if (hasNext) PlaybackState.ACTION_SKIP_TO_NEXT else 0L)
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, (positionSeconds * 1000).toLong(), if (playing) 1f else 0f)
                .build(),
        )
    }

    fun release() {
        runCatching { context.unregisterReceiver(noisy) }
        session.isActive = false
        session.release()
    }

    private companion object {
        const val JUMP_SECONDS = 30
    }
}
