package com.hiron.rivet

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hiron.rivet.core.Connection
import com.hiron.rivet.core.RivetRuntime
import com.hiron.rivet.core.rivet
import com.hiron.rivet.ui.ActivityScreen
import com.hiron.rivet.ui.ApprovalSheet
import com.hiron.rivet.ui.HomeScreen
import com.hiron.rivet.ui.LocalPalette
import com.hiron.rivet.ui.RivetTheme
import com.hiron.rivet.ui.SettingsScreen

class MainActivity : FragmentActivity() {
    private var listenRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent { RivetTheme { RivetAppUi(rivet, listenRequests) } }
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
        super.onStop()
    }

    /** Opened as the assistant (long-press Home/Power) or from the "listen" shortcut: start voice input. */
    private fun handle(intent: Intent?) {
        if (intent?.action == Intent.ACTION_ASSIST || intent?.action == Intent.ACTION_VOICE_COMMAND || intent?.getBooleanExtra(LISTEN, false) == true) {
            listenRequests++
        }
    }

    companion object {
        const val LISTEN = "listen"
    }
}

private enum class Tab(val label: String) { Rivet("Rivet"), Activity("Activity"), Settings("Settings") }

@Composable
private fun RivetAppUi(runtime: RivetRuntime, listenRequests: Int) {
    val state by runtime.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Rivet) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    val p = LocalPalette.current
    val context = LocalContext.current

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        runtime.listening(false)
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!heard.isNullOrBlank()) runtime.send(heard)
    }
    val listen = {
        runtime.stopSpeaking()
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
    LaunchedEffect(listenRequests) {
        if (listenRequests > 0) {
            tab = Tab.Rivet
            listen()
        }
    }
    LaunchedEffect(state.connection) {
        if (state.connection == Connection.Offline && !runtime.settings.gateway().complete) tab = Tab.Settings
    }

    Scaffold(
        containerColor = p.bg,
        bottomBar = {
            NavigationBar(containerColor = p.bgHi) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            BadgedBox(badge = { if (t == Tab.Rivet && state.approvals.isNotEmpty()) Badge { Text("${state.approvals.size}") } }) {
                                Icon(
                                    when (t) {
                                        Tab.Rivet -> Icons.Filled.SmartToy
                                        Tab.Activity -> Icons.Filled.ListAlt
                                        Tab.Settings -> Icons.Filled.Tune
                                    },
                                    contentDescription = null,
                                )
                            }
                        },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = p.accent, selectedTextColor = p.accent, indicatorColor = p.card),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.Rivet -> HomeScreen(runtime, state, onListen = listen, onReviewApprovals = { reviewing = true })
                Tab.Activity -> ActivityScreen(runtime, state)
                Tab.Settings -> SettingsScreen(runtime, state)
            }
        }
    }
    if (reviewing && state.approvals.isNotEmpty()) {
        ApprovalSheet(
            state.approvals,
            onAnswer = { lane, answers -> runtime.answer(lane, answers) },
            onDismiss = { reviewing = false },
        )
    }
    LaunchedEffect(state.approvals.size) {
        reviewing = state.approvals.isNotEmpty()
    }
}
