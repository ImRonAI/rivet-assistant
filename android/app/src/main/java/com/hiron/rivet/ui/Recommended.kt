package com.hiron.rivet.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hiron.rivet.core.Current
import com.hiron.rivet.core.PermissionGroups
import com.hiron.rivet.core.Recommendation
import com.hiron.rivet.core.Recommended
import com.hiron.rivet.core.RivetRuntime
import com.hiron.rivet.device.ForwardMode

/**
 * Rivet's suggested settings: one card per change with its own switch (all on), what it changes and, for settings,
 * the value now and after. Nothing changes until Apply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendedSheet(items: List<Recommendation>, onApply: (List<Recommendation>) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val chosen = remember(items) { mutableStateMapOf<Int, Boolean>().apply { items.indices.forEach { put(it, true) } } }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val density = LocalDensity.current
    val listMax = with(density) { (LocalWindowInfo.current.containerSize.height * 0.6f).toDp() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = p.raised,
        contentColor = p.ink,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(p.dot)) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Eyebrow("RECOMMENDED", Modifier.padding(top = 8.dp), tone = Tone.Accent)
            Spacer(Modifier.height(10.dp))
            if (items.isEmpty()) {
                Text("Everything already matches Rivet's recommendations.", style = Type.headline, color = p.ink)
                Spacer(Modifier.height(20.dp))
                PrimaryKey("Done", onDismiss, trailingArrow = false)
                return@Column
            }
            Text(if (items.size == 1) "Rivet suggests 1 change:" else "Rivet suggests ${items.size} changes:", style = Type.headline, color = p.ink)
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.heightIn(max = listMax).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items.forEachIndexed { i, item -> RecommendationCard(item, chosen[i] == true, { chosen[i] = it }, Modifier.enter(i)) }
            }
            Spacer(Modifier.height(20.dp))
            val picked = items.filterIndexed { i, _ -> chosen[i] == true }
            PrimaryKey(
                when (picked.size) {
                    0 -> "Apply changes"
                    1 -> "Apply 1 change"
                    else -> "Apply ${picked.size} changes"
                },
                { onApply(picked) }, enabled = picked.isNotEmpty(), trailingArrow = false,
            )
            Spacer(Modifier.height(10.dp))
            SecondaryKey("Not now", onDismiss, height = 56.dp)
        }
    }
}

@Composable
private fun RecommendationCard(item: Recommendation, on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier) {
    val p = LocalPalette.current
    RivetCard(modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Plain.numbers(item.title), style = Type.cardTitle, color = p.ink)
                Text(item.change, style = Type.cardBody, color = p.ink2)
            }
            Spacer(Modifier.width(12.dp))
            RivetToggle(on, onChange, Modifier.semantics { contentDescription = item.title })
        }
        if (item is Recommendation.Setting) {
            Spacer(Modifier.height(12.dp))
            Rule(color = p.edgeSoft)
            Spacer(Modifier.height(8.dp))
            Readout("Now", item.now)
            Spacer(Modifier.height(4.dp))
            Readout("After", item.after, valueColor = p.accent)
        }
    }
}

/** What recommendations are measured against on this phone right now. */
fun currentFor(runtime: RivetRuntime, context: Context): Current {
    val s = runtime.settings
    val missing = PermissionGroups.ALL.filterNot { group ->
        group.permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
    return Current(
        autoConnect = s.autoConnect,
        speakInOverlay = s.speakInOverlay,
        offerReplies = s.offerReplies,
        answeringMode = s.answeringMode,
        forwardingAllCalls = s.answeringMode == ForwardMode.Always && s.forwardedTo.isNotBlank(),
        missingPermissions = missing,
        assistantHeld = context.holdsAssistantRole(),
        denyCandidates = runtime.decisions.denyCandidates(runtime.state.value.rules),
    )
}

/**
 * The recommended-settings sheet and what Apply does, in order: settings and rules, then Android's permission prompt,
 * then the default apps screen. [onClose] runs when the sheet is dismissed or everything chosen has returned.
 */
@Composable
fun RecommendedFlow(runtime: RivetRuntime, onClose: (applied: Boolean) -> Unit) {
    val context = LocalContext.current
    val items = remember { Recommended.list(currentFor(runtime, context)) }
    val pending = remember { mutableStateMapOf<String, Boolean>() }
    val assistant = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onClose(true) }
    val openAssistant = {
        val opened = listOf(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, AndroidSettings.ACTION_SETTINGS).any { action ->
            try {
                assistant.launch(Intent(action))
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
        if (!opened) onClose(true)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pending["assistant"] == true) openAssistant() else onClose(true)
    }
    RecommendedSheet(
        items,
        onApply = { picked ->
            runtime.applyRecommended(picked)
            val ask = picked.filterIsInstance<Recommendation.AskPermissions>().flatMap { r -> r.groups.flatMap { it.permissions } }
            pending["assistant"] = picked.any { it is Recommendation.OpenAssistantSettings }
            when {
                ask.isNotEmpty() -> permissions.launch(ask.toTypedArray())
                pending["assistant"] == true -> openAssistant()
                else -> onClose(true)
            }
        },
        onDismiss = { onClose(false) },
    )
}
