package com.hiron.rivet.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.hiron.rivet.voice.SampleRecorder
import com.hiron.rivet.voice.VoiceChoice
import com.hiron.rivet.voice.VoicePreset
import com.hiron.rivet.voice.VoiceSample
import com.hiron.rivet.voice.VoiceStudio
import com.hiron.rivet.voice.VoiceTone
import java.io.File
import kotlinx.coroutines.delay

/** The voice setup screens, in the order the selector reaches them. */
enum class VoiceScreen { Selector, Presets, Describe, Record, Capturing, Review, Upload }

/** The line read on the record screen and shown on the review card. */
const val SAMPLE_LINE = "Hello Rivet. Today we are building a helpful assistant with an honest heart, expressive eyes, and a clear, friendly voice."

private const val MAX_SECONDS = 30
private const val MAX_DESCRIPTION = 280
private const val SKIP = "Skip voice setup"

private val DESCRIBE_CHIPS = listOf(
    "Vintage Radio" to "A warm, slightly crackly mid-century radio announcer who speaks clearly and kindly.",
    "Calm & Robotic" to "A calm, even robot voice: clear, friendly and unhurried, with a light mechanical edge.",
    "Warm Conversational" to "A warm, relaxed voice that sounds like a friend chatting over coffee.",
    "Crisp Newsreader" to "A crisp, confident newsreader who keeps things short and clear.",
    "Friendly Mechanic" to "A friendly, practical voice with a grin in it, like a mechanic explaining a fix.",
)

private val REVIEW_WAVE = listOf(0.35f, 0.6f, 0.9f, 0.5f, 0.75f, 0.4f, 0.85f, 0.6f, 0.3f, 0.55f, 0.8f, 0.45f, 0.25f, 0.5f)

/** Choosing Rivet's voice, shown over the whole app. [stage] sits under the header and stays put while pages change. */
@Composable
fun VoiceSetup(
    studio: VoiceStudio,
    onClose: () -> Unit,
    start: VoiceScreen = VoiceScreen.Selector,
    stage: @Composable () -> Unit = {},
) {
    val p = LocalPalette.current
    var path by rememberSaveable { mutableStateOf(start.name) }
    val stack = path.split(',').map(VoiceScreen::valueOf)
    val screen = stack.last()
    val recorder = remember(studio) { studio.recorder() }

    var saved by remember(studio) { mutableStateOf(studio.current()) }
    var description by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(studio.presets().firstOrNull { it.name == studio.current() }?.id) }
    var samplePath by rememberSaveable { mutableStateOf<String?>(null) }
    var sampleSeconds by rememberSaveable { mutableIntStateOf(0) }
    val sample = samplePath?.let { VoiceSample(File(it), sampleSeconds) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }

    fun show(next: List<VoiceScreen>) {
        studio.stopPreview()
        notice = null
        path = next.joinToString(",") { it.name }
    }
    fun go(to: VoiceScreen) = show(stack + to)
    fun replace(with: VoiceScreen) = show(stack.dropLast(1) + with)
    fun close() {
        studio.stopPreview()
        if (screen == VoiceScreen.Capturing) recorder.cancel()
        onClose()
    }
    fun back() {
        if (screen == VoiceScreen.Capturing) recorder.cancel()
        if (stack.size > 1) show(stack.dropLast(1)) else close()
    }
    /** Saves [choice] and returns to the selector; a refusal (a file that is too big) stays put with its reason. */
    fun choose(choice: VoiceChoice) {
        val label = studio.choose(choice)
        if (label != studio.current()) {
            notice = label
            return
        }
        saved = label
        show(listOf(VoiceScreen.Selector))
    }

    BackHandler { back() }

    val reduce = rememberReduceMotion()
    Column(Modifier.fillMaxSize().background(p.bg)) {
        StickyHeader(if (screen == VoiceScreen.Selector) "01 / 02" else "02 / 02", onBack = ::back)
        stage()
        AnimatedContent(
            targetState = screen,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                if (reduce) {
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                } else {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally(tween(Motion.SLOW, easing = Motion.Emphasized)) { (if (forward) it else -it) / 6 } + fadeIn(tween(Motion.BASE))) togetherWith
                        fadeOut(tween(Motion.QUICK))
                }
            },
            label = "voice",
        ) { at ->
            when (at) {
                VoiceScreen.Selector -> SelectorPage(studio, saved, ::go, ::close) { close() }
                VoiceScreen.Describe -> DescribePage(
                    studio, description, { description = it }, ::close,
                ) { choose(VoiceChoice.Described(description.trim())) }
                VoiceScreen.Presets -> PresetsPage(studio, picked, { picked = it }, ::close) { preset ->
                    choose(VoiceChoice.Preset(preset))
                }
                VoiceScreen.Record -> RecordPage(recorder, sample != null, notice, { notice = it }, ::close, onStarted = {
                    go(VoiceScreen.Capturing)
                }) { go(VoiceScreen.Review) }
                VoiceScreen.Capturing -> CapturingPage(
                    recorder,
                    onFinish = {
                        val done = recorder.stop()
                        if (done == null) {
                            replace(VoiceScreen.Record)
                            notice = "That recording didn't save. Try again."
                        } else {
                            samplePath = done.file.path
                            sampleSeconds = done.seconds
                            replace(VoiceScreen.Review)
                        }
                    },
                    onCancel = {
                        recorder.cancel()
                        replace(VoiceScreen.Record)
                    },
                )
                VoiceScreen.Review -> ReviewPage(studio, sample, notice, onRerecord = {
                    if (stack.getOrNull(stack.lastIndex - 1) == VoiceScreen.Record) show(stack.dropLast(1)) else replace(VoiceScreen.Record)
                }) { s -> choose(VoiceChoice.Recorded(s)) }
                VoiceScreen.Upload -> UploadPage(notice, { notice = it }, ::close) { uri, name -> choose(VoiceChoice.Uploaded(uri, name)) }
            }
        }
    }
}

/** Eyebrow, hero title and subline over scrolling content, with the keys in a fade footer. */
@Composable
private fun VoicePage(
    title: String,
    sub: String,
    centered: Boolean = false,
    eyebrow: @Composable () -> Unit = { Eyebrow("VOICE CONFIGURATION") },
    footer: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    // The last of the content clears the footer however tall it grows.
    var footerHeight by remember { mutableStateOf(160.dp) }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
                    .then(if (centered) Modifier.heightIn(min = maxHeight) else Modifier)
                    .padding(bottom = footerHeight + 16.dp),
            ) {
                Spacer(Modifier.height(24.dp))
                eyebrow()
                Spacer(Modifier.height(10.dp))
                HeroTitle(title)
                Spacer(Modifier.height(12.dp))
                Text(sub, style = Type.bodyLg, color = p.ink2)
                Spacer(Modifier.height(28.dp))
                // A capture screen sits its content in the middle of the room left above the footer.
                if (centered) Spacer(Modifier.weight(1f))
                body()
                if (centered) Spacer(Modifier.weight(1f))
            }
            FadeFooter(
                Modifier.align(Alignment.BottomCenter).onSizeChanged { footerHeight = with(density) { it.height.toDp() } },
                content = footer,
            )
        }
    }
}

/** Lets a row run edge to edge out of the 20dp gutters (the chip rail bleeds off the right side). */
private fun Modifier.bleed(x: Dp) = layout { measurable, constraints ->
    val px = x.roundToPx()
    val width = constraints.maxWidth + px * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-px, 0) }
}

/** Lays out [y] shorter at the top and bottom, letting the live key's empty halo margin overlap its neighbours. */
private fun Modifier.trimY(y: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val px = y.roundToPx()
    layout(placeable.width, (placeable.height - px * 2).coerceAtLeast(0)) { placeable.place(0, -px) }
}

private fun clock(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)

@Composable
private fun ArrowMark() {
    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = LocalPalette.current.ink2, modifier = Modifier.size(20.dp))
}

/** A row card with a large icon well: the selector's dropdown and its ways to make a voice. */
@Composable
private fun RowCard(
    icon: ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    lit: Boolean = false,
    monoSub: Boolean = false,
    trailing: @Composable () -> Unit = { ArrowMark() },
) {
    val p = LocalPalette.current
    RivetCard(modifier, onClick = onClick, role = Role.Button) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(icon, tone = tone, selected = lit, size = 48.dp, iconSize = 22.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = Type.title, color = p.ink)
                Text(sub, style = if (monoSub) Type.mono else Type.body, color = p.ink2)
            }
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun SelectorPage(
    studio: VoiceStudio,
    saved: String?,
    go: (VoiceScreen) -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit,
) {
    val p = LocalPalette.current
    VoicePage(
        title = "RIVET'S VOICE",
        sub = "Pick a ready-made voice or make one that's yours.",
        footer = {
            PrimaryKey("Continue", onContinue, style = CtaStyle.Display)
            SecondaryKey(SKIP, onSkip)
        },
    ) {
        SectionLabel("Selected voice", trailing = "OPTIONAL")
        Spacer(Modifier.height(12.dp))
        val sub = when {
            saved == null -> "No voice chosen yet"
            studio.connected -> "Saved"
            else -> "Saved · plays through your phone until ElevenLabs is connected"
        }
        RowCard(
            Icons.Outlined.GraphicEq, saved ?: "Choose or make a voice", sub, { go(VoiceScreen.Presets) },
            Modifier.enter(0), tone = Tone.Accent, lit = saved != null, monoSub = true,
            trailing = { Icon(Icons.Outlined.UnfoldMore, contentDescription = null, tint = p.ink2, modifier = Modifier.size(22.dp)) },
        )
        Spacer(Modifier.height(32.dp))
        SectionLabel("Ways to make one", trailing = "4 WAYS")
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RowCard(Icons.Outlined.Mic, "Record a sample", "Record 30 seconds of your own voice", { go(VoiceScreen.Record) }, Modifier.enter(1))
            RowCard(Icons.Outlined.AutoAwesome, "Describe a voice", "Say what it should sound like in your own words", { go(VoiceScreen.Describe) }, Modifier.enter(2))
            RowCard(Icons.Outlined.UploadFile, "Upload a recording", "Use a .wav or .mp3 file you already have", { go(VoiceScreen.Upload) }, Modifier.enter(3))
            RowCard(Icons.Outlined.Tune, "Preset library", "Ready-made voices to choose from", { go(VoiceScreen.Presets) }, Modifier.enter(4))
        }
    }
}

@Composable
private fun DescribePage(
    studio: VoiceStudio,
    text: String,
    onText: (String) -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit,
) {
    val p = LocalPalette.current
    VoicePage(
        title = "DESCRIBE A VOICE",
        sub = "Describe how Rivet should sound, in plain words.",
        footer = {
            PrimaryKey("Continue", onContinue, style = CtaStyle.Display, enabled = text.isNotBlank())
            SecondaryKey(SKIP, onSkip)
        },
    ) {
        ComposerCard(
            text, onText, "e.g., A calm, witty radio operator with dry warmth and crisp articulation…", MAX_DESCRIPTION,
            Modifier.enter(0),
        )
        Spacer(Modifier.height(28.dp))
        SectionLabel("Suggested presets", trailing = "TAP TO APPLY")
        Spacer(Modifier.height(12.dp))
        ChipRail(Modifier.bleed(20.dp).enter(1)) {
            DESCRIBE_CHIPS.forEach { (label, fill) ->
                item(label) { PillChip(label, { onText(fill.take(MAX_DESCRIPTION)) }, selected = text == fill) }
            }
        }
        Spacer(Modifier.height(12.dp))
        val ready = text.isNotBlank()
        TextKey(
            "Hear a sample", { if (ready) studio.preview(SAMPLE_LINE) },
            Modifier.padding(start = 0.dp).alpha(if (ready) 1f else 0.4f),
            leading = Icons.Outlined.PlayArrow, tone = Tone.Accent,
        )
        if (!studio.connected && ready) {
            Spacer(Modifier.height(8.dp))
            Text(
                "The sample plays in your phone's voice until Rivet's ElevenLabs voice is connected.",
                style = Type.cardBody, color = p.ink2,
            )
        }
    }
}

private fun VoicePreset.icon(): ImageVector = when {
    id == "vesper" -> Icons.Outlined.Radio
    tone == VoiceTone.Warm -> Icons.Outlined.GraphicEq
    tone == VoiceTone.Crisp -> Icons.Outlined.RecordVoiceOver
    else -> Icons.Outlined.Mic
}

@Composable
private fun PresetsPage(
    studio: VoiceStudio,
    picked: String?,
    onPick: (String) -> Unit,
    onSkip: () -> Unit,
    onChoose: (VoicePreset) -> Unit,
) {
    val presets = remember(studio) { studio.presets() }
    val active = presets.firstOrNull { it.name == studio.current() }?.id
    var filter by rememberSaveable { mutableStateOf<VoiceTone?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    val chosen = presets.firstOrNull { it.id == picked }
    VoicePage(
        title = "PRESET LIBRARY",
        sub = "Choose from ${presets.size} ready-made voices.",
        footer = {
            PrimaryKey("Continue", { chosen?.let(onChoose) }, style = CtaStyle.Display, enabled = chosen != null)
            SecondaryKey(SKIP, onSkip)
        },
    ) {
        Row(Modifier.bleed(20.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("ALL", filter == null, { filter = null })
            VoiceTone.entries.forEach { tone -> FilterChip(tone.name.uppercase(), filter == tone, { filter = tone }) }
        }
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            presets.filter { filter == null || it.tone == filter }.forEachIndexed { i, preset ->
                PresetCard(
                    preset, selected = preset.id == picked, active = preset.id == active, playing = playing == preset.id,
                    onSelect = { onPick(preset.id) },
                    onPlay = {
                        if (playing == preset.id) {
                            studio.stopPreview()
                            playing = null
                        } else {
                            playing = preset.id
                            studio.preview("Hi, I'm ${preset.name}. This is how Rivet sounds with my voice.", preset) {
                                if (playing == preset.id) playing = null
                            }
                        }
                    },
                    modifier = Modifier.enter(i),
                )
            }
        }
        if (!studio.connected) {
            Spacer(Modifier.height(20.dp))
            InfoStrip("Previews play through your phone's voice until Rivet's ElevenLabs voice is connected.")
        }
    }
}

@Composable
private fun PresetCard(
    preset: VoicePreset,
    selected: Boolean,
    active: Boolean,
    playing: Boolean,
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    RivetCard(modifier.semantics { this.selected = selected }, selected = selected, onClick = onSelect, role = Role.RadioButton) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(preset.icon(), selected = selected, size = 44.dp, iconSize = 22.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(preset.name, style = Type.title, color = p.ink, maxLines = 1)
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        StatusTag("ACTIVE", Tone.Accent)
                    }
                }
                Text(preset.blurb, style = Type.body, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(4.dp))
            PlayKey(playing, onPlay)
            Spacer(Modifier.width(4.dp))
            RadioDot(selected)
        }
    }
}

@Composable
private fun RecordPage(
    recorder: SampleRecorder,
    hasSample: Boolean,
    notice: String?,
    onNotice: (String?) -> Unit,
    onSkip: () -> Unit,
    onStarted: () -> Unit,
    onContinue: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val begin = {
        if (recorder.start()) onStarted() else onNotice("Rivet couldn't use the microphone. Check that no other app is recording.")
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else onNotice("Rivet needs the microphone to record. Allow it when Android asks, or in Android Settings.")
    }
    VoicePage(
        title = "RECORD SAMPLE",
        sub = "Tap the microphone to start recording.",
        eyebrow = { Eyebrow("VOICE SAMPLE") },
        centered = true,
        footer = {
            PrimaryKey("Continue", onContinue, style = CtaStyle.Display, enabled = hasSample)
            SecondaryKey(SKIP, onSkip)
        },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            HaloTalkKey(
                TalkState.Idle,
                {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (granted) begin() else ask.launch(Manifest.permission.RECORD_AUDIO)
                },
                "Start recording",
                large = true,
            )
            Spacer(Modifier.height(24.dp))
            Text("TAP TO RECORD", style = Type.monoKey, color = p.ink)
            Spacer(Modifier.height(6.dp))
            Text("30-second sample", style = Type.mono, color = p.ink2)
        }
        if (notice != null) {
            Spacer(Modifier.height(24.dp))
            InfoStrip(notice, icon = Icons.Outlined.ErrorOutline, tone = Tone.Red)
        }
    }
}

@Composable
private fun CapturingPage(recorder: SampleRecorder, onFinish: () -> Unit, onCancel: () -> Unit) {
    val p = LocalPalette.current
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val levels = remember { mutableStateListOf<Float>() }
    val recent = remember { ArrayDeque<Float>() }
    var peak by remember { mutableFloatStateOf(0f) }
    var showLine by rememberSaveable { mutableStateOf(false) }
    var stopped by remember { mutableStateOf(false) }
    val finish = {
        if (!stopped) {
            stopped = true
            onFinish()
        }
    }
    LaunchedEffect(Unit) {
        val t0 = withFrameMillis { it }
        while (!stopped) {
            delay(80)
            val level = recorder.level().coerceIn(0f, 1f)
            levels.add(level)
            if (levels.size > 12) levels.removeAt(0)
            recent.addLast(level)
            if (recent.size > 25) recent.removeFirst()
            peak = maxOf(peak, level)
            elapsedMs = withFrameMillis { it } - t0
            if (elapsedMs >= MAX_SECONDS * 1000L) finish()
        }
    }
    val (status, tone) = when {
        recent.any { it >= 0.98f } -> "TOO LOUD" to Tone.Red
        recent.size >= 25 && recent.all { it < 0.08f } -> "TOO QUIET" to Tone.Amber
        else -> "LEVEL GOOD" to Tone.Green
    }
    VoicePage(
        title = "CAPTURING",
        sub = "Speak naturally, up to 30 seconds.",
        eyebrow = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("RECORDING", tone = Tone.Red, live = true)
                Spacer(Modifier.weight(1f))
                TimerPill((elapsedMs / 1000).toInt().coerceAtMost(MAX_SECONDS), MAX_SECONDS)
            }
        },
        footer = {
            PrimaryKey("Finish recording", finish, style = CtaStyle.Display, leading = Icons.Outlined.StopCircle, trailingArrow = false)
            SecondaryKey("Cancel sample", { if (!stopped) { stopped = true; onCancel() } }, leading = Icons.Outlined.Close)
        },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            HaloTalkKey(TalkState.Listening, finish, "Finish recording", Modifier.trimY(36.dp), large = true)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RECORDING…", style = Type.monoKey, color = p.ink)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(6.dp).alpha(pulse(low = 0.3f, periodMs = 1200)).glow(Glows.peak, CircleShape).clip(CircleShape).background(p.accent))
            }
            Spacer(Modifier.height(6.dp))
            Text("Tap to finish sample", style = Type.mono, color = p.ink2)
        }
        Spacer(Modifier.height(28.dp))
        Rule(color = p.line)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Subtitles, contentDescription = null, tint = p.ink2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("Show the line to read", style = Type.title, color = p.ink, modifier = Modifier.weight(1f))
            RivetToggle(showLine, { showLine = it })
        }
        Spacer(Modifier.height(8.dp))
        RivetCard(padding = PaddingValues(20.dp)) {
            if (showLine) {
                Eyebrow("READ THIS OUT LOUD", tone = Tone.Neutral, dot = false)
                Spacer(Modifier.height(12.dp))
                Text(SAMPLE_LINE, style = Type.quote, color = p.ink)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.BarChart, contentDescription = null, tint = p.ink, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("SOUND LEVEL", style = Type.monoKey, color = p.ink)
                }
                Spacer(Modifier.height(14.dp))
                Rule(color = p.line)
                Spacer(Modifier.height(16.dp))
                LevelMeter(levels.toList().let { List(12 - it.size) { 0f } + it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Rule(color = p.line)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PEAK ${(peak * 100).toInt()}%", style = Type.labelMd, color = p.ink2, modifier = Modifier.weight(1f))
                    Text(status, style = Type.labelMd, color = tone.color())
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { stopped = true } }
}

@Composable
private fun ReviewPage(
    studio: VoiceStudio,
    sample: VoiceSample?,
    notice: String?,
    onRerecord: () -> Unit,
    onUse: (VoiceSample) -> Unit,
) {
    val p = LocalPalette.current
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val length = sample?.seconds ?: 0
    VoicePage(
        title = "SAMPLE REVIEW",
        sub = "Recording done. Listen back, or record it again.",
        centered = true,
        footer = {
            PrimaryKey("Use sample", { sample?.let(onUse) }, style = CtaStyle.Display, enabled = sample != null)
            SecondaryKey("Re-record sample", onRerecord, leading = Icons.Outlined.Replay)
        },
    ) {
        RivetCard(padding = PaddingValues(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("READY TO PREVIEW", Modifier.weight(1f))
                Icon(Icons.Outlined.Schedule, contentDescription = null, tint = p.ink2, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(clock(length), style = Type.labelMd, color = p.ink2)
            }
            Spacer(Modifier.height(16.dp))
            Rule(color = p.line)
            Spacer(Modifier.height(16.dp))
            Text("\u201C$SAMPLE_LINE\u201D", style = Type.quote, color = p.ink)
            Spacer(Modifier.height(28.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                PlayKey(
                    playing,
                    {
                        when {
                            sample == null -> Unit
                            playing -> {
                                studio.stopPreview()
                                playing = false
                            }
                            else -> {
                                playing = true
                                progress = 0f
                                studio.play(sample, onProgress = { progress = it }) { playing = false }
                            }
                        }
                    },
                    style = PlayStyle.Ghost,
                )
                Spacer(Modifier.height(18.dp))
                Text(if (playing) "PLAYING" else "TAP TO LISTEN", style = Type.monoKey, color = p.ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${clock((progress * length).toInt())} / ${clock(length)}",
                    style = Type.labelMd.copy(fontSize = 12.sp, fontFeatureSettings = "tnum"), color = p.ink2,
                )
            }
            Spacer(Modifier.height(24.dp))
            Rule(color = p.line)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(p.ok))
                Spacer(Modifier.width(8.dp))
                Text("SAVED ON THIS PHONE", style = Type.labelMd, color = p.ink2, maxLines = 1, modifier = Modifier.weight(1f))
                MiniWave(REVIEW_WAVE, progress)
            }
        }
        if (notice != null) {
            Spacer(Modifier.height(16.dp))
            InfoStrip(notice, icon = Icons.Outlined.ErrorOutline, tone = Tone.Red)
        }
    }
}

/** A picked audio file: its name and, when Android can read it, its length. */
private data class PickedFile(val uri: Uri, val name: String, val seconds: Int?)

@Composable
private fun UploadPage(
    notice: String?,
    onNotice: (String?) -> Unit,
    onSkip: () -> Unit,
    onChoose: (Uri, String) -> Unit,
) {
    val context = LocalContext.current
    var uri by rememberSaveable { mutableStateOf<String?>(null) }
    val picked = remember(uri) { uri?.let { readFile(context, it.toUri()) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { result ->
        if (result != null) {
            uri = result.toString()
            onNotice(null)
        }
    }
    VoicePage(
        title = "UPLOAD A RECORDING",
        sub = "Use a recording you already have of the voice you want.",
        footer = {
            PrimaryKey("Continue", { picked?.let { onChoose(it.uri, it.name) } }, style = CtaStyle.Display, enabled = picked != null)
            TextKey(SKIP, onSkip)
        },
    ) {
        RivetCard(padding = PaddingValues(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("AUDIO FILE", Modifier.weight(1f))
                StatusTag("WAV / FLAC / MP3", Tone.Neutral, outline = true)
            }
            Spacer(Modifier.height(16.dp))
            Rule(color = LocalPalette.current.line)
            Spacer(Modifier.height(16.dp))
            if (picked == null) {
                DropZone("CHOOSE AUDIO", "Tap to pick a file from your phone (at least 30 seconds)", { pick.launch(arrayOf("audio/*")) })
            } else {
                val length = picked.seconds?.let { "${clock(it)} long · " }.orEmpty()
                DropZone(picked.name, "${length}Tap to choose a different file", { pick.launch(arrayOf("audio/*")) }, picked = true)
            }
            Spacer(Modifier.height(16.dp))
            val seconds = picked?.seconds
            when {
                picked == null -> StatusLine("READY FOR A FILE", Tone.Green, bars = true)
                seconds == null -> StatusLine("FILE PICKED", Tone.Green)
                seconds >= MAX_SECONDS -> StatusLine("FILE LOOKS GOOD", Tone.Green, bars = true)
                else -> StatusLine("SHORTER THAN 30 SECONDS", Tone.Amber)
            }
        }
        if (notice != null) {
            Spacer(Modifier.height(16.dp))
            InfoStrip(notice, icon = Icons.Outlined.ErrorOutline, tone = Tone.Red)
        }
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CheckCard("Clean speech", "No heavy background noise or music", "REQUIRED", Tone.Neutral)
            CheckCard("Best quality", "A .wav or .flac file gives the clearest result", "RECOMMENDED", Tone.Accent)
        }
    }
}

private fun readFile(context: Context, uri: Uri): PickedFile {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Audio file"
    val seconds = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(context, uri)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { (it / 1000).toInt() }
            } finally {
                release()
            }
        }
    }.getOrNull()
    return PickedFile(uri, name, seconds)
}
