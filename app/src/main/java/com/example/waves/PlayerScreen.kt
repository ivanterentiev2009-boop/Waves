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
import androidx.compose.material.icons.filled.Equalizer
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
import android.net.Uri
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder

private fun Modifier.fadeEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.12f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn)
    }

@Composable
private fun RoundBtn(icon: ImageVector, size: Int = 44, onClick: () -> Unit) {
    Frosted(CircleShape, Modifier.size(size.dp)) {
        Box(Modifier.fillMaxSize().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(DS.iconM), tint = WP.text)
        }
    }
}

/** Полноэкранный плеер: обложка, текст песни (кнопка T), очередь, скорость, перемотка. */
@Composable
fun PlayerScreen(c: MediaController?, np: NowPlaying, onArtist: () -> Unit = {}, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val curTrack = np.id?.let { Track(it, np.title ?: "", np.artist ?: "", np.uri ?: Uri.EMPTY, np.art) }
    val likedNow = curTrack?.let { Likes.isLiked(it) } ?: false
    var sleepOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) }          // 0 обложка, 1 текст, 2 очередь
    var menu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var eqOpen by remember { mutableStateOf(false) }
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
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = DS.padL)) {
            // верхняя строка: свернуть, очередь, меню
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundBtn(Icons.Default.KeyboardArrowDown) { onClose() }
                Spacer(Modifier.weight(1f))
                Frosted(CircleShape, Modifier.height(44.dp)) {
                    Row(Modifier.clickable { mode = if (mode == 2) 0 else 2 }.padding(horizontal = 16.dp).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MusicNote, null, tint = WP.text2, modifier = Modifier.size(DS.iconS))
                        Spacer(Modifier.width(DS.padS))
                        Text("Очередь", color = WP.text, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(DS.padS))
                        Box(Modifier.size(24.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                            Text("${c?.mediaItemCount ?: 0}", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                RoundBtn(if (likedNow) Icons.Default.Favorite else Icons.Default.FavoriteBorder) {
                    curTrack?.let { t -> AppData.scope.launch { try { Likes.toggle(t) } catch (e: Exception) { appToast("Не получилось: ${e.message}") } } }
                }
                Spacer(Modifier.width(8.dp))
                Box {
                    RoundBtn(Icons.Default.MoreVert) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Загрузить текст из файла") }, { menu = false; picker.launch(arrayOf("*/*")) })
                        DropdownMenuItem({ Text("Сбросить текст трека") }, { menu = false; np.id?.let { Transcriber.clear(ctx, it) } })
                        DropdownMenuItem({ Text("Скачать трек") }, { menu = false; curTrack?.let { t -> AppData.scope.launch { appToast(Downloader.run(ctx, t)) } } })
                        DropdownMenuItem({ Text(if (SleepTimer.endAt > 0) "Таймер сна: ещё ${SleepTimer.leftMin()} мин" else "Таймер сна") }, { menu = false; sleepOpen = true })
                    }
                }
            }

            if (eqOpen) EqualizerSheet { eqOpen = false }

            if (sleepOpen) AlertDialog(onDismissRequest = { sleepOpen = false },
                title = { Text("Таймер сна") },
                text = {
                    Column {
                        listOf(15, 30, 45, 60).forEach { m -> TextButton({ SleepTimer.start(m); sleepOpen = false }) { Text("Через $m минут") } }
                        if (SleepTimer.endAt > 0) TextButton({ SleepTimer.cancel(); sleepOpen = false }) { Text("Выключить таймер") }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton({ sleepOpen = false }) { Text("Закрыть") } })

            // центр: обложка / текст / очередь
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Crossfade(mode, animationSpec = tween(250), label = "pm") { m ->
                    when (m) {
                        1 -> LyricsPane(c, np, lyr, pos) { editIndex = it }
                        2 -> QueuePane(c, np)
                        else -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val side = minOf(maxWidth, maxHeight)
                            Box(Modifier.size(side).clip(DS.shapeL).background(WP.fill)) {
                                if (np.art != null) AsyncImage(np.art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.MusicNote, null, Modifier.size(96.dp), tint = WP.text3)
                                }
                            }
                        }
                    }
                }
            }

            // бегущая строка с текстом песни под обложкой
            LyricTicker(lyr, pos, show = mode == 0, onClick = { mode = 1 })
            Text(np.title ?: "", Modifier.fillMaxWidth(), color = WP.text, style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // имя артиста открывает его страницу
            Box(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = DS.padS), contentAlignment = Alignment.Center) {
                Text(np.artist ?: "", Modifier.clip(DS.shapeS).clickable(onClick = onArtist).padding(horizontal = DS.padM, vertical = 4.dp),
                    color = WP.text2, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            SeekSection(c, np, pos)

            // управление: повтор · назад · играть · вперёд · перемешать; включённые режимы подсвечены кругом
            Row(Modifier.fillMaxWidth().padding(vertical = DS.padS), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                IconButton({ c?.repeatMode = (np.repeat + 1) % 3 },
                    Modifier.size(DS.btn).clip(CircleShape).background(if (np.repeat != 0) WP.selFill else Color.Transparent)) {
                    Icon(if (np.repeat == 1) Icons.Default.RepeatOne else Icons.Default.Repeat, "Повтор", Modifier.size(DS.iconM),
                        tint = if (np.repeat != 0) WP.accentInk else WP.text2)
                }
                IconButton({ c?.seekToPreviousMediaItem() }, Modifier.size(52.dp)) { Icon(Icons.Default.SkipPrevious, "Предыдущий", Modifier.size(DS.iconL), tint = WP.text) }
                Box(Modifier.size(68.dp).clip(CircleShape).background(WP.text)
                    .clickable { if (np.playing) c?.pause() else c?.play() }, contentAlignment = Alignment.Center) {
                    Icon(if (np.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (np.playing) "Пауза" else "Играть", Modifier.size(DS.iconL), tint = WP.bg)
                }
                IconButton({ c?.seekToNextMediaItem() }, Modifier.size(52.dp)) { Icon(Icons.Default.SkipNext, "Следующий", Modifier.size(DS.iconL), tint = WP.text) }
                IconButton({ c?.shuffleModeEnabled = !np.shuffle },
                    Modifier.size(DS.btn).clip(CircleShape).background(if (np.shuffle) WP.selFill else Color.Transparent)) {
                    Icon(Icons.Default.Shuffle, "Перемешать", Modifier.size(DS.iconM), tint = if (np.shuffle) WP.accentInk else WP.text2)
                }
            }

            // нижняя капсула: оверлей, текст песни, скорость
            Box(Modifier.fillMaxWidth().padding(top = DS.padXS, bottom = DS.padS), contentAlignment = Alignment.Center) {
                Frosted(CircleShape, Modifier.height(48.dp)) {
                    Row(Modifier.padding(horizontal = DS.padS).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DS.padXS)) {
                        IconButton(onClick = {
                            if (android.provider.Settings.canDrawOverlays(ctx)) ctx.startForegroundService(android.content.Intent(ctx, OverlayService::class.java))
                            else ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${ctx.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }, modifier = Modifier.size(DS.btn)) { Icon(Icons.Default.PictureInPicture, "Оверлей", Modifier.size(DS.iconM), tint = WP.text) }
                        TextButton({ mode = if (mode == 1) 0 else 1 }, Modifier.size(DS.btn), contentPadding = PaddingValues(0.dp)) {
                            Text("T", color = if (mode == 1) WP.accentInk else WP.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        }
                        IconButton({ eqOpen = true }, Modifier.size(DS.btn)) {
                            Icon(Icons.Default.Equalizer, "Эквалайзер", Modifier.size(DS.iconM), tint = if (Eq.enabled) WP.accentInk else WP.text)
                        }
                        Box {
                            IconButton({ speedMenu = true }, Modifier.size(DS.btn)) {
                                Icon(Icons.Default.Speed, "Скорость", Modifier.size(DS.iconM), tint = if (speed != 1f) WP.accentInk else WP.text)
                            }
                            DropdownMenu(speedMenu, { speedMenu = false }) {
                                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp ->
                                    DropdownMenuItem({ Text("${sp}x" + if (sp == speed) "  ✓" else "") }, { speed = sp; c?.setPlaybackSpeed(sp); speedMenu = false })
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
            Row(Modifier.fillMaxWidth().clip(DS.shapeM)
                .background(if (sel) WP.selFill else Color.Transparent)
                .clickable { c.seekTo(i, 0); c.play() }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(m.artworkUri, 48); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.title?.toString() ?: "", color = if (sel) WP.accentInk else WP.text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.artist?.toString() ?: "", color = WP.text2, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            colors = SliderDefaults.colors(thumbColor = WP.text, activeTrackColor = WP.text, inactiveTrackColor = WP.text.copy(0.22f)))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(p), color = WP.text2, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            NextPill(c, np)
            Spacer(Modifier.weight(1f))
            Text(fmt(dur), color = WP.text2, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NextPill(c: MediaController?, np: NowPlaying) {
    val n = c?.mediaItemCount ?: 0
    val nextIdx = np.index + 1
    if (c == null || nextIdx >= n) return
    val m = c.getMediaItemAt(nextIdx).mediaMetadata
    Frosted(CircleShape, Modifier.height(36.dp)) {
        Row(Modifier.clickable { c.seekToNextMediaItem() }.padding(horizontal = 8.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Cover(m.artworkUri, 24); Spacer(Modifier.width(DS.padS))
            Text("Далее: ${m.title ?: ""}", Modifier.widthIn(max = 130.dp), color = WP.text, style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
