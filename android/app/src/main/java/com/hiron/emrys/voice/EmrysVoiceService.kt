package com.hiron.emrys.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Emrys as the phone's digital assistant. Android binds it while Emrys holds the assistant role, and long-press
 * Home, the power button or the assist gesture then opens a [EmrysSession] over whatever app is showing.
 */
class EmrysVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // The overlay never reads the screen, so Android need not capture its text or a screenshot for Emrys.
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }

    companion object {
        /** Opens the app to ask for the microphone the overlay listens with. */
        const val ACTION_ALLOW_MIC = "com.hiron.emrys.action.ALLOW_MIC"
    }
}

/** Hosts the overlay sessions Android starts for [EmrysVoiceService]. */
class EmrysSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = EmrysSession(this)
}
