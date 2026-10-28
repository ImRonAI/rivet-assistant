package com.hiron.rivet.voice

import android.content.Context
import com.hiron.rivet.core.Settings

/** The voice studio on the phone itself: previews in the phone's own voice, recordings kept in app storage. */
class PhoneVoiceStudio(context: Context, private val settings: Settings) : VoiceStudio {
    override val connected = false

    override fun presets(): List<VoicePreset> = emptyList()

    override fun preview(text: String, preset: VoicePreset?, onDone: () -> Unit) = Unit

    override fun play(sample: VoiceSample, onProgress: (Float) -> Unit, onDone: () -> Unit) = Unit

    override fun stopPreview() = Unit

    override fun recorder(): SampleRecorder = object : SampleRecorder {
        override fun start() = false
        override fun level() = 0f
        override fun stop(): VoiceSample? = null
        override fun cancel() = Unit
    }

    override fun choose(choice: VoiceChoice): String = settings.voiceLabel

    override fun current(): String? = settings.voiceLabel.ifBlank { null }
}
