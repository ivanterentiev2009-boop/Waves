package com.example.waves

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Несколько источников текстов песен. Порядок: Яндекс Музыка (для её треков), затем LRCLIB (открытая база с таймкодами),
 * затем lyrics.ovh (только обычный текст). Каждый источник включается в настройках.
 * Приложение лишь показывает то, что вернул сервис, и держит найденное в памяти только до закрытия.
 */
object LyricSources {
    /** Блокирующий вызов (не из UI-потока). Возвращает название источника и строки либо null. */
    fun find(id: String, title: String?, artist: String?, durSec: Int?, notes: MutableList<String>): Pair<String, List<LyricLine>>? {
        if (id.startsWith("ym") && Style.lyYandex && YandexApi.token.isNotBlank()) {
            try {
                val l = YandexApi.lyrics(id.removePrefix("ym"))
                if (l.isNotEmpty()) return "Яндекс Музыка" to l
                notes += "Яндекс: текста нет"
            } catch (e: Exception) { notes += "Яндекс: ${e.message?.take(70)}" }
        }
        val t = clean(title)
        val a = clean(artist).substringBefore(",").trim()
        if (t.isBlank()) { notes += "Нет названия трека для поиска"; return null }
        if (Style.lyLrclib) {
            try {
                lrclib(t, a, durSec)?.let { return "LRCLIB" to it }
                notes += "LRCLIB: не найдено"
            } catch (e: Exception) { notes += "LRCLIB: ${e.message?.take(70)}" }
        }
        if (Style.lyOvh && a.isNotBlank()) {
            try {
                ovh(a, t)?.let { return "lyrics.ovh (без таймкодов)" to it }
                notes += "lyrics.ovh: не найдено"
            } catch (e: Exception) { notes += "lyrics.ovh: ${e.message?.take(70)}" }
        }
        return null
    }

    /** Хост -> до какого времени (мс) не стучимся, если сервис недавно лежал (503 и т.п.). */
    private val coolUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun get(url: String): String? {
        val host = URL(url).host
        if (System.currentTimeMillis() < (coolUntil[host] ?: 0L)) throw IOException("сервис временно недоступен")
        var last: IOException? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) {
                try { Thread.sleep(if (attempt == 1) 700L else 1800L) } catch (_: InterruptedException) { break }
            }
            var c: HttpURLConnection? = null
            try {
                c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 15000
                c.setRequestProperty("User-Agent", "Waves/0.10 (personal music player)")
                val code = c.responseCode
                if (code == 404) { coolUntil.remove(host); return null }
                if (code in 200..299) {
                    val body = c.inputStream.bufferedReader().readText()
                    coolUntil.remove(host)
                    return body
                }
                if (code == 429 || code >= 500) { last = IOException("HTTP $code"); continue }   // временная ошибка — повторяем
                throw IOException("HTTP $code")   // остальные коды повторять бессмысленно
            } catch (e: java.net.SocketTimeoutException) { last = IOException("таймаут")
            } catch (e: java.net.UnknownHostException) { last = IOException("нет сети"); break
            } catch (e: IOException) {
                if (e.message?.startsWith("HTTP") == true) throw e
                last = e
            } finally { c?.disconnect() }
        }
        coolUntil[host] = System.currentTimeMillis() + 3 * 60_000L   // не долбим лежащий сервер 3 минуты
        throw last ?: IOException("сбой запроса")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun encPath(s: String) = enc(s).replace("+", "%20")

    /** Убирает расширение, «feat.», «official video» и т.п. и служебное «<unknown>». */
    private fun clean(s: String?): String {
        val x = (s ?: "")
            .replace(Regex("\\.(mp3|flac|m4a|wav|ogg|opus)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[(\\[][^)\\]]*(feat|ft\\.|prod|remix|official|lyric|audio|video|cover|slowed|speed)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "")
            .replace("_", " ").replace(Regex("\\s+"), " ").trim()
        return if (x.contains("unknown", ignoreCase = true)) "" else x
    }

    private fun fromRecord(o: JSONObject): List<LyricLine>? {
        val synced = if (o.isNull("syncedLyrics")) "" else o.optString("syncedLyrics")
        val plain = if (o.isNull("plainLyrics")) "" else o.optString("plainLyrics")
        val text = synced.ifBlank { plain }
        if (text.isBlank()) return null
        return YandexApi.parseLrc(text).takeIf { it.isNotEmpty() }
    }

    private fun lrclib(t: String, a: String, durSec: Int?): List<LyricLine>? {
        val q = "track_name=${enc(t)}" + (if (a.isNotBlank()) "&artist_name=${enc(a)}" else "")
        if (a.isNotBlank()) {   // точное совпадение (длительность должна сходиться до ±2 с)
            get("https://lrclib.net/api/get?$q" + (durSec?.let { "&duration=$it" } ?: ""))
                ?.let { fromRecord(JSONObject(it)) }?.let { return it }
        }
        val arr = JSONArray(get("https://lrclib.net/api/search?$q") ?: return null)
        var best: JSONObject? = null; var bestScore = Int.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optBoolean("instrumental")) continue
            val synced = !o.isNull("syncedLyrics") && o.optString("syncedLyrics").isNotBlank()
            val plain = !o.isNull("plainLyrics") && o.optString("plainLyrics").isNotBlank()
            if (!synced && !plain) continue
            val d = if (durSec != null) abs(o.optDouble("duration", 0.0) - durSec).toInt() else 0
            if (durSec != null && d > 5) continue
            val score = d * 2 + (if (synced) 0 else 100)
            if (score < bestScore) { best = o; bestScore = score }
        }
        return best?.let { fromRecord(it) }
    }

    private fun ovh(a: String, t: String): List<LyricLine>? {
        val body = get("https://api.lyrics.ovh/v1/${encPath(a)}/${encPath(t)}") ?: return null
        val lines = JSONObject(body).optString("lyrics").lines().map { it.trim() }.filter { it.isNotEmpty() }
            .filterNot { it.startsWith("Paroles de la chanson") }
        return lines.map { LyricLine(null, it) }.takeIf { it.isNotEmpty() }
    }
}
