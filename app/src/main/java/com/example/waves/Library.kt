package com.example.waves

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.launch

/** Общие вещи приложения: контекст и фоновая область, которая не отменяется при закрытии экранов. */
object AppData {
    lateinit var app: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun init(c: Context) {
        app = c.applicationContext
        Likes.init()
        Playlists.init()
    }
}

fun appToast(msg: String) = Toast.makeText(AppData.app, msg, Toast.LENGTH_SHORT).show()

fun Track.toJson(): JSONObject = JSONObject().put("id", id).put("t", title).put("a", artist)
    .put("u", uri.toString()).put("c", art?.toString() ?: "").put("d", durationMs)

fun trackFromJson(o: JSONObject): Track = Track(o.getString("id"), o.getString("t"), o.getString("a"),
    Uri.parse(o.getString("u")), o.optString("c").takeIf { it.isNotBlank() }?.let { Uri.parse(it) }, o.optLong("d"))

// ---------- «Мне нравится» ----------

/** Лайки: для треков Яндекса отправляются в аккаунт, для остальных хранятся в телефоне. */
object Likes {
    val local = mutableStateListOf<Track>()
    private var sp: SharedPreferences? = null

    fun init() {
        if (sp != null) return
        val s = AppData.app.getSharedPreferences("likes", 0); sp = s
        try {
            val a = JSONArray(s.getString("list", "[]"))
            for (i in 0 until a.length()) local.add(trackFromJson(a.getJSONObject(i)))
        } catch (_: Exception) { }
    }

    fun isLiked(t: Track): Boolean =
        if (t.id.startsWith("ym")) YState.likes.any { it.id == t.id } else local.any { it.id == t.id }

    /** Всё, что отмечено: из Яндекса и с других источников. */
    fun all(): List<Track> = (YState.likes + local.toList()).distinctBy { it.id }

    /** Бросает исключение, если Яндекс не принял запрос (например, нет входа). */
    suspend fun toggle(t: Track) {
        val want = !isLiked(t)
        if (t.id.startsWith("ym")) {
            withContext(Dispatchers.IO) { YandexApi.setLike(t.id.removePrefix("ym"), want) }
            YState.likes = if (want) listOf(t) + YState.likes.filter { it.id != t.id } else YState.likes.filter { it.id != t.id }
        } else {
            if (want) local.add(0, t) else local.removeAll { it.id == t.id }
            sp?.edit()?.putString("list", JSONArray().also { a -> local.forEach { a.put(it.toJson()) } }.toString())?.apply()
        }
    }
}

// ---------- свои плейлисты ----------

class Playlist(val id: String, var name: String, val tracks: SnapshotStateList<Track>)

object Playlists {
    val all = mutableStateListOf<Playlist>()
    private var sp: SharedPreferences? = null

    fun init() {
        if (sp != null) return
        val s = AppData.app.getSharedPreferences("playlists", 0); sp = s
        try {
            val a = JSONArray(s.getString("list", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val ts = mutableStateListOf<Track>()
                val ta = o.getJSONArray("t")
                for (j in 0 until ta.length()) ts.add(trackFromJson(ta.getJSONObject(j)))
                all.add(Playlist(o.getString("id"), o.getString("n"), ts))
            }
        } catch (_: Exception) { }
    }

    fun create(name: String): Playlist {
        val p = Playlist(System.currentTimeMillis().toString(), name.trim().ifBlank { "Плейлист" }, mutableStateListOf())
        all.add(0, p); save()
        return p
    }

    fun delete(id: String) { all.removeAll { it.id == id }; save() }

    fun addTrack(id: String, t: Track): Boolean {
        val p = all.find { it.id == id } ?: return false
        if (p.tracks.any { it.id == t.id }) return false
        p.tracks.add(t); save()
        return true
    }

    fun removeTrack(id: String, t: Track) {
        all.find { it.id == id }?.tracks?.removeAll { it.id == t.id }
        save()
    }

    private fun save() {
        val a = JSONArray()
        all.forEach { p ->
            a.put(JSONObject().put("id", p.id).put("n", p.name)
                .put("t", JSONArray().also { arr -> p.tracks.forEach { arr.put(it.toJson()) } }))
        }
        sp?.edit()?.putString("list", a.toString())?.apply()
    }
}

// ---------- таймер сна ----------

object SleepTimer {
    var endAt by mutableLongStateOf(0L)

    fun start(minutes: Int) { endAt = System.currentTimeMillis() + minutes * 60_000L }
    fun cancel() { endAt = 0L }
    fun leftMin(): Int =
        if (endAt == 0L) 0 else ((endAt - System.currentTimeMillis() + 59_999L) / 60_000L).toInt().coerceAtLeast(0)
}

/** Ставится в корне: когда время выходит, пауза. Работает, пока открыто приложение. */
@Composable
fun SleepTimerEffect(c: androidx.media3.session.MediaController?) {
    LaunchedEffect(SleepTimer.endAt) {
        val e = SleepTimer.endAt
        if (e == 0L) return@LaunchedEffect
        delay((e - System.currentTimeMillis()).coerceAtLeast(0L))
        if (SleepTimer.endAt == e) { c?.pause(); SleepTimer.endAt = 0L }
    }
}

// ---------- скачивание ----------

object Downloader {
    /** Возвращает текст для пользователя: куда сохранено или почему нельзя. */
    suspend fun run(ctx: Context, t: Track): String = withContext(Dispatchers.IO) {
        try {
            if (t.uri.scheme == "content" || t.uri.scheme == "file") return@withContext "Этот трек уже на телефоне"
            if (t.uri.scheme == "scloud" && t.uri.path.orEmpty().endsWith(".m3u8"))
                return@withContext "Этот трек идёт только потоком, его нельзя скачать"
            val src = when {
                t.id.startsWith("ym") -> YandexApi.streamUrl(t.id.removePrefix("ym"))
                t.uri.scheme == "scloud" -> SoundCloud.streamUrl("https://" + t.uri.host + t.uri.path.orEmpty(), t.uri.getQueryParameter("ta"))
                t.uri.scheme == "http" || t.uri.scheme == "https" -> t.uri.toString()
                else -> return@withContext "Этот источник нельзя скачать"
            }
            val conn = URL(src).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "Waves/0.18")
            val name = "${t.artist} - ${t.title}".replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120) + ".mp3"
            conn.inputStream.use { input ->
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Waves")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                        ?: throw IllegalStateException("не удалось создать файл")
                    ctx.contentResolver.openOutputStream(uri)?.use { input.copyTo(it) }
                    cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, cv, null, null)
                    "Сохранено в «Загрузки/Waves»"
                } else {
                    val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Waves").apply { mkdirs() }
                    File(dir, name).outputStream().use { input.copyTo(it) }
                    "Сохранено: ${dir.path}"
                }
            }
        } catch (e: Exception) {
            "Не скачалось: ${e.message}"
        }
    }
}

// ---------- кэш текстов на диске ----------

object LyricsDisk {
    private fun file(id: String): File {
        val dir = File(AppData.app.filesDir, "lyrics_cache").apply { mkdirs() }
        return File(dir, id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
    }

    fun load(id: String): Pair<String, List<LyricLine>>? {
        val f = file(id)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val a = o.getJSONArray("l")
            val lines = (0 until a.length()).map { i ->
                val x = a.getJSONObject(i)
                val w = x.getJSONArray("w")
                LyricLine(if (x.isNull("t")) null else x.getLong("t"), x.getString("x"),
                    (0 until w.length()).map { k -> w.getJSONArray(k).let { it.getLong(0) to it.getLong(1) } }.ifEmpty { null })
            }
            o.optString("src") to lines
        } catch (_: Exception) { null }
    }

    fun save(id: String, src: String, lines: List<LyricLine>) {
        val arr = JSONArray()
        lines.forEach { l ->
            val w = JSONArray()
            l.wordTimes?.forEach { w.put(JSONArray().put(it.first).put(it.second)) }
            arr.put(JSONObject().put("t", l.timeMs ?: JSONObject.NULL).put("x", l.text).put("w", w))
        }
        try { file(id).writeText(JSONObject().put("src", src).put("l", arr).toString()) } catch (_: Exception) { }
    }

    fun clear() { File(AppData.app.filesDir, "lyrics_cache").deleteRecursively() }
}

// ---------- меню трека (нажать и держать) ----------

@Composable
private fun SheetItem(icon: ImageVector?, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = WP.text); Spacer(Modifier.width(16.dp)) }
        Text(label, color = WP.text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun NewPlaylistDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Новый плейлист") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Название") }) },
        confirmButton = { TextButton({ onCreate(name) }, enabled = name.isNotBlank()) { Text("Создать") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } })
}

/** Меню по нажатию и удержанию: нравится, плейлист, скачать и дополнительные действия из extra. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMenu(t: Track, extra: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    var newOpen by remember { mutableStateOf(false) }
    val liked = Likes.isLiked(t)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = WP.surfaceHi) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(t.title, color = WP.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist, color = WP.text2, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (page == 0) {
                SheetItem(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (liked) "Убрать из «Мне нравится»" else "Добавить в «Мне нравится»") {
                    AppData.scope.launch {
                        try { Likes.toggle(t); onDismiss() } catch (e: Exception) { appToast("Не получилось: ${e.message}") }
                    }
                }
                SheetItem(Icons.Default.PlaylistAdd, "Добавить в плейлист") { page = 1 }
                SheetItem(Icons.Default.Download, "Скачать") {
                    AppData.scope.launch { appToast(Downloader.run(ctx, t)) }
                    onDismiss()
                }
                extra.forEach { (label, act) -> SheetItem(null, label) { act(); onDismiss() } }
            } else {
                SheetItem(Icons.Default.Add, "Новый плейлист") { newOpen = true }
                Playlists.all.forEach { p ->
                    SheetItem(Icons.Default.QueueMusic, "${p.name}  ·  ${p.tracks.size}") {
                        if (Playlists.addTrack(p.id, t)) appToast("Добавлено в «${p.name}»") else appToast("Уже есть в этом плейлисте")
                        onDismiss()
                    }
                }
                SheetItem(Icons.AutoMirrored.Filled.ArrowBack, "Назад") { page = 0 }
            }
        }
    }
    if (newOpen) NewPlaylistDialog(
        onCreate = { name ->
            val p = Playlists.create(name); Playlists.addTrack(p.id, t)
            appToast("Добавлено в «${p.name}»"); newOpen = false; onDismiss()
        },
        onDismiss = { newOpen = false })
}
