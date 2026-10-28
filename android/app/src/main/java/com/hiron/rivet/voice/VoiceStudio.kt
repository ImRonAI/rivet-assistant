package com.hiron.rivet.voice

import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File

/** A ready-made voice. Until Rivet's ElevenLabs voice is connected, [pitch] and [rate] shape the phone's own voice for previews. */
data class VoicePreset(val id: String, val name: String, val tone: VoiceTone, val blurb: String, val pitch: Float, val rate: Float)

enum class VoiceTone { Warm, Crisp, Deep }

/** A recording made on the voice setup screen, kept in the app's own storage. */
data class VoiceSample(val file: File, val seconds: Int)

sealed interface VoiceChoice {
    data class Preset(val preset: VoicePreset) : VoiceChoice
    data class Described(val text: String) : VoiceChoice
    data class Recorded(val sample: VoiceSample) : VoiceChoice
    data class Uploaded(val uri: Uri, val name: String) : VoiceChoice
}

/** Records a voice sample and reports the microphone level. */
interface SampleRecorder {
    /** False when the microphone can't be used. */
    fun start(): Boolean

    /** The latest level, 0 to 1. */
    fun level(): Float
    fun stop(): VoiceSample?
    fun cancel()
}

/** What the voice setup screens need. The ElevenLabs agent plugs in behind this; nothing here calls a network. */
interface VoiceStudio {
    /** True once Rivet's ElevenLabs voice is connected. */
    val connected: Boolean
    fun presets(): List<VoicePreset>
    fun preview(text: String, preset: VoicePreset? = null, onDone: () -> Unit = {})
    fun play(sample: VoiceSample, onProgress: (Float) -> Unit = {}, onDone: () -> Unit = {})
    fun stopPreview()
    fun recorder(): SampleRecorder

    /** Saves the choice and returns the label to show for it. */
    fun choose(choice: VoiceChoice): String

    /** The saved voice's label, or null. */
    fun current(): String?
}

val LocalVoiceStudio = staticCompositionLocalOf<VoiceStudio?> { null }
