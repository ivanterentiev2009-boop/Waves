package com.example.waves

import android.net.Uri
import kotlin.math.max
import kotlin.math.min

/**
 * Выравнивание готового текста по голосу. Есть правильный текст без таймкодов (из Яндекса, LRCLIB, lyrics.ovh)
 * и слова, которые распознала модель, но с ошибками и со временем. Слова сопоставляются между собой
 * (как при сравнении двух версий файла), и слова правильного текста получают время распознанных.
 * Слова, которых модель не услышала, получают время по соседям. Итог: правильные слова и точные строки.
 */
object LyricAlign {
    /** Слово для сравнения: без регистра, без знаков, ё = е. */
    private fun norm(w: String) = w.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

    /** Похожесть слов от 0 до 1 по расстоянию Левенштейна. */
    private fun sim(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return 1f - prev[b.length].toFloat() / max(a.length, b.length)
    }

    /**
     * reference: строки правильного текста. asr: слова распознавания (слово, начало мс, конец мс).
     * Возвращает строки с временем или null, если совпало слишком мало и выравниванию нельзя верить.
     */
    class Result(val lines: List<LyricLine>, val ratio: Float)

    fun align(reference: List<LyricLine>, asr: List<Triple<String, Long, Long>>): List<LyricLine>? = alignScored(reference, asr)?.lines

    /** То же, но с долей совпавших слов текста (от 0 до 1): по ней можно выбрать лучший из нескольких вариантов звука. */
    fun alignScored(reference: List<LyricLine>, asr: List<Triple<String, Long, Long>>): Result? {
        // слова правильного текста с номерами строк; делим по пробелам, как делит экран текста
        val refWords = ArrayList<String>(); val refLine = ArrayList<Int>()
        reference.forEachIndexed { li, l -> l.text.split(" ").filter { it.isNotBlank() }.forEach { refWords.add(it); refLine.add(li) } }
        val rn = refWords.map { norm(it) }
        val an = asr.map { norm(it.first) }
        if (refWords.size < 4 || asr.size < 4) return null

        // таблица выравнивания: совпадение +2, похожее слово до +2, пропуск -1
        val n = rn.size; val m = an.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) dp[i][0] = -i
        for (j in 1..m) dp[0][j] = -j
        fun pts(i: Int, j: Int): Int {
            if (rn[i].isEmpty() || an[j].isEmpty()) return -1
            val s = sim(rn[i], an[j])
            return if (s >= 0.99f) 2 else if (s >= 0.6f) (s * 2f).toInt().coerceAtLeast(1) else -2
        }
        for (i in 1..n) for (j in 1..m) dp[i][j] = maxOf(dp[i - 1][j - 1] + pts(i - 1, j - 1), dp[i - 1][j] - 1, dp[i][j - 1] - 1)

        // обратный проход: какое слово текста какому распознанному соответствует
        val match = IntArray(n) { -1 }
        var i = n; var j = m
        while (i > 0 && j > 0) {
            val p = pts(i - 1, j - 1)
            when {
                dp[i][j] == dp[i - 1][j - 1] + p -> { if (p > 0) match[i - 1] = j - 1; i--; j-- }
                dp[i][j] == dp[i - 1][j] - 1 -> i--
                else -> j--
            }
        }
        val matched = match.count { it >= 0 }
        if (matched < max(4, (n * 0.25f).toInt())) return null   // совпало слишком мало: распознавание не то, что поют

        // время слов: у совпавших из распознавания, у остальных поровну между соседями
        val st = LongArray(n); val en = LongArray(n)
        val known = BooleanArray(n)
        for (k in 0 until n) if (match[k] >= 0) { st[k] = asr[match[k]].second; en[k] = asr[match[k]].third; known[k] = true }
        val avg = (0 until n).filter { known[it] }.map { en[it] - st[it] }.average().toLong().coerceIn(150L, 700L)
        var k = 0
        while (k < n) {
            if (known[k]) { k++; continue }
            var e = k; while (e < n && !known[e]) e++
            val leftEnd = if (k > 0) en[k - 1] else null
            val rightStart = if (e < n) st[e] else null
            val cnt = e - k
            val from: Long; val to: Long
            when {
                leftEnd != null && rightStart != null -> { from = leftEnd; to = max(rightStart, leftEnd + cnt * 120L) }
                leftEnd != null -> { from = leftEnd; to = leftEnd + cnt * avg }
                rightStart != null -> { to = rightStart; from = max(0L, rightStart - cnt * avg) }
                else -> return null
            }
            val w = (k until e).map { max(rn[it].length, 2) }; val sum = w.sum().toFloat()
            var cum = 0
            for (x in k until e) { st[x] = from + ((to - from) * cum / sum).toLong(); cum += w[x - k]; en[x] = from + ((to - from) * cum / sum).toLong() }
            k = e
        }
        // время не должно идти назад, у слова не меньше 80 мс
        for (x in 0 until n) {
            if (x > 0 && st[x] < en[x - 1] - 400) st[x] = max(st[x], st[x - 1])
            if (en[x] < st[x] + 80) en[x] = st[x] + 80
        }

        val out = ArrayList<LyricLine>()
        var idx = 0
        reference.forEachIndexed { li, l ->
            val cnt = refLine.count { it == li }
            if (cnt == 0) { out.add(LyricLine(out.lastOrNull()?.wordTimes?.lastOrNull()?.second, l.text)); return@forEachIndexed }
            val times = (idx until idx + cnt).map { st[it] to en[it] }
            out.add(LyricLine(times.first().first, refWords.subList(idx, idx + cnt).joinToString(" "), times))
            idx += cnt
        }
        return Result(out, matched.toFloat() / n)
    }

    /** Блокирующий вызов: прямая ссылка на звук трека для распознавания или null, если трек нельзя прочитать. */
    fun resolveAudio(id: String, uri: Uri): Uri? = when {
        uri.scheme == "content" || uri.scheme == "file" -> uri
        id.startsWith("ym") -> Uri.parse(YandexApi.streamUrl(id.removePrefix("ym")))
        uri.scheme == "scloud" ->
            if (uri.path.orEmpty().endsWith(".m3u8")) null
            else Uri.parse(SoundCloud.streamUrl("https://" + uri.host + uri.path.orEmpty(), uri.getQueryParameter("ta")))
        uri.scheme == "http" || uri.scheme == "https" -> uri
        else -> null
    }
}
