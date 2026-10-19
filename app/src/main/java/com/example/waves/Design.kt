package com.example.waves

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Палитра Waves. Все цвета текста, фонов и линий берутся отсюда и зависят от выбранной темы:
 * 0 стекло, 1 минимализм, 2 AMOLED, 3 матовое стекло, 4 светлая.
 * Поверхности идут уровнями: bg (фон) → surface (карточки) → surfaceHi (поднятые панели, мини-плеер, меню).
 */
object WP {
    val light: Boolean get() = Style.theme == 4

    val bg: Color get() = when (Style.theme) { 2 -> Color(0xFF000000); 1 -> Color(0xFF0E0E12); 4 -> Color(0xFFF4F4F8); else -> Color(0xFF0B0B10) }
    val surface: Color get() = when (Style.theme) { 2 -> Color(0xFF101014); 4 -> Color(0xFFFFFFFF); 0 -> Color(0xFF14141B); else -> Color(0xFF17171E) }
    val surfaceHi: Color get() = when (Style.theme) { 2 -> Color(0xFF1A1A20); 4 -> Color(0xFFFFFFFF); else -> Color(0xFF20202A) }

    val text: Color get() = if (light) Color(0xFF14141A) else Color(0xFFFFFFFF)
    val text2: Color get() = if (light) Color(0xFF4A4A57) else Color(0xB8FFFFFF)
    val text3: Color get() = if (light) Color(0xFF6C6C7A) else Color(0x85FFFFFF)
    val line: Color get() = if (light) Color(0x24000000) else Color(0x1FFFFFFF)
    val fill: Color get() = if (light) Color(0x0F000000) else Color(0x14FFFFFF)
    val error: Color get() = if (light) Color(0xFFB3261F) else Color(0xFFFFB4AB)
    val ok: Color get() = if (light) Color(0xFF1B7F5C) else Color(0xFF6EE7B7)

    /** Акцент как заливка (кнопки, активные плашки): текст на нём всегда тёмный. */
    val accent: Color get() = Color(Style.accent)
    val onAccent: Color get() = Color(0xFF0B0B10)
    /** Акцент как цвет текста и значков на поверхности: на светлом фоне затемняется, чтобы читался. */
    val accentInk: Color get() = if (light) lerp(Color(Style.accent), Color.Black, 0.45f) else Color(Style.accent)
    /** Мягкая подсветка активной строки. */
    val selFill: Color get() = Color(Style.accent).copy(if (light) 0.18f else 0.14f)

    /** Цвета поверх обложек и тёмных градиентов: всегда светлые, не зависят от темы. */
    val onArt: Color = Color.White
    val onArt2: Color = Color(0xD9FFFFFF)

    /** Подложка нижней панели (навигация) и мини-плеера. */
    val bar: Color get() = when (Style.theme) { 4 -> Color(0xFFFFFFFF); 2 -> Color(0xFF0A0A0D); 0 -> Color(0xF2101016); else -> Color(0xFF14141A) }
}

/** Высота закреплённых снизу панелей (мини-плеер + навигация), измеряется на экране. Списки отступают на неё. */
val LocalBottomInset = compositionLocalOf<Dp> { 140.dp }

@Composable
fun bottomInset(): Dp = LocalBottomInset.current + 8.dp

/**
 * Единая дизайн-система: все экраны берут радиусы, обводки, прозрачность, отступы и размеры иконок отсюда.
 * Радиусы строятся от настройки «Скругление» (Style.corner): S = 0.5×, M = 1×, L = 1.4×.
 *  S — обложки, мелкие плитки; M — карточки, строки списков, панели; L — крупные блоки (герой, плеер); Pill — капсулы.
 */
object DS {
    // радиусы
    val rS get() = (Style.corner * 0.5f).dp
    val rM get() = Style.corner.dp
    val rL get() = (Style.corner * 1.4f).dp
    val shapeS get() = RoundedCornerShape(rS)
    val shapeM get() = RoundedCornerShape(rM)
    val shapeL get() = RoundedCornerShape(rL)
    val pill = CircleShape

    // обводки
    val stroke = 1.dp
    val strokeSel = 1.5.dp

    // прозрачность (оставлена для совместимости; цвета лучше брать из WP)
    const val LINE = 0.12f
    const val FILL = 0.08f
    const val TEXT2 = 0.70f
    const val TEXT3 = 0.50f
    const val SEL = 0.14f
    val lineColor get() = WP.line
    val fillColor get() = WP.fill

    // отступы
    val padXS = 4.dp
    val padS = 8.dp
    val padM = 12.dp
    val padL = 16.dp             // боковое поле страницы
    val padXL = 24.dp

    // иконки
    val iconS = 18.dp            // внутри чипов
    val iconM = 22.dp            // обычные кнопки и навигация
    val iconL = 32.dp            // крупные кнопки плеера

    // компактное управление
    val btn = 40.dp              // иконочная кнопка
    val navH = 60.dp             // высота нижней навигации (без системной панели)
    val miniH = 60.dp            // высота мини-плеера
    val miniCover = 44           // обложка в мини-плеере (dp)
    val coverRow = 52            // обложка в строках списков (dp)
    val bottomReserve = 148.dp   // запас по умолчанию, пока панели не измерены
}
