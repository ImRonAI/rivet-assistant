package com.hiron.emrys.ui

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
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.hiron.emrys.voice.SampleRecorder
import com.hiron.emrys.voice.VoiceChoice
import com.hiron.emrys.voice.VoicePreset
import com.hiron.emrys.voice.VoiceSample
import com.hiron.emrys.voice.VoiceStudio
import com.hiron.emrys.voice.VoiceTone
import java.io.File
import kotlinx.coroutines.delay

/** The voice setup screens, in the order the selector reaches them. */
enum class VoiceScreen { Selector, Presets, Describe, Record, Capturing, Review, Upload }

/** The line read on the record screen and shown on the review card. */
const val SAMPLE_LINE = "Hello Emrys. Today we are building a helpful assistant with an honest heart, expressive eyes, and a clear, friendly voice."

private const val MAX_SECONDS = 30
private const val MAX_DESCRIPTION = 280
private const val SKIP = "Skip voice setup"

private val DESCRIBE_CHIPS = listOf(
    "Vintage radio" to "A warm, slightly crackly mid-century radio announcer who speaks clearly and kindly.",
    "Calm & robotic" to "A calm, even robot voice: clear, friendly and unhurried, with a light mechanical edge.",
    "Warm conversational" to "A warm, relaxed voice that sounds like a friend chatting over coffee.",
    "Crisp newsreader" to "A crisp, confident newsreader who keeps things short and clear.",
    "Friendly mechanic" to "A friendly, practical voice with a grin in it, like a mechanic explaining a fix.",
)

private val REVIEW_WAVE = listOf(0.35f, 0.6f, 0.9f, 0.5f, 0.75f, 0.4f, 0.85f, 0.6f, 0.3f, 0.55f, 0.8f, 0.45f, 0.25f, 0.5f)

/** A voice's name, set like a name: Instrument Serif, a little smaller than the wordmark. */
private val VoiceName = Type.brand.copy(fontSize = 24.sp, lineHeight = 28.sp)

/** Choosing Emrys's voice, shown over the whole app. [stage] sits under the header and stays put while pages change. */
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

/** Eyebrow, serif title (ended by the halo stop) and sub-line over scrolling content, with the keys in a fade footer. */
@Composable
private fun VoicePage(
    title: String,
    sub: String,
    centered: Boolean = false,
    eyebrow: @Composable () -> Unit = { Eyebrow("Voice configuration") },
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
                Spacer(Modifier.height(28.dp))
                eyebrow()
                Spacer(Modifier.height(14.dp))
                HeroTitle(title, Modifier.semantics { heading() })
                Spacer(Modifier.height(12.dp))
                Text(sub, style = Type.bodyLg.copy(lineBreak = LineBreak.Heading), color = p.ink2)
                Spacer(Modifier.height(32.dp))
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
    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = LocalPalette.current.ink2, modifier = Modifier.size(18.dp))
}

/**
 * The selected-voice card: an icon well, the voice's name (set in serif once one is [named]) over a quiet line, and
 * [trailing] on the right.
 */
@Composable
private fun RowCard(
    icon: ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lit: Boolean = false,
    named: Boolean = false,
    trailing: @Composable () -> Unit = { ArrowMark() },
) {
    val p = LocalPalette.current
    EmrysCard(modifier, onClick = onClick, role = Role.Button, padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(icon, selected = lit, size = 48.dp, iconSize = 22.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = if (named) VoiceName else Type.title, color = p.ink)
                Text(sub, style = Type.body, color = p.ink2)
            }
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** One way to make a voice, a row of the selector's glass list: icon well, title over a line, and an arrow. */
@Composable
private fun WayRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier.fillMaxWidth().background(if (pressed) p.cardHi else Color.Transparent)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconWell(icon, size = 40.dp, iconSize = 20.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Type.title, color = p.ink)
            Text(sub, style = Type.body, color = p.ink2)
        }
        Spacer(Modifier.width(12.dp))
        ArrowMark()
    }
}

private class Way(val icon: ImageVector, val title: String, val sub: String, val to: VoiceScreen)

private val WAYS = listOf(
    Way(Icons.Outlined.Mic, "Record a sample", "Record 30 seconds of your own voice", VoiceScreen.Record),
    Way(Icons.Outlined.AutoAwesome, "Describe a voice", "Say what it should sound like in your own words", VoiceScreen.Describe),
    Way(Icons.Outlined.UploadFile, "Upload a recording", "Use a .wav or .mp3 file you already have", VoiceScreen.Upload),
    Way(Icons.Outlined.Tune, "Preset library", "Ready-made voices to choose from", VoiceScreen.Presets),
)

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
        title = "Emrys's voice",
        sub = "Pick a ready-made voice or make one that's yours.",
        footer = {
            PrimaryKey("Continue", onContinue, style = CtaStyle.Display)
            SecondaryKey(SKIP, onSkip)
        },
    ) {
        SectionLabel("Selected voice", trailing = "Optional")
        Spacer(Modifier.height(12.dp))
        val sub = when {
            saved == null -> "No voice chosen yet"
            studio.connected -> "Saved"
            else -> "Saved · plays through your phone until ElevenLabs is connected"
        }
        RowCard(
            Icons.Outlined.GraphicEq, saved ?: "Choose or make a voice", sub, { go(VoiceScreen.Presets) },
            Modifier.enter(0), lit = saved != null, named = saved != null,
            trailing = { Icon(Icons.Outlined.UnfoldMore, contentDescription = null, tint = p.ink2, modifier = Modifier.size(22.dp)) },
        )
        Spacer(Modifier.height(36.dp))
        SectionLabel("Ways to make one", trailing = "${WAYS.size} ways")
        Spacer(Modifier.height(12.dp))
        val shape = RoundedCornerShape(20.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(p.card).border(1.dp, p.line, shape)) {
            WAYS.forEachIndexed { i, way ->
                if (i > 0) Rule(Modifier.padding(start = 74.dp), color = p.line)
                WayRow(way.icon, way.title, way.sub, { go(way.to) }, Modifier.enter(i + 1))
            }
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
        title = "Describe a voice",
        sub = "Describe how Emrys should sound, in plain words.",
        footer = {
            PrimaryKey("Continue", onContinue, style = CtaStyle.Display, enabled = text.isNotBlank())
            SecondaryKey(SKIP, onSkip)
        },
    ) {
        ComposerCard(
            text, onText, "e.g., A calm, witty radio operator with dry warmth and crisp articulation…", MAX_DESCRIPTION,
            Modifier.enter(0),
        )
        Spacer(Modifier.height(32.dp))
        SectionLabel("Suggested presets", trailing = "Tap to apply")
        Spacer(Modifier.height(12.dp))
        ChipRail(Modifier.bleed(20.dp).enter(1)) {
            DESCRIBE_CHIPS.forEach { (label, fill) ->
                item(label) { PillChip(label, { onText(fill.take(MAX_DESCRIPTION)) }, selected = text == fill) }
            }
        }
        Spacer(Modifier.height(16.dp))
        val ready = text.isNotBlank()
        TextKey(
            "Hear a sample", { if (ready) studio.preview(SAMPLE_LINE) },
            Modifier.padding(start = 0.dp).alpha(if (ready) 1f else 0.4f),
            leading = Icons.Outlined.PlayArrow, tone = Tone.Accent,
        )
        if (!studio.connected && ready) {
            Spacer(Modifier.height(6.dp))
            Text(
                "The sample plays in your phone's voice until Emrys's ElevenLabs voice is connected.",
                style = Type.cardBody, color = p.ink2, modifier = Modifier.padding(horizontal = 10.dp),
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
        title = "Preset library",
        sub = "Choose from ${presets.size} ready-made voices.",
        footer = {
            PrimaryKey("Continue", { chosen?.let(onChoose) }, style = CtaStyle.Display, enabled = chosen != null)
            SecondaryKey(SKIP, onSkip)
        },
    ) {
        Row(Modifier.bleed(20.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("All", filter == null, { filter = null })
            VoiceTone.entries.forEach { tone -> FilterChip(tone.name, filter == tone, { filter = tone }) }
        }
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                            studio.preview("Hi, I'm ${preset.name}. This is how Emrys sounds with my voice.", preset) {
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
            InfoStrip("Previews play through your phone's voice until Emrys's ElevenLabs voice is connected.")
        }
    }
}

/** A ready-made voice as a choice card: its name in serif over its character, a preview key and a radio; chosen, it wears the halo ring. */
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
    EmrysCard(
        modifier.ring(p.accent.copy(alpha = 0.2f), RoundedCornerShape(20.dp), selected, gap = 3.dp).semantics { this.selected = selected },
        selected = selected, onClick = onSelect, role = Role.RadioButton,
        padding = PaddingValues(start = 18.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(preset.icon(), selected = selected, size = 44.dp, iconSize = 20.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(preset.name, style = VoiceName, color = p.ink, maxLines = 1)
                    if (active) {
                        Spacer(Modifier.width(10.dp))
                        StatusTag("Active", Tone.Accent)
                    }
                }
                Text(preset.blurb, style = Type.body, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(4.dp))
            PlayKey(playing, onPlay)
            RadioDot(selected, Modifier.padding(start = 4.dp, end = 6.dp))
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
        if (recorder.start()) onStarted() else onNotice("Emrys couldn't use the microphone. Check that no other app is recording.")
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else onNotice("Emrys needs the microphone to record. Allow it when Android asks, or in Android Settings.")
    }
    VoicePage(
        title = "Record sample",
        sub = "Tap the microphone to start recording.",
        eyebrow = { Eyebrow("Voice sample") },
        centered = true,
        footer = {
            PrimaryKey("Continue", onContinue, style = CtaStyle.Display, enabled = hasSample)
            SecondaryKey(SKIP, onSkip)
        },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            HaloTalkKey(
                TalkState.Idle,
                {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (granted) begin() else ask.launch(Manifest.permission.RECORD_AUDIO)
                },
                "Start recording",
                large = true,
            )
            Spacer(Modifier.height(20.dp))
            Text("Tap to record", style = Type.title, color = p.ink)
            Spacer(Modifier.height(4.dp))
            Text("30-second sample", style = Type.body, color = p.ink2)
        }
        if (notice != null) {
            Spacer(Modifier.height(24.dp))
            InfoStrip(notice, icon = Icons.Outlined.ErrorOutline, tone = Tone.Red)
        }
    }
}

/**
 * The microphone's live level around the record key: a calm aurora ring that widens, thickens and brightens with
 * the real level, easing between readings (stepping, with motion reduced).
 */
@Composable
private fun LevelRing(level: Float, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val spec: AnimationSpec<Float> = if (rememberReduceMotion()) snap() else tween(Motion.BASE, easing = Motion.Standard)
    val v by animateFloatAsState(level.coerceIn(0f, 1f), spec, label = "level")
    Canvas(modifier) {
        val r = 84.dp.toPx() + 14.dp.toPx() * v
        val bloom = r + 12.dp.toPx()
        val edge = r / bloom
        drawCircle(
            Brush.radialGradient(
                (edge - 0.25f).coerceAtLeast(0f) to Color.Transparent, edge to p.accent.copy(alpha = 0.12f * v), 1f to Color.Transparent,
                center = center, radius = bloom,
            ),
            bloom,
        )
        val aurora = listOf(p.accentHi, p.accent, p.accentDeep, p.cy, p.accent, p.accentHi).map { it.copy(alpha = 0.22f + 0.6f * v) }
        drawCircle(Brush.sweepGradient(aurora, center), r, style = Stroke((1f + 1.5f * v).dp.toPx()))
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
        recent.any { it >= 0.98f } -> "Too loud" to Tone.Red
        recent.size >= 25 && recent.all { it < 0.08f } -> "Too quiet" to Tone.Amber
        else -> "Level good" to Tone.Green
    }
    VoicePage(
        title = "Capturing",
        sub = "Speak naturally, up to 30 seconds.",
        eyebrow = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Recording", tone = Tone.Red, live = true)
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
            Box(Modifier.trimY(36.dp), contentAlignment = Alignment.Center) {
                LevelRing(levels.lastOrNull() ?: 0f, Modifier.size(224.dp))
                HaloTalkKey(TalkState.Listening, finish, "Finish recording", large = true)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recording…", style = Type.title, color = p.ink)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(6.dp).alpha(pulse(low = 0.3f, periodMs = 1200)).clip(CircleShape).background(p.rec))
            }
            Spacer(Modifier.height(4.dp))
            Text("Tap to finish sample", style = Type.body, color = p.ink2)
        }
        Spacer(Modifier.height(32.dp))
        Rule(color = p.line)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Subtitles, contentDescription = null, tint = if (showLine) p.ink else p.ink2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text("Show the line to read", style = Type.title, color = p.ink, modifier = Modifier.weight(1f))
            EmrysToggle(showLine, { showLine = it })
        }
        Rule(color = p.line)
        Spacer(Modifier.height(16.dp))
        EmrysCard(padding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 20.dp)) {
            if (showLine) {
                Eyebrow("Read this out loud", tone = Tone.Neutral, dot = false)
                Spacer(Modifier.height(14.dp))
                Text(SAMPLE_LINE, style = Type.quote, color = p.ink)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Sound level", Modifier.weight(1f), tone = Tone.Neutral, dot = false)
                    SignalDot(tone.color())
                    Spacer(Modifier.width(8.dp))
                    Text(status, style = Type.labelLg, color = p.ink)
                }
                Spacer(Modifier.height(18.dp))
                LevelMeter(levels.toList().let { List(12 - it.size) { 0f } + it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                Text("Peak ${(peak * 100).toInt()}%", style = Type.labelMd.copy(fontFeatureSettings = "tnum"), color = p.ink2)
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
        title = "Sample review",
        sub = "Recording done. Listen back, or record it again.",
        centered = true,
        footer = {
            PrimaryKey("Use sample", { sample?.let(onUse) }, style = CtaStyle.Display, enabled = sample != null)
            SecondaryKey("Re-record sample", onRerecord, leading = Icons.Outlined.Replay)
        },
    ) {
        EmrysCard(padding = PaddingValues(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Ready to preview", Modifier.weight(1f))
                Icon(Icons.Outlined.Schedule, contentDescription = null, tint = p.ink2, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(clock(length), style = Type.tele, color = p.ink2)
            }
            Spacer(Modifier.height(20.dp))
            Text("“$SAMPLE_LINE”", style = Type.quote, color = p.ink)
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
                Spacer(Modifier.height(16.dp))
                Text(if (playing) "Playing" else "Tap to listen", style = Type.title, color = p.ink)
                Spacer(Modifier.height(4.dp))
                Text("${clock((progress * length).toInt())} / ${clock(length)}", style = Type.tele, color = p.ink2)
            }
            Spacer(Modifier.height(24.dp))
            Rule(color = p.line)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalDot(p.ok)
                Spacer(Modifier.width(10.dp))
                Text("Saved on this phone", style = Type.labelLg, color = p.ink2, maxLines = 1, modifier = Modifier.weight(1f))
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
    val p = LocalPalette.current
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
        title = "Upload a recording",
        sub = "Use a recording you already have of the voice you want.",
        footer = {
            PrimaryKey("Continue", { picked?.let { onChoose(it.uri, it.name) } }, style = CtaStyle.Display, enabled = picked != null)
            TextKey(SKIP, onSkip)
        },
    ) {
        EmrysCard(padding = PaddingValues(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Audio file", Modifier.weight(1f))
                Text("WAV / FLAC / MP3", style = Type.labelMd, color = p.ink2, maxLines = 1)
            }
            Spacer(Modifier.height(18.dp))
            if (picked == null) {
                DropZone("Choose audio", "Tap to pick a file from your phone (at least 30 seconds)", { pick.launch(arrayOf("audio/*")) })
            } else {
                val length = picked.seconds?.let { "${clock(it)} long · " }.orEmpty()
                DropZone(picked.name, "${length}Tap to choose a different file", { pick.launch(arrayOf("audio/*")) }, picked = true)
            }
            Spacer(Modifier.height(18.dp))
            val seconds = picked?.seconds
            when {
                picked == null -> StatusLine("Ready for a file", Tone.Green, bars = true)
                seconds == null -> StatusLine("File picked", Tone.Green)
                seconds >= MAX_SECONDS -> StatusLine("File looks good", Tone.Green, bars = true)
                else -> StatusLine("Shorter than 30 seconds", Tone.Amber)
            }
        }
        if (notice != null) {
            Spacer(Modifier.height(16.dp))
            InfoStrip(notice, icon = Icons.Outlined.ErrorOutline, tone = Tone.Red)
        }
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CheckCard("Clean speech", "No heavy background noise or music", "Required", Tone.Neutral)
            CheckCard("Best quality", "A .wav or .flac file gives the clearest result", "Recommended", Tone.Accent)
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
