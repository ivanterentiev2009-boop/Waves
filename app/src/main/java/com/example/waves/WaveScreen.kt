package com.example.waves

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Названия и описания видов «Моей волны» (выбираются в настройках). */
val WAVE_STYLES = listOf(
    "Океан" to "Волны по спектру, обложка качается как буй",
    "Круговой спектр" to "Лучи вокруг обложки пляшут в такт музыке",
    "Круги на воде" to "Каждый удар басов пускает круги по воде",
    "Винил" to "Пластинка крутится, игла опускается во время игры",
    "Минимализм" to "Крупное название и одна тонкая волна",
    "Три палочки" to "Эквалайзер: басы, середина и верха",
)

private fun FloatArray.band(from: Float, to: Float): Float {
    if (isEmpty()) return 0f
    val a = (size * from).toInt().coerceIn(0, size - 1)
    val b = (size * to).toInt().coerceIn(a + 1, size)
    var s = 0f
    for (i in a until b) s += this[i]
    return s / (b - a)
}

/** Обложка текущего трека или фирменный значок с волной. */
@Composable
private fun WaveArtFill(np: NowPlaying, active: Boolean) {
    val accent = Color(Style.accent); val pink = Color(0xFFFF8AD8)
    if (active && np.art != null) AsyncImage(np.art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.linearGradient(listOf(accent, pink)))
        val p = Path(); p.moveTo(0f, size.height / 2)
        for (i in 0..40) p.lineTo(size.width * i / 40, size.height / 2 + sin(i * 0.5f) * size.height * 0.13f)
        drawPath(p, Color.White, style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** «Моя волна»: вид выбирается в настройках. Все виды реагируют на спектр играющей музыки. */
@Composable
fun WaveHero(np: NowPlaying, active: Boolean, loading: Boolean, onToggle: () -> Unit, onStop: () -> Unit) {
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { clock.longValue = it / 1_000_000 } }
    val bars = VizBus.bars.collectAsState()
    val playing = np.playing && active
    val shape = RoundedCornerShape((Style.corner * 1.2f).dp)
    // герой-карточка со скруглением: не торчит прямоугольником из фона, а выглядит как единый блок
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(440.dp)
        .clip(shape).border(1.dp, Color.White.copy(0.08f), shape)) {
        if (Style.waveStyle != 2 && Style.waveStyle != 4) Box(Modifier.matchParentSize().background(Color.Black.copy(0.28f)))
        when (Style.waveStyle) {
            1 -> RaysLayer(np, active, playing, clock, bars)
            2 -> RipplesLayer(np, active, playing, clock)
            3 -> VinylLayer(np, active, playing, clock, bars)
            4 -> MinimalLayer(np, active, playing, clock, bars)
            5 -> BarsLayer(playing, clock, bars)
            else -> OceanLayer(np, active, playing, clock, bars)
        }
        if (Style.waveStyle == 0) Text("Моя\nволна", Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 8.dp),
            color = Color.White, fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold)
        else if (Style.waveStyle != 4) Text("Моя волна", Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 16.dp),
            color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (active) TextButton(onStop, Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp)) {
            Text("Остановить", color = Color.White.copy(0.85f))
        }
        WaveControls(np, active, loading, playing, bars, onToggle)
    }
}

@Composable
private fun BoxScope.WaveControls(np: NowPlaying, active: Boolean, loading: Boolean, playing: Boolean,
                                  bars: State<FloatArray>, onToggle: () -> Unit) {
    val accent = Color(Style.accent)
    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (active && np.title != null) "${np.title} · ${np.artist ?: ""}" else "Нажми и поплыви по своим вкусам",
            Modifier.padding(horizontal = 24.dp).panel(RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 6.dp),
            color = Color.White.copy(0.95f), style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.size(84.dp)
            .graphicsLayer {
                val b = bars.value
                val e = if (playing && b.isNotEmpty()) b.average().toFloat() else 0f
                scaleX = 1f + e * 0.22f; scaleY = 1f + e * 0.22f
            }
            .clip(CircleShape).background(accent).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(34.dp), color = Color.Black, strokeWidth = 3.dp)
            else Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(44.dp), tint = Color.Black)
        }
    }
}

// ---------- 1. Океан ----------
@Composable
private fun BoxScope.OceanLayer(np: NowPlaying, active: Boolean, playing: Boolean, clock: LongState, bars: State<FloatArray>) {
    val accent = Color(Style.accent); val pink = Color(0xFFFF8AD8); val cyan = Color(0xFF5CC8FF)
    Canvas(Modifier.fillMaxSize()) {
        val phase = clock.longValue / 1000f * (2 * PI.toFloat() / 7f)
        val b = bars.value
        val live = playing && b.isNotEmpty()
        val avg = if (live) b.average().toFloat() else 0.04f
        val w = size.width; val h = size.height
        val layers = listOf(Triple(accent, 0.60f, 0f), Triple(pink, 0.42f, 1.7f), Triple(cyan, 0.36f, 3.4f))
        layers.forEachIndexed { li, (col, alpha, off) ->
            val base = h * (0.46f + li * 0.06f)
            val path = Path()
            val steps = 64
            path.moveTo(0f, h)
            for (s in 0..steps) {
                val x = w * s / steps
                val idx = ((s.toFloat() / steps) * (b.size - 1)).toInt().coerceIn(0, maxOf(b.size - 1, 0))
                val e = if (live) b[idx] else 0.04f
                val amp = h * (0.02f + 0.09f * e + 0.04f * avg)
                val y = base + amp * sin(phase * (1f + li * 0.5f) + off + s * 0.26f) +
                    amp * 0.5f * sin(phase * 2f + s * 0.11f + li)
                path.lineTo(x, y)
            }
            path.lineTo(w, h); path.close()
            drawPath(path, Brush.verticalGradient(listOf(col.copy(alpha), col.copy(alpha * 0.12f)), startY = base - h * 0.08f, endY = h))
        }
    }
    Box(Modifier.align(Alignment.TopCenter).padding(top = 110.dp).size(150.dp)
        .graphicsLayer {
            val phase = clock.longValue / 1000f * (2 * PI.toFloat() / 7f)
            translationY = sin(phase) * 14.dp.toPx()
            rotationZ = sin(phase * 0.9f + 1f) * 5f
        }
        .clip(RoundedCornerShape(38.dp))
        .border(1.5.dp, Brush.linearGradient(listOf(Color.White.copy(0.8f), Color.White.copy(0.1f), Color.White.copy(0.4f))), RoundedCornerShape(38.dp))
        .background(Color(0x33FFFFFF))) { WaveArtFill(np, active) }
}

// ---------- 3. Круговой спектр ----------
@Composable
private fun BoxScope.RaysLayer(np: NowPlaying, active: Boolean, playing: Boolean, clock: LongState, bars: State<FloatArray>) {
    val accent = Color(Style.accent); val pink = Color(0xFFFF8AD8)
    Canvas(Modifier.fillMaxSize()) {
        val b = bars.value
        val live = playing && b.isNotEmpty()
        val avg = if (live) b.average().toFloat() else 0.05f
        val c = Offset(size.width / 2, 175.dp.toPx())
        val glow = 175.dp.toPx()
        drawCircle(Brush.radialGradient(listOf(accent.copy(0.18f + 0.18f * avg), Color.Transparent), center = c, radius = glow), glow, c)
        val count = 64; val half = count / 2
        val r0 = 68.dp.toPx()
        val rot = clock.longValue / 1000f * 0.25f
        for (i in 0 until count) {
            val k = if (i < half) i else count - 1 - i
            val e = if (live) b[(k * (b.size - 1) / (half - 1)).coerceIn(0, b.size - 1)] else 0.06f + 0.04f * sin(rot * 6f + i)
            val len = 5.dp.toPx() + e * 34.dp.toPx()
            val a = i * (2f * PI.toFloat() / count) + rot
            val dir = Offset(cos(a), sin(a))
            drawLine(lerp(accent, pink, i / count.toFloat()), c + dir * r0, c + dir * (r0 + len), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        }
    }
    Box(Modifier.align(Alignment.TopCenter).padding(top = 115.dp).size(120.dp)
        .graphicsLayer {
            val b = bars.value
            val e = if (playing && b.isNotEmpty()) b.average().toFloat() else 0f
            scaleX = 1f + e * 0.07f; scaleY = 1f + e * 0.07f
        }.clip(CircleShape).border(2.dp, Color.White.copy(0.6f), CircleShape)) { WaveArtFill(np, active) }
}

// ---------- 4. Круги на воде ----------
@Composable
private fun BoxScope.RipplesLayer(np: NowPlaying, active: Boolean, playing: Boolean, clock: LongState) {
    val accent = Color(Style.accent); val cyan = Color(0xFF5CC8FF)
    val ripples = remember { mutableStateListOf<Long>() }
    val pl = rememberUpdatedState(playing)
    LaunchedEffect(Unit) {
        var prev = 0f; var last = 0L
        snapshotFlow { clock.longValue }.collect { now ->
            val b = VizBus.bars.value
            val bass = if (b.size >= 4) (b[0] + b[1] + b[2] + b[3]) / 4f else 0f
            if (pl.value && bass > 0.5f && bass > prev + 0.1f && now - last > 260) { ripples.add(now); last = now }
            else if (!pl.value && now - last > 2400) { ripples.add(now); last = now }
            prev = bass
            ripples.removeAll { now - it > 2600 }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val now = clock.longValue
        drawRect(Brush.verticalGradient(listOf(Color(0xFF07101A).copy(0.70f), Color(0xFF0B2233).copy(0.55f),
            lerp(accent, Color(0xFF0B2233), 0.5f).copy(0.45f)), startY = 0f, endY = size.height))
        val cx = size.width / 2; val cy = 215.dp.toPx()
        val coverC = Offset(cx, 150.dp.toPx())
        drawCircle(Brush.radialGradient(listOf(accent.copy(0.18f), Color.Transparent), center = coverC, radius = 120.dp.toPx()), 120.dp.toPx(), coverC)
        ripples.forEach { start ->
            val f = ((now - start) / 2200f).coerceIn(0f, 1f)
            val r = 36.dp.toPx() + f * 170.dp.toPx()
            val col = lerp(accent, cyan, f)
            val fade = (1f - f) * (1f - f)
            val tl = Offset(cx - r, cy - r * 0.28f); val sz = Size(2 * r, r * 0.56f)
            drawOval(col.copy(fade * 0.20f), tl, sz, style = Stroke(10.dp.toPx() * (1f - f) + 2.dp.toPx()))   // мягкое свечение
            drawOval(col.copy(fade * 0.85f), tl, sz, style = Stroke((2.5f * (1f - f) + 1f).dp.toPx()))
        }
    }
    Box(Modifier.align(Alignment.TopCenter).padding(top = 85.dp).size(130.dp)
        .graphicsLayer { translationY = sin(clock.longValue / 1000f * 1.2f) * 8.dp.toPx() }
        .clip(CircleShape).border(2.dp, Color.White.copy(0.6f), CircleShape)) { WaveArtFill(np, active) }
}

// ---------- 6. Винил ----------
@Composable
private fun BoxScope.VinylLayer(np: NowPlaying, active: Boolean, playing: Boolean, clock: LongState, bars: State<FloatArray>) {
    val accent = Color(Style.accent)
    val angle = remember { mutableFloatStateOf(0f) }
    val pl = rememberUpdatedState(playing)
    LaunchedEffect(Unit) {
        var last = 0L
        snapshotFlow { clock.longValue }.collect { now ->
            if (last != 0L && pl.value) angle.floatValue = (angle.floatValue + (now - last) / 1000f * 45f) % 360f
            last = now
        }
    }
    val arm by animateFloatAsState(if (playing) 0f else 24f, tween(600), label = "arm")
    Canvas(Modifier.fillMaxSize()) {
        val b = bars.value
        val avg = if (playing && b.isNotEmpty()) b.average().toFloat() else 0f
        val c = Offset(size.width / 2, 185.dp.toPx()); val r = 220.dp.toPx()
        drawCircle(Brush.radialGradient(listOf(accent.copy(0.18f + 0.18f * avg), Color.Transparent), center = c, radius = r), r, c)
    }
    Box(Modifier.align(Alignment.TopCenter).padding(top = 70.dp).size(230.dp).graphicsLayer { rotationZ = angle.floatValue }) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2; val c = center
            drawCircle(Color(0xFF0D0D10), r, c)
            for (k in 1..9) drawCircle(Color.White.copy(0.05f), r * (0.42f + k * 0.055f), c, style = Stroke(1.dp.toPx()))
            drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(0.14f), Color.Transparent, Color.White.copy(0.14f), Color.Transparent), center = c), r, c)
            drawCircle(accent, r * 0.40f, c)
        }
        Box(Modifier.align(Alignment.Center).size(82.dp).clip(CircleShape)) { WaveArtFill(np, active) }
        Box(Modifier.align(Alignment.Center).size(8.dp).clip(CircleShape).background(Color(0xFF0D0D10)))
    }
    // тонарм: во время игры лежит на пластинке, на паузе отходит
    Box(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 24.dp).size(130.dp)
        .graphicsLayer { rotationZ = arm; transformOrigin = TransformOrigin(0.85f, 0.12f) }) {
        Canvas(Modifier.fillMaxSize()) {
            val pivot = Offset(size.width * 0.85f, size.height * 0.12f)
            val tip = Offset(size.width * 0.18f, size.height * 0.84f)
            drawLine(Color(0xFFCFCFD6), pivot, tip, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Color(0xFF9A9AA4), 11.dp.toPx(), pivot)
            drawCircle(Color(0xFF3A3A42), 5.dp.toPx(), pivot)
            drawRoundRect(Color(0xFF2A2A30), Offset(tip.x - 9.dp.toPx(), tip.y - 4.dp.toPx()), Size(18.dp.toPx(), 12.dp.toPx()), CornerRadius(3.dp.toPx()))
        }
    }
}

// ---------- 13. Минимализм ----------
@Composable
private fun BoxScope.MinimalLayer(np: NowPlaying, active: Boolean, playing: Boolean, clock: LongState, bars: State<FloatArray>) {
    val accent = Color(Style.accent)
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.linearGradient(listOf(lerp(accent, Color(0xFF0B0B10), 0.45f).copy(0.75f), Color(0xFF0B0B10)), start = Offset(0f, 0f), end = Offset(size.width, size.height * 0.85f)))
        val rnd = kotlin.random.Random(7)
        repeat(260) { drawCircle(Color.White.copy(0.05f), 1.dp.toPx(), Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height)) }
        val b = bars.value; val live = playing && b.isNotEmpty()
        val t = clock.longValue / 1000f
        for (layer in 0..1) {
            val path = Path(); val y0 = 248.dp.toPx(); val steps = 72
            for (s in 0..steps) {
                val x = size.width * s / steps
                val e = if (live) b[((s.toFloat() / steps) * (b.size - 1)).toInt().coerceIn(0, b.size - 1)] else 0.05f
                val y = y0 + (3.dp.toPx() + e * 18.dp.toPx()) * sin(t * (1.6f + layer * 0.5f) + s * 0.30f + layer * 2f)
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Color.White.copy(if (layer == 0) 0.9f else 0.35f),
                style = Stroke(width = (if (layer == 0) 2.5f else 1.5f).dp.toPx(), cap = StrokeCap.Round))
        }
    }
    Column(Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 28.dp, end = 24.dp)) {
        Text("МОЯ ВОЛНА", color = Color.White.copy(0.6f), style = MaterialTheme.typography.labelLarge, letterSpacing = 3.sp)
        Spacer(Modifier.height(14.dp))
        Text(if (active) (np.title ?: "") else "Включи волну", color = Color.White, fontSize = 38.sp, lineHeight = 42.sp,
            fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (active) Text(np.artist ?: "", Modifier.padding(top = 8.dp), color = Color.White.copy(0.7f), fontSize = 18.sp, maxLines = 1)
    }
}

// ---------- 7. Три палочки ----------
@Composable
private fun BoxScope.BarsLayer(playing: Boolean, clock: LongState, bars: State<FloatArray>) {
    val accent = Color(Style.accent); val pink = Color(0xFFFF8AD8)
    Canvas(Modifier.fillMaxSize()) {
        val b = bars.value; val live = playing && b.isNotEmpty()
        val t = clock.longValue / 1000f
        val levels = if (live) listOf(b.band(0f, 0.33f), b.band(0.33f, 0.66f), b.band(0.66f, 1f))
        else List(3) { 0.06f + 0.04f * sin(t * 1.6f + it) }
        val bw = 52.dp.toPx(); val gap = 26.dp.toPx(); val total = 3 * bw + 2 * gap
        val x0 = (size.width - total) / 2
        val base = 215.dp.toPx(); val minH = 22.dp.toPx(); val maxH = 150.dp.toPx()
        levels.forEachIndexed { i, lv ->
            val h = minH + (lv * 1.15f).coerceIn(0f, 1f) * (maxH - minH)
            val x = x0 + i * (bw + gap)
            drawRoundRect(accent.copy(0.22f), Offset(x - 8.dp.toPx(), base - h - 8.dp.toPx()), Size(bw + 16.dp.toPx(), h + 16.dp.toPx()), CornerRadius(bw / 2 + 8.dp.toPx()))
            drawRoundRect(Brush.verticalGradient(listOf(pink, accent), startY = base - h, endY = base), Offset(x, base - h), Size(bw, h), CornerRadius(bw / 2))
            val ry = base + 6.dp.toPx()
            drawRoundRect(Brush.verticalGradient(listOf(accent.copy(0.28f), Color.Transparent), startY = ry, endY = ry + h * 0.5f),
                Offset(x, ry), Size(bw, h * 0.5f), CornerRadius(bw / 2))
        }
        drawLine(Color.White.copy(0.15f), Offset(x0 - 20.dp.toPx(), base + 2.dp.toPx()), Offset(x0 + total + 20.dp.toPx(), base + 2.dp.toPx()), strokeWidth = 1.dp.toPx())
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
