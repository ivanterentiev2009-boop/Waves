package com.example.waves

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.max
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

object LyricsCache { val map = HashMap<String, List<LyricLine>>() }
class LyricsData(val lines: List<LyricLine>, val status: String)

@Composable
fun rememberLyrics(id: String?): LyricsData {
    val ctx = LocalContext.current
    var data by remember(id) { mutableStateOf(LyricsData(emptyList(), "Загрузка…")) }
    LaunchedEffect(id, Transcriber.version) {
        if (id == null) { data = LyricsData(emptyList(), "Ничего не играет"); return@LaunchedEffect }
        if (!id.startsWith("ym")) {   // локальный трек: сохранённые субтитры (Vosk)
            val cached = withContext(Dispatchers.IO) { Transcriber.load(ctx, id) }
            data = if (cached != null) LyricsData(cached, "") else LyricsData(emptyList(),
                if (Style.subsEnabled) "Текста нет. Его можно распознать на устройстве."
                else "Для локальных треков можно включить распознавание текста: Стиль → Субтитры.")
            return@LaunchedEffect
        }
        val none = "Для этого трека текста нет"
        LyricsCache.map[id]?.let { data = LyricsData(it, if (it.isEmpty()) none else ""); return@LaunchedEffect }
        try {
            val l = withContext(Dispatchers.IO) { YandexApi.lyrics(id.removePrefix("ym")) }
            LyricsCache.map[id] = l
            data = LyricsData(l, if (l.isEmpty()) none else "")
        } catch (e: Exception) { data = LyricsData(emptyList(), "Ошибка: ${e.message}") }
    }
    return data
}

@Composable
fun rememberPosition(c: MediaController?, running: Boolean, periodMs: Long = 200): State<Long> {
    val pos = remember { mutableLongStateOf(0L) }
    LaunchedEffect(c, running) {
        pos.longValue = c?.currentPosition ?: 0L
        while (running) { delay(periodMs); pos.longValue = c?.currentPosition ?: 0L }
    }
    return pos
}

/** Индекс активной строки: пересчитывается часто, но состояние меняется только при смене строки. */
@Composable
fun rememberActive(lines: List<LyricLine>, pos: State<Long>): State<Int> =
    remember(lines) { derivedStateOf { activeLine(lines, pos.value) } }

fun activeLine(lines: List<LyricLine>, pos: Long): Int =
    if (lines.none { it.timeMs != null }) -1
    else lines.indexOfLast { (it.timeMs ?: Long.MAX_VALUE) <= pos + 250 }.coerceAtLeast(0)

fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

/**
 * Пословная подсветка. В LRC есть время только у строки, поэтому слова распределяются по её длительности
 * пропорционально длине, а текущее слово заполняется по буквам слева направо (меняется только цвет, перенос не двигается).
 */
fun karaoke(text: String, start: Long, end: Long, pos: Long, on: Color, off: Color, times: List<Pair<Long, Long>>? = null): AnnotatedString {
    val words = text.split(" ")
    val w = words.map { max(it.length, 2) }
    val sum = w.sum().toFloat()
    val dur = ((end - start).coerceIn(600L, 8000L) * 0.9f)
    val p = ((pos - start) / dur).coerceIn(0f, 1f)
    return buildAnnotatedString {
        var cum = 0
        words.forEachIndexed { i, word ->
            val a = cum / sum; val b = (cum + w[i]) / sum
            cum += w[i]
            val local = if (times != null && times.size == words.size) {
                val (ws, we) = times[i]   // точное время слова (Vosk)
                ((pos - ws).toFloat() / max(we - ws, 80L)).coerceIn(0f, 1f)
            } else ((p - a) / (b - a)).coerceIn(0f, 1f)
            when {
                local >= 1f -> withStyle(SpanStyle(color = on)) { append(word) }
                local <= 0f -> withStyle(SpanStyle(color = off)) { append(word) }
                else -> word.forEachIndexed { ci, ch ->
                    val k = (local * word.length - ci).coerceIn(0f, 1f)
                    withStyle(SpanStyle(color = lerp(off, on, k))) { append(ch) }
                }
            }
            if (i < words.lastIndex) append(" ")
        }
    }
}

@Composable
fun LyricRow(l: LyricLine, i: Int, active: Int, synced: Boolean, big: Boolean, pos: State<Long>, nextMs: Long?, onClick: () -> Unit) {
    val dist = if (active < 0) 0 else abs(i - active)
    val target = if (!synced || dist == 0) 1f else (0.6f - 0.1f * dist).coerceAtLeast(0.2f)
    val alpha = animateFloatAsState(target, tween(350), label = "a")
    val scale = animateFloatAsState(if (i == active || !synced) 1f else 0.84f, spring(stiffness = 250f), label = "s")
    // Размер шрифта не меняется, анимируется только масштаб слоя: без перерасчёта текста, прокрутка плавная.
    val mod = Modifier.fillMaxWidth()
        .graphicsLayer {
            this.alpha = alpha.value; scaleX = scale.value; scaleY = scale.value
            transformOrigin = TransformOrigin(0f, 0.5f)
        }
        .clickable(enabled = l.timeMs != null, onClick = onClick)
        .padding(vertical = if (big) 8.dp else 4.dp)
    val fs = (if (big) 26 else 16).sp
    val lh = (if (big) 32 else 20).sp
    val t = l.text.ifBlank { "♪" }
    if (synced && i == active && l.timeMs != null) {
        val start = l.timeMs
        Text(karaoke(t, start, nextMs ?: (start + 4000), pos.value, Color.White, Color.White.copy(0.4f), l.wordTimes),
            mod, fontSize = fs, lineHeight = lh, fontWeight = FontWeight.Bold)
    } else Text(t, mod, color = Color.White, fontSize = fs, lineHeight = lh, fontWeight = FontWeight.Bold)
}

/** Экран текста: строки подсвечиваются по времени, тап по строке перематывает. */
@Composable
fun LyricsScreen(c: MediaController?, np: NowPlaying, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val lyr = rememberLyrics(np.id)
    val pos = rememberPosition(c, np.playing, 80)
    val synced = lyr.lines.any { it.timeMs != null }
    val active = rememberActive(lyr.lines, pos).value
    val listState = rememberLazyListState()
    LaunchedEffect(active) { if (active >= 0) listState.animateScrollToItem(active, scrollOffset = -280) }
    AppBackground(np.art) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(np.title ?: "", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(np.artist ?: "", color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (lyr.lines.isEmpty()) EmptyLyrics(lyr.status, np)
                else LazyColumn(Modifier.fillMaxSize(), state = listState,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 160.dp)) {
                    itemsIndexed(lyr.lines) { i, l -> LyricRow(l, i, active, synced, true, pos, lyr.lines.getOrNull(i + 1)?.timeMs) { c?.seekTo(l.timeMs!!) } }
                }
            }
            SeekBar(c, pos)
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                val tintS by animateColorAsState(if (np.shuffle) Color(Style.accent) else Color.White, label = "sh")
                val tintR by animateColorAsState(if (np.repeat != 0) Color(Style.accent) else Color.White, label = "rp")
                IconButton({ c?.shuffleModeEnabled = !np.shuffle }) { Icon(Icons.Default.Shuffle, null, tint = tintS) }
                Controls(c, np.playing)
                IconButton({ c?.repeatMode = (np.repeat + 1) % 3 }) {
                    Icon(if (np.repeat == 1) Icons.Default.RepeatOne else Icons.Default.Repeat, null, tint = tintR)
                }
            }
        }
    }
}

@Composable
private fun SeekBar(c: MediaController?, pos: State<Long>) {
    val p = pos.value
    val dur = (c?.duration ?: 0L).coerceAtLeast(0L)
    Column(Modifier.padding(horizontal = 24.dp)) {
        Slider(if (dur > 0) (p.toFloat() / dur).coerceIn(0f, 1f) else 0f, { c?.seekTo((it * dur).toLong()) })
        Row {
            Text(fmt(p), color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(fmt(dur), color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptyLyrics(status: String, np: NowPlaying) {
    val ctx = LocalContext.current
    val id = np.id
    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(status, color = Color(0xB3FFFFFF), textAlign = TextAlign.Center)
        if (id != null && !id.startsWith("ym") && Style.subsEnabled) {
            LaunchedEffect(Unit) { Transcriber.refresh(ctx) }
            val p = Transcriber.progress[id]
            when {
                p != null -> {
                    LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
                    Text("Распознавание… ${(p * 100).toInt()}%", color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodySmall)
                }
                !Transcriber.modelReady -> Button({ Transcriber.downloadModel(ctx) }, enabled = !Transcriber.modelBusy) {
                    Text(if (Transcriber.modelBusy) "Скачивание ${(Transcriber.modelProgress * 100).toInt()}%" else "Скачать модель (около 45 МБ)")
                }
                else -> Button({ np.uri?.let { Transcriber.start(ctx, id, it) } }, enabled = np.uri != null) { Text("Распознать текст") }
            }
            Transcriber.errors[id]?.let { Text("Ошибка: $it", color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
