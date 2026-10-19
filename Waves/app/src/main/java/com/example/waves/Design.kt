package com.example.waves

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
    val stroke = 1.dp            // обычная
    val strokeSel = 1.5.dp       // выбранный элемент / фокус

    // прозрачность
    const val LINE = 0.12f       // контур панелей, чипов, плашек
    const val FILL = 0.08f       // лёгкая заливка плашек
    const val TEXT2 = 0.70f      // второстепенный текст и значки
    const val TEXT3 = 0.50f      // подсказки
    const val SEL = 0.20f        // подсветка выбранной строки
    val lineColor = Color.White.copy(LINE)
    val fillColor = Color.White.copy(FILL)

    // отступы
    val padS = 8.dp
    val padM = 12.dp
    val padL = 16.dp             // боковое поле страницы

    // иконки
    val iconS = 18.dp            // внутри чипов
    val iconM = 22.dp            // обычные кнопки и навигация
    val iconL = 32.dp            // крупные кнопки плеера

    // компактное управление
    val btn = 40.dp              // иконочная кнопка
    val navH = 44.dp             // высота нижней навигации
    val miniCover = 40           // обложка в мини-плеере (dp)
    val coverRow = 48            // обложка в строках списков (dp)
    val bottomReserve = 148.dp   // запас под мини-плеером и навигацией в списках
}
