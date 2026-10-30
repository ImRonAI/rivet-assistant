package com.hiron.emrys.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NorthWest
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiron.emrys.core.Activity
import com.hiron.emrys.core.ChatMessage
import com.hiron.emrys.core.Connection
import com.hiron.emrys.core.ContextItem
import com.hiron.emrys.core.LiveCallStatus
import com.hiron.emrys.core.EmrysRuntime
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.core.Suggester
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Date

/** One-tap requests on the dock, the widget and the launcher shortcuts: the things people ask Emrys for most. */
enum class Quick(val label: String, val tag: String, val prompt: String) {
    CatchUp("Catch me up", "ALL", "Catch me up: unread texts, missed calls, messages callers left, and anything waiting on me."),
    MyDay("My day", "CAL", "What's on my calendar today?"),
    Texts("Texts", "SMS", "Read my unread texts and suggest replies."),
    Calls("Calls", "TEL", "Did anyone call or leave a message? Tell me what they wanted."),
    ;

    companion object {
        fun of(key: String?): Quick? = entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
    }
}

const val STAGE_HINT = "Drag to look · tap to say hi"

/** The page's side margins: 20dp, and 16dp on phones under 360dp wide. */
@Composable
private fun gutter(): Dp {
    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return if (wide > 0.dp && wide < 360.dp) 16.dp else 20.dp
}

/**
 * Home: Emrys on its stage with a greeting, the transcript sheet once there is a conversation, and the dock with
 * quick requests and the composer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    runtime: EmrysRuntime,
    state: EmrysState,
    onListen: () -> Unit,
    onReviewApprovals: () -> Unit,
    focusRequests: Int = 0,
    onOpenResults: (ResultsRequest) -> Unit = {},
    suggester: Suggester? = runtime.search,
) {
    val p = LocalPalette.current
    val keyboard = WindowInsets.isImeVisible
    val talking = state.messages.isNotEmpty()
    val voice = runtime.settings.voiceModel
    var expanded by rememberSaveable { mutableStateOf(false) }
    // While a call is live its plate holds the room the transcript would take, so the robot keeps a readable stage.
    val callLive = state.liveCall.let { it != null && it.status != LiveCallStatus.Ended }
    val sheet = talking && (!callLive || keyboard)
    // The robot owns the screen until there is a conversation, then shares it with the transcript sheet.
    val target = when {
        !sheet -> 1f
        keyboard -> 0.2f
        expanded -> 0.24f
        state.messages.size == 1 -> 0.62f
        else -> 0.5f
    }
    val stageShare by animateFloatAsState(target, spring(dampingRatio = 0.85f, stiffness = 300f), label = "stage")
    val actions = remember(runtime) {
        ResultActions(add = runtime::addContext, seeAll = { results, kind -> onOpenResults(ResultsRequest(results.query, kind, results)) })
    }
    // Emrys's own brain is offered when the ElevenLabs agent couldn't start and the brain can be reached.
    val useBrain = if (state.agentDown && runtime.settings.elevenLabsAgents && state.connection == Connection.Online) runtime::useOwnBrain else null
    CompositionLocalProvider(LocalResultActions provides actions) {
        Column(Modifier.fillMaxSize().background(p.bg)) {
            val idle = !talking && !keyboard && !callLive
            val fade by animateDpAsState(if (idle) 260.dp else 96.dp, tween(Motion.SLOW, easing = Motion.Emphasized), label = "fade")
            StageFrame(
                Modifier.weight(stageShare).fillMaxWidth(),
                framed = false,
                hint = if (idle) STAGE_HINT else null,
                mood = moodColor(state.activity),
            ) {
                // While the greeting shows, Emrys stands in the upper part of the stage with the words under its feet.
                RobotView(runtime, Modifier.fillMaxSize(), focus = if (idle) RobotFocus.Greet else RobotFocus.Stage)
                // The stage melts into what sits below it, and the greeting stays legible over the floor.
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(fade)
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to p.bg.copy(alpha = 0.78f), 1f to p.bg)),
                )
                StageGreeting(idle, state, voice)
            }
            AnimatedVisibility(state.approvals.isNotEmpty()) {
                ApprovalBanner(state.approvals.size, onReviewApprovals)
            }
            AnimatedVisibility(state.liveCall != null) {
                state.liveCall?.let { call ->
                    LiveCallCard(call, onTakeOver = { runtime.takeOverCall(call.callId) }, onDismiss = runtime::dismissLiveCall)
                }
            }
            if (sheet) {
                TranscriptSheet(state, expanded || keyboard, { expanded = it }, Modifier.weight((1f - stageShare).coerceAtLeast(0.01f)), useBrain)
            } else {
                state.lastError?.let { ErrorNote(it, useBrain, Modifier.padding(horizontal = gutter(), vertical = 8.dp)) }
                if (stageShare < 1f) Spacer(Modifier.weight(1f - stageShare))
            }
            Dock(
                keyboard = keyboard,
                voice = voice,
                listening = state.activity == Activity.Listening,
                focusRequests = focusRequests,
                chips = state.chips,
                onRemoveChip = runtime::removeChip,
                suggester = suggester.takeIf { runtime.settings.searchSuggestions },
                onSend = { text, asTask -> if (asTask) runtime.startTask(text) else runtime.ask(text) },
                onListen = onListen,
            )
        }
    }
}

const val USE_BRAIN = "Use Emrys's own brain"

/** The porcelain key that hands the conversation to Emrys's own brain. */
@Composable
private fun UseBrainKey(onClick: () -> Unit) = Keycap(USE_BRAIN, onClick, tone = KeyTone.Accent, icon = Icons.Outlined.Psychology)

/** Why something failed, as a coral dot and a plain sentence on glass, with Emrys's own brain offered under it when it can help. */
@Composable
private fun ErrorNote(error: String, useBrain: (() -> Unit)?, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(p.hud).border(1.dp, p.line, shape).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DotLine(error, p.rec)
        useBrain?.let { UseBrainKey(it) }
    }
}

/** A status dot set on the first line of a sentence that may wrap. */
@Composable
private fun DotLine(text: String, color: Color, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier) {
        // The dot sits on the middle of the first line (the body style's line is 19sp high).
        Box(Modifier.height(19.dp), contentAlignment = Alignment.Center) { SignalDot(color) }
        Spacer(Modifier.width(12.dp))
        Text(text, style = Type.body, color = p.ink)
    }
}

const val STAGE_TAG = "stage"

@Composable
private fun BoxScope.StageGreeting(visible: Boolean, state: EmrysState, voice: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Motion.SLOW)) + slideInVertically(tween(Motion.SLOW, easing = Motion.Emphasized)) { it / 4 },
        exit = fadeOut(tween(Motion.QUICK)),
        // Clears the stage hint under it.
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = gutter(), end = gutter(), bottom = 60.dp),
    ) { Greeting(state, voice) }
}

/** The greeting's serif, a size up from the type scale's: the hero line of the page. */
private val GREETING = Type.greeting.copy(fontSize = 52.sp, lineHeight = 54.sp)

/** The date and time as an eyebrow, the greeting set large in the serif, and one quiet line on what comes next. */
@Composable
private fun Greeting(state: EmrysState, voice: Boolean) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    // The second word is set in the serif's italic: the one emphasised word of the line.
    val (lead, lift) = when (hour) {
        in 5..11 -> "Good" to "morning"
        in 12..16 -> "Good" to "afternoon"
        in 17..21 -> "Good" to "evening"
        else -> "Up" to "late"
    }
    val date = DateFormat.format("EEE d MMM", Date(now)).toString()
    val time = DateFormat.getTimeFormat(context).format(Date(now))
    val (note, tone) = when {
        state.approvals.isNotEmpty() -> "${state.approvals.size} waiting on you" to p.amb
        state.connection == Connection.Offline -> "Not connected" to p.rec
        state.connection == Connection.Connecting -> "Connecting…" to p.amb
        voice -> "Tap the mic or pick a key below." to null
        else -> "Type below or pick a key." to null
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Eyebrow("$date · $time", dot = false)
        Spacer(Modifier.height(10.dp))
        Text(
            buildAnnotatedString {
                append("$lead ")
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(lift) }
                appendHaloStop()
            },
            style = GREETING, color = p.ink, textAlign = TextAlign.Center, inlineContent = haloStopContent(),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Something that needs the user is a status: a dot in its colour and the words in porcelain.
            if (tone != null) {
                SignalDot(tone, live = tone == p.amb)
                Spacer(Modifier.width(10.dp))
            }
            Text(note, style = Type.bodyLg, color = if (tone != null) p.ink else p.ink2, textAlign = TextAlign.Center)
        }
    }
}

/** Approvals waiting: a breathing honey dot and a sentence on glass, and the porcelain Review pill. */
@Composable
private fun ApprovalBanner(count: Int, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.padding(horizontal = gutter(), vertical = 8.dp).fillMaxWidth()
            .clip(shape).background(p.hud).border(1.dp, p.amb.copy(alpha = 0.28f), shape)
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalDot(p.amb, live = true)
        Spacer(Modifier.width(14.dp))
        Text(
            if (count == 1) "Emrys is waiting on 1 action" else "Emrys is waiting on $count actions",
            style = Type.title, color = p.ink, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(14.dp))
        Keycap("Review", onClick, tone = KeyTone.Accent)
    }
}

/** The transcript as a glass sheet over the stage: tap or drag its header to pull it up over Emrys, or back down. */
@Composable
private fun TranscriptSheet(state: EmrysState, expanded: Boolean, onExpand: (Boolean) -> Unit, modifier: Modifier, useBrain: (() -> Unit)?) {
    val p = LocalPalette.current
    val streaming = state.messages.any { it.streaming }
    val working = state.activeTurn != null && state.messages.lastOrNull()?.let { it.fromUser || (it.streaming && it.text.isBlank()) } == true
    var drag by remember { mutableFloatStateOf(0f) }
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(Motion.BASE, easing = Motion.Emphasized), label = "chevron")
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val gutter = gutter()
    Column(
        modifier.fillMaxWidth().clip(shape).background(p.raised)
            // A faint porcelain sheen along the top edge, as light catches glass.
            .background(Brush.verticalGradient(0f to p.ink.copy(alpha = 0.035f), 0.18f to Color.Transparent))
            .border(1.dp, p.line, shape),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .draggable(
                    rememberDraggableState { drag += it },
                    Orientation.Vertical,
                    onDragStopped = {
                        if (drag < -24f) onExpand(true) else if (drag > 24f) onExpand(false)
                        drag = 0f
                    },
                )
                .clickable { onExpand(!expanded) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.padding(top = 10.dp).size(width = 32.dp, height = 3.dp).clip(RoundedCornerShape(50)).background(p.dot))
            Row(Modifier.fillMaxWidth().height(48.dp).padding(start = gutter, end = gutter - 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Conversation", dot = false)
                val meta = when {
                    streaming -> "Replying…"
                    working -> "Thinking…"
                    else -> null
                }
                if (meta != null) {
                    Spacer(Modifier.width(14.dp))
                    SignalDot(p.cy, live = true)
                    Spacer(Modifier.width(8.dp))
                    Text(meta, style = Type.labelMd, color = p.ink2, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Outlined.KeyboardArrowUp, contentDescription = if (expanded) "Show Emrys" else "Expand transcript",
                    tint = p.ink2, modifier = Modifier.size(22.dp).rotate(turn),
                )
            }
        }
        Chat(state.messages, state.lastError, Modifier.weight(1f), working = working, onUseBrain = useBrain)
    }
}

const val CHAT_TAG = "chat"
const val COMPOSER_TAG = "composer"

@Composable
internal fun Chat(messages: List<ChatMessage>, error: String?, modifier: Modifier, working: Boolean = false, onUseBrain: (() -> Unit)? = null) {
    val p = LocalPalette.current
    val list = rememberLazyListState()
    val gutter = gutter()
    // Newest at the bottom (reversed layout): streaming text stays in view while you're at the bottom and
    // never pulls you back down if you scrolled up; a new message brings you to the bottom.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) list.animateScrollToItem(0)
    }
    if (messages.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(horizontal = gutter + 8.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
            Text(
                error ?: "Ask Emrys to text someone, check your calendar, reply to a message, or run a task.",
                color = if (error != null) p.rec else p.ink2, style = Type.bodyLg, textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(
        modifier.fillMaxWidth().testTag(CHAT_TAG).fadeTop(list.canScrollForward),
        state = list,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.Bottom),
    ) {
        item(key = "end") { Spacer(Modifier.height(6.dp)) }
        if (working) item(key = "working") { Working(Modifier.animateItem().padding(horizontal = gutter)) }
        if (error != null) {
            item(key = "error") {
                Column(Modifier.padding(horizontal = gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DotLine(error, p.rec)
                    onUseBrain?.let { UseBrainKey(it) }
                }
            }
        }
        items(messages.asReversed(), key = { it.id }) { m ->
            when {
                m.search != null || m.searching != null -> SearchBlock(m.id, m.search, m.searching, Modifier.animateItem())
                m.system -> SystemLine(m.text, Modifier.animateItem().padding(horizontal = gutter))
                m.fromUser -> UserBubble(m, Modifier.animateItem().padding(horizontal = gutter))
                else -> Reply(m, Modifier.animateItem().padding(horizontal = gutter))
            }
        }
        item(key = "start") { Spacer(Modifier.height(6.dp)) }
    }
}

/** Older messages fade out under the sheet's header instead of being cut off by it. */
private fun Modifier.fadeTop(on: Boolean): Modifier = if (!on) {
    this
} else {
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 28.dp.toPx() / size.height to Color.Black), blendMode = BlendMode.DstIn)
    }
}

/** Emrys has the message and is working on it, before any reply text arrives: a glacier dot and a band of light crossing the word. */
@Composable
private fun Working(modifier: Modifier) {
    val p = LocalPalette.current
    val sweep = if (rememberReduceMotion()) {
        null
    } else {
        // The light crosses in the first 2.4 s of each 3.2 s, then rests past the word.
        rememberInfiniteTransition(label = "working").animateFloat(0f, 1.33f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "sweep")
    }
    Row(modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        SignalDot(p.cy, live = true)
        Spacer(Modifier.width(12.dp))
        Text("Thinking", style = Type.bodyLg, color = p.ink2, modifier = Modifier.shimmer(sweep, p.ink))
    }
}

/** A soft band of [light] crossing the content from left to right as [sweep] runs from 0 to 1, after ElevenLabs UI's ShimmeringText. */
private fun Modifier.shimmer(sweep: State<Float>?, light: Color): Modifier = if (sweep == null) {
    this
} else {
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val band = size.width * 0.4f
        val x = -band + (size.width + band * 2) * sweep.value
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, light, Color.Transparent), startX = x - band, endX = x + band),
            blendMode = BlendMode.SrcAtop,
        )
    }
}

/** A small line from the app, such as what was just added to the conversation: quiet and centred. */
@Composable
private fun SystemLine(text: String, modifier: Modifier) {
    val p = LocalPalette.current
    Text(
        text, style = Type.labelMd, color = p.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
    )
}

/** The user's own words: a halo-tinted bubble on the right, with the results that went with them. */
@Composable
private fun UserBubble(m: ChatMessage, modifier: Modifier) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 8.dp, bottomStart = 22.dp)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.padding(start = 40.dp).widthIn(max = 300.dp).clip(shape).background(p.accentSoft)
                .border(1.dp, if (m.failed) p.rec.copy(alpha = 0.6f) else p.accent.copy(alpha = 0.18f), shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (m.text.isNotBlank()) ReplyText(AnnotatedString(m.text), caret = false)
            Attachments(m)
        }
        if (m.failed) NotDelivered()
    }
}

/** Emrys's reply: formatted text set straight on the page, with no bubble, and a glacier caret while it streams in. */
@Composable
private fun Reply(m: ChatMessage, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth().padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val body = remember(m.text) { Rich.format(m.text.ifBlank { "…" }, p) }
        ReplyText(body, caret = m.streaming)
        Attachments(m)
        if (m.failed) NotDelivered()
    }
}

/** The results that went with a message, one quiet line each behind a link mark. */
@Composable
private fun Attachments(m: ChatMessage) {
    val p = LocalPalette.current
    m.attachments.forEach {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Link, contentDescription = null, tint = p.accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text(it.title, style = Type.labelLg, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NotDelivered() = StatusTag("Not delivered", Tone.Red)

/** The message text as one node; while it streams, a blinking caret is drawn after its last character. */
@Composable
private fun ReplyText(body: AnnotatedString, caret: Boolean) {
    val p = LocalPalette.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val blink = pulse(caret, low = 0.1f, periodMs = 1000)
    Text(
        body,
        color = p.ink,
        style = Type.bodyLg,
        onTextLayout = { layout = it },
        modifier = Modifier.drawWithContent {
            drawContent()
            val l = layout
            if (caret && l != null && l.lineCount > 0) {
                val line = l.lineCount - 1
                val top = l.getLineTop(line) + 4.dp.toPx()
                val bottom = l.getLineBottom(line) - 4.dp.toPx()
                drawRect(p.cy.copy(alpha = blink), Offset(l.getLineRight(line) + 3.dp.toPx(), top), Size(1.5.dp.toPx(), bottom - top))
            }
        },
    )
}

const val SUGGESTIONS_TAG = "suggestions"

/** A result waiting to go with the next message to Emrys's own brain, in the halo of the user's own words; × takes it off. */
@Composable
private fun AttachedChip(item: ContextItem, onRemove: () -> Unit) {
    val p = LocalPalette.current
    val pill = RoundedCornerShape(50)
    Row(
        Modifier.heightIn(min = 48.dp).widthIn(max = 260.dp).clip(pill).background(p.accentSoft).border(1.dp, p.accent.copy(alpha = 0.22f), pill)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Link, contentDescription = null, tint = p.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(item.title, style = Type.labelLg, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Box(
            Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = "Remove") { onRemove() }
                .semantics { contentDescription = "Remove ${item.title}" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Close, contentDescription = null, tint = p.ink2, modifier = Modifier.size(16.dp)) }
    }
}

/** A quick request: a soft outline pill in Geist, its touch area kept at 48dp. */
@Composable
private fun QuickPill(text: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pill = RoundedCornerShape(50)
    Box(
        Modifier.minimumInteractiveComponentSize()
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.height(38.dp).clip(pill).background(if (pressed) p.cardHi else Color.Transparent)
                .border(1.dp, if (pressed) p.ink.copy(alpha = 0.28f) else p.lineHi, pill).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Text(text, style = Type.labelLg, color = p.ink, maxLines = 1) }
    }
}

/**
 * The Task latch: a quiet toggle pill. Off, a faint well with a grey bolt; on, it takes the halo, since what follows
 * is the user's goal for a background task.
 */
@Composable
private fun TaskLatch(on: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pill = RoundedCornerShape(50)
    val fill by animateColorAsState(if (on) p.accentSoft else p.edgeSoft, tween(Motion.BASE), label = "task")
    val line by animateColorAsState(if (on) p.accent.copy(alpha = 0.5f) else p.line, tween(Motion.BASE), label = "task-line")
    Box(
        Modifier.minimumInteractiveComponentSize().semantics { selected = on }
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                onToggle()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(38.dp).clip(pill).background(fill).border(1.dp, line, pill).padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Bolt, contentDescription = null, tint = if (on) p.accent else p.ink2, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("Task", style = Type.labelLg, color = if (on) p.ink else p.ink2, maxLines = 1)
        }
    }
}

/**
 * What is being typed, helped along: Brave's suggestions as a quiet list on glass, each filling in the field, and
 * Brave's spelling correction under them.
 */
@Composable
private fun TypingList(query: String, suggestions: List<String>, corrected: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(p.hud).border(1.dp, p.line, shape).padding(vertical = 4.dp)) {
        if (suggestions.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().testTag(SUGGESTIONS_TAG)) {
                suggestions.forEachIndexed { i, s ->
                    if (i > 0) Rule(Modifier.padding(start = 52.dp, end = 16.dp), color = p.line)
                    TypingRow(Icons.Outlined.Search, { onPick(s) }, trailing = Icons.Outlined.NorthWest) {
                        // The letters already typed stay grey; the rest of the suggestion is in porcelain.
                        val typed = query.trim()
                        Text(
                            buildAnnotatedString {
                                if (typed.isNotEmpty() && s.startsWith(typed, ignoreCase = true)) {
                                    withStyle(SpanStyle(color = p.ink2)) { append(s.substring(0, typed.length)) }
                                    append(s.substring(typed.length))
                                } else {
                                    append(s)
                                }
                            },
                            style = Type.bodyLg, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (corrected != null) {
            if (suggestions.isNotEmpty()) Rule(Modifier.padding(horizontal = 16.dp), color = p.line)
            TypingRow(Icons.Outlined.Spellcheck, { onPick(corrected) }) {
                Text(
                    buildAnnotatedString {
                        append("Did you mean ")
                        withStyle(SpanStyle(color = p.accent, fontWeight = FontWeight.Medium)) { append(corrected) }
                        append("?")
                    },
                    style = Type.bodyLg, color = p.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TypingRow(icon: ImageVector, onClick: () -> Unit, trailing: ImageVector? = null, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (pressed) p.edgeSoft else Color.Transparent)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(start = 18.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = p.ink2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f)) { content() }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Icon(trailing, contentDescription = null, tint = p.ink2.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * The composer: a rounded field card on a hairline that takes a halo ring, border and caret while focused.
 * [fieldModifier] goes on the text field itself.
 */
@Composable
private fun ComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(26.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.then(fieldModifier),
        singleLine = false,
        maxLines = 5,
        textStyle = Type.bodyLg.copy(color = p.ink),
        cursorBrush = SolidColor(p.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        interactionSource = source,
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).ring(p.accent.copy(alpha = 0.16f), shape, focused, gap = 2.dp)
                    .clip(shape).background(p.field)
                    .border(1.dp, if (focused) p.accent.copy(alpha = 0.55f) else p.lineHi, shape)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, style = Type.bodyLg, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            }
        },
    )
}

/** The dock: the Task latch and quick requests, then the composer with its talk or send key. */
@Composable
private fun Dock(
    keyboard: Boolean,
    voice: Boolean,
    listening: Boolean,
    focusRequests: Int,
    chips: List<ContextItem>,
    onRemoveChip: (ContextItem) -> Unit,
    suggester: Suggester?,
    onSend: (String, Boolean) -> Unit,
    onListen: () -> Unit,
) {
    val p = LocalPalette.current
    val gutter = gutter()
    var text by rememberSaveable { mutableStateOf("") }
    var asTask by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val ime = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusRequests) {
        if (focusRequests > 0) {
            focus.requestFocus()
            ime?.show()
        }
    }
    // Results waiting to go to the brain can be sent on their own.
    val sendable = text.isNotBlank() || (chips.isNotEmpty() && !asTask)
    val send = {
        val t = text.trim()
        if (sendable) {
            onSend(t, asTask)
            text = ""
            asTask = false
        }
    }
    val help = rememberTypingHelp(text, !asTask, suggester)
    Column(Modifier.fillMaxWidth().background(p.bg).padding(top = 4.dp)) {
        if (chips.isNotEmpty()) {
            ChipRail(Modifier.padding(top = 10.dp), start = gutter) {
                items(chips, key = { it.url }) { chip -> AttachedChip(chip) { onRemoveChip(chip) } }
            }
        }
        val suggestions = help.suggestions.take(5).takeIf { text.trim().length >= 2 }.orEmpty()
        if (suggestions.isEmpty() && !keyboard) {
            ChipRail(Modifier.padding(top = 6.dp), start = gutter) {
                item(key = "task") { TaskLatch(asTask) { asTask = !asTask } }
                item(key = "rule") { Box(Modifier.padding(horizontal = 4.dp).size(width = 1.dp, height = 20.dp).background(p.lineHi)) }
                items(Quick.entries, key = { it.name }) { q -> QuickPill(q.label, { onSend(q.prompt, false) }) }
            }
        }
        if (suggestions.isNotEmpty() || help.corrected != null) {
            TypingList(text, suggestions, help.corrected, { picked -> text = picked }, Modifier.padding(start = gutter, end = gutter, top = 10.dp))
        }
        Row(Modifier.fillMaxWidth().padding(start = gutter, end = gutter - 4.dp, top = 10.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
            ComposerField(
                text, { text = it },
                placeholder = if (asTask) "Describe the goal for a background task…" else "Ask Emrys anything",
                onSend = { send() },
                modifier = Modifier.weight(1f),
                fieldModifier = Modifier.focusRequester(focus).testTag(COMPOSER_TAG),
            )
            Spacer(Modifier.width(10.dp))
            // A field-high slot: the talk key's halo spills past it without changing the row's height.
            Box(Modifier.size(width = 56.dp, height = 52.dp), contentAlignment = Alignment.Center) {
                val unbounded = Modifier.wrapContentSize(unbounded = true)
                when {
                    sendable -> SendKey(send, unbounded)
                    voice -> HaloTalkKey(if (listening) TalkState.Listening else TalkState.Idle, onListen, "Speak to Emrys", unbounded)
                    else -> Box(
                        unbounded.alpha(0.4f).clearAndSetSemantics {
                            contentDescription = "Send"
                            disabled()
                        },
                    ) { SendKey({}) }
                }
            }
        }
    }
}
