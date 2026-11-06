package com.hiron.emrys.voice

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * The phone's side of a Gemini Live conversation: the microphone as 16-bit PCM at 16 kHz, mono, and Emrys's 16-bit
 * PCM at 24 kHz played as it arrives, the formats the Live API takes and sends. The voice-communication source and
 * usage turn on the phone's echo cancellation, so Emrys doesn't hear itself.
 */
class GeminiAudio(private val scope: CoroutineScope) {
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var reading: Job? = null
    private var writing: Job? = null
    private var queued = Channel<ByteArray>(Channel.UNLIMITED)

    /**
     * Starts the microphone, each [send] about 100 ms of audio, and the speaker, which reports each chunk's [loudness]
     * from 0 to 1 as it plays, for the stage.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(send: (ByteArray) -> Unit, loudness: (Float) -> Unit) {
        val size = maxOf(AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), CHUNK)
        val mic = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
        val speaker = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        record = mic
        track = speaker
        speaker.play()
        mic.startRecording()
        val chunks = Channel<ByteArray>(Channel.UNLIMITED).also { queued = it }
        reading = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(CHUNK)
            while (isActive) {
                val read = mic.read(buffer, 0, buffer.size)
                if (read > 0) send(buffer.copyOf(read))
            }
        }
        writing = scope.launch(Dispatchers.IO) {
            for (pcm in chunks) {
                loudness(level(pcm))
                speaker.write(pcm, 0, pcm.size)
            }
        }
    }

    /** Plays [pcm] after what is already queued. */
    fun play(pcm: ByteArray) {
        queued.trySend(pcm)
    }

    /** Drops what hasn't played yet, for a barge-in. */
    fun clear() {
        while (queued.tryReceive().isSuccess) continue
        val speaker = track ?: return
        speaker.pause()
        speaker.flush()
        speaker.play()
    }

    fun stop() {
        queued.close()
        reading?.cancel()
        reading = null
        writing?.cancel()
        writing = null
        record?.run { runCatching { stop() }; release() }
        record = null
        track?.run { runCatching { stop() }; release() }
        track = null
    }

    companion object {
        const val IN_RATE = 16_000
        const val OUT_RATE = 24_000
        private const val CHUNK = IN_RATE / 10 * 2

        /** The root mean square of 16-bit little-endian samples, from 0 to 1. */
        fun level(pcm: ByteArray): Float {
            val samples = pcm.size / 2
            if (samples == 0) return 0f
            var sum = 0.0
            for (i in 0 until samples) {
                val sample = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
                sum += sample.toDouble() * sample
            }
            return (sqrt(sum / samples) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
        }
    }
}
