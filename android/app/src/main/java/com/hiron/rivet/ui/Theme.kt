package com.hiron.rivet.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hiron.rivet.R

/** Rivet's palette, taken from the robot page's own tokens so the app and the robot read as one surface. */
data class Palette(
    val bg: Color,
    val bgHi: Color,
    val card: Color,
    val ink: Color,
    val ink2: Color,
    val line: Color,
    val accent: Color,
    val cy: Color,
    val ok: Color,
    val amb: Color,
    val rec: Color,
    val dark: Boolean,
)

val LightPalette = Palette(
    bg = Color(0xFFE2E5E8), bgHi = Color(0xFFF3F4F5), card = Color(0xFFFBFBFC), ink = Color(0xFF13171C),
    ink2 = Color(0xFF56606A), line = Color(0x1A13171C), accent = Color(0xFFFF5A1F), cy = Color(0xFF0E9FB5),
    ok = Color(0xFF1E9E6A), amb = Color(0xFFC98A16), rec = Color(0xFFE5484D), dark = false,
)

val DarkPalette = Palette(
    bg = Color(0xFF0B0E12), bgHi = Color(0xFF161B22), card = Color(0xFF12171D), ink = Color(0xFFE8ECF0),
    ink2 = Color(0xFF8A949E), line = Color(0x1AE8ECF0), accent = Color(0xFFFF5A1F), cy = Color(0xFF5FE4F5),
    ok = Color(0xFF42D392), amb = Color(0xFFF2B544), rec = Color(0xFFE5484D), dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

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

/** Small uppercase mono labels, like the robot's HUD. */
val Label = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.1.sp)

@Composable
fun RivetTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    val scheme = if (p.dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = Color.White, secondary = p.cy, background = p.bg, onBackground = p.ink,
            surface = p.bgHi, onSurface = p.ink, surfaceVariant = p.card, onSurfaceVariant = p.ink2, error = p.rec,
            outline = p.line, surfaceContainer = p.bgHi, surfaceContainerHigh = p.card,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = Color.White, secondary = p.cy, background = p.bg, onBackground = p.ink,
            surface = p.bgHi, onSurface = p.ink, surfaceVariant = p.card, onSurfaceVariant = p.ink2, error = p.rec,
            outline = p.line, surfaceContainer = p.bgHi, surfaceContainerHigh = p.card,
        )
    }
    val base = Typography()
    val type = Typography(
        displaySmall = base.displaySmall.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontFamily = Display, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
        titleMedium = base.titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = Sans, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontFamily = Sans),
        bodyMedium = base.bodyMedium.copy(fontFamily = Sans),
        bodySmall = base.bodySmall.copy(fontFamily = Sans),
        labelLarge = base.labelLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontFamily = Mono),
        labelSmall = base.labelSmall.copy(fontFamily = Mono),
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

/** Selected chips in Rivet orange instead of Material's default lavender. */
@Composable
fun rivetChip(): SelectableChipColors {
    val p = LocalPalette.current
    return FilterChipDefaults.filterChipColors(
        containerColor = p.card,
        labelColor = p.ink,
        selectedContainerColor = p.accent,
        selectedLabelColor = Color.White,
    )
}
