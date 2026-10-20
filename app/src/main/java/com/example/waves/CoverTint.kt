package com.example.waves

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Акцент из обложки: из текущей обложки выбирается самый выразительный цвет, и WP.accent плавно переходит к нему.
 * Серые и почти чёрно-белые обложки цвета не дают: тогда остаётся акцент, выбранный в настройках.
 */
object CoverTint {
    /** Текущий (анимированный) цвет обложки; null, если у обложки нет выраженного цвета. */
    var shown by mutableStateOf<Color?>(null)

    suspend fun extract(ctx: Context, art: Uri?): Color? {
        if (art == null) return null
        return withContext(Dispatchers.Default) {
            try {
                val res = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(art).allowHardware(false).size(64).build())
                val bmp = ((res as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@withContext null
                dominant(bmp)
            } catch (_: Exception) { null }
        }
    }

    private fun dominant(b: Bitmap): Color? {
        val w = b.width; val h = b.height
        if (w == 0 || h == 0) return null
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val weight = FloatArray(12); val hue = FloatArray(12); val sat = FloatArray(12)
        val hsv = FloatArray(3)
        for (p in px) {
            android.graphics.Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.28f || hsv[2] < 0.25f) continue          // серое и тёмное цвета не несёт
            val k = (hsv[0] / 30f).toInt().coerceIn(0, 11)
            val wgt = hsv[1] * hsv[2]
            weight[k] += wgt; hue[k] += hsv[0] * wgt; sat[k] += hsv[1] * wgt
        }
        var best = 0
        for (k in 1 until 12) if (weight[k] > weight[best]) best = k
        if (weight[best] < px.size * 0.02f) return null
        return Color.hsv(hue[best] / weight[best], (sat[best] / weight[best]).coerceIn(0.45f, 0.8f), 0.95f)
    }
}

/** Ставится один раз в корне экрана плеера: следит за обложкой и плавно меняет CoverTint.shown. */
@Composable
fun CoverTintHost(art: Uri?) {
    val ctx = LocalContext.current.applicationContext
    var target by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(art) { target = CoverTint.extract(ctx, art) }
    val shown by animateColorAsState(target ?: Color(Style.accent), tween(700), label = "coverAccent")
    SideEffect { CoverTint.shown = if (target != null) shown else null }
}
