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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.text.style.TextOverflow

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
                val id = o.getString("id")
                if (id.startsWith("radio") || id.startsWith("au")) continue   // удалённые источники: радио и Audius
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
internal fun ArtFill(uri: Uri?) {
    if (uri != null) AsyncImage(uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(WP.accent.copy(0.5f), Color(0xFFFF8AD8).copy(0.4f)))),
        contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, Modifier.size(DS.iconL), tint = WP.text) }
}

/** Какая вкладка нижней панели открыта (0 волна, 1 коллекция, 2 поиск). */
object NavState { var tab by mutableIntStateOf(0) }

/**
 * Главная в стиле современных стриминговых приложений: крупный заголовок, композиция из обложек, большая кнопка,
 * карточка «Недавние», ленты с обложками и плавающая нижняя панель. Графика и значки нарисованы заново.
 */
@Composable
fun StreamingHome(c: MediaController?, np: NowPlaying, device: List<Track>, startPlay: (List<Track>, Int) -> Unit,
                  onSettings: () -> Unit, onLyrics: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Recent.init(ctx) }
    val nav = NavState.tab   // хранится глобально: не сбрасывается, пока открыт полноэкранный плеер
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
    // высота закреплённых снизу панелей измеряется: списки получают точный отступ, ничего не перекрывается
    var barH by remember { mutableStateOf(DS.bottomReserve) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBottomInset provides barH) {
            Box(Modifier.fillMaxSize().then(src).statusBarsPadding()) {
                when {
                    recentOpen -> RecentScreen(np, startPlay) { recentOpen = false }
                    nav == 0 -> HomeTab(c, np, startPlay) { recentOpen = true }
                    nav == 1 -> CollectionScreen(np, device, startPlay)
                    else -> SourcesTab(c, np, startPlay, Modifier.fillMaxSize().padding(bottom = barH))
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, WP.bg.copy(0.94f))))   // контент мягко гаснет под панелями
            .onSizeChanged { barH = with(density) { it.height.toDp() } }) {
            MiniPlayer(c, np, onLyrics)
            NavBar(nav) { i -> if (i == 3) onSettings() else { NavState.tab = i; recentOpen = false } }
        }
    }
}

/** Нижняя навигация: подложка во всю ширину под системной панелью, значок с подписью, активный пункт подсвечен капсулой. */
@Composable
private fun NavBar(sel: Int, onSel: (Int) -> Unit) {
    val items = listOf(Icons.Default.GraphicEq to "Волна", Icons.Default.LibraryMusic to "Коллекция",
        Icons.Default.Search to "Поиск", Icons.Default.Tune to "Настройки")
    if (Style.barStyle == 1) {   // плавающая стеклянная капсула, только значки
        Frosted(CircleShape, Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = DS.padM, vertical = DS.padXS)) {
            Row(Modifier.padding(DS.padXS)) {
                items.forEachIndexed { i, (icon, label) ->
                    val on = i == sel
                    val fg by animateColorAsState(if (on) WP.accentInk else WP.text2, tween(250), label = "cn")
                    Box(Modifier.weight(1f).height(60.dp).clip(CircleShape)
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onSel(i) },
                        contentAlignment = Alignment.Center) {
                        Icon(icon, label, Modifier.size(DS.iconL), tint = fg)
                    }
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().background(WP.bar)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(WP.line))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(DS.navH).padding(horizontal = DS.padS)) {
            items.forEachIndexed { i, (icon, label) ->
                val on = i == sel
                val pill by animateColorAsState(if (on) WP.accent.copy(if (WP.light) 0.30f else 0.22f) else Color.Transparent, tween(250), label = "nb")
                val fg by animateColorAsState(if (on) WP.accentInk else WP.text2, tween(250), label = "nf")
                Column(Modifier.weight(1f).fillMaxHeight()
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onSel(i) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(width = 56.dp, height = 30.dp).clip(CircleShape).background(pill), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(DS.iconM), tint = fg)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(label, color = fg, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun HomeSection(text: String) {
    Text(text, Modifier.padding(start = DS.padL, end = DS.padL, top = DS.padXL, bottom = DS.padM), color = WP.text,
        style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun HomeTab(c: MediaController?, np: NowPlaying, startPlay: (List<Track>, Int) -> Unit, onRecent: () -> Unit) {
    if (YandexApi.token.isBlank()) { YandexTab(c, np, startPlay, Modifier.fillMaxSize().padding(bottom = bottomInset()), showTabs = false); return }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(YandexApi.token) {
        if (YState.likes.isEmpty()) try { YState.likes = withContext(Dispatchers.IO) { YandexApi.likes() } } catch (_: Exception) { }
    }
    val cur = np.id ?: ""
    val source = if (YState.wave.isNotEmpty()) YState.wave else YState.likes
    val idx = source.indexOfFirst { it.id == cur }
    val upcoming = (if (Wave.active && idx >= 0) source.drop(idx + 1) else source).take(12)
    val heroArt = np.art ?: source.firstOrNull()?.art   // слева всегда то, что играет сейчас
    // трек, от которого строятся волны по артисту и по песне: текущий или первый из понравившихся
    val seedLive = np.id?.startsWith("ym") == true
    val seedId: String? = if (seedLive) np.id else source.firstOrNull()?.id
    val seedTitle: String? = if (seedLive) np.title else source.firstOrNull()?.title
    val seedArtist: String? = if (seedLive) np.artist else source.firstOrNull()?.artist
    val seedArt: Uri? = if (seedLive) np.art else source.firstOrNull()?.art
    // плитки «По артисту» и «По песне» получают разные обложки: другой трек этого артиста, затем любые другие из подборки
    val altArts = source.mapNotNull { it.art }.distinct().filter { it != heroArt }
    val artistTileArt: Uri? = source.firstOrNull { it.artist == seedArtist && it.art != null && it.art != heroArt }?.art ?: altArts.getOrNull(0) ?: seedArt
    val songTileArt: Uri? = altArts.firstOrNull { it != artistTileArt } ?: artistTileArt
    fun loadWave(resolve: suspend () -> String) {
        scope.launch {
            loading = true; err = null
            try {
                val station = resolve()
                val l = WaveMix.start(ctx, station, np.id?.takeIf { it.startsWith("sc") } ?: seedId)
                if (l.isEmpty()) err = "Волна пока пуста" else { Wave.station = station; YState.wave = l; startPlay(l, 0); Wave.active = true }
            } catch (e: Exception) { err = "Ошибка: ${e.message}" }
            loading = false
        }
    }
    fun startStation(kind: String) {
        val sid = seedId?.removePrefix("ym")
        if (kind != "wave" && sid == null) { err = "Сначала включи трек Яндекс Музыки"; return }
        loadWave {
            when (kind) {
                "artist" -> "artist:" + (withContext(Dispatchers.IO) { YandexApi.trackArtistId(sid.orEmpty()) }
                    ?: throw IllegalStateException("не удалось найти исполнителя"))
                "song" -> "track:" + sid.orEmpty()
                else -> "user:onyourwave"
            }
        }
    }
    fun shake() = loadWave { Wave.station }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset())) {
        item {
            WaveHero(np, heroArt, listOf(artistTileArt, songTileArt), np.playing && Wave.active, loading,
                onToggle = { if (!Wave.active) startStation("wave") else if (np.playing) c?.pause() else c?.play() },
                artistName = seedArtist, songName = seedTitle,
                activeKind = if (!Wave.active) null else when {
                    Wave.station.startsWith("artist:") -> "artist"
                    Wave.station.startsWith("track:") -> "song"
                    else -> "wave"
                },
                onStation = { startStation(it) },
                onShake = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); shake() },
                onOverlay = {
                    if (Settings.canDrawOverlays(ctx)) ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
                    else ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
        }
        item { WaveSourceChips(Modifier.padding(horizontal = DS.padL, vertical = DS.padS)) }
        err?.let { item { Text(it, Modifier.padding(DS.padL, 4.dp), color = WP.error, style = MaterialTheme.typography.bodySmall) } }
        item { RecentCard(onRecent) }
        if (Recent.list.isNotEmpty()) {
            item { HomeSection("Вы слушали") }
            item {
                val rl = Recent.list.take(15)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(rl, key = { _, t -> t.id }) { i, t -> WaveCard(t) { startPlay(rl, i) } }
                }
            }
        }
        if (upcoming.isNotEmpty()) {
            item { HomeSection(if (YState.wave.isNotEmpty()) "Дальше в волне" else "Из понравившегося") }
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
private fun RecentCard(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = DS.padL, vertical = DS.padS).panel(DS.shapeM)
        .clickable(onClick = onClick).padding(DS.padS), verticalAlignment = Alignment.CenterVertically) {
        Cover(Recent.list.firstOrNull()?.art, DS.coverRow); Spacer(Modifier.width(DS.padM))
        Column(Modifier.weight(1f)) {
            Text("Недавние", color = WP.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text("${Recent.list.size} треков", color = WP.text2, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Default.ChevronRight, null, Modifier.size(DS.iconM), tint = WP.text2)
    }
}

@Composable
private fun RecentScreen(np: NowPlaying, startPlay: (List<Track>, Int) -> Unit, onBack: () -> Unit) {
    val list = Recent.list.toList()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset())) {
        item {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = WP.text) }
                Text("Недавние", color = WP.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
        }
        itemsIndexed(list, key = { _, t -> t.id }) { i, t -> TrackRow(t, t.id == np.id) { startPlay(list, i) } }
    }
}
