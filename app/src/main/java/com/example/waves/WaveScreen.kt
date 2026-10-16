package com.example.waves

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.PI
import kotlin.math.sin

/**
 * «Моя волна»: живой океан звука. Три слоя волн плывут сами, а их высота повторяет спектр музыки,
 * обложка текущего трека качается на волнах как буй, а кнопка пульсирует в такт.
 */
@Composable
fun WaveHero(np: NowPlaying, active: Boolean, loading: Boolean, onToggle: () -> Unit, onStop: () -> Unit) {
    val phase = rememberInfiniteTransition(label = "wave").animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "ph")
    val bars = VizBus.bars.collectAsState()
    val accent = Color(Style.accent)
    val pink = Color(0xFFFF8AD8)
    val cyan = Color(0xFF5CC8FF)
    val playing = np.playing && active

    Box(Modifier.fillMaxWidth().height(400.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val b = bars.value
            val live = playing && b.isNotEmpty()
            val avg = if (live) b.average().toFloat() else 0.04f
            val w = size.width; val h = size.height
            val layers = listOf(Triple(accent, 0.60f, 0f), Triple(pink, 0.42f, 1.7f), Triple(cyan, 0.36f, 3.4f))
            layers.forEachIndexed { li, (col, alpha, off) ->
                val base = h * (0.50f + li * 0.07f)
                val path = Path()
                val steps = 64
                path.moveTo(0f, h)
                for (s in 0..steps) {
                    val x = w * s / steps
                    val idx = ((s.toFloat() / steps) * (b.size - 1)).toInt().coerceIn(0, maxOf(b.size - 1, 0))
                    val e = if (live) b[idx] else 0.04f
                    val amp = h * (0.025f + 0.11f * e + 0.05f * avg)
                    val y = base + amp * sin(phase.value * (1f + li * 0.5f) + off + s * 0.26f) +
                        amp * 0.5f * sin(phase.value * 2f + s * 0.11f + li)
                    path.lineTo(x, y)
                }
                path.lineTo(w, h); path.close()
                drawPath(path, Brush.verticalGradient(listOf(col.copy(alpha), col.copy(alpha * 0.12f)), startY = base - h * 0.1f, endY = h))
            }
        }

        Text("Моя\nволна", Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 8.dp),
            color = Color.White, fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold)

        // обложка-буй, качается на волнах
        Box(Modifier.align(Alignment.TopCenter).padding(top = 110.dp).size(150.dp)
            .graphicsLayer {
                translationY = sin(phase.value) * 14.dp.toPx()
                rotationZ = sin(phase.value * 0.9f + 1f) * 5f
            }
            .clip(RoundedCornerShape(38.dp))
            .border(1.5.dp, Brush.linearGradient(listOf(Color.White.copy(0.8f), Color.White.copy(0.1f), Color.White.copy(0.4f))), RoundedCornerShape(38.dp))
            .background(Color(0x33FFFFFF))) {
            if (active && np.art != null) AsyncImage(np.art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.linearGradient(listOf(accent, pink)))
                val p = Path(); p.moveTo(0f, size.height / 2)
                for (i in 0..40) p.lineTo(size.width * i / 40, size.height / 2 + sin(i * 0.5f) * size.height * 0.13f)
                drawPath(p, Color.White, style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round))
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (active && np.title != null) "${np.title} · ${np.artist ?: ""}" else "Нажми и поплыви по своим вкусам",
                Modifier.padding(horizontal = 24.dp), color = Color.White.copy(0.9f), style = MaterialTheme.typography.bodyMedium,
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
            if (active) TextButton(onStop) { Text("Остановить подборку", color = Color.White.copy(0.8f)) }
        }
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
