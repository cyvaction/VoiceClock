package com.cyvaction.voiceclock

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.annotation.RawRes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

object VoicePlayer {
    private val mutex = Mutex()
    private const val SPEECH_SPEED = 0.82f

    suspend fun play(
        context: Context,
        @RawRes rawId: Int,
        canStart: suspend () -> Boolean = { true },
    ): Boolean = mutex.withLock {
        if (!canStart()) return false
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT ||
            audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0
        ) return false

        var player: MediaPlayer? = null
        var completed: CompletableDeferred<Boolean>? = null
        val focusLost = AtomicBoolean(false)
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change < 0) {
                focusLost.set(true)
                completed?.complete(false)
                runCatching { player?.stop() }
            }
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(listener)
            .build()
        if (audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false

        try {
            suspend fun playResource(@RawRes id: Int, speed: Float): Boolean {
                if (focusLost.get()) return false
                completed = CompletableDeferred()
                try {
                    player = MediaPlayer.create(context, id, attributes, 0) ?: return false
                    player!!.playbackParams = player!!.playbackParams.setSpeed(speed)
                    player!!.setOnCompletionListener { completed?.complete(true) }
                    player!!.setOnErrorListener { _, _, _ -> completed?.complete(false); true }
                    player!!.start()
                } catch (_: RuntimeException) {
                    return false
                }
                return try {
                    completed!!.await()
                } finally {
                    runCatching { player?.stop() }
                    player?.release()
                    player = null
                    completed = null
                }
            }

            playResource(R.raw.chime, 1f) && playResource(rawId, SPEECH_SPEED)
        } finally {
            runCatching { player?.stop() }
            player?.release()
            audio.abandonAudioFocusRequest(focusRequest)
        }
    }
}
