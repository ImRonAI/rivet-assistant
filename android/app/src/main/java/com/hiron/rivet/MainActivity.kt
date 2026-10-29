package com.hiron.rivet

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hiron.rivet.core.Connection
import com.hiron.rivet.core.RivetRuntime
import com.hiron.rivet.core.RivetState
import com.hiron.rivet.core.rivet
import com.hiron.rivet.service.RivetService
import com.hiron.rivet.ui.ActivityScreen
import com.hiron.rivet.ui.ApprovalSheet
import com.hiron.rivet.ui.HomeScreen
import com.hiron.rivet.ui.HudHeader
import com.hiron.rivet.ui.LocalImages
import com.hiron.rivet.ui.LocalOpenVoiceSetup
import com.hiron.rivet.ui.LocalPalette
import com.hiron.rivet.ui.LocalRobotStage
import com.hiron.rivet.ui.MiniStage
import com.hiron.rivet.ui.Onboarding
import com.hiron.rivet.ui.PendantTab
import com.hiron.rivet.ui.PendantTabs
import com.hiron.rivet.ui.RivetTheme
import com.hiron.rivet.ui.Quick
import com.hiron.rivet.ui.ResultsRequest
import com.hiron.rivet.ui.ResultsScreen
import com.hiron.rivet.ui.SettingsScreen
import com.hiron.rivet.ui.StageLayer
import com.hiron.rivet.ui.rememberRobotStage
import com.hiron.rivet.ui.VoiceSetup
import com.hiron.rivet.voice.AgentMode
import com.hiron.rivet.voice.LocalVoiceStudio
import com.hiron.rivet.voice.PhoneVoiceStudio
import com.hiron.rivet.voice.RivetVoiceService
import com.hiron.rivet.widget.RivetWidgets

class MainActivity : FragmentActivity() {
    private var listenRequests by mutableIntStateOf(0)
    private var focusRequests by mutableIntStateOf(0)
    private var homeRequests by mutableIntStateOf(0)
    private var searchRequests by mutableIntStateOf(0)

    // Asked for from the assistant overlay, which has no activity to show the permission dialog itself.
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> Toast.makeText(this, "Microphone on. Long-press Home to talk to Rivet.", Toast.LENGTH_LONG).show()
            // Denied for good: only the app's settings page can turn it back on.
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Rivet is dark first: light system-bar icons on its graphite surfaces, whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // A recreated activity (rotation, theme change) must not replay the request that first opened it.
        if (savedInstanceState == null) handle(intent)
        // A ready-to-use build connects as soon as it opens; nothing to type first.
        if (rivet.settings.gateway().complete && rivet.state.value.connection == Connection.Offline) RivetService.start(this)
        setContent { RivetTheme { RivetAppUi(rivet, listenRequests, focusRequests, homeRequests, searchRequests) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        rivet.visible = true
    }

    override fun onStop() {
        rivet.visible = false
        rivet.stopSpeaking()
        if (rivet.agent.mode == AgentMode.Voice) rivet.agent.stop()
        super.onStop()
    }

    /**
     * Opened as the assistant (long-press Home/Power), from a widget, a launcher shortcut or the assistant
     * overlay: talk, type, run a quick key, or allow the overlay's microphone. A quick key carries only its fixed
     * name, so another app can at most ask Rivet one of those read-only questions.
     */
    private fun handle(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND, RivetWidgets.ACTION_LISTEN -> listenRequests++
            RivetWidgets.ACTION_TYPE -> focusRequests++
            RivetWidgets.ACTION_SEARCH -> searchRequests++
            RivetVoiceService.ACTION_ALLOW_MIC -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
            RivetWidgets.ACTION_QUICK -> Quick.of(intent.data?.lastPathSegment)?.let {
                rivet.ask(it.prompt)
                homeRequests++
            }
            else -> if (intent?.getBooleanExtra(LISTEN, false) == true) listenRequests++
        }
    }

    companion object {
        const val LISTEN = "listen"
    }
}

private enum class Tab(val label: String) { Rivet("Rivet"), Activity("Activity"), Settings("Settings") }

@Composable
private fun RivetAppUi(runtime: RivetRuntime, listenRequests: Int, focusRequests: Int, homeRequests: Int, searchRequests: Int) {
    val state by runtime.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Rivet) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        runtime.listening(false)
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!heard.isNullOrBlank()) runtime.send(heard)
    }
    val listen = listen@{
        runtime.stopSpeaking()
        if (runtime.settings.elevenLabsAgents) {
            if (runtime.agent.mode == AgentMode.Voice) runtime.agent.stop() else runtime.agent.switchMode(AgentMode.Voice)
            return@listen
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Rivet")
        try {
            runtime.listening(true)
            voice.launch(intent)
        } catch (e: ActivityNotFoundException) {
            runtime.listening(false)
            Toast.makeText(context, "No speech recognizer is installed", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(focusRequests, homeRequests) {
        if (focusRequests > 0 || homeRequests > 0) tab = Tab.Rivet
    }
    LaunchedEffect(listenRequests) {
        if (listenRequests > 0) {
            tab = Tab.Rivet
            listen()
        }
    }
    LaunchedEffect(state.connection) {
        if (state.connection == Connection.Offline && !runtime.settings.gateway().complete) tab = Tab.Settings
    }
    // First launch: the power-on screens ask for what Rivet uses, instead of making the user hunt through Settings.
    var onboarded by rememberSaveable { mutableStateOf(runtime.settings.onboarded) }
    val finish = {
        runtime.settings.onboarded = true
        onboarded = true
    }
    val studio = remember { PhoneVoiceStudio(context, runtime.settings) }
    DisposableEffect(studio) { onDispose { studio.stopPreview() } }
    var voiceSetup by rememberSaveable { mutableStateOf(false) }
    // The results page; its results are in memory only, so it isn't saved across a recreate.
    var results by remember { mutableStateOf<ResultsRequest?>(null) }
    LaunchedEffect(searchRequests) {
        if (searchRequests > 0) results = ResultsRequest.Sheet
    }
    // One robot page for the whole activity, lent to whichever screen is in front.
    val stage = rememberRobotStage(runtime)

    CompositionLocalProvider(
        LocalOpenVoiceSetup provides { voiceSetup = true },
        LocalVoiceStudio provides studio,
        LocalRobotStage provides stage,
        LocalImages provides runtime.images,
    ) {
        Box(Modifier.fillMaxSize()) {
            if (onboarded) {
                MainUi(runtime, state, tab, { tab = it }, listen, { reviewing = true }, focusRequests) { results = it }
            } else {
                Onboarding(runtime, onFinish = finish)
            }
            val page = results
            if (onboarded && page != null) {
                ResultsScreen(
                    page, runtime.search::search, runtime.search, runtime.settings.searchSuggestions,
                    onAsk = runtime::ask, onAdd = runtime::addContext, onClose = { results = null },
                )
            }
            if (voiceSetup) VoiceSetup(studio, onClose = { voiceSetup = false }, stage = { MiniStage(runtime, state, layer = StageLayer.Overlay) })
        }
    }
    if (onboarded && reviewing && state.approvals.isNotEmpty()) {
        ApprovalSheet(
            state.approvals,
            onAnswer = { lane, answers -> runtime.answer(lane, answers) },
            onDismiss = { reviewing = false },
            names = state.names,
        )
    }
    LaunchedEffect(state.approvals.size) {
        reviewing = state.approvals.isNotEmpty()
    }
}

/** Header, the screen, and the tab strip; the strip steps aside while the keyboard is up. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainUi(
    runtime: RivetRuntime,
    state: RivetState,
    tab: Tab,
    onTab: (Tab) -> Unit,
    listen: () -> Unit,
    onReviewApprovals: () -> Unit,
    focusRequests: Int,
    onOpenResults: (ResultsRequest) -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().background(p.bg).imePadding()) {
        HudHeader(
            state,
            when (tab) {
                Tab.Rivet -> "Assistant"
                Tab.Activity -> "Activity"
                Tab.Settings -> "Settings"
            },
            rule = tab != Tab.Rivet,
        )
        MiniStage(runtime, state, visible = tab != Tab.Rivet, onOpen = { onTab(Tab.Rivet) })
        Crossfade(tab, Modifier.weight(1f).fillMaxWidth(), animationSpec = tween(220), label = "tab") { shown ->
            when (shown) {
                Tab.Rivet -> HomeScreen(
                    runtime, state, onListen = listen, onReviewApprovals = onReviewApprovals, focusRequests = focusRequests,
                    onOpenResults = onOpenResults,
                )
                Tab.Activity -> ActivityScreen(runtime, state)
                Tab.Settings -> SettingsScreen(runtime, state)
            }
        }
        if (!WindowInsets.isImeVisible) {
            PendantTabs(
                listOf(
                    PendantTab(Tab.Rivet, Tab.Rivet.label, ImageVector.vectorResource(R.drawable.ic_rivet), badge = state.approvals.size),
                    PendantTab(Tab.Activity, Tab.Activity.label, Icons.Outlined.Timeline),
                    PendantTab(Tab.Settings, Tab.Settings.label, Icons.Outlined.Tune),
                ),
                tab,
                onTab,
            )
        }
    }
}
