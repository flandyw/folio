package com.folio.notes.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** PCM is queued on a worker; the beat indicator follows playback, not the frames queued ahead. */
internal suspend fun playMetronome(
    context: Context, rhythm: MetronomeRhythm, voice: MetronomeVoice, volume: Float,
    onFocusLost: () -> Unit, onPosition: (MetronomePosition) -> Unit,
) = withContext(Dispatchers.Default) {
    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    val manager = checkNotNull(context.getSystemService(AudioManager::class.java)) { "Audio service is unavailable" }
    val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change -> if (change < 0) onFocusLost() }, Handler(Looper.getMainLooper())).build()
    check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio is in use by another app" }
    var track: AudioTrack? = null
    try {
        val minimum = AudioTrack.getMinBufferSize(MetronomeRhythm.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "This device could not open metronome audio" }
        val audio = AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(MetronomeRhythm.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, 2048))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
        track = audio
        check(audio.state == AudioTrack.STATE_INITIALIZED) { "Metronome audio could not start" }
        check(audio.setVolume(volume) == AudioTrack.SUCCESS) { "Metronome volume could not be set" }
        val renderer = MetronomePcm(rhythm, voice)
        val buffer = ShortArray(512)
        renderer.fill(buffer)
        var offset = 0
        var previousHead = 0L
        var played = 0L
        var previousPosition: MetronomePosition? = null
        audio.play()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (offset == buffer.size) { renderer.fill(buffer); offset = 0 }
            val written = audio.write(buffer, offset, buffer.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            check(written >= 0) { "Metronome audio stopped ($written)" }
            offset += written
            val head = audio.playbackHeadPosition.toLong() and 0xffffffffL
            played += (head - previousHead) and 0xffffffffL
            previousHead = head
            val position = rhythm.position(played)
            if (position != previousPosition) {
                withContext(Dispatchers.Main) { onPosition(position) }
                previousPosition = position
            }
            if (written == 0) delay(2)
        }
    } finally {
        try {
            track?.let { audio ->
                try { audio.pause(); audio.flush() } finally { audio.release() }
            }
        } finally { manager.abandonAudioFocusRequest(focus) }
    }
}
