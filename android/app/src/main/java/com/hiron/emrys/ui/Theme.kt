package com.hiron.emrys.ui

import android.provider.Settings as AndroidSettings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hiron.emrys.R

/**
 * Emrys design system tokens ("Noir"), dark first and shared with the robot page so the app and the stage read as
 * one editorial spread: near-black paper, porcelain ink, hairline rules, soft glass surfaces and one luminous halo
 * accent. The full system is in docs/design/EMRYS_DESIGN.md.
 */
data class Palette(
    /** Page background (surface-bg). */
    val bg: Color,
    /** Raised panels: sheets, the dock (surface-raised). */
    val raised: Color,
    /** Glass card fill on the stage and over content. */
    val hud: Color,
    /** Soft key fill: secondary pills and icon keys. */
    val key: Color,
    /** Recessed fills: inputs, Emrys's bubbles, field wells (surface-tile). */
    val tile: Color,
    /** Active segment and other solid cards on a tile (surface-container-high). */
    val high: Color,
    /** Porcelain: primary text and the primary call to action's fill. */
    val ink: Color,
    /** Secondary text. */
    val ink2: Color,
    /** Hairlines between tiers. */
    val rule: Color,
    /** Glass card outline. */
    val hudLine: Color,
    val keyLine: Color,
    /** Shadow under a raised key; transparent, since Noir keys sit flat on hairlines. */
    val keyShadow: Color,
    /** Halo: focus, links, the user's own words and the live voice. Never more than one halo element per region. */
    val accent: Color,
    /** Ink on halo and on porcelain. */
    val accentInk: Color,
    /** Glacier: streaming, Emrys working. */
    val cy: Color,
    /** Sage: connected, granted, done. */
    val ok: Color,
    /** Honey: waiting on your OK. */
    val amb: Color,
    /** Coral: live, failed, destructive. */
    val rec: Color,
    /** Feature, row, radio and toggle cards; the live call plate; stat tiles. */
    val card: Color,
    /** A card while it is pressed. */
    val cardHi: Color,
    /** The composer card and chips. */
    val field: Color,
    /** 7% porcelain hairline: card borders, header rules. */
    val line: Color,
    /** 14% porcelain: a pressed or focused card's border. */
    val lineHi: Color,
    /** 9% white: stage frame, chips, secondary and play keys. */
    val edge: Color,
    /** 4% white: icon wells, nested-row dividers, info strips. */
    val edgeSoft: Color,
    /** 18% white: step dots, an unselected radio ring. */
    val dot: Color,
    /** Halo highlight: pressed fill, gradient top. */
    val accentHi: Color,
    /** Halo shadow: the end of the live voice core's gradient. */
    val accentDeep: Color,
    /** 12% halo: the user's bubble, a selected icon well. */
    val accentSoft: Color,
    /** A toggle's track when off. */
    val track: Color,
    /** Translucent noir under sticky headers and the tab bar. */
    val glass: Color,
    val white: Color,
    val dark: Boolean,
)

val DarkPalette = Palette(
    bg = Color(0xFF0A0A0C), raised = Color(0xFF141417), hud = Color(0xD9121215), key = Color(0xFF18181C),
    tile = Color(0xFF1A1A1E), high = Color(0xFF242429), ink = Color(0xFFF3F2EE), ink2 = Color(0xFF9E9DA4),
    rule = Color(0x14F3F2EE), hudLine = Color(0x1FF3F2EE), keyLine = Color(0x1AF3F2EE), keyShadow = Color(0x00000000),
    accent = Color(0xFFB7C2FF), accentInk = Color(0xFF0A0A0C), cy = Color(0xFF8FDBE8), ok = Color(0xFF97D4A6),
    amb = Color(0xFFE8C07C), rec = Color(0xFFF07B6C),
    card = Color(0xFF111114), cardHi = Color(0xFF17171B), field = Color(0xFF131316), line = Color(0x12F3F2EE),
    lineHi = Color(0x24F3F2EE), edge = Color(0x17FFFFFF), edgeSoft = Color(0x0AFFFFFF), dot = Color(0x2EFFFFFF),
    accentHi = Color(0xFFCDD5FF), accentDeep = Color(0xFF7F8EF0), accentSoft = Color(0x1FB7C2FF), track = Color(0xFF1C1C21),
    glass = Color(0xD90A0A0C), white = Color(0xFFFFFFFF),
    dark = true,
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Instrument Serif: the editorial voice. Greetings, headlines, the wordmark, quotes and counters. */
val Display = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)
/** Geist: every piece of interface text. */
val Sans = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)
/** Geist Mono: numbers that tick (times, counts, codes) and technical details only. */
val Mono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
)

/**
 * The type scale. Display (serif) carries the editorial moments, always in sentence case; interface text is Geist;
 * mono is reserved for figures and technical details. Nothing is set in all caps except eyebrows.
 */
object Type {
    val brand = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 32.sp, letterSpacing = (-0.01).em)
    val greeting = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = (-0.02).em)
    val stat = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 38.sp, lineHeight = 40.sp, letterSpacing = (-0.01).em, fontFeatureSettings = "tnum")
    val headline = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em)
    val title = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.005).em)
    val bodyLg = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp)
    val body = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp)
    val key = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp)
    val labelLg = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp)
    val labelMd = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.02.em)
    val labelSm = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.03.em)
    val tele = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 14.sp, fontFeatureSettings = "tnum")
    val mono = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp)

    /** Onboarding step headlines. */
    val step = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.015).em)
    /** Call to action (onboarding, sheets). */
    val button = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
    /** Secondary keys and text links. */
    val monoKey = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.01.em)
    /** Eyebrows and section labels: the one uppercase style, small and widely tracked. */
    val eyebrow = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.12.em)
    /** Status tags. */
    val tag = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.03.em)
    val cardTitle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp)
    val cardBody = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 18.sp)
    val chip = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp)
    /** Quoted text inside a card: the line to read, the message Emrys will send. Serif, like a pull quote. */
    val quote = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 27.sp, letterSpacing = (-0.005).em)
}

/** Durations (ms) and easings shared by every animated component. Noir moves slower and settles softly. */
object Motion {
    const val QUICK = 140
    const val BASE = 260
    const val SLOW = 480
    /** Per-item entrance delay. */
    const val STAGGER = 50
    /** Opacity 1 to .5 and back. */
    const val PULSE = 2400
    /** The listening ring: scale 1 to 2 while it fades out, repeating. */
    const val PING = 2800
    /** A halo ring's alpha .55 to 1 and back. */
    const val BREATHE = 1800
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
}

/** True when the system has animations off; infinite animations then hold their resting frame. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        AndroidSettings.Global.getFloat(context.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

@Composable
fun EmrysTheme(content: @Composable () -> Unit) {
    val p = DarkPalette
    val scheme = darkColorScheme(
        primary = p.ink, onPrimary = p.accentInk, primaryContainer = p.ink, onPrimaryContainer = p.accentInk,
        secondary = p.accent, onSecondary = p.accentInk, tertiary = p.ok, onTertiary = p.accentInk,
        background = p.bg, onBackground = p.ink, surface = p.raised, onSurface = p.ink, surfaceVariant = p.tile,
        onSurfaceVariant = p.ink2, error = p.rec, onError = p.accentInk, outline = p.keyLine, outlineVariant = p.rule,
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.card, surfaceContainer = p.raised,
        surfaceContainerHigh = p.tile, surfaceContainerHighest = p.high, scrim = Color(0xB8000000),
    )
    val type = Typography(
        headlineSmall = Type.headline,
        titleLarge = Type.headline,
        titleMedium = Type.title,
        titleSmall = Type.labelLg,
        bodyLarge = Type.bodyLg,
        bodyMedium = Type.body,
        bodySmall = Type.body.copy(fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = Type.key,
        labelMedium = Type.labelMd,
        labelSmall = Type.labelSm,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
