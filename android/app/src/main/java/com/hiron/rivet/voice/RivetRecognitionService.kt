package com.hiron.rivet.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * The recognizer Android requires every assistant to name. Rivet has no speech engine of its own, so it relays
 * each request to another recognizer on the phone ([Recognizers]). On Android 10 and 11 the assistant's
 * recognizer becomes the phone's default, so this keeps other apps' voice input working while Rivet is the
 * assistant; Android 12+ never makes it the default (`selectableAsDefault="false"`).
 */
class RivetRecognitionService : RecognitionService() {
    private var relay: SpeechRecognizer? = null

    override fun onStartListening(intent: Intent, listener: Callback) {
        release()
        val target = Recognizers.target(this)
        if (target == null) {
            deliver(listener) { error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        relay = SpeechRecognizer.createSpeechRecognizer(this, ComponentName(target.pkg, target.cls)).apply {
            setRecognitionListener(Relay(listener))
            startListening(intent)
        }
    }

    override fun onStopListening(listener: Callback) {
        relay?.stopListening()
    }

    override fun onCancel(listener: Callback) = release()

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        relay?.destroy()
        relay = null
    }

    /** A client that went away ends the relay. */
    private fun deliver(client: Callback, call: Callback.() -> Unit) {
        try {
            client.call()
        } catch (e: RemoteException) {
            release()
        }
    }

    private inner class Relay(private val client: Callback) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = deliver(client) { readyForSpeech(params ?: Bundle()) }

        override fun onBeginningOfSpeech() = deliver(client) { beginningOfSpeech() }

        override fun onRmsChanged(rmsdB: Float) = deliver(client) { rmsChanged(rmsdB) }

        override fun onBufferReceived(buffer: ByteArray?) = deliver(client) { bufferReceived(buffer ?: ByteArray(0)) }

        override fun onEndOfSpeech() = deliver(client) { endOfSpeech() }

        override fun onError(error: Int) {
            deliver(client) { error(error) }
            release()
        }

        override fun onResults(results: Bundle?) {
            deliver(client) { results(results ?: Bundle()) }
            release()
        }

        override fun onPartialResults(partialResults: Bundle?) = deliver(client) { partialResults(partialResults ?: Bundle()) }

        override fun onSegmentResults(segmentResults: Bundle) {
            if (Build.VERSION.SDK_INT >= 33) deliver(client) { segmentResults(segmentResults) }
        }

        override fun onEndOfSegmentedSession() {
            if (Build.VERSION.SDK_INT >= 33) deliver(client) { endOfSegmentedSession() }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

/** Picks the speech recognizer Rivet listens through. Rivet's own [RivetRecognitionService] is never picked. */
object Recognizers {
    /** A recognizer service on the phone; [preinstalled] ones ship with it. */
    data class Candidate(val pkg: String, val cls: String, val preinstalled: Boolean = false)

    /** The phone's default recognizer unless it is Rivet's own, else a preinstalled one, else any other. */
    fun pick(own: String, default: String?, installed: List<Candidate>): Candidate? {
        val others = installed.filter { it.pkg != own }
        val preferred = default?.let(::parse)
        others.firstOrNull { preferred != null && it.pkg == preferred.first && it.cls == preferred.second }?.let { return it }
        return others.firstOrNull { it.preinstalled } ?: others.firstOrNull()
    }

    /** Splits a flattened component name ("pkg/cls" or "pkg/.cls") into package and class. */
    fun parse(flat: String): Pair<String, String>? {
        val pkg = flat.substringBefore('/', "").ifBlank { return null }
        val cls = flat.substringAfter('/', "").ifBlank { return null }
        return pkg to if (cls.startsWith(".")) pkg + cls else cls
    }

    fun target(context: Context): Candidate? {
        // Settings.Secure.VOICE_RECOGNITION_SERVICE is hidden but readable; it is what SpeechRecognizer binds by default.
        val default = runCatching { Settings.Secure.getString(context.contentResolver, "voice_recognition_service") }.getOrNull()
        val installed = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).map {
            val info = it.serviceInfo
            Candidate(info.packageName, info.name, info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        }
        return pick(context.packageName, default, installed)
    }
}
