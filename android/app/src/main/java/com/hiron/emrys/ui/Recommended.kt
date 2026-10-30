package com.hiron.emrys.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hiron.emrys.core.Current
import com.hiron.emrys.core.PermissionGroups
import com.hiron.emrys.core.Recommendation
import com.hiron.emrys.core.Recommended
import com.hiron.emrys.core.EmrysRuntime

/**
 * Emrys's suggested settings: a ruled list with one row per change and its own switch (all on), what it changes
 * and, for settings, the value now and after. Nothing changes until Apply; a row switched off fades back.
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
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(p.dot)) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            Eyebrow("Recommended", Modifier.padding(top = 14.dp), tone = Tone.Accent)
            Spacer(Modifier.height(12.dp))
            if (items.isEmpty()) {
                Text("Everything already matches Emrys's recommendations.", style = Type.step, color = p.ink, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(28.dp))
                PrimaryKey("Done", onDismiss, trailingArrow = false)
                return@Column
            }
            Text(
                if (items.size == 1) "Emrys suggests 1 change" else "Emrys suggests ${items.size} changes",
                style = Type.step, color = p.ink, modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(20.dp))
            val scroll = rememberScrollState()
            Box {
                Column(Modifier.heightIn(max = listMax).verticalScroll(scroll)) {
                    Rule(color = p.line)
                    items.forEachIndexed { i, item -> RecommendationCard(item, chosen[i] == true, { chosen[i] = it }, Modifier.enter(i)) }
                }
                // More below: the list fades into the sheet instead of stopping at a hard edge.
                if (scroll.canScrollForward) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(36.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, p.raised))),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            val picked = items.filterIndexed { i, _ -> chosen[i] == true }
            PrimaryKey(
                when (picked.size) {
                    0 -> "Apply changes"
                    1 -> "Apply 1 change"
                    else -> "Apply ${picked.size} changes"
                },
                { onApply(picked) }, enabled = picked.isNotEmpty(), trailingArrow = false,
            )
            Spacer(Modifier.height(6.dp))
            TextKey("Not now", onDismiss, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** One change in the list: its title and what it does, the value now and after for settings, and its switch. */
@Composable
private fun RecommendationCard(item: Recommendation, on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier) {
    val p = LocalPalette.current
    val shown by animateFloatAsState(if (on) 1f else 0.5f, tween(Motion.BASE, easing = Motion.Emphasized), label = "chosen")
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).alpha(shown), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Plain.numbers(item.title), style = Type.title, color = p.ink)
                Text(item.change, style = Type.body, color = p.ink2)
                if (item is Recommendation.Setting) {
                    Spacer(Modifier.height(6.dp))
                    Change(item.now, item.after)
                }
            }
            Spacer(Modifier.width(16.dp))
            EmrysToggle(on, onChange, Modifier.semantics { contentDescription = item.title })
        }
        Rule(color = p.line)
    }
}

/** A setting's value now and after, in a quiet capsule: "Off → On". Read as "Now Off, after On". */
@Composable
private fun Change(now: String, after: String) {
    val p = LocalPalette.current
    val pill = RoundedCornerShape(50)
    Row(
        Modifier.clip(pill).background(p.field).border(1.dp, p.line, pill).padding(horizontal = 12.dp, vertical = 6.dp)
            .clearAndSetSemantics { contentDescription = "Now $now, after $after" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(now, style = Type.labelLg.copy(fontWeight = Type.body.fontWeight), color = p.ink2, maxLines = 1)
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = p.ink2, modifier = Modifier.padding(horizontal = 8.dp).size(14.dp))
        Text(after, style = Type.labelLg, color = p.ink, maxLines = 1)
    }
}

/** What recommendations are measured against on this phone right now. */
fun currentFor(runtime: EmrysRuntime, context: Context): Current {
    val s = runtime.settings
    val missing = PermissionGroups.ALL.filterNot { group ->
        group.permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
    return Current(
        autoConnect = s.autoConnect,
        speakInOverlay = s.speakInOverlay,
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
fun RecommendedFlow(runtime: EmrysRuntime, onClose: (applied: Boolean) -> Unit) {
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
