package com.example.waves

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import coil.compose.AsyncImage

private val dim = Color(0xB3FFFFFF)

@Composable
fun Cover(art: android.net.Uri?, size: Int) {
    Box(Modifier.size(size.dp).clip(DS.shapeS).background(DS.fillColor)) {
        AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (art == null) Icon(Icons.Default.MusicNote, null, Modifier.align(Alignment.Center).size(DS.iconM), tint = Color.White)
    }
}

@Composable
fun Controls(c: MediaController?, playing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton({ c?.seekToPreviousMediaItem() }, Modifier.size(DS.btn)) { Icon(Icons.Default.SkipPrevious, null, Modifier.size(DS.iconM), tint = Color.White) }
        FilledIconButton({ if (playing) c?.pause() else c?.play() }, Modifier.size(DS.btn)) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(DS.iconM))
        }
        IconButton({ c?.seekToNextMediaItem() }, Modifier.size(DS.btn)) { Icon(Icons.Default.SkipNext, null, Modifier.size(DS.iconM), tint = Color.White) }
    }
}

@Composable
fun MiniPlayer(c: MediaController?, np: NowPlaying, onOpen: () -> Unit = {}) {
    val pos = rememberPosition(c, np.playing, 100)
    Frosted(DS.shapeM, Modifier.fillMaxWidth().padding(horizontal = DS.padM, vertical = 4.dp), refract = true) {
    Column(Modifier.clickable(onClick = onOpen).padding(start = 6.dp, end = 2.dp, top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(np.art, DS.miniCover); Spacer(Modifier.width(DS.padS))
            Column(Modifier.weight(1f)) {
                Text(np.title ?: "Ничего не играет", color = Color.White, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(np.artist ?: "", color = dim, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Controls(c, np.playing)
        }
        // прогресс рисуется в фазе отрисовки: без перекомпоновки интерфейса
        Box(Modifier.fillMaxWidth().padding(top = 4.dp, start = 2.dp, end = 8.dp).height(2.dp)
            .clip(CircleShape).background(Color.White.copy(0.15f)).drawBehind {
                val d = (c?.duration ?: 0L).coerceAtLeast(0L)
                val f = if (d > 0) (pos.value.toFloat() / d).coerceIn(0f, 1f) else 0f
                drawRect(Color(Style.accent), size = Size(size.width * f, size.height))
            })
    }
    }
}

@Composable
fun Pill(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(CircleShape).background(DS.fillColor).border(DS.stroke, DS.lineColor, CircleShape).padding(3.dp)) {
        options.forEachIndexed { i, t ->
            val bg by animateColorAsState(if (i == selected) Color(Style.accent) else Color.Transparent, tween(250), label = "pb")
            val fg by animateColorAsState(if (i == selected) Color.Black else Color.White, tween(250), label = "pf")
            Box(Modifier.clip(CircleShape).background(bg).clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text(t, color = fg, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun TrackList(tracks: List<Track>, current: Int, onClick: (Int) -> Unit, modifier: Modifier = Modifier,
              currentId: String? = null, footer: (@Composable () -> Unit)? = null) {
    LazyColumn(modifier, contentPadding = PaddingValues(DS.padM, 4.dp, DS.padM, DS.padM), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            val sel = if (currentId != null) t.id == currentId else i == current
            val bg by animateColorAsState(if (sel) Color(Style.accent).copy(0.22f) else Color.Transparent, tween(300), label = "rb")
            Row(Modifier.animateItem().fillMaxWidth().clip(DS.shapeM)
                .background(bg).clickable { onClick(i) }.padding(horizontal = DS.padS, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Cover(t.art, DS.coverRow); Spacer(Modifier.width(DS.padM))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, color = dim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (sel) Icon(Icons.Default.GraphicEq, null, Modifier.size(DS.iconM), tint = Color(Style.accent))
            }
        }
        footer?.let { f -> item { f() } }
    }
}

@Composable
fun Equalizer(modifier: Modifier = Modifier, playing: Boolean) {
    if (!Style.showViz) return
    val barsState = VizBus.bars.collectAsState()
    val bottom = Color(Style.accent); val top = lerp(bottom, Color.White, 0.55f)
    Canvas(modifier) {
        if (!playing) return@Canvas
        val bars = barsState.value
        val n = bars.size
        val gap = size.width / n * 0.4f
        val w = (size.width - gap * (n - 1)) / n
        bars.forEachIndexed { i, v ->
            val h = (size.height * (if (playing) v else 0f)).coerceAtLeast(3.dp.toPx())
            drawRoundRect(Brush.verticalGradient(listOf(top, bottom), startY = size.height - h, endY = size.height),
                Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(w / 2, w / 2))
        }
    }
}

enum class OvMode { Bubble, Compact, Full }

@Composable
fun OverlayPlayer(onZone: (android.graphics.Rect?) -> Unit, onClose: () -> Unit) {
    val c = rememberController()
    val np = rememberNowPlaying(c)
    var mode by remember { mutableStateOf(OvMode.entries[Style.ovStart.coerceIn(0, 2)]) }
    // без анимации размера окна: меняется один раз, плавно проявляется только содержимое
    // у пузырька тянуть можно за всё окно
    LaunchedEffect(mode) { if (mode == OvMode.Bubble) onZone(null) }
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density * Style.ovScale, d.fontScale)) {
        if (mode == OvMode.Bubble) BubbleView(np) { mode = OvMode.Compact }
        else PanelView(c, np, mode == OvMode.Full, { mode = it }, onZone, onClose)
    }
}

@Composable
private fun Modifier.popIn(key: Any): Modifier {
    val t = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { t.animateTo(1f, tween(150)) }
    return this.graphicsLayer {
        val v = t.value
        alpha = 0.4f + 0.6f * v
        val s = 0.94f + 0.06f * v; scaleX = s; scaleY = s
    }
}

@Composable
private fun BubbleView(np: NowPlaying, onTap: () -> Unit) {
    val rot by rememberInfiniteTransition(label = "r").animateFloat(0f, 360f,
        infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "rot")
    OverlaySurface(CircleShape, Style.overlayAlpha,
        Modifier.size(60.dp).popIn(0).pointerInput(Unit) { detectTapGestures { onTap() } }, np.art, 1000f) {
        Box(Modifier.align(Alignment.Center).size(46.dp).rotate(if (np.playing) rot else 0f)
            .clip(CircleShape).background(Color(0x33FFFFFF))) {
            AsyncImage(np.art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun PanelView(c: MediaController?, np: NowPlaying, full: Boolean, setMode: (OvMode) -> Unit, onZone: (android.graphics.Rect?) -> Unit, onClose: () -> Unit) {
    val lyr = rememberLyrics(np.id, np.title, np.artist, c)
    var tab by remember { mutableIntStateOf(0) }
    val pos = rememberPosition(c, np.playing && lyr.lines.isNotEmpty(), if (full && tab == 1) 80 else 250)
    val synced = lyr.lines.any { it.timeMs != null }
    val active = rememberActive(lyr.lines, pos).value
    val w = if (full) 300.dp else 230.dp
    val ls = rememberLazyListState()
    LaunchedEffect(active, tab, full) { if (full && tab == 1 && active >= 0) ls.animateScrollToItem(active, -70) }

    OverlaySurface(DS.shapeM, Style.overlayAlpha, Modifier.width(w).popIn(full), np.art) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth().onGloballyPositioned {
                val b = it.boundsInRoot()
                onZone(android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()))
            }, verticalAlignment = Alignment.CenterVertically) {
                if (Style.ovCover) { Cover(np.art, 36); Spacer(Modifier.width(8.dp)) }
                Text(np.title ?: "—", Modifier.weight(1f).clickable { setMode(if (full) OvMode.Compact else OvMode.Full) },
                    color = Color.White, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!full) IconButton({ if (np.playing) c?.pause() else c?.play() }, Modifier.size(34.dp)) {
                    Icon(if (np.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White)
                } else IconButton({ setMode(OvMode.Compact) }, Modifier.size(32.dp)) { Icon(Icons.Default.UnfoldLess, null, tint = Color.White) }
                IconButton({ setMode(OvMode.Bubble) }, Modifier.size(32.dp)) { Icon(Icons.Default.Remove, null, tint = Color.White) }
                if (full) IconButton(onClose, Modifier.size(32.dp)) { Icon(Icons.Default.Close, null, tint = Color.White) }
            }
            if (!full && Style.ovLyric && active >= 0) {
                AnimatedContent(lyr.lines[active].text,
                    transitionSpec = { (slideInVertically(tween(300)) { it / 2 } + fadeIn(tween(300))) togetherWith
                        (slideOutVertically(tween(200)) { -it / 2 } + fadeOut(tween(200))) }, label = "line") { t ->
                    Text(t.ifBlank { "♪" }, Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp),
                        color = lerp(Color(Style.accent), Color.White, 0.6f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (Style.ovEq) Equalizer(Modifier.fillMaxWidth().height(if (full) 40.dp else 16.dp).padding(top = 6.dp), np.playing)
            if (full) {
                Pill(listOf("Очередь", "Текст"), tab, { tab = it }, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Controls(c, np.playing) }
                tab.let { t ->
                    if (t == 0) {
                        if (c != null) LazyColumn(Modifier.height(200.dp)) {
                            items(c.mediaItemCount) { i ->
                                val sel by animateColorAsState(if (i == np.index) Color(Style.accent).copy(0.25f) else Color.Transparent, label = "q")
                                Text(c.getMediaItemAt(i).mediaMetadata.title?.toString() ?: "", Modifier.fillMaxWidth()
                                    .clip(DS.shapeS).background(sel)
                                    .clickable { c.seekTo(i, 0); c.play() }.padding(10.dp),
                                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else if (lyr.lines.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Text(lyr.status, color = Color(0xB3FFFFFF), fontSize = 13.sp)
                        }
                    } else LazyColumn(Modifier.height(200.dp), state = ls, contentPadding = PaddingValues(vertical = 60.dp, horizontal = 4.dp)) {
                        itemsIndexed(lyr.lines) { i, l -> LyricRow(l, i, active, synced, false, pos, lyr.lines.getOrNull(i + 1)?.timeMs, {}) { c?.seekTo(l.timeMs!!) } }
                    }
                }
            }
        }
    }
}

/**
 * Поисковая строка в стиле приложения: стеклянная «таблетка», значок поиска, акцентная рамка при вводе,
 * кнопка очистки, на клавиатуре кнопка «Найти».
 */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, onSearch: () -> Unit = {}) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val accent = Color(Style.accent)
    val ring by animateColorAsState(if (focused) accent else Color.Transparent, tween(250), label = "sfr")
    val icon by animateColorAsState(if (focused) accent else Color.White.copy(DS.TEXT2), tween(250), label = "sfi")
    Frosted(CircleShape, modifier.fillMaxWidth().height(48.dp)) {
        Row(Modifier.fillMaxSize().border(DS.strokeSel, ring, CircleShape).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, Modifier.size(DS.iconM), tint = icon)
            Spacer(Modifier.width(DS.padM))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, color = Color.White.copy(0.5f), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                androidx.compose.foundation.text.BasicTextField(value, onValueChange, Modifier.fillMaxWidth(), singleLine = true,
                    interactionSource = interaction,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch(); focus.clearFocus() }))
            }
            androidx.compose.animation.AnimatedVisibility(value.isNotEmpty()) {
                IconButton({ onValueChange("") }) { Icon(Icons.Default.Close, "Очистить", Modifier.size(DS.iconM), tint = Color.White.copy(DS.TEXT2)) }
            }
        }
    }
}
