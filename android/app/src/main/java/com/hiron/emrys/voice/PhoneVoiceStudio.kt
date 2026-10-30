package com.hiron.emrys.voice

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.hiron.emrys.core.Settings
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * The voice studio on the phone itself: previews in the phone's own voice, recordings kept in app storage.
 * Nothing here is uploaded or sent anywhere.
 */
class PhoneVoiceStudio(context: Context, private val settings: Settings) : VoiceStudio {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var queued: (() -> Unit)? = null
    private var queuedDone: (() -> Unit)? = null
    private val utterances = mutableMapOf<String, () -> Unit>()

    private var player: MediaPlayer? = null
    private var playerDone: (() -> Unit)? = null
    private var ticker: Runnable? = null
    private val recorder by lazy { PhoneRecorder() }

    override val connected = false

    override fun presets(): List<VoicePreset> = PRESETS

    override fun preview(text: String, preset: VoicePreset?, onDone: () -> Unit) {
        stopPlayer()
        val say = say@{
            val engine = tts ?: return@say onDone()
            engine.setPitch(preset?.pitch ?: 1f)
            engine.setSpeechRate(preset?.rate ?: 1f)
            val id = UUID.randomUUID().toString()
            utterances[id] = onDone
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id)
        }
        if (ttsReady) return say()
        queuedDone?.invoke()
        queued = say
        queuedDone = onDone
        if (tts == null) tts = newEngine()
    }

    private fun newEngine(): TextToSpeech {
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(app) { status ->
            if (tts !== engine) return@TextToSpeech
            val next = queued
            val done = queuedDone
            queued = null
            queuedDone = null
            if (status != TextToSpeech.SUCCESS) {
                engine.shutdown()
                tts = null
                done?.invoke()
                return@TextToSpeech
            }
            engine.language = Locale.getDefault()
            ttsReady = true
            next?.invoke()
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = finish(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)

            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })
        return engine
    }

    private fun finish(id: String?) {
        main.post { utterances.remove(id)?.invoke() }
    }

    override fun play(sample: VoiceSample, onProgress: (Float) -> Unit, onDone: () -> Unit) {
        stopSpeech()
        stopPlayer()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(sample.file.path)
            mp.prepare()
        } catch (_: Exception) {
            mp.release()
            return onDone()
        }
        player = mp
        playerDone = onDone
        val tick = object : Runnable {
            override fun run() {
                if (player !== mp) return
                val length = mp.duration.coerceAtLeast(1)
                onProgress((mp.currentPosition.toFloat() / length).coerceIn(0f, 1f))
                main.postDelayed(this, 250)
            }
        }
        ticker = tick
        mp.setOnCompletionListener {
            onProgress(1f)
            stopPlayer()
        }
        mp.start()
        tick.run()
    }

    override fun stopPreview() {
        stopSpeech()
        stopPlayer()
    }

    /** Stops speaking and lets the engine go; the next preview starts a fresh one. */
    private fun stopSpeech() {
        queued = null
        queuedDone?.invoke()
        queuedDone = null
        tts?.let {
            it.stop()
            it.shutdown()
        }
        tts = null
        ttsReady = false
        val pending = utterances.values.toList()
        utterances.clear()
        pending.forEach { it() }
    }

    private fun stopPlayer() {
        val mp = player ?: return
        player = null
        ticker?.let(main::removeCallbacks)
        ticker = null
        runCatching { mp.stop() }
        mp.release()
        playerDone?.invoke()
        playerDone = null
    }

    override fun recorder(): SampleRecorder = recorder

    override fun choose(choice: VoiceChoice): String {
        when (choice) {
            is VoiceChoice.Preset -> save("preset", choice.preset.name) { settings.voicePreset = choice.preset.id }
            is VoiceChoice.Described -> save("described", "Described voice") { settings.voiceDescription = choice.text }
            is VoiceChoice.Recorded -> {
                val copied = runCatching { choice.sample.file.copyTo(File(voiceDir(), "sample.m4a"), overwrite = true) }
                if (copied.isFailure) return COULD_NOT_SAVE
                save("recorded", "Your recording")
            }
            is VoiceChoice.Uploaded -> {
                val error = copyUpload(choice)
                if (error != null) return error
                save("uploaded", "Uploaded recording")
            }
        }
        return settings.voiceLabel
    }

    private fun save(kind: String, label: String, extra: () -> Unit = {}) {
        extra()
        settings.voiceKind = kind
        settings.voiceLabel = label
    }

    /** Copies the picked file into app storage; returns why it couldn't, or null. */
    private fun copyUpload(choice: VoiceChoice.Uploaded): String? {
        val target = File(voiceDir(), "upload")
        val part = File(voiceDir(), "upload.part")
        try {
            val input = app.contentResolver.openInputStream(choice.uri) ?: return COULD_NOT_READ
            input.use { source ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_UPLOAD_BYTES) {
                            part.delete()
                            return TOO_BIG
                        }
                        out.write(buffer, 0, n)
                    }
                }
            }
        } catch (_: IOException) {
            part.delete()
            return COULD_NOT_READ
        } catch (_: SecurityException) {
            part.delete()
            return COULD_NOT_READ
        }
        if (!part.renameTo(target)) {
            part.delete()
            return COULD_NOT_SAVE
        }
        return null
    }

    private fun voiceDir() = File(app.filesDir, "voice").apply { mkdirs() }

    override fun current(): String? = settings.voiceLabel.ifBlank { null }

    /** Records up to 30 seconds of AAC into the cache; [stop] hands back the sample. */
    private inner class PhoneRecorder : SampleRecorder {
        private val file = File(app.cacheDir, "voice-sample.m4a")
        private var rec: MediaRecorder? = null
        private var startedAt = 0L

        override fun start(): Boolean {
            cancel()
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else @Suppress("DEPRECATION") MediaRecorder()
            return try {
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44_100)
                r.setAudioEncodingBitRate(128_000)
                r.setMaxDuration(MAX_RECORD_MS)
                r.setOutputFile(file.path)
                r.prepare()
                r.start()
                rec = r
                startedAt = SystemClock.elapsedRealtime()
                true
            } catch (_: Exception) {
                r.release()
                file.delete()
                false
            }
        }

        override fun level(): Float = runCatching { (rec?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f).coerceIn(0f, 1f)

        override fun stop(): VoiceSample? {
            val r = rec ?: return null
            rec = null
            val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt().coerceIn(0, MAX_RECORD_MS / 1000)
            // At the 30-second limit the recorder has already stopped itself, and stop() can throw; the file is complete.
            runCatching { r.stop() }
            r.release()
            return if (file.length() > 0) VoiceSample(file, seconds) else null
        }

        override fun cancel() {
            rec?.let {
                runCatching { it.stop() }
                it.release()
            }
            rec = null
            file.delete()
        }
    }

    companion object {
        /** Stand-in voices until Emrys's ElevenLabs voices are connected; pitch and rate shape the phone's voice. */
        val PRESETS = listOf(
            VoicePreset("copper", "Copper", VoiceTone.Warm, "Warm, grounded baritone", 0.85f, 0.95f),
            VoicePreset("sol", "Sol", VoiceTone.Crisp, "Bright, energetic delivery", 1.15f, 1.08f),
            VoicePreset("slate", "Slate", VoiceTone.Deep, "Low, measured pace", 0.75f, 0.9f),
            VoicePreset("vesper", "Vesper", VoiceTone.Warm, "Gentle mid-century radio operator", 0.95f, 0.92f),
            VoicePreset("ember", "Ember", VoiceTone.Warm, "Soft and friendly, unhurried", 1.0f, 0.9f),
            VoicePreset("flint", "Flint", VoiceTone.Crisp, "Clear and quick, gets to the point", 1.05f, 1.12f),
            VoicePreset("basalt", "Basalt", VoiceTone.Deep, "Deep and steady, calm under pressure", 0.7f, 0.95f),
            VoicePreset("lumen", "Lumen", VoiceTone.Crisp, "Light and upbeat, easy to follow", 1.2f, 1.0f),
        )

        private const val MAX_RECORD_MS = 30_000
        private const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024
        const val TOO_BIG = "That file is over 20 MB"
        private const val COULD_NOT_READ = "Emrys couldn't read that file"
        private const val COULD_NOT_SAVE = "Emrys couldn't save that recording"
    }
}
