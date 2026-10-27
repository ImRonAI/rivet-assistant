package com.hiron.rivet.voice

import android.Manifest
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.hiron.rivet.MainActivity
import com.hiron.rivet.core.Connection
import com.hiron.rivet.core.rivet
import com.hiron.rivet.service.Notifications
import com.hiron.rivet.service.RivetService
import com.hiron.rivet.ui.AssistOverlay
import com.hiron.rivet.ui.RivetTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The assistant overlay in the session's own window, over whatever app is showing. It listens as soon as it
 * opens, shows the words as they are heard, sends the request with `rivet.send` and streams the reply from
 * `rivet.state`. A session window is not an activity, so the session owns the lifecycle, saved state and
 * view-model store that Compose looks up from the view tree.
 */
class RivetSession(context: Context) :
    VoiceInteractionSession(context), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private val runtime get() = context.rivet
    private val local = MutableStateFlow(AssistLocal())
    private var recognizer: SpeechRecognizer? = null

    /** Bumped for every recognizer, so callbacks from one already dropped are ignored. */
    private var take = 0
    private var showing = false

    /** Whether Rivet's own screen was in front when the overlay opened; restored when it closes. */
    private var appWasVisible = false

    /** Set when the overlay hands over to the app, which then owns `rivet.visible`. */
    private var handedOff = false

    /** Approvals already pending when the overlay opened; any others are notified when it closes unseen. */
    private var knownApprovals = emptySet<String>()

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onCreateContentView(): View {
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@RivetSession))
            setContent { RivetTheme { Overlay() } }
        }
        own(view)
        getWindow().window?.let { window ->
            own(window.decorView)
            // The scrim runs edge to edge; the panel pads itself for the system bars and the keyboard, as the app
            // does with adjustResize and edge-to-edge.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            @Suppress("DEPRECATION")
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        return view
    }

    private fun own(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
    }

    @Composable
    private fun Overlay() {
        val state by runtime.state.collectAsStateWithLifecycle()
        val mine by local.collectAsStateWithLifecycle()
        AssistOverlay(
            AssistModel.ui(mine, state),
            onMic = ::mic,
            onTyping = ::typing,
            onSend = ::ask,
            onOpen = { open(null) },
            onAllowMic = { open(RivetVoiceService.ACTION_ALLOW_MIC) },
            onDismiss = ::hide,
        )
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (!showing) {
            showing = true
            appWasVisible = runtime.visible
            handedOff = false
            knownApprovals = runtime.state.value.approvals.map { it.id }.toSet()
        }
        // As with the app on screen: replies are read aloud, device tools may open apps directly, and approvals
        // show here instead of as notifications.
        runtime.visible = true
        registry.currentState = Lifecycle.State.RESUMED
        if (runtime.settings.gateway().complete && runtime.state.value.connection == Connection.Offline) RivetService.start(context)
        val locked = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
        local.value = AssistLocal(locked = locked)
        if (!locked) listen()
    }

    override fun onHide() {
        leave()
        super.onHide()
    }

    override fun onDestroy() {
        leave()
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        super.onDestroy()
    }

    /** Stops listening and speaking, and gives `rivet.visible` back to whoever had it. */
    private fun leave() {
        drop()
        if (!showing) return
        showing = false
        runtime.stopSpeaking()
        if (!handedOff) {
            runtime.visible = appWasVisible
            if (!appWasVisible) {
                val waiting = runtime.state.value.approvals
                waiting.filter { it.id !in knownApprovals }
                    .forEach { Notifications.approval(context, it, reviewOnly = it.high || waiting.size > 1) }
            }
        }
        registry.currentState = Lifecycle.State.CREATED
    }

    private fun mic() {
        if (local.value.listening) recognizer?.stopListening() else listen()
    }

    private fun listen() {
        drop()
        runtime.stopSpeaking()
        val typing = local.value.typing
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            local.value = AssistLocal(needsMic = true, typing = typing)
            return
        }
        val target = Recognizers.target(context)
        if (target == null) {
            local.value = AssistLocal(problem = AssistModel.NO_RECOGNIZER, typing = true)
            return
        }
        val mine = ++take
        recognizer = SpeechRecognizer.createSpeechRecognizer(context, ComponentName(target.pkg, target.cls)).apply {
            setRecognitionListener(Heard(mine))
            startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName),
            )
        }
        local.value = AssistLocal(listening = true)
        runtime.listening(true)
    }

    /** Drops the recognizer; anything it was still hearing is abandoned. */
    private fun drop() {
        take++
        recognizer?.destroy()
        recognizer = null
        if (local.value.listening) {
            local.update { it.copy(listening = false, level = 0f) }
            runtime.listening(false)
        }
    }

    private fun typing(on: Boolean) {
        if (on) drop()
        local.update { it.copy(typing = on) }
    }

    private fun ask(text: String) {
        val request = text.trim()
        if (request.isEmpty()) return
        if (!runtime.settings.gateway().complete) {
            local.value = AssistLocal(asked = request, problem = AssistModel.SET_UP_FIRST)
            return
        }
        local.value = AssistLocal(turnId = runtime.send(request), asked = request)
    }

    /** Opens the full app (with [action], if any) and closes the overlay. */
    private fun open(action: String?) {
        handedOff = true
        context.startActivity(Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        hide()
    }

    private inner class Heard(private val mine: Int) : RecognitionListener {
        private val live get() = mine == take

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            if (live) local.update { it.copy(level = AssistModel.level(rmsdB)) }
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (live) local.update { it.copy(level = 0f) }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (live) local.update { AssistModel.partial(it, partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)) }
        }

        override fun onResults(results: Bundle?) {
            if (!live) return
            val said = AssistModel.said(local.value, results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            drop()
            if (said.isBlank()) local.value = AssistLocal(hint = AssistModel.DIDNT_CATCH) else ask(said)
        }

        override fun onError(error: Int) {
            if (!live) return
            val before = local.value
            drop()
            local.value = AssistModel.misheard(before, error)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
