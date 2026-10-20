package com.example.waves

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.UploadFile
import kotlinx.coroutines.launch
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

/**
 * Тексты, найденные заранее. Хранятся только в памяти до закрытия приложения.
 * misses: треки, для которых текст уже искали и не нашли (с сигнатурой настроек, чтобы после их смены искать снова).
 */
object LyricsCache {
    val map = HashMap<String, Pair<String, List<LyricLine>>>()
    val misses = HashMap<String, Pair<String, String>>()
    private val inflight = java.util.Collections.synchronizedSet(HashSet<String>())

    fun sig() = "${Style.lyYandex}${Style.lyLrclib}${Style.lyOvh}"

    /** Находит текст, если его ещё нет. Если этот трек уже ищет кто-то другой, просто ждёт его. */
    suspend fun ensure(id: String, title: String?, artist: String?, durSec: Int?) {
        if (map.containsKey(id) || misses[id]?.first == sig()) return
        withContext(Dispatchers.IO) { LyricsDisk.load(id) }?.let { (src, lines) -> if (lines.isNotEmpty()) { map[id] = src to lines; return } }
        if (!inflight.add(id)) { while (inflight.contains(id)) delay(100); return }
        try {
            val notes = mutableListOf<String>()
            val found = withContext(Dispatchers.IO) { LyricSources.find(id, title, artist, durSec, notes) }
            if (found != null) { map[id] = found; LyricsDisk.save(id, found.first, found.second) } else misses[id] = sig() to notes.joinToString("\n")
        } finally { inflight.remove(id) }
    }

    fun clearDisk() { LyricsDisk.clear(); map.clear(); misses.clear() }

    fun peek(id: String): LyricsData? {
        map[id]?.let { (src, l) -> if (l.isNotEmpty()) return LyricsData(l, "", src) }
        misses[id]?.takeIf { it.first == sig() }?.let { (_, notes) ->
            val hint = if (id.startsWith("ym")) "" else "\nМожно загрузить .lrc кнопкой сверху или распознать текст на устройстве."
            return LyricsData(emptyList(), notes.ifBlank { "Текст не найден" } + hint)
        }
        return null
    }
}

class LyricsData(val lines: List<LyricLine>, val status: String, val source: String = "")

@Composable
fun rememberLyrics(id: String?, title: String?, artist: String?, c: MediaController?): LyricsData {
    val ctx = LocalContext.current
    // если текст уже найден заранее, он показывается с первого кадра
    var data by remember(id) { mutableStateOf((if (id != null) LyricsCache.peek(id) else null) ?: LyricsData(emptyList(), "Загрузка…")) }
    LaunchedEffect(id, title, Transcriber.version, Style.lyYandex, Style.lyLrclib, Style.lyOvh) {
        if (id == null) { data = LyricsData(emptyList(), "Ничего не играет"); return@LaunchedEffect }
        // свои правки, загруженный файл или распознанный текст важнее всего остального
        val saved = withContext(Dispatchers.IO) { Transcriber.load(ctx, id) }
        if (saved != null && saved.isNotEmpty()) { data = LyricsData(saved, "", "мои правки / файл"); return@LaunchedEffect }
        LyricsCache.peek(id)?.let { data = it; return@LaunchedEffect }
        data = LyricsData(emptyList(), "Ищем текст…")
        // длительность нужна для точного поиска, ждём её не дольше секунды
        var durMs = c?.duration ?: -1L
        var tries = 0
        while (c != null && durMs <= 0 && tries < 10) { delay(100); durMs = c.duration; tries++ }
        val durSec = if (durMs > 0) (durMs / 1000).toInt() else null
        LyricsCache.ensure(id, title, artist, durSec)
        LyricsCache.peek(id)?.let { data = it }
    }
    return data
}

/**
 * Ищет тексты заранее: для играющего трека и нескольких следующих в очереди. Когда открываешь текст,
 * он уже найден и показывается сразу, без «Ищем текст…».
 */
@Composable
fun LyricsPrefetcher(c: MediaController?, np: NowPlaying) {
    LaunchedEffect(np.id, np.index, c, Style.lyYandex, Style.lyLrclib, Style.lyOvh) {
        val ctrl = c ?: return@LaunchedEffect
        val start = ctrl.currentMediaItemIndex
        for (k in 0..3) {
            val i = start + k
            if (i >= ctrl.mediaItemCount) break
            val item = ctrl.getMediaItemAt(i)
            if (item.mediaId.isBlank()) continue
            var durSec: Int? = null
            if (k == 0) {   // у текущего трека ждём длительность для точного поиска
                var tries = 0
                while (ctrl.duration <= 0 && tries < 15) { delay(100); tries++ }
                if (ctrl.duration > 0) durSec = (ctrl.duration / 1000).toInt()
            }
            LyricsCache.ensure(item.mediaId, item.mediaMetadata.title?.toString(), item.mediaMetadata.artist?.toString(), durSec)
        }
    }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LyricRow(l: LyricLine, i: Int, active: Int, synced: Boolean, big: Boolean, pos: State<Long>, nextMs: Long?, onLong: () -> Unit, onClick: () -> Unit) {
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
        .combinedClickable(onClick = { if (l.timeMs != null) onClick() }, onLongClick = onLong)
        .padding(vertical = if (big) 8.dp else 4.dp)
    val fs = (if (big) 26 else 16).sp
    val lh = (if (big) 32 else 20).sp
    val t = l.text.ifBlank { "♪" }
    if (synced && i == active && l.timeMs != null) {
        val start = l.timeMs
        Text(karaoke(t, start, nextMs ?: (start + 4000), pos.value, WP.text, WP.text3, l.wordTimes),
            mod, fontSize = fs, lineHeight = lh, fontWeight = FontWeight.Bold)
    } else Text(t, mod, color = WP.text, fontSize = fs, lineHeight = lh, fontWeight = FontWeight.Bold)
}

/** Экран текста: строки подсвечиваются по времени, тап по строке перематывает. */
@Composable
fun LyricsScreen(c: MediaController?, np: NowPlaying, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val lyr = rememberLyrics(np.id, np.title, np.artist, c)
    val pos = rememberPosition(c, np.playing, 80)
    val synced = lyr.lines.any { it.timeMs != null }
    val active = rememberActive(lyr.lines, pos).value
    val listState = rememberLazyListState()
    LaunchedEffect(active) { if (active >= 0) listState.animateScrollToItem(active, scrollOffset = -280) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var editIndex by remember { mutableStateOf<Int?>(null) }
    editIndex?.let { i -> if (i in lyr.lines.indices) EditLineDialog(ctx, np.id, lyr.lines, i) { editIndex = null } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = np.id
        if (uri != null && id != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText().removePrefix("\uFEFF")
                    val head = text.trimStart()
                    // JSON со словами (Vosk / GigaAM-скрипт) или обычный .lrc / .txt
                    val parsed = if (head.startsWith("[") && head.contains("\"word\"") || head.startsWith("{"))
                        Transcriber.parseWordsJson(head) else YandexApi.parseLrc(text)
                    if (parsed.isNotEmpty()) { Transcriber.save(ctx, id, parsed); true } else false
                } catch (_: Exception) { false }
            }
            if (ok) Transcriber.version++
            android.widget.Toast.makeText(ctx, if (ok) "Текст загружен" else "Не удалось прочитать файл", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    AppBackground(np.art) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = WP.text) }
                Column(Modifier.weight(1f)) {
                    Text(np.title ?: "", color = WP.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(np.artist ?: "", color = WP.text2, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    if (lyr.source.isNotEmpty()) Text("Источник: ${lyr.source}", color = WP.text3, style = MaterialTheme.typography.labelSmall)
                }
                IconButton({ picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.UploadFile, null, tint = WP.text) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (lyr.lines.isEmpty()) EmptyLyrics(lyr.status, np)
                else LazyColumn(Modifier.fillMaxSize(), state = listState,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 160.dp)) {
                    if (!synced && Style.subsEnabled) item { SyncBanner(np, lyr.lines) }
                    itemsIndexed(lyr.lines) { i, l -> LyricRow(l, i, active, synced, true, pos, lyr.lines.getOrNull(i + 1)?.timeMs, { editIndex = i }) { c?.seekTo(l.timeMs!!) } }
                }
            }
            SeekBar(c, pos)
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                val tintS by animateColorAsState(if (np.shuffle) WP.accent else WP.text, label = "sh")
                val tintR by animateColorAsState(if (np.repeat != 0) WP.accent else WP.text, label = "rp")
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
            Text(fmt(p), color = WP.text2, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(fmt(dur), color = WP.text2, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun EmptyLyrics(status: String, np: NowPlaying) {
    val ctx = LocalContext.current
    val id = np.id
    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(status, color = WP.text2, textAlign = TextAlign.Center)
        if (id != null && !id.startsWith("ym") && Style.subsEnabled) {
            LaunchedEffect(Unit) { Transcriber.refresh(ctx) }
            val p = Transcriber.progress[id]
            when {
                p != null -> {
                    LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
                    Text("Распознавание… ${(p * 100).toInt()}%", color = WP.text2, style = MaterialTheme.typography.bodySmall)
                }
                !(if (Style.asrEngine == 1) Transcriber.gigaReady else Transcriber.modelReady) -> {
                    val giga = Style.asrEngine == 1
                    val busy = if (giga) Transcriber.gigaBusy else Transcriber.modelBusy
                    val prog = if (giga) Transcriber.gigaProgress else Transcriber.modelProgress
                    Button({ if (giga) Transcriber.downloadGiga(ctx) else Transcriber.downloadModel(ctx) }, enabled = !busy) {
                        Text(if (busy) "Скачивание ${(prog * 100).toInt()}%" else if (giga) "Скачать GigaAM (около 285 МБ)" else "Скачать модель (около 45 МБ)")
                    }
                }
                else -> Button({ np.uri?.let { Transcriber.start(ctx, id, it) } }, enabled = np.uri != null) { Text("Распознать текст") }
            }
            Transcriber.errors[id]?.let { Text("Ошибка: $it", color = WP.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Правка строки: время слов сохраняется (пересчитывается, если число слов изменилось). */
@Composable
fun EditLineDialog(ctx: android.content.Context, id: String?, lines: List<LyricLine>, i: Int, onClose: () -> Unit) {
    var text by remember(i) { mutableStateOf(lines[i].text) }
    fun commit(newText: String?) {
        if (id != null) {
            val out = lines.toMutableList()
            val t = newText?.split(" ")?.filter { it.isNotBlank() }?.joinToString(" ") ?: ""
            if (t.isEmpty()) out.removeAt(i) else out[i] = lines[i].copy(text = t, wordTimes = Transcriber.retime(lines[i].wordTimes, t))
            Transcriber.save(ctx, id, out); Transcriber.version++
        }
        onClose()
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("Править строку") },
        text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth()) },
        confirmButton = { TextButton({ commit(text) }) { Text("Сохранить") } },
        dismissButton = {
            Row {
                TextButton({ commit(null) }) { Text("Удалить") }
                TextButton({ if (id != null) Transcriber.clear(ctx, id); onClose() }) { Text("Сбросить всё") }
                TextButton(onClose) { Text("Отмена") }
            }
        })
}

/**
 * Бегущая строка под обложкой: текущая строка песни, слова подсвечиваются по ходу. Длинная строка плавно
 * едет по кругу. Нажатие открывает полный текст. Для текста без таймкодов ничего не показывается.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LyricTicker(lyr: LyricsData, pos: State<Long>, show: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val lines = lyr.lines
    val synced = lines.any { it.timeMs != null }
    val active = rememberActive(lines, pos)
    val noRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
        if (show && synced) androidx.compose.animation.Crossfade(active.value, animationSpec = tween(220), label = "ticker") { idx ->
            val line = lines.getOrNull(idx)
            if (line != null && line.text.isNotBlank()) {
                val start = line.timeMs ?: 0L
                val next = lines.getOrNull(idx + 1)?.timeMs
                Text(karaoke(line.text, start, next ?: (start + 4000), pos.value, WP.text, WP.text3, line.wordTimes),
                    Modifier.fillMaxWidth()
                        .clickable(noRipple, null, onClick = onClick)
                        .basicMarquee(iterations = Int.MAX_VALUE),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Предложение синхронизировать текст без таймкодов с голосом: слова получают точное время. */
@Composable
fun SyncBanner(np: NowPlaying, lines: List<LyricLine>) {
    val ctx = LocalContext.current
    val id = np.id ?: return
    LaunchedEffect(Unit) { Transcriber.refresh(ctx) }
    val p = Transcriber.progress[id]
    val ready = if (Style.asrEngine == 1) Transcriber.gigaReady else Transcriber.modelReady
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("У этого текста нет времени строк", color = WP.text2, style = MaterialTheme.typography.bodySmall)
        when {
            p != null -> {
                LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
                Text("Синхронизация… ${(p * 100).toInt()}%", color = WP.text2, style = MaterialTheme.typography.bodySmall)
            }
            !ready -> Text("Чтобы синхронизировать, скачай модель распознавания в настройках", color = WP.text3, style = MaterialTheme.typography.bodySmall)
            else -> OutlinedButton({ np.uri?.let { Transcriber.startAlign(ctx, id, it, lines) } }, enabled = np.uri != null) { Text("Синхронизировать по голосу") }
        }
        Transcriber.errors[id]?.let { Text("Ошибка: $it", color = WP.error, style = MaterialTheme.typography.bodySmall) }
    }
}
