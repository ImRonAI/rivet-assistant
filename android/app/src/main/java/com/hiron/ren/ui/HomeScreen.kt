package com.hiron.ren.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiron.ren.core.Activity
import com.hiron.ren.core.ChatMessage
import com.hiron.ren.core.RenRuntime
import com.hiron.ren.core.RenState
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Date

/** One-tap requests on the dock, the widget and the launcher shortcuts: the things people ask Ren for most. */
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
 * Home: Ren on its stage with a greeting and the quick keys until there is a conversation, then the chat on its own
 * full screen with New chat over it. A voice conversation keeps Ren on its stage, with the same chat folded under it
 * as its transcript. The composer stays at the bottom of all three.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    runtime: RenRuntime,
    state: RenState,
    onListen: () -> Unit,
    onReviewApprovals: () -> Unit,
    focusRequests: Int = 0,
) {
    val p = LocalPalette.current
    val keyboard = WindowInsets.isImeVisible
    val heard = state.voice && state.messages.isNotEmpty()
    val talking = state.messages.isNotEmpty() && !state.voice
    val voice = runtime.settings.voiceModel
    Column(Modifier.fillMaxSize().background(p.bg)) {
        if (talking) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(start = gutter(), end = gutter() - 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Eyebrow("Conversation", dot = false)
                Spacer(Modifier.weight(1f))
                Keycap("New chat", runtime::newChat)
            }
        } else {
            val idle = !keyboard && !heard
            val fade by animateDpAsState(if (idle) 260.dp else 96.dp, tween(Motion.SLOW, easing = Motion.Emphasized), label = "fade")
            StageFrame(Modifier.weight(1f).fillMaxWidth(), framed = false, hint = if (idle) STAGE_HINT else null) {
                // While the greeting shows, Ren stands in the upper part of the stage with the words under its feet.
                RobotView(runtime, Modifier.fillMaxSize(), focus = if (idle) RobotFocus.Greet else RobotFocus.Stage)
                // The stage melts into what sits below it, and the greeting stays legible over the floor.
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(fade)
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to p.bg.copy(alpha = 0.78f), 1f to p.bg)),
                )
                StageGreeting(idle, state, voice)
            }
        }
        AnimatedVisibility(state.approvals.isNotEmpty()) {
            ApprovalBanner(state.approvals.size, onReviewApprovals)
        }
        if (talking) {
            ChatView(state, Modifier.weight(1f).fillMaxWidth())
        } else {
            if (heard) Transcript(state)
            state.lastError?.let { ErrorNote(it, Modifier.padding(horizontal = gutter(), vertical = 8.dp)) }
        }
        Dock(
            quick = !talking && !heard && !keyboard,
            voice = voice,
            listening = state.activity == Activity.Listening,
            focusRequests = focusRequests,
            onSend = { runtime.ask(it) },
            onListen = onListen,
        )
    }
}

const val TRANSCRIPT = "Transcript"

/** A voice conversation's transcript: a quiet header under the stage that opens the chat, as typing shows it. */
@Composable
private fun Transcript(state: RenState) {
    val p = LocalPalette.current
    var open by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(Motion.QUICK), label = "turn")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button) { open = !open }.padding(horizontal = gutter()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow(TRANSCRIPT, dot = false)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Outlined.KeyboardArrowUp, if (open) "Hide the transcript" else "Show the transcript", Modifier.rotate(turn), tint = p.ink2)
        }
        AnimatedVisibility(open) { ChatView(state, Modifier.fillMaxWidth().height(320.dp)) }
    }
}

/** Why something failed, as a coral dot and a plain sentence on glass. */
@Composable
private fun ErrorNote(error: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(p.raised).border(1.dp, p.line, shape).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DotLine(error, p.danger)
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
private fun BoxScope.StageGreeting(visible: Boolean, state: RenState, voice: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Motion.SLOW)) + slideInVertically(tween(Motion.SLOW, easing = Motion.Emphasized)) { it / 4 },
        exit = fadeOut(tween(Motion.QUICK)),
        // Clears the stage hint under it.
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = gutter(), end = gutter(), bottom = 60.dp),
    ) { Greeting(state, voice) }
}

/** The greeting's serif, a size up from the type scale's: the hero line of the page. */
private val GREETING = Type.greeting

/** The date and time as an eyebrow, the greeting set large in the serif, and one quiet line on what comes next. */
@Composable
private fun Greeting(state: RenState, voice: Boolean) {
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
        voice -> "Tap the mic or pick a key below." to null
        else -> "Type below or pick a key." to null
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Eyebrow("$date · $time", dot = false)
        Spacer(Modifier.height(10.dp))
        Text(
            buildAnnotatedString {
                append("$lead ")
                append(lift)
                appendVoltStop()
            },
            style = GREETING, color = p.ink, textAlign = TextAlign.Center, inlineContent = voltStopContent(),
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
            .clip(shape).background(p.raised).border(1.dp, p.amb.copy(alpha = 0.28f), shape)
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalDot(p.amb, live = true)
        Spacer(Modifier.width(14.dp))
        Text(
            if (count == 1) "Ren is waiting on 1 action" else "Ren is waiting on $count actions",
            style = Type.title, color = p.ink, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(14.dp))
        Keycap("Review", onClick, tone = KeyTone.Accent)
    }
}

const val COMPOSER_TAG = "composer"

/** A quick request: a soft outline pill in Inter, its touch area kept at 48dp. */
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

/** The dock: the quick requests when [quick], then the composer with its talk or send key. */
@Composable
private fun Dock(
    quick: Boolean,
    voice: Boolean,
    listening: Boolean,
    focusRequests: Int,
    onSend: (String) -> Unit,
    onListen: () -> Unit,
) {
    val p = LocalPalette.current
    val gutter = gutter()
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val ime = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusRequests) {
        if (focusRequests > 0) {
            focus.requestFocus()
            ime?.show()
        }
    }
    val sendable = text.isNotBlank()
    val send = {
        if (sendable) {
            onSend(text.trim())
            text = ""
        }
    }
    Column(Modifier.fillMaxWidth().background(p.bg).padding(top = 4.dp)) {
        if (quick) {
            ChipRail(Modifier.padding(top = 6.dp), start = gutter) {
                items(Quick.entries, key = { it.name }) { q -> QuickPill(q.label, { onSend(q.prompt) }) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = gutter, end = gutter - 4.dp, top = 10.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
            ComposerField(
                text, { text = it },
                placeholder = "Ask Ren anything",
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
                    voice -> TalkKey(if (listening) TalkState.Listening else TalkState.Idle, onListen, "Speak to Ren", unbounded)
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
