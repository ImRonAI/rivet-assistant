package com.hiron.ren

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.platform.testTag
import com.hiron.ren.ui.BrowserScreen
import com.hiron.ren.ui.Motion
import com.hiron.ren.ui.Page
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hiron.ren.core.RenRuntime
import com.hiron.ren.core.RenState
import com.hiron.ren.core.ToolStep
import com.hiron.ren.core.ren
import com.hiron.ren.mcp.HubService
import com.hiron.ren.ui.ActivityScreen
import com.hiron.ren.ui.ApprovalSheet
import com.hiron.ren.ui.HomeScreen
import com.hiron.ren.ui.HudHeader
import com.hiron.ren.ui.LocalImages
import com.hiron.ren.ui.LocalPalette
import com.hiron.ren.ui.Onboarding
import com.hiron.ren.ui.RenTheme
import com.hiron.ren.ui.Quick
import com.hiron.ren.ui.SettingsScreen
import com.hiron.ren.voice.AgentMode
import com.hiron.ren.voice.RenVoiceService
import com.hiron.ren.widget.RenWidgets

class MainActivity : FragmentActivity() {
    private var listenRequests by mutableIntStateOf(0)
    private var focusRequests by mutableIntStateOf(0)
    private var homeRequests by mutableIntStateOf(0)
    private var linkRequests by mutableIntStateOf(0)

    // Asked for from the assistant overlay, which has no activity to show the permission dialog itself.
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> Toast.makeText(this, "Microphone on. Long-press Home to talk to Ren.", Toast.LENGTH_LONG).show()
            // Denied for good: only the app's settings page can turn it back on.
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge, with the system bars' icons following the theme.
        enableEdgeToEdge()
        // A recreated activity (rotation, theme change) must not replay the request that first opened it.
        if (savedInstanceState == null) handle(intent)
        setContent { RenTheme { CompositionLocalProvider(LocalImages provides ren.images) { RenAppUi(ren, listenRequests, focusRequests, homeRequests, linkRequests) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        ren.visible = true
        ren.browser.visible(true)
        HubService.start(this)
    }

    override fun onStop() {
        ren.visible = false
        ren.browser.visible(false)
        if (ren.agent.mode == AgentMode.Voice) ren.agent.stop()
        super.onStop()
    }

    /**
     * Opened as the assistant (long-press Home/Power), from a widget, a launcher shortcut, the assistant overlay or
     * another app's web link: talk, type, run a quick key, allow the overlay's microphone, or open the link in a tab. A quick key carries only its fixed
     * name, so another app can at most ask Ren one of those read-only questions.
     */
    private fun handle(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND, RenWidgets.ACTION_LISTEN -> listenRequests++
            RenWidgets.ACTION_TYPE -> focusRequests++
            RenVoiceService.ACTION_ALLOW_MIC -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
            RenWidgets.ACTION_QUICK -> Quick.of(intent.data?.lastPathSegment)?.let {
                ren.ask(it.prompt)
                homeRequests++
            }
            // A web link from another app, with Ren as the browser: it opens in a new tab, in front.
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "http" || it.scheme == "https" }?.let {
                ren.browser.open(it.toString())
                linkRequests++
            }
            else -> if (intent?.getBooleanExtra(LISTEN, false) == true) listenRequests++
        }
    }

    companion object {
        const val LISTEN = "listen"
    }
}

@Composable
private fun RenAppUi(runtime: RenRuntime, listenRequests: Int, focusRequests: Int, homeRequests: Int, linkRequests: Int) {
    val state by runtime.state.collectAsStateWithLifecycle()
    var panel by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    // The voice agent starts once the microphone is allowed; denied for good, only the app's settings page can allow it.
    val agentMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activity = context as? Activity
        when {
            granted -> runtime.agent.switchMode(AgentMode.Voice)
            activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> context.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
            else -> runtime.agent.switchMode(AgentMode.Voice)
        }
    }
    // The talk key starts the voice agent, or ends its session when one is running.
    val listen = {
        when {
            runtime.agent.mode == AgentMode.Voice -> runtime.agent.stop()
            !runtime.agent.hasMicrophone() -> agentMic.launch(Manifest.permission.RECORD_AUDIO)
            else -> runtime.agent.switchMode(AgentMode.Voice)
        }
    }
    LaunchedEffect(focusRequests, homeRequests) {
        if (focusRequests > 0 || homeRequests > 0) panel = true
    }
    // An opened link shows its tab: the panel and any page over the browser close.
    LaunchedEffect(linkRequests) {
        if (linkRequests > 0) {
            panel = false
            page = null
        }
    }
    LaunchedEffect(listenRequests) {
        if (listenRequests > 0) {
            panel = true
            listen()
        }
    }
    // First launch: the power-on screens ask for what Ren uses, instead of making the user hunt through Settings.
    var onboarded by rememberSaveable { mutableStateOf(runtime.settings.onboarded) }
    val finish = {
        runtime.settings.onboarded = true
        onboarded = true
    }
    Box(Modifier.fillMaxSize()) {
        if (onboarded) {
            BrowserScreen(runtime, waiting = state.approvals.size, onAskRen = { panel = true }, onOpen = { page = it })
            // While Ren drives the browser in this turn, the panel takes the lower half, so the page it works on stays in view.
            val browsing = state.messages.lastOrNull()?.takeIf { !it.fromUser }?.parts.orEmpty().any { it is ToolStep && it.name.startsWith(BROWSER_TOOLS) }
            val height by animateFloatAsState(if (browsing) BROWSING_HEIGHT else 1f, tween(Motion.BASE, easing = Motion.Standard), label = "panel height")
            AnimatedVisibility(
                panel,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxHeight(height),
                enter = slideInVertically(tween(Motion.BASE, easing = Motion.Standard)) { it } + fadeIn(tween(Motion.QUICK)),
                exit = slideOutVertically(tween(Motion.BASE, easing = Motion.Standard)) { it } + fadeOut(tween(Motion.QUICK)),
            ) {
                AgentPanel(runtime, state, listen, { reviewing = true }, focusRequests, onClose = { panel = false })
            }
            page?.let { shown -> SubPage(shown, runtime, state, onClose = { page = null }) }
        } else {
            Onboarding(runtime, onFinish = finish)
        }
    }
    BackHandler(onboarded && page != null) { page = null }
    BackHandler(onboarded && page == null && panel) { panel = false }
    if (onboarded && reviewing && state.approvals.isNotEmpty()) {
        ApprovalSheet(state.approvals.first(), onAnswer = runtime::answer, onDismiss = { reviewing = false })
    }
    LaunchedEffect(state.approvals.size) {
        reviewing = state.approvals.isNotEmpty()
    }
}

const val PANEL_TAG = "agent-panel"
private const val BROWSER_TOOLS = "browser_"
private const val BROWSING_HEIGHT = 0.5f

/** The agent panel: Ren over the browser, full height, with its stage, the conversation and the composer. */
@Composable
private fun AgentPanel(runtime: RenRuntime, state: RenState, listen: () -> Unit, onReviewApprovals: () -> Unit, focusRequests: Int, onClose: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().background(p.bg).imePadding().testTag(PANEL_TAG)) {
        HudHeader(state, "Assistant", rule = false, onClose = onClose)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HomeScreen(runtime, state, onListen = listen, onReviewApprovals = onReviewApprovals, focusRequests = focusRequests)
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** Activity or Settings over the browser, under a header that closes back to it. */
@Composable
private fun SubPage(page: Page, runtime: RenRuntime, state: RenState, onClose: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().background(p.bg).imePadding()) {
        HudHeader(state, if (page == Page.Activity) "Activity" else "Settings", onClose = onClose)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                Page.Activity -> ActivityScreen(runtime, state)
                Page.Settings -> SettingsScreen(runtime)
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}
