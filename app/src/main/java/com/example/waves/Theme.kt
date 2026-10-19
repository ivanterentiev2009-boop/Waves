package com.example.waves

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** Типографика Waves: одна шкала размеров для всех экранов. */
private val WavesTypography = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun WavesTheme(content: @Composable () -> Unit) {
    val light = WP.light
    val scheme = if (light) lightColorScheme(
        primary = WP.accentInk, onPrimary = Color.White,
        background = WP.bg, onBackground = WP.text,
        surface = WP.surface, onSurface = WP.text, surfaceVariant = WP.fill, onSurfaceVariant = WP.text2,
        surfaceContainer = WP.surface, surfaceContainerHigh = WP.surfaceHi, surfaceContainerHighest = WP.surfaceHi,
        outline = WP.line, outlineVariant = WP.line, error = WP.error)
    else darkColorScheme(
        primary = WP.accent, onPrimary = WP.onAccent,
        background = WP.bg, onBackground = WP.text,
        surface = WP.surface, onSurface = WP.text, surfaceVariant = WP.fill, onSurfaceVariant = WP.text2,
        surfaceContainer = WP.surface, surfaceContainerHigh = WP.surfaceHi, surfaceContainerHighest = WP.surfaceHi,
        outline = WP.line, outlineVariant = WP.line, error = WP.error)
    // значки системных панелей: тёмные на светлой теме, светлые на остальных (окно оверлея не Activity: пропускаем)
    val view = LocalView.current
    SideEffect {
        val act = view.context as? android.app.Activity ?: return@SideEffect
        val ctl = WindowCompat.getInsetsController(act.window, view)
        ctl.isAppearanceLightStatusBars = light
        ctl.isAppearanceLightNavigationBars = light
    }
    MaterialTheme(colorScheme = scheme, typography = WavesTypography, content = content)
}
