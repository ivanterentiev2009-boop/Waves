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
import androidx.compose.ui.text.font.FontWeight
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
    var data by remember(id) { mutableStateOf(LyricsData(emptyList(), "Загрузка…")) }
    LaunchedEffect(id) {
        if (id == null || !id.startsWith("ym")) { data = LyricsData(emptyList(), "Тексты доступны для треков Яндекс Музыки"); return@LaunchedEffect }
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
fun rememberPosition(c: MediaController?, running: Boolean): Long {
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(c, running) {
        pos = c?.currentPosition ?: 0L
        while (running) { delay(200); pos = c?.currentPosition ?: 0L }
    }
    return pos
}

fun activeLine(lines: List<LyricLine>, pos: Long): Int =
    if (lines.none { it.timeMs != null }) -1
    else lines.indexOfLast { (it.timeMs ?: Long.MAX_VALUE) <= pos + 250 }.coerceAtLeast(0)

fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

@Composable
fun LyricRow(l: LyricLine, i: Int, active: Int, synced: Boolean, big: Boolean, onClick: () -> Unit) {
    val dist = if (active < 0) 0 else abs(i - active)
    val target = if (!synced || dist == 0) 1f else (0.6f - 0.1f * dist).coerceAtLeast(0.2f)
    val alpha by animateFloatAsState(target, tween(350), label = "a")
    val size by animateFloatAsState(if (i == active) (if (big) 28f else 17f) else (if (big) 22f else 14f),
        spring(stiffness = 200f), label = "s")
    Text(l.text.ifBlank { "♪" },
        Modifier.fillMaxWidth().clickable(enabled = l.timeMs != null, onClick = onClick).padding(vertical = if (big) 8.dp else 4.dp),
        color = Color.White.copy(alpha), fontSize = size.sp, lineHeight = (size * 1.25f).sp, fontWeight = FontWeight.Bold)
}

/** Экран текста: строки подсвечиваются по времени, тап по строке перематывает. */
@Composable
fun LyricsScreen(c: MediaController?, np: NowPlaying, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val lyr = rememberLyrics(np.id)
    val pos = rememberPosition(c, np.playing)
    val synced = lyr.lines.any { it.timeMs != null }
    val active = activeLine(lyr.lines, pos)
    val listState = rememberLazyListState()
    LaunchedEffect(active) { if (active >= 0) listState.animateScrollToItem(active, scrollOffset = -280) }
    val dur = (c?.duration ?: 0L).coerceAtLeast(0L)

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
                if (lyr.lines.isEmpty()) Text(lyr.status, Modifier.padding(32.dp), color = Color(0xB3FFFFFF))
                else LazyColumn(Modifier.fillMaxSize(), state = listState,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 160.dp)) {
                    itemsIndexed(lyr.lines) { i, l -> LyricRow(l, i, active, synced, true) { c?.seekTo(l.timeMs!!) } }
                }
            }
            Column(Modifier.padding(horizontal = 24.dp)) {
                Slider(if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f, { c?.seekTo((it * dur).toLong()) })
                Row { Text(fmt(pos), color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Text(fmt(dur), color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodySmall) }
            }
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
