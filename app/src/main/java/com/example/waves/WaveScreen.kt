package com.example.waves

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Три вида «Моей волны» на главной (выбираются в настройках). Первый: тот, что был раньше. */
val WAVE_STYLES = listOf(
    "Коллаж" to "Крупный заголовок, плавающие обложки и волнистая лента",
    "Пульс" to "Обложка в центре, кольцо спектра вокруг неё пляшет в такт музыке",
    "Прибой" to "Размытая обложка на весь блок и живые волны внизу",
)

/** «Моя волна» на главной: вид берётся из настроек и меняется сразу. */
@Composable
fun WaveHero(np: NowPlaying, art: Uri?, extra: List<Uri?>, playing: Boolean, loading: Boolean,
             onToggle: () -> Unit, onOverlay: (() -> Unit)? = null) {
    val bob = rememberInfiniteTransition(label = "h").animateFloat(0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "bob")
    val bars = VizBus.bars.collectAsState()
    Crossfade(Style.waveStyle, animationSpec = tween(300), label = "wavestyle") { s ->
        when (s) {
            1 -> PulseHero(np, art, playing, loading, bars, bob, onToggle, onOverlay)
            2 -> SurfHero(np, art, playing, loading, bars, bob, onToggle, onOverlay)
            else -> CollageHero(np, art, extra, playing, loading, bars, bob, onToggle, onOverlay)
        }
    }
}

@Composable
private fun PlayOrb(size: Dp, playing: Boolean, loading: Boolean, bars: State<FloatArray>, modifier: Modifier, onToggle: () -> Unit) {
    Box(modifier.size(size)
        .graphicsLayer {
            val b = bars.value
            val e = if (playing && b.isNotEmpty()) b.average().toFloat() else 0f
            scaleX = 1f + e * 0.18f; scaleY = 1f + e * 0.18f
        }
        .clip(CircleShape).background(Color(Style.accent)).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator(Modifier.size(size * 0.37f), color = Color.Black, strokeWidth = 3.dp)
        else Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(size * 0.52f), tint = Color.Black)
    }
}

@Composable
private fun BoxScope.OverlayButton(onOverlay: (() -> Unit)?) {
    if (onOverlay != null) IconButton(onOverlay, Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 6.dp)) {
        Icon(Icons.Default.PictureInPicture, "Оверлей", tint = Color.White)
    }
}

private fun FloatArray.at(i: Int): Float = if (isEmpty()) 0f else this[i.coerceIn(0, size - 1)]

// ---------- 1. Коллаж (как было) ----------
@Composable
private fun CollageHero(np: NowPlaying, art: Uri?, extra: List<Uri?>, playing: Boolean, loading: Boolean,
                        bars: State<FloatArray>, bob: State<Float>, onToggle: () -> Unit, onOverlay: (() -> Unit)?) {
    val accent = Color(Style.accent)
    Box(Modifier.fillMaxWidth().height(450.dp)) {
        // волнистая лента на фоне
        Canvas(Modifier.fillMaxSize()) {
            val path = Path(); val steps = 50
            for (s in 0..steps) {
                val x = size.width * s / steps
                val y = size.height * 0.56f + sin(s * 0.22f + bob.value) * 34.dp.toPx() - (x / size.width) * 150.dp.toPx()
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Color.White.copy(0.09f), style = Stroke(34.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            drawPath(path, accent.copy(0.20f), style = Stroke(12.dp.toPx(), cap = StrokeCap.Round))
        }
        Text("Моя\nволна", Modifier.padding(start = 20.dp, top = 12.dp), color = Color.White, fontSize = 76.sp,
            lineHeight = 74.sp, fontWeight = FontWeight.Black)
        OverlayButton(onOverlay)
        Box(Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 190.dp).size(176.dp)
            .graphicsLayer { translationY = sin(bob.value) * 6.dp.toPx(); rotationZ = -3f }
            .clip(RoundedCornerShape(40.dp)).background(Color(0x22FFFFFF))) { ArtFill(art) }
        Box(Modifier.align(Alignment.TopEnd).padding(end = 20.dp, top = 76.dp).size(96.dp)
            .graphicsLayer { translationY = sin(bob.value + 1.5f) * 7.dp.toPx(); rotationZ = 6f }
            .clip(RoundedCornerShape(26.dp)).background(Color(0x22FFFFFF))) { ArtFill(extra.getOrNull(0)) }
        Box(Modifier.align(Alignment.TopEnd).padding(end = 84.dp, top = 188.dp).size(84.dp)
            .graphicsLayer { translationY = sin(bob.value + 3f) * 7.dp.toPx(); rotationZ = -5f }
            .clip(RoundedCornerShape(24.dp)).background(Color(0x22FFFFFF))) { ArtFill(extra.getOrNull(1)) }
        Row(Modifier.align(Alignment.BottomStart).padding(start = 22.dp, bottom = 54.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.GraphicEq, null, tint = Color.White.copy(0.8f), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (playing && np.title != null) np.title else "Бесконечная подборка", color = Color.White.copy(0.9f),
                style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.widthIn(max = 200.dp))
        }
        PlayOrb(104.dp, playing, loading, bars, Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 14.dp), onToggle)
    }
}

// ---------- 2. Пульс ----------
@Composable
private fun PulseHero(np: NowPlaying, art: Uri?, playing: Boolean, loading: Boolean,
                      bars: State<FloatArray>, bob: State<Float>, onToggle: () -> Unit, onOverlay: (() -> Unit)?) {
    val accent = Color(Style.accent)
    Box(Modifier.fillMaxWidth().height(490.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val b = bars.value
            val live = playing && b.isNotEmpty()
            val e = if (live) b.average().toFloat() else 0f
            val cx = size.width / 2; val cy = 185.dp.toPx(); val r0 = 80.dp.toPx()
            drawCircle(Brush.radialGradient(listOf(accent.copy(0.30f + e * 0.35f), Color.Transparent), Offset(cx, cy), r0 * 2.5f),
                r0 * 2.5f, Offset(cx, cy))
            val n = 60
            for (i in 0 until n) {
                // спектр зеркально по кольцу: низкие частоты сверху, высокие внизу
                val k = if (i < n / 2) i else n - 1 - i
                val v = if (live) b.at(k * b.size / (n / 2)) else 0.05f + 0.04f * (1f + sin(k * 0.6f + bob.value * 2f)) / 2f
                val a = i.toFloat() / n * 2f * PI.toFloat() - PI.toFloat() / 2f
                val len = 5.dp.toPx() + v * 44.dp.toPx()
                drawLine(lerp(accent, Color.White, v.coerceIn(0f, 1f) * 0.8f),
                    Offset(cx + cos(a) * r0, cy + sin(a) * r0), Offset(cx + cos(a) * (r0 + len), cy + sin(a) * (r0 + len)),
                    strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Text("Моя волна", Modifier.padding(start = 20.dp, top = 16.dp), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
        OverlayButton(onOverlay)
        Box(Modifier.align(Alignment.TopCenter).padding(top = 115.dp).size(140.dp)
            .graphicsLayer {
                val b = bars.value
                val e = if (playing && b.isNotEmpty()) b.average().toFloat() else 0f
                scaleX = 1f + e * 0.08f; scaleY = 1f + e * 0.08f
            }
            .clip(CircleShape).background(Color(0x22FFFFFF))) { ArtFill(art) }
        Column(Modifier.align(Alignment.TopCenter).padding(top = 330.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (playing && np.title != null) np.title else "Бесконечная подборка", color = Color.White, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (playing) (np.artist ?: "") else "Нажми, чтобы начать", color = Color(0xB3FFFFFF),
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PlayOrb(76.dp, playing, loading, bars, Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp), onToggle)
    }
}

// ---------- 3. Прибой ----------
@Composable
private fun SurfHero(np: NowPlaying, art: Uri?, playing: Boolean, loading: Boolean,
                     bars: State<FloatArray>, bob: State<Float>, onToggle: () -> Unit, onOverlay: (() -> Unit)?) {
    val accent = Color(Style.accent); val pink = Color(0xFFFF8AD8); val cyan = Color(0xFF5CC8FF)
    val shape = RoundedCornerShape((Style.corner * 1.4f).dp)
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(450.dp)
        .clip(shape).border(1.dp, Color.White.copy(0.10f), shape)) {
        Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(accent.copy(0.65f), Color(0xFF14141C)))))
        if (art != null) SoftArt(art, Modifier.matchParentSize(), 0.9f)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(0.35f), Color.Black.copy(0.10f), Color.Black.copy(0.45f)))))
        Canvas(Modifier.matchParentSize()) {
            val b = bars.value
            val live = playing && b.isNotEmpty()
            val w = size.width; val h = size.height
            val layers = listOf(Triple(accent, 0.55f, 0.64f), Triple(pink, 0.42f, 0.72f), Triple(cyan, 0.38f, 0.80f))
            layers.forEachIndexed { li, (col, alpha, base) ->
                val path = Path(); val steps = 64
                for (s in 0..steps) {
                    val x = w * s / steps
                    val v = if (live) b.at(s * b.size / steps) else 0.05f
                    val amp = 7.dp.toPx() + v * 34.dp.toPx()
                    val y = h * base + sin(s * 0.20f * (1f + li * 0.35f) + bob.value * (li + 1)) * amp
                    if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.lineTo(w, h); path.lineTo(0f, h); path.close()
                drawPath(path, col.copy(alpha))
            }
        }
        Text("Моя\nволна", Modifier.padding(start = 20.dp, top = 14.dp), color = Color.White, fontSize = 52.sp,
            lineHeight = 52.sp, fontWeight = FontWeight.Black)
        OverlayButton(onOverlay)
        Box(Modifier.align(Alignment.TopCenter).padding(top = 150.dp).size(100.dp)
            .graphicsLayer { translationY = sin(bob.value) * 7.dp.toPx(); rotationZ = sin(bob.value) * 4f }
            .clip(RoundedCornerShape(30.dp)).background(Color(0x22FFFFFF))) { ArtFill(art) }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 20.dp).widthIn(max = 220.dp)) {
            Text(if (playing && np.title != null) np.title else "Бесконечная подборка", color = Color.White,
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (playing) Text(np.artist ?: "", color = Color.White.copy(0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        PlayOrb(84.dp, playing, loading, bars, Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp), onToggle)
    }
}

@Composable
fun WaveCard(t: Track, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        Cover(t.art, 140)
        Text(t.title, Modifier.padding(top = 8.dp), color = Color.White, fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(t.artist, color = Color(0xB3FFFFFF), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TrackRow(t: Track, sel: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape((Style.corner * 0.7f).dp))
        .background(if (sel) Color(Style.accent).copy(0.22f) else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(t.art, 50); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (sel) Icon(Icons.Default.GraphicEq, null, tint = Color(Style.accent))
    }
}
