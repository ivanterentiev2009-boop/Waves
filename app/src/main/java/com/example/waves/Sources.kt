package com.example.waves

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

private fun httpGet(url: String, ua: String = "Waves/0.18", headers: Map<String, String> = emptyMap()): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 12000; c.readTimeout = 20000
    c.setRequestProperty("User-Agent", ua)
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    val code = c.responseCode
    val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
    if (code !in 200..299) throw IOException("HTTP $code")
    return body
}

/**
 * SoundCloud: публичные треки, без входа. Использует тот же ключ, что и сайт soundcloud.com, поэтому
 * неофициально: если сайт поменяется, ключ придётся получить заново (приложение делает это само).
 * Платные треки Go+ отдают только 30-секундное превью.
 */
object SoundCloud {
    private var sp: SharedPreferences? = null
    var cid by mutableStateOf("")
        private set
    /** Как получен ключ: «автоматически», «через сайт» или «вручную». */
    var how by mutableStateOf("")
        private set
    val hasKey: Boolean get() = cid.isNotBlank()
    /** Итог последнего запроса: сколько найдено и сколько можно играть. */
    var info by mutableStateOf("")

    fun init(ctx: Context) {
        if (sp != null) return
        val s = ctx.applicationContext.getSharedPreferences("soundcloud", 0); sp = s
        cid = s.getString("cid", "").orEmpty()
        how = s.getString("how", "").orEmpty()
    }

    fun setKey(key: String, source: String) {
        cid = key; how = source
        sp?.edit()?.putString("cid", key)?.putString("how", source)?.apply()
    }

    fun reset() { cid = ""; how = ""; sp?.edit()?.clear()?.apply() }

    /** Ключ из адреса запроса вида ...?client_id=XXXX... */
    fun fromUrl(text: String): String? = Regex("client_id=([A-Za-z0-9]{32})").find(text)?.groupValues?.get(1)

    /** Принимает сам ключ (32 символа) или любую ссылку со строкой client_id=. */
    fun parseKey(text: String): String? =
        fromUrl(text) ?: text.trim().takeIf { Regex("[A-Za-z0-9]{32}").matches(it) }

    /** Блокирующий вызов: найти ключ на сайте. */
    fun refreshAuto(): String = fetchClientId()

    /** Блокирующий вызов: проверить, что ключ работает. Бросает исключение, если нет. */
    fun check() { get("https://api-v2.soundcloud.com/search/tracks?q=a&limit=1&client_id=$cid") }

    private fun fetchClientId(): String {
        val html = httpGet("https://soundcloud.com", BROWSER_UA)
        val scripts = Regex("src=\"(https://a-v2\\.sndcdn\\.com/assets/[^\"]+\\.js)\"").findAll(html).map { it.groupValues[1] }.toList().reversed()
        for (u in scripts) {
            val m = Regex("client_id\\s*[:=]\\s*\"([A-Za-z0-9]{32})\"").find(httpGet(u, BROWSER_UA))
            if (m != null) {
                setKey(m.groupValues[1], "автоматически")
                return cid
            }
        }
        throw IOException("не удалось получить ключ SoundCloud")
    }

    private fun clientId() = cid.ifBlank { fetchClientId() }

    // заголовки, как у сайта: без них API иногда отвечает отказом
    private val apiHeaders = mapOf("Origin" to "https://soundcloud.com", "Referer" to "https://soundcloud.com/",
        "Accept" to "application/json, text/javascript, */*; q=0.01")
    private fun get(url: String) = httpGet(url, BROWSER_UA, apiHeaders)
    private fun isAuthError(e: Exception) = e.message.orEmpty().let { "HTTP 401" in it || "HTTP 403" in it }

    /** Выполняет запрос с ключом. Новый ключ ищем, только если сайт отказал по доступу, иначе показываем настоящую ошибку. */
    private inline fun <T> withKey(block: (String) -> T): T {
        var first: Exception? = null
        if (cid.isNotBlank()) {
            try { return block(cid) } catch (e: Exception) {
                if (!isAuthError(e)) throw e
                first = e
            }
        }
        val rejected = first
        return try { block(fetchClientId()) } catch (e: Exception) {
            throw IOException(if (rejected != null) "ключ отклонён (${rejected.message}), новый найти не удалось" else (e.message ?: "ошибка"))
        }
    }

    fun search(q: String): List<Track> = withKey { key ->
        val body = if (q.isBlank()) {
            try { get("https://api-v2.soundcloud.com/charts?kind=trending&genre=soundcloud%3Agenres%3Aall-music&limit=50&client_id=$key") }
            catch (e: Exception) {
                if (isAuthError(e)) throw e
                get("https://api-v2.soundcloud.com/search/tracks?q=${enc("music")}&limit=40&client_id=$key")   // чарты недоступны: берём обычный поиск
            }
        } else get("https://api-v2.soundcloud.com/search/tracks?q=${enc(q)}&limit=40&client_id=$key")
        val a = JSONObject(body).optJSONArray("collection") ?: JSONArray()
        val out = (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            parse(o.optJSONObject("track") ?: o)
        }.distinctBy { it.id }
        info = if (a.length() > out.size) "Найдено ${a.length()}, можно играть ${out.size}: остальные закрыты правообладателем"
        else ""
        out
    }

    private fun parse(o: JSONObject): Track? {
        val id = o.optLong("id")
        val policy = o.optString("policy")
        if (id == 0L || policy == "BLOCK" || !o.optBoolean("streamable", true)) return null
        val tr = o.optJSONObject("media")?.optJSONArray("transcodings") ?: return null
        // сначала обычный поток; если его нет, берём HLS в mp3 или aac (opus плеер не потянет)
        var url: String? = null
        var hls = false
        for (i in 0 until tr.length()) {
            val t = tr.getJSONObject(i)
            if (t.optJSONObject("format")?.optString("protocol") == "progressive") { url = t.optString("url"); break }
        }
        if (url == null) for (i in 0 until tr.length()) {
            val t = tr.getJSONObject(i)
            val f = t.optJSONObject("format") ?: continue
            val mime = f.optString("mime_type")
            if (f.optString("protocol") == "hls" && ("mpeg" in mime || "mp4a" in mime)) { url = t.optString("url"); hls = true; break }
        }
        val u = Uri.parse(url ?: return null)
        val ta = o.optString("track_authorization")
        val uri = Uri.Builder().scheme("scloud").authority(u.host).path(u.path + if (hls) ".m3u8" else "").apply {
            if (ta.isNotBlank()) appendQueryParameter("ta", ta)
        }.build()
        val title = o.optString("title").ifBlank { "Без названия" } + if (policy == "SNIP") " (превью)" else ""
        val user = o.optJSONObject("user")
        val art = (o.optString("artwork_url").ifBlank { user?.optString("avatar_url").orEmpty() })
            .takeIf { it.startsWith("http") }?.replace("-large", "-t500x500")
        return Track("sc$id", title, user?.optString("username").orEmpty().ifBlank { "SoundCloud" }, uri, art?.let { Uri.parse(it) },
            if (policy == "SNIP") 30_000L else o.optLong("duration"))
    }

    /** Блокирующий вызов: подсказки к началу запроса. */
    fun suggest(q: String): List<String> = withKey { key ->
        val a = JSONObject(get("https://api-v2.soundcloud.com/search/queries?q=${enc(q)}&limit=8&client_id=$key")).optJSONArray("collection") ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it).optString("output") }.filter { it.isNotBlank() }
    }

    /** Блокирующий вызов: номер автора трека (trackNumId: число без префикса «sc»). */
    fun userIdOfTrack(trackNumId: String): Long = withKey { key ->
        JSONObject(get("https://api-v2.soundcloud.com/tracks/$trackNumId?client_id=$key")).getJSONObject("user").getLong("id")
    }

    /** Блокирующий вызов: страница автора с его треками. */
    fun userProfile(userId: Long): SCProfile = withKey { key ->
        val u = JSONObject(get("https://api-v2.soundcloud.com/users/$userId?client_id=$key"))
        val coll = JSONObject(get("https://api-v2.soundcloud.com/users/$userId/tracks?limit=50&client_id=$key")).optJSONArray("collection") ?: JSONArray()
        var tracks = (0 until coll.length()).mapNotNull { parse(coll.getJSONObject(it)) }
        val ids = (0 until coll.length()).map { coll.getJSONObject(it).optLong("id") }.filter { it != 0L }
        if (ids.isNotEmpty() && tracks.size < ids.size / 2) {
            // в списке автора треки пришли неполными: просим их целиком по номерам
            val arr = JSONArray(get("https://api-v2.soundcloud.com/tracks?ids=${ids.take(40).joinToString(",")}&client_id=$key"))
            tracks = (0 until arr.length()).mapNotNull { parse(arr.getJSONObject(it)) }
        }
        SCProfile(
            u.optString("username").ifBlank { u.optString("full_name") },
            u.optString("avatar_url").takeIf { it.startsWith("http") }?.replace("-large", "-t500x500")?.let { Uri.parse(it) },
            u.optInt("followers_count"), u.optInt("track_count"), u.optString("description").trim(),
            tracks.distinctBy { it.id })
    }

    /** Блокирующий вызов из загрузчика плеера: прямая ссылка на поток (временная). */
    fun streamUrl(base: String, ta: String?): String = withKey { key ->
        val extra = if (ta.isNullOrBlank()) "" else "&track_authorization=${enc(ta)}"
        JSONObject(get("$base?client_id=$key$extra")).getString("url")
    }
}

/** Свой сервер с протоколом Subsonic: Navidrome, Airsonic, Gonic, Jellyfin (с плагином) и другие. */
object Subsonic {
    private var sp: SharedPreferences? = null
    var url by mutableStateOf("")
    var user by mutableStateOf("")
    private var pass = ""
    val configured: Boolean get() = url.isNotBlank() && user.isNotBlank()

    fun init(ctx: Context) {
        if (sp != null) return
        val s = ctx.applicationContext.getSharedPreferences("subsonic", 0); sp = s
        url = s.getString("url", "").orEmpty(); user = s.getString("user", "").orEmpty(); pass = s.getString("pass", "").orEmpty()
    }

    fun save(u: String, l: String, p: String) {
        url = norm(u); user = l.trim(); pass = p
        sp?.edit()?.putString("url", url)?.putString("user", user)?.putString("pass", pass)?.apply()
    }

    fun logout() { url = ""; user = ""; pass = ""; sp?.edit()?.clear()?.apply() }

    private fun norm(u: String) = (if ("://" in u) u.trim() else "http://" + u.trim()).trimEnd('/')

    private fun auth(login: String, password: String): String {
        val salt = (1..8).joinToString("") { "%x".format((0..15).random()) }
        val t = MessageDigest.getInstance("MD5").digest((password + salt).toByteArray()).joinToString("") { "%02x".format(it) }
        return "u=${enc(login)}&t=$t&s=$salt&v=1.16.1&c=Waves&f=json"
    }

    private fun call(base: String, login: String, password: String, method: String, extra: String = ""): JSONObject {
        val r = JSONObject(httpGet("$base/rest/$method.view?${auth(login, password)}$extra")).getJSONObject("subsonic-response")
        if (r.optString("status") != "ok") throw IOException(r.optJSONObject("error")?.optString("message") ?: "сервер отклонил запрос")
        return r
    }

    /** Проверка адреса и пароля до сохранения. */
    fun ping(u: String, l: String, p: String) { call(norm(u), l.trim(), p, "ping") }

    fun tracks(q: String): List<Track> {
        val r = if (q.isBlank()) call(url, user, pass, "getRandomSongs", "&size=50").optJSONObject("randomSongs")
        else call(url, user, pass, "search3", "&query=${enc(q)}&songCount=50&albumCount=0&artistCount=0").optJSONObject("searchResult3")
        val a = r?.optJSONArray("song") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val id = o.optString("id")
            val cover = o.optString("coverArt").takeIf { it.isNotBlank() }
            Track("sub$id", o.optString("title").ifBlank { "Без названия" }, o.optString("artist").ifBlank { "Неизвестный исполнитель" },
                Uri.parse("$url/rest/stream.view?id=${enc(id)}&${auth(user, pass)}"),
                cover?.let { Uri.parse("$url/rest/getCoverArt.view?id=${enc(it)}&size=400&${auth(user, pass)}") },
                o.optLong("duration") * 1000)
        }.distinctBy { it.id }
    }
}

// ---------- страница автора SoundCloud ----------

data class SCProfile(val name: String, val avatar: Uri?, val followers: Int, val trackCount: Int, val about: String, val tracks: List<Track>)

object SCState {
    var open by mutableStateOf(false)
    var profile by mutableStateOf<SCProfile?>(null)
    var error by mutableStateOf<String?>(null)
}

/** Открывает страницу автора по номеру трека SoundCloud (без префикса «sc»). */
fun openSoundCloudArtist(scope: kotlinx.coroutines.CoroutineScope, trackNumId: String) {
    SCState.open = true; SCState.profile = null; SCState.error = null
    scope.launch {
        try {
            val uid = withContext(Dispatchers.IO) { SoundCloud.userIdOfTrack(trackNumId) }
            val p = withContext(Dispatchers.IO) { SoundCloud.userProfile(uid) }
            if (SCState.open) SCState.profile = p
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { SCState.error = "Ошибка: ${e.message}" }
    }
}

@Composable
private fun SoundCloudArtistScreen(np: NowPlaying, startPlay: (List<Track>, Int) -> Unit, modifier: Modifier) {
    BackHandler { SCState.open = false }
    val p = SCState.profile
    val err = SCState.error
    Column(modifier) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ SCState.open = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
            Text("SoundCloud", color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodyMedium)
        }
        when {
            err != null -> Text(err, Modifier.padding(16.dp), color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodyMedium)
            p == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> LazyColumn(Modifier.weight(1f)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(120.dp).clip(CircleShape).background(Color(0x22FFFFFF))) {
                            if (p.avatar != null) AsyncImage(p.avatar, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Text(p.name, Modifier.fillMaxWidth().padding(top = 12.dp), color = Color.White, fontSize = 28.sp,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Text("Подписчиков: ${p.followers}, треков: ${p.trackCount}", Modifier.padding(top = 4.dp),
                            color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodyMedium)
                        if (p.about.isNotBlank()) Text(p.about.take(240), Modifier.fillMaxWidth().padding(top = 8.dp),
                            color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        Button({ if (p.tracks.isNotEmpty()) startPlay(p.tracks, 0) }, Modifier.padding(top = 14.dp), enabled = p.tracks.isNotEmpty()) {
                            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Слушать")
                        }
                        if (p.tracks.isEmpty()) Text("Доступных для воспроизведения треков нет", Modifier.padding(top = 8.dp),
                            color = Color(0x80FFFFFF), style = MaterialTheme.typography.bodySmall)
                    }
                }
                itemsIndexed(p.tracks, key = { _, t -> t.id }) { i, t -> TrackRow(t, t.id == np.id) { startPlay(p.tracks, i) } }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

// ---------- состояние и интерфейс ----------

class PaneState {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Track>>(emptyList())
    var status by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)
    var lastQ = ""
}

object SrcState {
    var src by mutableIntStateOf(0)
    private val panes = mutableMapOf<String, PaneState>()
    fun pane(key: String) = panes.getOrPut(key) { PaneState() }
    fun reset(key: String) { panes.remove(key) }
}

private val SOURCE_CHIPS = listOf(Icons.Default.LibraryMusic to "Яндекс", Icons.Default.Cloud to "SoundCloud",
    Icons.Default.Dns to "Сервер", Icons.Default.Link to "Ссылка")

/** Вкладка «Поиск»: переключатель источников музыки. */
@Composable
fun SourcesTab(c: MediaController?, np: NowPlaying, startPlay: (List<Track>, Int) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    remember { Subsonic.init(ctx); SoundCloud.init(ctx) }
    Column(modifier) {
        ChipRow(SOURCE_CHIPS, SrcState.src, { SrcState.src = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        when (SrcState.src) {
            0 -> {
                LaunchedEffect(Unit) { YState.section = 1 }
                YandexTab(c, np, startPlay, Modifier.weight(1f), showTabs = false)
            }
            1 -> if (SCState.open) SoundCloudArtistScreen(np, startPlay, Modifier.weight(1f)) else {
                Text("Платные треки Go+ играют только 30-секундное превью.", Modifier.padding(horizontal = 20.dp),
                    color = Color(0x80FFFFFF), style = MaterialTheme.typography.bodySmall)
                if (SoundCloud.info.isNotBlank()) Text(SoundCloud.info, Modifier.padding(horizontal = 20.dp),
                    color = Color(0x80FFFFFF), style = MaterialTheme.typography.bodySmall)
                TrackPane("sc", "Трек или исполнитель на SoundCloud", "SC", { SoundCloud.suggest(it) }, np, startPlay, Modifier.weight(1f)) { SoundCloud.search(it) }
            }
            2 -> if (Subsonic.configured) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Subsonic.url.substringAfter("://"), Modifier.weight(1f), color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    TextButton({ Subsonic.logout(); SrcState.reset("sub") }) { Text("Отключить") }
                }
                TrackPane("sub", "Трек или исполнитель на сервере", "S", null, np, startPlay, Modifier.weight(1f)) { Subsonic.tracks(it) }
            } else SubsonicSetup(Modifier.weight(1f))
            else -> LinkPane(startPlay, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TrackPane(key: String, hint: String, badge: String, suggest: ((String) -> List<String>)?, np: NowPlaying,
                      startPlay: (List<Track>, Int) -> Unit, modifier: Modifier, search: (String) -> List<Track>) {
    val st = SrcState.pane(key)
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(st.query) {
        val q = st.query.trim()
        if (st.loaded && q == st.lastQ) return@LaunchedEffect
        if (st.loaded) delay(500)
        loading = true; st.status = null
        try {
            val r = withContext(Dispatchers.IO) { search(q) }.distinctBy { it.id }
            st.results = r; st.lastQ = q; st.loaded = true
            if (r.isEmpty()) st.status = "Ничего не найдено"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { st.status = "Ошибка: ${e.message}" }
        loading = false
    }
    Column(modifier) {
        SearchBox(st.query, { st.query = it }, hint, badge, suggest, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp))
        st.status?.let { Text(it, Modifier.padding(16.dp, 8.dp), color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall) }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(st.results, key = { _, t -> t.id }) { i, t -> TrackRow(t, t.id == np.id) { startPlay(st.results, i) } }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SubsonicSetup(modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var u by remember { mutableStateOf("") }
    var l by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Свой сервер", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Text("Подключи Navidrome, Airsonic, Gonic или другой сервер с протоколом Subsonic, и вся твоя музыка с него будет здесь.",
            color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(u, { u = it; err = null }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Адрес сервера") }, placeholder = { Text("192.168.1.10:4533") })
        OutlinedTextField(l, { l = it; err = null }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Логин") })
        OutlinedTextField(p, { p = it; err = null }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Пароль") },
            visualTransformation = PasswordVisualTransformation())
        err?.let { Text(it, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall) }
        Button({
            scope.launch {
                busy = true; err = null
                try {
                    withContext(Dispatchers.IO) { Subsonic.ping(u, l, p) }
                    Subsonic.save(u, l, p)
                } catch (e: Exception) { err = "Не удалось подключиться: ${e.message}" }
                busy = false
            }
        }, enabled = !busy && u.isNotBlank() && l.isNotBlank()) { Text(if (busy) "Проверяю…" else "Подключиться") }
        Text("Логин и пароль хранятся только на этом телефоне.", color = Color(0x80FFFFFF), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LinkPane(startPlay: (List<Track>, Int) -> Unit, modifier: Modifier) {
    var url by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Своя ссылка", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Text("Прямая ссылка на аудиофайл (mp3, aac, ogg, flac) или на поток интернет-радио.",
            color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(url, { url = it; err = null }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("https://…") })
        err?.let { Text(it, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall) }
        Button({
            val u = url.trim()
            if (!(u.startsWith("http://") || u.startsWith("https://"))) err = "Ссылка должна начинаться с http:// или https://"
            else {
                val uri = Uri.parse(u)
                val name = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: uri.host ?: u
                startPlay(listOf(Track("url" + u.hashCode(), name, "Ссылка", uri, null)), 0)
            }
        }, enabled = url.isNotBlank()) { Text("Играть") }
    }
}
