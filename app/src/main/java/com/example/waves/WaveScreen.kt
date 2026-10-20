package com.example.waves

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable

/** Три вида «Моей волны» на главной (выбираются в настройках). Первый: тот, что был раньше. */
val WAVE_STYLES = listOf(
    "Коллаж" to "Крупный заголовок, плавающие обложки и волнистая лента",
    "Пульс" to "Обложка в центре, кольцо спектра вокруг неё пляшет в такт музыке",
    "Прибой" to "Размытая обложка на весь блок и живые волны внизу",
)

/**
 * «Моя волна» на главной: вид берётся из настроек и меняется сразу.
 * activeKind: какая волна играет сейчас ("wave", "artist", "song") или null.
 */
@Composable
fun WaveHero(np: NowPlaying, art: Uri?, extra: List<Uri?>, playing: Boolean, loading: Boolean,
             onToggle: () -> Unit, onOverlay: (() -> Unit)? = null,
             artistName: String? = null, songName: String? = null, activeKind: String? = null,
             onStation: ((String) -> Unit)? = null, onShake: (() -> Unit)? = null) {
    val bob = rememberInfiniteTransition(label = "h").animateFloat(0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "bob")
    val bars = VizBus.bars.collectAsState()
    Crossfade(Style.waveStyle, animationSpec = tween(300), label = "wavestyle") { s ->
        when (s) {
            1 -> PulseHero(np, art, playing, loading, bars, bob, onToggle, onOverlay, onShake)
            2 -> SurfHero(np, art, playing, loading, bars, bob, onToggle, onOverlay, onShake)
            else -> CollageHero(np, art, extra, playing, loading, bars, bob, onToggle, onOverlay,
                artistName, songName, activeKind, onStation, onShake)
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
        .clip(CircleShape).background(WP.accent).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator(Modifier.size(size * 0.37f), color = Color.Black, strokeWidth = 3.dp)
        else Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(size * 0.52f), tint = Color.Black)
    }
}

@Composable
private fun BoxScope.OverlayButton(onOverlay: (() -> Unit)?, onShake: (() -> Unit)? = null, tint: Color = WP.text) {
    Row(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 6.dp)) {
        if (onShake != null) IconButton(onShake) { Icon(Icons.Default.Refresh, "Стряхнуть волну", tint = tint) }
        if (onOverlay != null) IconButton(onOverlay) { Icon(Icons.Default.PictureInPicture, "Оверлей", tint = tint) }
    }
}

/** Кнопка «Стряхнуть волну»: загружает новую подборку той же волны. Тёмная капсула читается на любом фоне. */
@Composable
private fun ShakeChip(modifier: Modifier, onShake: () -> Unit) {
    val rot = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Row(modifier.clip(CircleShape).background(Color.Black.copy(0.50f)).border(DS.stroke, Color.White.copy(0.22f), CircleShape)
        .clickable { scope.launch { rot.snapTo(0f); rot.animateTo(360f, tween(500)) }; onShake() }
        .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Refresh, null, Modifier.size(DS.iconS).graphicsLayer { rotationZ = rot.value }, tint = WP.onArt)
        Spacer(Modifier.width(8.dp))
        Text("Стряхнуть волну", color = WP.onArt, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Плитка волны (по артисту или по песне): обложка с тёмным градиентом, оттенок задаёт цвет волны. Текст всегда светлый. */
@Composable
private fun WaveTile(label: String, name: String?, art: Uri?, tint: Color, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = DS.shapeM
    Box(modifier.clip(shape).background(WP.fill)
        .then(if (active) Modifier.border(DS.strokeSel, WP.accent, shape) else Modifier)
        .clickable(onClick = onClick)) {
        ArtFill(art)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(tint.copy(0.25f), Color.Black.copy(0.78f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = DS.padM, vertical = 10.dp)) {
            Text(label, color = WP.onArt, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            if (!name.isNullOrBlank()) Text(name, color = WP.onArt2, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun FloatArray.at(i: Int): Float = if (isEmpty()) 0f else this[i.coerceIn(0, size - 1)]

// ---------- 1. Коллаж ----------
// заголовок и оверлей в одной строке; большая карточка текущей волны; под ней две равные плитки «По артисту» и «По песне».
// Волнистая линия живёт только в средней зоне карточки: над подписью и кнопкой, ничего не пересекает.
@Composable
private fun CollageHero(np: NowPlaying, art: Uri?, extra: List<Uri?>, playing: Boolean, loading: Boolean,
                        bars: State<FloatArray>, bob: State<Float>, onToggle: () -> Unit, onOverlay: (() -> Unit)?,
                        artistName: String?, songName: String?, activeKind: String?,
                        onStation: ((String) -> Unit)?, onShake: (() -> Unit)?) {
    val accent = WP.accent
    val mainShape = DS.shapeL
    val tileArt = extra.getOrNull(0) ?: art
    Column(Modifier.fillMaxWidth().padding(horizontal = DS.padL)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Моя волна", Modifier.weight(1f), color = WP.text, style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onOverlay != null) IconButton(onOverlay) { Icon(Icons.Default.PictureInPicture, "Оверлей", Modifier.size(DS.iconM), tint = WP.text) }
        }
        Spacer(Modifier.height(DS.padS))
        Box(Modifier.fillMaxWidth().aspectRatio(1.35f).clip(mainShape).background(WP.fill)
            .then(if (activeKind == "wave") Modifier.border(DS.strokeSel, accent, mainShape) else Modifier)
            .clickable { onStation?.invoke("wave") }) {
            ArtFill(art)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(colorStops = arrayOf(0.30f to Color.Transparent, 1f to Color.Black.copy(0.84f)))))
            Canvas(Modifier.matchParentSize()) {
                // линия реагирует на музыку: низкие частоты (бас, бит) поднимают амплитуду, спектр задаёт рябь по ширине.
                // Без воспроизведения остаётся спокойная волна.
                val b = bars.value
                val beat = if (playing) (0 until 6).sumOf { b.at(it).toDouble() }.toFloat() / 6f else 0f
                val amp = (7f + beat * 22f).dp.toPx()
                val path = Path(); val steps = 40
                for (st in 0..steps) {
                    val x = size.width * st / steps
                    val ripple = if (playing) b.at(st * b.size / (steps + 1)) * 6.dp.toPx() * sin(st * 0.9f + bob.value * 3f) else 0f
                    val y = size.height * 0.50f + sin(st * 0.30f + bob.value) * amp + ripple
                    if (st == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, accent.copy(0.55f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            if (onShake != null) ShakeChip(Modifier.align(Alignment.TopStart).padding(DS.padM), onShake)
            Column(Modifier.align(Alignment.BottomStart).padding(start = DS.padL, bottom = DS.padL, end = 104.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GraphicEq, null, Modifier.size(DS.iconS), tint = WP.onArt2)
                    Spacer(Modifier.width(6.dp))
                    Text(if (np.title != null) "Сейчас в волне" else "Бесконечная подборка", color = WP.onArt2,
                        style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                if (np.title != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(np.title, color = WP.onArt, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!np.artist.isNullOrBlank()) Text(np.artist, color = WP.onArt2, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            PlayOrb(72.dp, playing, loading, bars, Modifier.align(Alignment.BottomEnd).padding(end = DS.padL, bottom = DS.padL), onToggle)
        }
        Spacer(Modifier.height(DS.padM))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DS.padM)) {
            WaveTile("По артисту", artistName, tileArt, Color(0xFFFF8AD8), activeKind == "artist", Modifier.weight(1f).aspectRatio(1.7f)) { onStation?.invoke("artist") }
            WaveTile("По песне", songName, extra.getOrNull(1) ?: tileArt, Color(0xFF5CC8FF), activeKind == "song", Modifier.weight(1f).aspectRatio(1.7f)) { onStation?.invoke("song") }
        }
    }
}

// ---------- 2. Пульс ----------
@Composable
private fun PulseHero(np: NowPlaying, art: Uri?, playing: Boolean, loading: Boolean,
                      bars: State<FloatArray>, bob: State<Float>, onToggle: () -> Unit, onOverlay: (() -> Unit)?, onShake: (() -> Unit)?) {
    val accent = WP.accent
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
        Text("Моя волна", Modifier.padding(start = 20.dp, top = 16.dp), color = WP.text, fontSize = 28.sp, fontWeight = FontWeight.Black)
        OverlayButton(onOverlay, onShake)
        Box(Modifier.align(Alignment.TopCenter).padding(top = 115.dp).size(140.dp)
            .graphicsLayer {
                val b = bars.value
                val e = if (playing && b.isNotEmpty()) b.average().toFloat() else 0f
                scaleX = 1f + e * 0.08f; scaleY = 1f + e * 0.08f
            }
            .clip(CircleShape).background(WP.fill)) { ArtFill(art) }
        Column(Modifier.align(Alignment.TopCenter).padding(top = 330.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (np.title != null) np.title else "Бесконечная подборка", color = WP.text, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (np.title != null) (np.artist ?: "") else "Нажми, чтобы начать", color = WP.text2,
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PlayOrb(76.dp, playing, loading, bars, Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp), onToggle)
    }
}

// ---------- 3. Прибой ----------
@Composable
private fun SurfHero(np: NowPlaying, art: Uri?, playing: Boolean, loading: Boolean,
                     bars: State<FloatArray>, bob: State<Float>, onToggle: () -> Unit, onOverlay: (() -> Unit)?, onShake: (() -> Unit)?) {
    val accent = WP.accent; val pink = Color(0xFFFF8AD8); val cyan = Color(0xFF5CC8FF)
    val shape = DS.shapeL
    Box(Modifier.fillMaxWidth().padding(horizontal = DS.padM, vertical = 6.dp).height(450.dp)
        .clip(shape).border(DS.stroke, DS.lineColor, shape)) {
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
        Text("Моя\nволна", Modifier.padding(start = 20.dp, top = 14.dp), color = WP.onArt, fontSize = 52.sp,
            lineHeight = 52.sp, fontWeight = FontWeight.Black)
        OverlayButton(onOverlay, onShake, WP.onArt)
        Box(Modifier.align(Alignment.TopCenter).padding(top = 150.dp).size(100.dp)
            .graphicsLayer { translationY = sin(bob.value) * 7.dp.toPx(); rotationZ = sin(bob.value) * 4f }
            .clip(DS.shapeL).background(DS.fillColor)) { ArtFill(art) }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 20.dp).widthIn(max = 220.dp)) {
            Text(if (np.title != null) np.title else "Бесконечная подборка", color = WP.onArt,
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (np.title != null) Text(np.artist ?: "", color = WP.onArt, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        PlayOrb(84.dp, playing, loading, bars, Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp), onToggle)
    }
}

@Composable
fun WaveCard(t: Track, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clip(DS.shapeM).clickable(onClick = onClick)) {
        Cover(t.art, 140, DS.shapeM)
        Text(t.title, Modifier.padding(top = 8.dp), color = WP.text, fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(t.artist, color = WP.text2, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 1:46 из миллисекунд. */
fun fmtDuration(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(t: Track, sel: Boolean, extra: List<Pair<String, () -> Unit>> = emptyList(), divider: Boolean = false, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = DS.padM).clip(DS.shapeM)
        .background(if (sel) WP.selFill else Color.Transparent)
        .then(if (divider && !sel) Modifier.drawBehind {
            val x = (DS.padS + DS.coverRow.dp + DS.padM).toPx()
            drawLine(WP.line, androidx.compose.ui.geometry.Offset(x, size.height - 0.5.dp.toPx()), androidx.compose.ui.geometry.Offset(size.width - DS.padS.toPx(), size.height - 0.5.dp.toPx()), 1.dp.toPx())
        } else Modifier)
        .combinedClickable(onClick = onClick, onLongClick = { menu = true }).padding(horizontal = DS.padS, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Cover(t.art, DS.coverRow); Spacer(Modifier.width(DS.padM))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = if (sel) WP.accentInk else WP.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, color = WP.text2, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (sel) Icon(Icons.Default.GraphicEq, null, Modifier.padding(start = DS.padS).size(DS.iconM), tint = WP.accentInk)
        if (t.durationMs > 0) Text(fmtDuration(t.durationMs), Modifier.padding(start = DS.padM), color = WP.text3,
            style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
    if (menu) TrackMenu(t, extra) { menu = false }
}
