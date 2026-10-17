package com.example.waves

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.sin

/** Недавно играли: хранится на телефоне, последние 30 треков. */
object Recent {
    val list = mutableStateListOf<Track>()
    private var sp: SharedPreferences? = null

    fun init(ctx: android.content.Context) {
        if (sp != null) return
        val s = ctx.applicationContext.getSharedPreferences("recent", 0); sp = s
        try {
            val a = JSONArray(s.getString("list", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                list.add(Track(o.getString("id"), o.getString("t"), o.getString("a"), Uri.parse(o.getString("u")),
                    o.optString("c").takeIf { it.isNotBlank() }?.let { Uri.parse(it) }))
            }
        } catch (_: Exception) { }
    }

    fun add(t: Track) {
        list.removeAll { it.id == t.id }
        list.add(0, t)
        while (list.size > 30) list.removeAt(list.lastIndex)
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("t", it.title).put("a", it.artist).put("u", it.uri.toString()).put("c", it.art?.toString() ?: "")) }
        sp?.edit()?.putString("list", a.toString())?.apply()
    }
}

@Composable
private fun ArtFill(uri: Uri?) {
    if (uri != null) AsyncImage(uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(Style.accent).copy(0.5f), Color(0xFFFF8AD8).copy(0.4f)))),
        contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, Modifier.size(48.dp), tint = Color.White) }
}

/**
 * Главная в стиле современных стриминговых приложений: крупный заголовок, композиция из обложек, большая кнопка,
 * карточка «Недавние», ленты с обложками и плавающая нижняя панель. Графика и значки нарисованы заново.
 */
@Composable
fun StreamingHome(c: MediaController?, np: NowPlaying, device: List<Track>, startPlay: (List<Track>, Int) -> Unit,
                  onSettings: () -> Unit, onLyrics: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Recent.init(ctx) }
    var nav by remember { mutableIntStateOf(0) }
    var recentOpen by remember { mutableStateOf(false) }
    LaunchedEffect(np.id, np.playing) {
        val id = np.id ?: return@LaunchedEffect
        val title = np.title ?: return@LaunchedEffect
        if (!np.playing) return@LaunchedEffect
        val uri = if (id.startsWith("ym")) Uri.parse("ymusic://" + id.removePrefix("ym")) else np.uri
        if (uri != null) Recent.add(Track(id, title, np.artist ?: "", uri, np.art))
    }
    BackHandler(enabled = recentOpen) { recentOpen = false }

    val haze = LocalHaze.current
    // всё, что прокручивается под нижними панелями (обложки, списки), размывается ими
    val src = if (Style.theme == 3 && haze != null) Modifier.hazeSource(haze, zIndex = 1f) else Modifier
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(src).statusBarsPadding()) {
            when {
                recentOpen -> RecentScreen(np, startPlay) { recentOpen = false }
                nav == 0 -> HomeTab(c, np, startPlay) { recentOpen = true }
                nav == 1 -> CollectionTab(np, device, startPlay)
                else -> {
                    LaunchedEffect(Unit) { YState.section = 1 }
                    YandexTab(c, np, startPlay, Modifier.fillMaxSize().padding(bottom = 190.dp), showTabs = false)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
            MiniPlayer(c, np, onLyrics)
            NavBar(nav) { i -> if (i == 3) onSettings() else { nav = i; recentOpen = false } }
        }
    }
}

@Composable
private fun NavBar(sel: Int, onSel: (Int) -> Unit) {
    val items = listOf(Icons.Default.GraphicEq to "Волна", Icons.Default.LibraryMusic to "Коллекция",
        Icons.Default.Search to "Поиск", Icons.Default.Tune to "Настройки")
    Frosted(RoundedCornerShape(32.dp), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
    Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, (icon, label) ->
            val on = i == sel
            val bg by animateColorAsState(if (on) Color(Style.accent) else Color.Transparent, tween(250), label = "nb")
            val fg by animateColorAsState(if (on) Color.Black else Color.White, tween(250), label = "nf")
            Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(bg).clickable { onSel(i) },
                contentAlignment = Alignment.Center) { Icon(icon, label, tint = fg) }
        }
    }
    }
}

@Composable
private fun HomeTab(c: MediaController?, np: NowPlaying, startPlay: (List<Track>, Int) -> Unit, onRecent: () -> Unit) {
    if (YandexApi.token.isBlank()) { YandexTab(c, np, startPlay, Modifier.fillMaxSize(), showTabs = false); return }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(YandexApi.token) {
        if (YState.likes.isEmpty()) try { YState.likes = withContext(Dispatchers.IO) { YandexApi.likes() } } catch (_: Exception) { }
    }
    fun startWave() {
        scope.launch {
            loading = true; err = null
            try {
                val l = withContext(Dispatchers.IO) { YandexApi.wave() }
                if (l.isEmpty()) err = "Волна пока пуста" else { YState.wave = l; startPlay(l, 0); Wave.active = true }
            } catch (e: Exception) { err = "Ошибка: ${e.message}" }
            loading = false
        }
    }
    val cur = np.id ?: ""
    val source = if (YState.wave.isNotEmpty()) YState.wave else YState.likes
    val idx = source.indexOfFirst { it.id == cur }
    val upcoming = (if (Wave.active && idx >= 0) source.drop(idx + 1) else source).take(12)
    val heroArt = if (Wave.active && np.art != null) np.art else source.firstOrNull()?.art
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 200.dp)) {
        item {
            WaveHome(np, heroArt, source.drop(1).map { it.art }, np.playing && Wave.active, loading,
                onToggle = { if (!Wave.active) startWave() else if (np.playing) c?.pause() else c?.play() },
                onOverlay = {
                    if (Settings.canDrawOverlays(ctx)) ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
                    else ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
        }
        err?.let { item { Text(it, Modifier.padding(20.dp, 4.dp), color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall) } }
        item { RecentCard(onRecent) }
        if (upcoming.isNotEmpty()) {
            item { Text(if (YState.wave.isNotEmpty()) "Дальше в волне" else "Из понравившегося", Modifier.padding(start = 20.dp, top = 22.dp, bottom = 10.dp),
                color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(upcoming, key = { it.id }) { t ->
                        WaveCard(t) {
                            val i = source.indexOfFirst { it.id == t.id }
                            if (i >= 0) { startPlay(source, i); if (YState.wave.isNotEmpty()) Wave.active = true }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WaveHome(np: NowPlaying, art: Uri?, extra: List<Uri?>, playing: Boolean, loading: Boolean,
                     onToggle: () -> Unit, onOverlay: () -> Unit) {
    val accent = Color(Style.accent)
    val bob = rememberInfiniteTransition(label = "h").animateFloat(0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "bob")
    val bars = VizBus.bars.collectAsState()
    Box(Modifier.fillMaxWidth().height(450.dp)) {
        // волнистая лента на фоне
        Canvas(Modifier.fillMaxSize()) {
            val path = Path(); val steps = 50
            for (s in 0..steps) {
                val x = size.width * s / steps
                val y = size.height * 0.56f + sin(s * 0.22f + bob.value) * 34.dp.toPx() - (x / size.width) * 150.dp.toPx()
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Color.White.copy(0.09f), style = Stroke(34.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, accent.copy(0.20f), style = Stroke(12.dp.toPx(), cap = StrokeCap.Round))
        }
        Text("Моя\nволна", Modifier.padding(start = 20.dp, top = 12.dp), color = Color.White, fontSize = 76.sp,
            lineHeight = 74.sp, fontWeight = FontWeight.Black)
        IconButton(onOverlay, Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 6.dp)) {
            Icon(Icons.Default.PictureInPicture, "Оверлей", tint = Color.White)
        }
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
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 14.dp).size(104.dp)
            .graphicsLayer {
                val b = bars.value
                val e = if (playing && b.isNotEmpty()) b.average().toFloat() else 0f
                scaleX = 1f + e * 0.18f; scaleY = 1f + e * 0.18f
            }
            .clip(CircleShape).background(accent).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(38.dp), color = Color.Black, strokeWidth = 3.dp)
            else Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(54.dp), tint = Color.Black)
        }
    }
}

@Composable
private fun RecentCard(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).panel(RoundedCornerShape(24.dp))
        .clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(Recent.list.firstOrNull()?.art, 56); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Недавние", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text("${Recent.list.size} треков", color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(0.7f))
    }
}

@Composable
private fun RecentScreen(np: NowPlaying, startPlay: (List<Track>, Int) -> Unit, onBack: () -> Unit) {
    val list = Recent.list.toList()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 200.dp)) {
        item {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
                Text("Недавние", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
        }
        itemsIndexed(list, key = { _, t -> t.id }) { i, t -> TrackRow(t, t.id == np.id) { startPlay(list, i) } }
    }
}

@Composable
private fun CollectionTab(np: NowPlaying, device: List<Track>, startPlay: (List<Track>, Int) -> Unit) {
    var part by remember { mutableIntStateOf(0) }
    LaunchedEffect(YandexApi.token) {
        if (YandexApi.token.isNotBlank() && YState.likes.isEmpty())
            try { YState.likes = withContext(Dispatchers.IO) { YandexApi.likes() } } catch (_: Exception) { }
    }
    val list = if (part == 0) YState.likes else device
    Column(Modifier.fillMaxSize()) {
        Text("Коллекция", Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Pill(listOf("Мне нравится", "На устройстве"), part, { part = it }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = 200.dp)) {
            if (list.isEmpty()) item {
                Text(if (part == 0) "Здесь появятся понравившиеся треки Яндекс Музыки" else "На устройстве не найдено музыки",
                    Modifier.padding(24.dp), color = Color(0xB3FFFFFF))
            }
            itemsIndexed(list, key = { _, t -> t.id }) { i, t -> TrackRow(t, t.id == np.id) { startPlay(list, i) } }
        }
    }
}
