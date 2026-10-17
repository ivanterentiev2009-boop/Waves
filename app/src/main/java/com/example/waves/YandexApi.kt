package com.example.waves

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Неофициальный клиент Яндекс Музыки. Может перестать работать в любой момент. */
object YandexApi {
    const val AUTH_URL = "https://oauth.yandex.ru/authorize?response_type=token&client_id=23cabbbdc6cd418abb4b39c32c41195d"
    private const val BASE = "https://api.music.yandex.net"
    private const val SIGN_KEY = "kzqU4XhfCaY6B6JTHODeq5"
    private var sp: android.content.SharedPreferences? = null
    private var uid: String? = null
    var token by mutableStateOf("")

    fun init(ctx: Context) {
        if (sp != null) return
        sp = ctx.applicationContext.getSharedPreferences("yandex", 0)
        token = sp?.getString("token", "") ?: ""
    }

    /** Принимает токен или весь адрес со строкой access_token=... */
    fun saveToken(raw: String) {
        val t = Regex("access_token=([^&\\s]+)").find(raw)?.groupValues?.get(1) ?: raw.trim()
        token = t; uid = null
        sp?.edit()?.putString("token", t)?.apply()
    }
    fun logout() = saveToken("")

    private fun request(url: String, post: String? = null, auth: Boolean = true): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        if (auth) c.setRequestProperty("Authorization", "OAuth $token")
        c.setRequestProperty("X-Yandex-Music-Client", "YandexMusicAndroid/24023231")
        if (post != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(post.toByteArray()) }
        }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw IOException("HTTP $code ${body.take(100)}")
        return body
    }
    private fun json(url: String, post: String? = null) = JSONObject(request(url, post))

    private fun parse(o: JSONObject): Track {
        val id = o.get("id").toString().substringBefore(":")
        val artists = o.optJSONArray("artists")
        val names = (0 until (artists?.length() ?: 0)).joinToString(", ") { artists!!.getJSONObject(it).optString("name") }
        val cover = o.optString("coverUri").takeIf { it.isNotBlank() }?.let { Uri.parse("https://" + it.replace("%%", "400x400")) }
        return Track("ym$id", o.optString("title", "Без названия"), names.ifBlank { "Неизвестный исполнитель" },
            Uri.parse("ymusic://$id"), cover)
    }

    private fun details(ids: List<String>): List<Track> = ids.chunked(100).flatMap { chunk ->
        val r = json("$BASE/tracks", "track-ids=" + chunk.joinToString(",")).getJSONArray("result")
        (0 until r.length()).map { parse(r.getJSONObject(it)) }
    }

    fun likes(): List<Track> {
        val id = uid ?: json("$BASE/account/status").getJSONObject("result").getJSONObject("account").get("uid").toString().also { uid = it }
        val arr = json("$BASE/users/$id/likes/tracks").getJSONObject("result").getJSONObject("library").getJSONArray("tracks")
        return details((0 until minOf(arr.length(), 300)).map { arr.getJSONObject(it).get("id").toString() })
    }

    fun search(q: String, page: Int = 0): List<Track> {
        val res = json("$BASE/search?text=${URLEncoder.encode(q, "UTF-8")}&nocorrect=false&type=track&page=$page")
            .getJSONObject("result").optJSONObject("tracks")?.optJSONArray("results") ?: return emptyList()
        return (0 until res.length()).map { parse(res.getJSONObject(it)) }.distinctBy { it.id }
    }

    private fun coverUri(raw: String?): Uri? =
        raw?.takeIf { it.isNotBlank() }?.let { Uri.parse("https://" + it.replace("%%", "400x400")) }

    private fun parseArtist(o: JSONObject): ArtistItem {
        val c = o.optJSONObject("counts")
        val sub = listOfNotNull(
            c?.optInt("tracks", 0)?.takeIf { it > 0 }?.let { "$it треков" },
            c?.optInt("directAlbums", 0)?.takeIf { it > 0 }?.let { "$it альбомов" }).joinToString(" · ")
        val gs = o.optJSONArray("genres")
        val genres = (0 until (gs?.length() ?: 0)).joinToString(", ") { gs!!.optString(it) }
        val cover = coverUri(o.optJSONObject("cover")?.optString("uri")) ?: coverUri(o.optString("ogImage"))
        return ArtistItem(o.get("id").toString(), o.optString("name"), cover, sub, genres)
    }

    fun searchArtists(q: String): List<ArtistItem> {
        val res = json("$BASE/search?text=${URLEncoder.encode(q, "UTF-8")}&nocorrect=false&type=artist&page=0")
            .getJSONObject("result").optJSONObject("artists")?.optJSONArray("results") ?: return emptyList()
        return (0 until res.length()).map { parseArtist(res.getJSONObject(it)) }.distinctBy { it.id }.take(15)
    }

    private fun artistTracks(id: String): List<Track> {
        val arr = json("$BASE/artists/$id/tracks?page=0&page-size=30").getJSONObject("result").optJSONArray("tracks") ?: return emptyList()
        return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
    }

    /** Профиль исполнителя: популярные треки, альбомы, похожие исполнители. */
    fun artistProfile(id: String): ArtistProfile {
        val r = json("$BASE/artists/$id/brief-info").getJSONObject("result")
        val artist = parseArtist(r.getJSONObject("artist"))
        var tracks = r.optJSONArray("popularTracks")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> parse(o) } }
        } ?: emptyList()
        if (tracks.isEmpty()) tracks = artistTracks(id)
        val albums = r.optJSONArray("albums")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
                AlbumItem(o.get("id").toString(), o.optString("title"),
                    o.optString("year").ifBlank { o.optString("releaseDate").take(4) }, coverUri(o.optString("coverUri")))
            }
        } ?: emptyList()
        val similar = r.optJSONArray("similarArtists")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> parseArtist(o) } }
        } ?: emptyList()
        return ArtistProfile(artist, tracks.distinctBy { it.id }, albums.distinctBy { it.id }, similar.distinctBy { it.id })
    }

    /** Треки альбома. */
    fun album(id: String): List<Track> {
        val vols = json("$BASE/albums/$id/with-tracks").getJSONObject("result").optJSONArray("volumes") ?: return emptyList()
        val out = mutableListOf<Track>()
        for (i in 0 until vols.length()) {
            val v = vols.getJSONArray(i)
            for (k in 0 until v.length()) out += parse(v.getJSONObject(k))
        }
        return out.distinctBy { it.id }
    }

    /** «Моя волна»: бесконечная подборка. afterId — последний трек очереди, чтобы получить продолжение. */
    fun wave(afterId: String? = null): List<Track> {
        val q = if (afterId != null) "&queue=$afterId" else ""
        val seq = json("$BASE/rotor/station/user:onyourwave/tracks?settings2=true$q").getJSONObject("result").getJSONArray("sequence")
        return (0 until seq.length()).mapNotNull { i -> seq.getJSONObject(i).optJSONObject("track")?.let { parse(it) } }.distinctBy { it.id }
    }

    /** Блокирующий вызов: вызывается из загрузчика ExoPlayer (не из UI-потока). */
    fun streamUrl(id: String): String {
        try {
            val ts = System.currentTimeMillis() / 1000
            val quality = "nq"; val codecs = "mp3,aac,he-aac"; val transports = "raw"
            val msg = "$ts$id$quality${codecs.replace(",", "")}$transports"
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(SIGN_KEY.toByteArray(), "HmacSHA256")) }
            val sign = Base64.encodeToString(mac.doFinal(msg.toByteArray()), Base64.NO_WRAP).dropLast(1)
            val r = json("$BASE/get-file-info?ts=$ts&trackId=$id&quality=$quality&codecs=$codecs&transports=$transports&sign=${URLEncoder.encode(sign, "UTF-8")}")
            return r.getJSONObject("result").getJSONObject("downloadInfo").getString("url")
        } catch (_: Exception) { /* пробуем старый способ */ }
        try {
            val infos = json("$BASE/tracks/$id/download-info").getJSONArray("result")
            var best: JSONObject? = null
            for (i in 0 until infos.length()) {
                val o = infos.getJSONObject(i)
                if (o.optString("codec") == "mp3" && (best == null || o.getInt("bitrateInKbps") > best.getInt("bitrateInKbps"))) best = o
            }
            val xml = request(best!!.getString("downloadInfoUrl"), auth = false)
            fun tag(t: String) = Regex("<$t>(.*?)</$t>").find(xml)!!.groupValues[1]
            val path = tag("path"); val s = tag("s")
            val sign = MessageDigest.getInstance("MD5").digest("XGRlBW9FXlekgbPrRHuSiA${path.drop(1)}$s".toByteArray())
                .joinToString("") { "%02x".format(it) }
            return "https://${tag("host")}/get-mp3/$sign/${tag("ts")}$path"
        } catch (e: Exception) { throw IOException("Не удалось получить ссылку на трек: ${e.message}", e) }
    }

    private const val LYRICS_KEY = "p93jhgh689SBReK6ghtw62"

    /** Текст трека от Яндекса (LRC с таймкодами или обычный). Отдаётся сервисом, мы его не храним. */
    fun lyrics(id: String): List<LyricLine> {
        val ts = System.currentTimeMillis() / 1000
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(LYRICS_KEY.toByteArray(), "HmacSHA256")) }
        val raw = Base64.encodeToString(mac.doFinal("$id$ts".toByteArray()), Base64.NO_WRAP)
        var err: Exception? = null
        for (sign in listOf(raw, raw.dropLast(1))) {
            try {
                val r = json("$BASE/tracks/$id/lyrics?format=LRC&timeStamp=$ts&sign=${URLEncoder.encode(sign, "UTF-8")}").getJSONObject("result")
                return parseLrc(request(r.getString("downloadUrl"), auth = false))
            } catch (e: Exception) {
                if (e.message?.startsWith("HTTP 404") == true) return emptyList()
                err = e
            }
        }
        throw err ?: IOException("нет ответа")
    }

    fun parseLrc(text: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        val rx = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]")
        for (line in text.lines()) {
            val tags = rx.findAll(line).toList()
            val body = rx.replace(line, "").trim()
            if (tags.isEmpty()) { if (line.isNotBlank() && !line.startsWith("[")) out += LyricLine(null, line.trim()); continue }
            for (m in tags) {
                val frac = m.groupValues[3].padEnd(3, '0').take(3).toInt()
                out += LyricLine((m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000 + frac, body)
            }
        }
        return out.sortedBy { it.timeMs ?: 0L }
    }
}

data class LyricLine(val timeMs: Long?, val text: String, val wordTimes: List<Pair<Long, Long>>? = null)

data class ArtistItem(val id: String, val name: String, val cover: Uri?, val sub: String, val genres: String = "")
data class AlbumItem(val id: String, val title: String, val year: String, val cover: Uri?)
data class ArtistProfile(val artist: ArtistItem, val tracks: List<Track>, val albums: List<AlbumItem>, val similar: List<ArtistItem>)
