package com.example.waves

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun Modifier.fadeEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.12f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn)
    }

@Composable
private fun RoundBtn(icon: ImageVector, size: Int = 52, onClick: () -> Unit) {
    Frosted(CircleShape, Modifier.size(size.dp)) {
        Box(Modifier.fillMaxSize().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White)
        }
    }
}

/** Полноэкранный плеер: обложка, текст песни (кнопка T), очередь, скорость, перемотка. */
@Composable
fun PlayerScreen(c: MediaController?, np: NowPlaying, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) }          // 0 обложка, 1 текст, 2 очередь
    var menu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    val lyr = rememberLyrics(np.id, np.title, np.artist, c)
    val pos = rememberPosition(c, np.playing, 80)
    val accent = Color(Style.accent)

    editIndex?.let { i -> if (i in lyr.lines.indices) EditLineDialog(ctx, np.id, lyr.lines, i) { editIndex = null } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = np.id
        if (uri != null && id != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText().removePrefix("\uFEFF")
                    val head = text.trimStart()
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
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            // верхняя строка: свернуть, очередь, меню
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundBtn(Icons.Default.KeyboardArrowDown) { onClose() }
                Spacer(Modifier.weight(1f))
                Frosted(RoundedCornerShape(50), Modifier.height(52.dp)) {
                    Row(Modifier.clickable { mode = if (mode == 2) 0 else 2 }.padding(horizontal = 16.dp).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(0.8f), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Очередь", color = Color.White, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.size(28.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                            Text("${c?.mediaItemCount ?: 0}", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Box {
                    RoundBtn(Icons.Default.MoreVert) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Загрузить текст из файла") }, { menu = false; picker.launch(arrayOf("*/*")) })
                        DropdownMenuItem({ Text("Сбросить текст трека") }, { menu = false; np.id?.let { Transcriber.clear(ctx, it) } })
                    }
                }
            }

            // центр: обложка / текст / очередь
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Crossfade(mode, animationSpec = tween(250), label = "pm") { m ->
                    when (m) {
                        1 -> LyricsPane(c, np, lyr, pos) { editIndex = it }
                        2 -> QueuePane(c, np)
                        else -> Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(40.dp)).background(Color(0x22FFFFFF))) {
                            if (np.art != null) AsyncImage(np.art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.MusicNote, null, Modifier.size(96.dp), tint = Color.White.copy(0.6f))
                            }
                        }
                    }
                }
            }

            Text(np.title ?: "", Modifier.fillMaxWidth(), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(np.artist ?: "", Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp), color = Color.White.copy(0.7f),
                fontSize = 20.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)

            SeekSection(c, np, pos)

            // панель управления
            Frosted(RoundedCornerShape(36.dp), Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ c?.repeatMode = (np.repeat + 1) % 3 }) {
                        Icon(if (np.repeat == 1) Icons.Default.RepeatOne else Icons.Default.Repeat, null,
                            tint = if (np.repeat != 0) accent else Color.White.copy(0.7f))
                    }
                    IconButton({ c?.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, null, Modifier.size(34.dp), tint = Color.White) }
                    Box(Modifier.size(86.dp).clip(CircleShape).background(Color.White)
                        .clickable { if (np.playing) c?.pause() else c?.play() }, contentAlignment = Alignment.Center) {
                        Icon(if (np.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(46.dp), tint = Color.Black)
                    }
                    IconButton({ c?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, null, Modifier.size(34.dp), tint = Color.White) }
                    IconButton({ c?.shuffleModeEnabled = !np.shuffle }) {
                        Icon(Icons.Default.Shuffle, null, tint = if (np.shuffle) accent else Color.White.copy(0.7f))
                    }
                }
            }

            // нижние капсулы
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Frosted(RoundedCornerShape(30.dp), Modifier.height(56.dp)) {
                    Row(Modifier.padding(horizontal = 10.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({
                            if (android.provider.Settings.canDrawOverlays(ctx)) ctx.startForegroundService(android.content.Intent(ctx, OverlayService::class.java))
                            else ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${ctx.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Icon(Icons.Default.PictureInPicture, null, tint = Color.White) }
                    }
                }
                Frosted(RoundedCornerShape(30.dp), Modifier.height(56.dp)) {
                    Row(Modifier.padding(horizontal = 10.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ mode = if (mode == 1) 0 else 1 }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Text("T", color = if (mode == 1) accent else Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                        }
                        Box {
                            IconButton({ speedMenu = true }) {
                                Icon(Icons.Default.Speed, null, tint = if (speed != 1f) accent else Color.White)
                            }
                            DropdownMenu(speedMenu, { speedMenu = false }) {
                                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                                    DropdownMenuItem({ Text("${s}x" + if (s == speed) "  ✓" else "") }, { speed = s; c?.setPlaybackSpeed(s); speedMenu = false })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsPane(c: MediaController?, np: NowPlaying, lyr: LyricsData, pos: State<Long>, onEdit: (Int) -> Unit) {
    val synced = lyr.lines.any { it.timeMs != null }
    val active = rememberActive(lyr.lines, pos).value
    val listState = rememberLazyListState()
    LaunchedEffect(active) { if (active >= 0) listState.animateScrollToItem(active, scrollOffset = -140) }
    Box(Modifier.fillMaxSize().fadeEdges(), contentAlignment = Alignment.Center) {
        if (lyr.lines.isEmpty()) EmptyLyrics(lyr.status, np)
        else LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 120.dp)) {
            itemsIndexed(lyr.lines) { i, l ->
                LyricRow(l, i, active, synced, true, pos, lyr.lines.getOrNull(i + 1)?.timeMs, { onEdit(i) }) { c?.seekTo(l.timeMs!!) }
            }
        }
    }
}

@Composable
private fun QueuePane(c: MediaController?, np: NowPlaying) {
    val n = c?.mediaItemCount ?: 0
    LazyColumn(Modifier.fillMaxSize().fadeEdges(), contentPadding = PaddingValues(vertical = 24.dp)) {
        items(n) { i ->
            val m = c!!.getMediaItemAt(i).mediaMetadata
            val sel = i == np.index
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(if (sel) Color(Style.accent).copy(0.22f) else Color.Transparent)
                .clickable { c.seekTo(i, 0); c.play() }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(m.artworkUri, 48); Spacer(Modifier.width(12.dp))
                Column {
                    Text(m.title?.toString() ?: "", color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.artist?.toString() ?: "", color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SeekSection(c: MediaController?, np: NowPlaying, pos: State<Long>) {
    val p = pos.value
    val dur = (c?.duration ?: 0L).coerceAtLeast(0L)
    Column(Modifier.fillMaxWidth()) {
        Slider(if (dur > 0) (p.toFloat() / dur).coerceIn(0f, 1f) else 0f, { c?.seekTo((it * dur).toLong()) },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(0.25f)))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(p), color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            NextPill(c, np)
            Spacer(Modifier.weight(1f))
            Text(fmt(dur), color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NextPill(c: MediaController?, np: NowPlaying) {
    val n = c?.mediaItemCount ?: 0
    val nextIdx = np.index + 1
    if (c == null || nextIdx >= n) return
    val m = c.getMediaItemAt(nextIdx).mediaMetadata
    Frosted(RoundedCornerShape(50), Modifier.height(42.dp)) {
        Row(Modifier.clickable { c.seekToNextMediaItem() }.padding(horizontal = 8.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Cover(m.artworkUri, 28); Spacer(Modifier.width(8.dp))
            Text("Далее: ${m.title ?: ""}", Modifier.widthIn(max = 170.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
