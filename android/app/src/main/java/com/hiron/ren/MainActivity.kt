package com.hiron.ren

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hiron.ren.core.RenRuntime
import com.hiron.ren.core.RenState
import com.hiron.ren.core.ren
import com.hiron.ren.ui.ActivityScreen
import com.hiron.ren.ui.ApprovalSheet
import com.hiron.ren.ui.HomeScreen
import com.hiron.ren.ui.HudHeader
import com.hiron.ren.ui.LocalImages
import com.hiron.ren.ui.LocalPalette
import com.hiron.ren.ui.MiniStage
import com.hiron.ren.ui.Onboarding
import com.hiron.ren.ui.PendantTab
import com.hiron.ren.ui.PendantTabs
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
        // Ren is dark first: light system-bar icons on its noir surfaces, whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // A recreated activity (rotation, theme change) must not replay the request that first opened it.
        if (savedInstanceState == null) handle(intent)
        setContent { RenTheme { CompositionLocalProvider(LocalImages provides ren.images) { RenAppUi(ren, listenRequests, focusRequests, homeRequests) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        ren.visible = true
    }

    override fun onStop() {
        ren.visible = false
        if (ren.agent.mode == AgentMode.Voice) ren.agent.stop()
        super.onStop()
    }

    /**
     * Opened as the assistant (long-press Home/Power), from a widget, a launcher shortcut or the assistant
     * overlay: talk, type, run a quick key, or allow the overlay's microphone. A quick key carries only its fixed
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
            else -> if (intent?.getBooleanExtra(LISTEN, false) == true) listenRequests++
        }
    }

    companion object {
        const val LISTEN = "listen"
    }
}

/** The tabs; each keeps its test tag ("tab-ren", …) from its name, whatever its label says. */
private enum class Tab(val label: String) {
    Ren("Ren"), Activity("Activity"), Settings("Settings");

    val tag: String get() = name.lowercase()
}

@Composable
private fun RenAppUi(runtime: RenRuntime, listenRequests: Int, focusRequests: Int, homeRequests: Int) {
    val state by runtime.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Ren) }
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
        if (focusRequests > 0 || homeRequests > 0) tab = Tab.Ren
    }
    LaunchedEffect(listenRequests) {
        if (listenRequests > 0) {
            tab = Tab.Ren
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
            MainUi(runtime, state, tab, { tab = it }, listen, { reviewing = true }, focusRequests)
        } else {
            Onboarding(runtime, onFinish = finish)
        }
    }
    if (onboarded && reviewing && state.approvals.isNotEmpty()) {
        ApprovalSheet(state.approvals.first(), onAnswer = runtime::answer, onDismiss = { reviewing = false })
    }
    LaunchedEffect(state.approvals.size) {
        reviewing = state.approvals.isNotEmpty()
    }
}

/** Header, the screen, and the tab strip; the strip steps aside while the keyboard is up. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainUi(
    runtime: RenRuntime,
    state: RenState,
    tab: Tab,
    onTab: (Tab) -> Unit,
    listen: () -> Unit,
    onReviewApprovals: () -> Unit,
    focusRequests: Int,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().background(p.bg).imePadding()) {
        HudHeader(
            state,
            when (tab) {
                Tab.Ren -> "Assistant"
                Tab.Activity -> "Activity"
                Tab.Settings -> "Settings"
            },
            rule = tab != Tab.Ren,
        )
        MiniStage(runtime, state, visible = tab != Tab.Ren, onOpen = { onTab(Tab.Ren) })
        Crossfade(tab, Modifier.weight(1f).fillMaxWidth(), animationSpec = tween(220), label = "tab") { shown ->
            when (shown) {
                Tab.Ren -> HomeScreen(runtime, state, onListen = listen, onReviewApprovals = onReviewApprovals, focusRequests = focusRequests)
                Tab.Activity -> ActivityScreen(runtime, state)
                Tab.Settings -> SettingsScreen(runtime)
            }
        }
        if (!WindowInsets.isImeVisible) {
            PendantTabs(
                listOf(
                    PendantTab(Tab.Ren, Tab.Ren.label, ImageVector.vectorResource(R.drawable.ren_sigil), badge = state.approvals.size, tag = Tab.Ren.tag),
                    PendantTab(Tab.Activity, Tab.Activity.label, Icons.Outlined.Timeline, tag = Tab.Activity.tag),
                    PendantTab(Tab.Settings, Tab.Settings.label, Icons.Outlined.Tune, tag = Tab.Settings.tag),
                ),
                tab,
                onTab,
            )
        }
    }
}
