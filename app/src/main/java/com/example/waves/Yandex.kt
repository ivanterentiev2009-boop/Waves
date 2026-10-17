package com.example.waves

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Включена ли «Моя волна»: пока да, очередь сама пополняется новыми треками. */
object Wave { var active by mutableStateOf(false) }

/** Состояние раздела Яндекса живёт здесь, чтобы не сбрасываться при переключении вкладок. */
object YState {
    var section by mutableIntStateOf(0)          // 0 волна, 1 поиск, 2 мне нравится
    var wave by mutableStateOf<List<Track>>(emptyList())
    var likes by mutableStateOf<List<Track>>(emptyList())
    var results by mutableStateOf<List<Track>>(emptyList())
    var query by mutableStateOf("")
    var lastQ by mutableStateOf("")
    var page by mutableIntStateOf(0)
    var more by mutableStateOf(false)
    var artists by mutableStateOf<List<ArtistItem>>(emptyList())
    var profileId by mutableStateOf<String?>(null)
    var profile by mutableStateOf<ArtistProfile?>(null)
    var profileError by mutableStateOf<String?>(null)
}

/** Подгружает следующие треки волны, когда в очереди остаётся три и меньше. */
@Composable
fun WaveFeeder(c: MediaController?, np: NowPlaying) {
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(np.index, Wave.active, c) {
        val ctl = c ?: return@LaunchedEffect
        if (!Wave.active || busy || ctl.mediaItemCount == 0) return@LaunchedEffect
        if (ctl.mediaItemCount - ctl.currentMediaItemIndex <= 3) {
            busy = true
            try {
                val last = ctl.getMediaItemAt(ctl.mediaItemCount - 1).mediaId.removePrefix("ym")
                val more = withContext(Dispatchers.IO) { YandexApi.wave(last) }
                val have = (0 until ctl.mediaItemCount).map { ctl.getMediaItemAt(it).mediaId }.toSet()
                val fresh = more.filter { it.id !in have }
                if (fresh.isNotEmpty()) { ctl.addMediaItems(fresh.map { it.toMediaItem() }); YState.wave = YState.wave + fresh }
            } catch (_: Exception) { }
            busy = false
        }
    }
}

@Composable
fun YandexTab(c: MediaController?, np: NowPlaying, onPlay: (List<Track>, Int) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var showLogin by remember { mutableStateOf(false) }
    val cur = np.id ?: ""

    fun launchIO(block: suspend () -> Unit) = scope.launch {
        loading = true; status = null
        try { block() } catch (e: Exception) { status = "Ошибка: ${e.message}" }
        loading = false
    }
    fun loadLikes() = launchIO {
        YState.likes = withContext(Dispatchers.IO) { YandexApi.likes() }
        if (YState.likes.isEmpty()) status = "Пока нет понравившихся треков"
    }
    fun search(reset: Boolean) = launchIO {
        val q = YState.query.trim()
        YState.lastQ = q
        if (q.length < 2) { YState.results = emptyList(); YState.artists = emptyList(); YState.more = false; return@launchIO }
        val page = if (reset) 0 else YState.page + 1
        if (reset) YState.artists = withContext(Dispatchers.IO) { try { YandexApi.searchArtists(q) } catch (_: Exception) { emptyList() } }
        val r = withContext(Dispatchers.IO) { YandexApi.search(q, page) }
        YState.results = if (reset) r else YState.results + r.filter { n -> YState.results.none { it.id == n.id } }
        YState.page = page; YState.more = r.size >= 10
        if (YState.results.isEmpty() && YState.artists.isEmpty()) status = "Ничего не найдено"
    }
    fun startWave() = launchIO {
        val l = withContext(Dispatchers.IO) { YandexApi.wave() }
        if (l.isEmpty()) status = "Волна пока пуста"
        else { YState.wave = l; onPlay(l, 0); Wave.active = true }
    }
    fun openArtist(id: String) {
        YState.profileId = id; YState.profile = null; YState.profileError = null
        scope.launch {
            try {
                val p = withContext(Dispatchers.IO) { YandexApi.artistProfile(id) }
                if (YState.profileId == id) YState.profile = p
            } catch (e: Exception) { if (YState.profileId == id) YState.profileError = "Ошибка: ${e.message}" }
        }
    }
    LaunchedEffect(YandexApi.token) { if (YandexApi.token.isNotBlank() && YState.likes.isEmpty()) loadLikes() }
    // поиск по мере ввода, с небольшой задержкой
    LaunchedEffect(YState.query, YState.section) {
        if (YState.section == 1 && YandexApi.token.isNotBlank() && YState.query.trim() != YState.lastQ) { delay(600); search(true) }
    }

    if (showLogin) LoginDialog(
        onToken = { url -> YandexApi.saveToken(url); showLogin = false },
        onClose = { showLogin = false })

    Column(modifier) {
        if (YandexApi.token.isBlank()) {
            Column(Modifier.padding(16.dp).fillMaxWidth().panel(RoundedCornerShape(Style.corner.dp), refract = true).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Вход в Яндекс Музыку", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("Нажми «Войти через Яндекс», войди в аккаунт и разреши доступ: токен подхватится сам. Если не получилось, вставь адрес страницы или токен вручную ниже.",
                    color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodySmall)
                FilledTonalButton({ showLogin = true }) { Text("Войти через Яндекс") }
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Адрес или токен") })
                Button({ if (input.isNotBlank()) YandexApi.saveToken(input) }, enabled = input.isNotBlank()) { Text("Войти") }
            }
        } else if (YState.profileId != null) {
            ArtistScreen(cur, onBack = { YState.profileId = null; YState.profile = null },
                onPlay = onPlay, onOpenArtist = { openArtist(it) },
                onAlbum = { al ->
                    launchIO {
                        val tr = withContext(Dispatchers.IO) { YandexApi.album(al.id) }
                        if (tr.isNotEmpty()) onPlay(tr, 0)
                    }
                },
                modifier = Modifier.weight(1f))
        } else {
            Pill(listOf("Моя волна", "Поиск", "Мне нравится"), YState.section,
                { YState.section = it; status = null; if (it == 2 && YState.likes.isEmpty()) loadLikes() },
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp, 8.dp))
            status?.let { Text(it, Modifier.padding(16.dp, 4.dp), color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall) }
            when (YState.section) {
                0 -> {
                    val idx = YState.wave.indexOfFirst { it.id == cur }
                    val upcoming = (if (Wave.active && idx >= 0) YState.wave.drop(idx + 1) else YState.wave).take(12)
                    fun playAt(track: Track) {
                        val i = YState.wave.indexOfFirst { it.id == track.id }
                        if (i >= 0) { onPlay(YState.wave, i); Wave.active = true }
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        item {
                            WaveHero(np, Wave.active, loading,
                                onToggle = { if (!Wave.active) startWave() else if (np.playing) c?.pause() else c?.play() },
                                onStop = { Wave.active = false })
                        }
                        if (upcoming.isNotEmpty()) {
                            item { Text("Дальше в волне", Modifier.padding(start = 20.dp, top = 8.dp, bottom = 10.dp), color = Color.White, style = MaterialTheme.typography.titleMedium) }
                            item {
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(upcoming, key = { it.id }) { tr -> WaveCard(tr) { playAt(tr) } }
                                }
                            }
                            item { Text("Все треки волны", Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp), color = Color.White, style = MaterialTheme.typography.titleMedium) }
                            itemsIndexed(YState.wave, key = { _, tr -> tr.id }) { _, tr -> TrackRow(tr, tr.id == cur) { playAt(tr) } }
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
                1 -> {
                    OutlinedTextField(YState.query, { YState.query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
                        placeholder = { Text("Трек или исполнитель") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { if (YState.query.isNotEmpty()) IconButton({ YState.query = "" }) { Icon(Icons.Default.Close, null) } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search(true) }))
                    LazyColumn(Modifier.weight(1f)) {
                        if (YState.artists.isNotEmpty()) {
                            item { Text("Исполнители", Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp), color = Color.White, style = MaterialTheme.typography.titleMedium) }
                            item {
                                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(YState.artists, key = { it.id }) { a -> ArtistChip(a) { openArtist(a.id) } }
                                }
                            }
                            if (YState.results.isNotEmpty()) item { Text("Треки", Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp), color = Color.White, style = MaterialTheme.typography.titleMedium) }
                        }
                        itemsIndexed(YState.results, key = { _, t -> t.id }) { i, t -> TrackRow(t, t.id == cur) { onPlay(YState.results, i) } }
                        if (YState.more) item { TextButton({ search(false) }, Modifier.fillMaxWidth()) { Text("Показать ещё") } }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
                else -> {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Мне нравится", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleMedium)
                        IconButton({ loadLikes() }) { Icon(Icons.Default.Refresh, null, tint = Color.White) }
                        IconButton({ YandexApi.logout(); YState.likes = emptyList(); YState.results = emptyList(); YState.wave = emptyList(); Wave.active = false }) {
                            Icon(Icons.Default.Logout, null, tint = Color.White)
                        }
                    }
                    TrackList(YState.likes, -1, { i -> onPlay(YState.likes, i) }, Modifier.weight(1f), currentId = cur)
                }
            }
        }
    }
}

@Composable
private fun LoginDialog(onToken: (String) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = object : WebViewClient() {
                        private var done = false
                        private fun check(url: String?): Boolean {
                            if (!done && url != null && url.contains("access_token=")) { done = true; onToken(url); return true }
                            return false
                        }
                        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = check(r.url.toString())
                        override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) { check(url) }
                        override fun onPageStarted(v: WebView, url: String?, f: Bitmap?) { check(url) }
                    }
                    loadUrl(YandexApi.AUTH_URL)
                }
            })
            IconButton(onClose, Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
                Icon(Icons.Default.Close, null, tint = Color.White)
            }
        }
    }
}
