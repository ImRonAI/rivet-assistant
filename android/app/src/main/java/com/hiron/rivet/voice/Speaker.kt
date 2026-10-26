package com.hiron.rivet.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/** Reads Rivet's replies aloud; each spoken word is reported so the robot's mouth can follow it. */
class Speaker(
    context: Context,
    private val onWord: (word: String, seconds: Double) -> Unit,
    private val onDone: () -> Unit,
) {
    private var ready = false
    private var current: String = ""
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale.getDefault()
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                val text = current
                if (start in 0..end && end <= text.length) onWord(text.substring(start, end), (end - start) / WORD_RATE)
            }

            override fun onDone(utteranceId: String?) = onDone()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onDone()

            override fun onStop(utteranceId: String?, interrupted: Boolean) = onDone()
        })
    }

    fun speak(text: String) {
        if (!ready) return
        current = Speech.clean(text)
        if (current.isBlank()) return
        tts.speak(current, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() = tts.shutdown()

    companion object {
        private const val WORD_RATE = 14.0
    }
}

/** Text that reads well aloud: no markdown, links or citation markers, and not too long. */
object Speech {
    fun clean(text: String, limit: Int = 600): String {
        val plain = text
            .replace(Regex("\\[(\\d+(,\\s*\\d+)*)]"), "")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*_`#>|]+"), "")
            .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\s+"), " ")
            .replace(Regex(" ([.,!?;:])"), "$1")
            .trim()
        if (plain.length <= limit) return plain
        val cut = plain.take(limit)
        val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (end > limit / 2) cut.take(end + 1) else "$cut…"
    }
}
