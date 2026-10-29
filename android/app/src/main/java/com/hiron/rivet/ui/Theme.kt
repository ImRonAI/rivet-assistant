package com.hiron.rivet.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hiron.rivet.R

/**
 * Rivet design system tokens (dark first), shared with the robot page so the app and the robot read as one
 * test bay: recessed surfaces, keycaps, HUD cards and status LEDs.
 */
data class Palette(
    /** Page background (surface-bg). */
    val bg: Color,
    /** Raised panels: sheets, the dock (surface-raised). */
    val raised: Color,
    /** HUD card fill. */
    val hud: Color,
    /** Keycap fill. */
    val key: Color,
    /** Recessed fills: inputs, Rivet's bubbles, field wells (surface-tile). */
    val tile: Color,
    /** Active segment and other solid cards on a tile (surface-container-high). */
    val high: Color,
    val ink: Color,
    val ink2: Color,
    /** Hairlines between tiers. */
    val rule: Color,
    /** HUD card outline. */
    val hudLine: Color,
    val keyLine: Color,
    /** The 1px lip under a keycap. */
    val keyShadow: Color,
    /** Safety orange: the one action that matters on a screen, focus, the user's own words. */
    val accent: Color,
    /** Ink on safety orange. */
    val accentInk: Color,
    /** Cyan: streaming, Rivet working. */
    val cy: Color,
    /** Green: connected, granted, done. */
    val ok: Color,
    /** Amber: waiting on your OK. */
    val amb: Color,
    /** Crimson: live, failed, destructive. */
    val rec: Color,
    /** Feature and toggle cards; the live call plate. */
    val card: Color,
    /** A card while it is pressed. */
    val cardHi: Color,
    /** Fields and chips. */
    val field: Color,
    /** 8% ink hairline: card borders, header rules. */
    val line: Color,
    /** 16% ink: a pressed card's border. */
    val lineHi: Color,
    /** 10% white: stage frame, chips, secondary keys. */
    val edge: Color,
    /** 5% white: icon wells, nested-row dividers, info strips. */
    val edgeSoft: Color,
    /** 20% white: step dots, an unselected radio ring. */
    val dot: Color,
    /** Accent highlight: pressed fill, gradient top. */
    val accentHi: Color,
    /** Accent shadow: the end of the listening talk key's gradient. */
    val accentDeep: Color,
    /** 12% accent: the user's bubble. */
    val accentSoft: Color,
    /** A toggle's track when off. */
    val track: Color,
    /** Translucent graphite under the tab bar. */
    val glass: Color,
    val white: Color,
    val dark: Boolean,
)

val DarkPalette = Palette(
    bg = Color(0xFF0B0E12), raised = Color(0xFF161B22), hud = Color(0xFF12171D), key = Color(0xFF151A20),
    tile = Color(0xFF1D232B), high = Color(0xFF252A31), ink = Color(0xFFE8ECF0), ink2 = Color(0xFF8A949E),
    rule = Color(0x1AE8ECF0), hudLine = Color(0x24E8ECF0), keyLine = Color(0x21E8ECF0), keyShadow = Color(0x99000000),
    accent = Color(0xFFFF5A1F), accentInk = Color(0xFF13171C), cy = Color(0xFF5FE4F5), ok = Color(0xFF42D392),
    amb = Color(0xFFF2B544), rec = Color(0xFFE5484D),
    card = Color(0xFF11161B), cardHi = Color(0xFF171D23), field = Color(0xFF12171D), line = Color(0x14E8ECF0),
    lineHi = Color(0x29E8ECF0), edge = Color(0x1AFFFFFF), edgeSoft = Color(0x0DFFFFFF), dot = Color(0x33FFFFFF),
    accentHi = Color(0xFFFF723B), accentDeep = Color(0xFFE0450C), accentSoft = Color(0x1FFF5A1F), track = Color(0xFF1B2026),
    glass = Color(0xCC0B0E12), white = Color(0xFFFFFFFF),
    dark = true,
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

val Display = FontFamily(
    Font(R.font.big_shoulders_display_bold, FontWeight.Bold),
    Font(R.font.big_shoulders_display_extrabold, FontWeight.ExtraBold),
)
val Sans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)
val Mono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

/** The type scale. Display is for the brand mark and counters only; UI text is Plex Sans; telemetry is Plex Mono. */
object Type {
    val brand = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 26.sp, letterSpacing = 0.06.em)
    val greeting = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 46.sp, lineHeight = 42.sp, letterSpacing = 0.03.em)
    val stat = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp, lineHeight = 34.sp, letterSpacing = 0.04.em)
    val headline = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
    val title = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp)
    val bodyLg = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val key = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp)
    val labelLg = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp)
    val labelMd = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.10.em)
    val labelSm = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.14.em)
    val mono = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp)

    /** Onboarding step headlines. */
    val step = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 32.sp, letterSpacing = 0.05.em)
    /** Sans call-to-action (onboarding, sheets). */
    val button = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.025.em)
    /** Mono secondary keys and text links. */
    val monoKey = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.10.em)
    /** Eyebrows and section labels. */
    val eyebrow = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.20.em)
    /** Status tags. */
    val tag = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.08.em)
    val cardTitle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp)
    val cardBody = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp)
    val chip = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp)
    /** Quoted text inside a card: the message Rivet will send. */
    val quote = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 26.sp)
}

/** Durations (ms) and easings shared by every animated component. */
object Motion {
    const val QUICK = 120
    const val BASE = 220
    const val SLOW = 420
    /** Per-item entrance delay. */
    const val STAGGER = 40
    /** Opacity 1 to .5 and back. */
    const val PULSE = 2000
    /** The listening ring: scale 1 to 2 while it fades out, repeating. */
    const val PING = 2500
    /** A halo ring's alpha .55 to 1 and back. */
    const val BREATHE = 1400
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Standard = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)
}

/** True when the system has animations off; infinite animations then hold their resting frame. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        AndroidSettings.Global.getFloat(context.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Small uppercase mono labels, like the robot's HUD. */
val Label = Type.labelMd

@Composable
fun RivetTheme(content: @Composable () -> Unit) {
    val p = DarkPalette
    val scheme = darkColorScheme(
        primary = p.accent, onPrimary = p.accentInk, primaryContainer = p.accent, onPrimaryContainer = p.accentInk,
        secondary = p.cy, onSecondary = p.accentInk, tertiary = p.ok, onTertiary = p.accentInk,
        background = p.bg, onBackground = p.ink, surface = p.raised, onSurface = p.ink, surfaceVariant = p.tile,
        onSurfaceVariant = p.ink2, error = p.rec, onError = p.ink, outline = p.keyLine, outlineVariant = p.rule,
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.hud, surfaceContainer = p.raised,
        surfaceContainerHigh = p.tile, surfaceContainerHighest = p.high, scrim = Color(0xB3000000),
    )
    val type = Typography(
        headlineSmall = Type.headline,
        titleLarge = Type.headline,
        titleMedium = Type.title,
        titleSmall = Type.labelLg,
        bodyLarge = Type.bodyLg,
        bodyMedium = Type.body,
        bodySmall = Type.body.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = Type.key,
        labelMedium = Type.labelMd,
        labelSmall = Type.labelSm,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
