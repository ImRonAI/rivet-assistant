package com.hiron.rivet.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Rivet as the phone's digital assistant. Android binds it while Rivet holds the assistant role, and long-press
 * Home, the power button or the assist gesture then opens a [RivetSession] over whatever app is showing.
 */
class RivetVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // The overlay never reads the screen, so Android need not capture its text or a screenshot for Rivet.
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }

    companion object {
        /** Opens the app to ask for the microphone the overlay listens with. */
        const val ACTION_ALLOW_MIC = "com.hiron.rivet.action.ALLOW_MIC"
    }
}

/** Hosts the overlay sessions Android starts for [RivetVoiceService]. */
class RivetSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = RivetSession(this)
}
