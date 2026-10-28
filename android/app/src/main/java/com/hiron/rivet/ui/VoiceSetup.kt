package com.hiron.rivet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.hiron.rivet.voice.VoiceStudio

/** The voice setup screens, in the order the selector reaches them. */
enum class VoiceScreen { Selector, Presets, Describe, Record, Capturing, Review, Upload }

/** Choosing Rivet's voice, shown over the whole app. */
@Composable
fun VoiceSetup(studio: VoiceStudio, onClose: () -> Unit, start: VoiceScreen = VoiceScreen.Selector) {
    val p = LocalPalette.current
    BackHandler {
        studio.stopPreview()
        onClose()
    }
    Box(Modifier.fillMaxSize().background(p.bg)) {
        FadeFooter(Modifier.align(Alignment.BottomCenter)) {
            PrimaryKey("Continue", onClose, style = CtaStyle.Display)
        }
    }
}
