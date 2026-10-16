package com.example.waves

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

data class VoskModel(val name: String, val lang: String, val langText: String, val sizeText: String,
                     val type: String, val url: String, val obsolete: Boolean)

/** Распознавание речи на устройстве (Vosk): текст с таймкодами и временем слов, каталог всех моделей Vosk. */
object Transcriber {
    private const val LIST_URL = "https://alphacephei.com/vosk/models/model-list.json"
    private val DEFAULT = VoskModel("vosk-model-small-ru-0.22", "ru", "Russian", "45 MiB", "small",
        "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sp: android.content.SharedPreferences? = null

    val progress = mutableStateMapOf<String, Float>()
    val errors = mutableStateMapOf<String, String>()
    var version by mutableIntStateOf(0)
    var activeModel by mutableStateOf("")
    val installed = mutableStateListOf<String>()
    var modelReady by mutableStateOf(false)
    var modelBusy by mutableStateOf(false)
    var modelName by mutableStateOf<String?>(null)
    var modelProgress by mutableFloatStateOf(0f)
    var modelStage by mutableStateOf("")
    val logs = mutableStateListOf<String>()
    var gigaReady by mutableStateOf(false)
    var gigaBusy by mutableStateOf(false)
    var gigaProgress by mutableFloatStateOf(0f)
    var gigaStage by mutableStateOf("")
    var gigaError by mutableStateOf<String?>(null)
    var modelError by mutableStateOf<String?>(null)
    var catalog by mutableStateOf<List<VoskModel>>(emptyList())
    var catalogLoading by mutableStateOf(false)
    var catalogError by mutableStateOf<String?>(null)

    private fun root(ctx: Context) = File(ctx.filesDir, "vosk")
    private fun modelDir(ctx: Context) = File(root(ctx), activeModel)

    fun refresh(ctx: Context) {
        if (sp == null) { sp = ctx.applicationContext.getSharedPreferences("vosk", 0); activeModel = sp!!.getString("active", "") ?: "" }
        val names = root(ctx).listFiles()?.filter { it.isDirectory && (File(it, "am").isDirectory || File(it, "conf").isDirectory) }
            ?.map { it.name }?.sorted() ?: emptyList()
        installed.clear(); installed.addAll(names)
        if (activeModel !in names) { activeModel = names.firstOrNull() ?: ""; sp?.edit()?.putString("active", activeModel)?.apply() }
        modelReady = activeModel.isNotEmpty()
        refreshGiga(ctx)
    }

    fun select(ctx: Context, name: String) { refresh(ctx); activeModel = name; sp?.edit()?.putString("active", name)?.apply(); modelReady = true }
    fun deleteModel(ctx: Context, name: String = activeModel) { File(root(ctx), name).deleteRecursively(); refresh(ctx) }
    fun downloadModel(ctx: Context) = download(ctx, DEFAULT)

    /** Полный список моделей с сайта Vosk (все языки, лёгкие и большие, устаревшие тоже). */
    fun fetchCatalog() {
        if (catalogLoading) return
        catalogLoading = true; catalogError = null
        scope.launch {
            try {
                val c = URL(LIST_URL).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 20000
                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                val arr = JSONArray(c.inputStream.bufferedReader().readText())
                catalog = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    VoskModel(o.getString("name"), o.optString("lang"), o.optString("lang_text"), o.optString("size_text"),
                        o.optString("type"), o.getString("url"), o.optString("obsolete") == "true")
                }.sortedWith(compareBy({ it.langText }, { it.type }, { it.name }))
            } catch (e: Exception) { catalogError = "Не удалось получить список: ${e.message}" }
            catalogLoading = false
        }
    }

    private fun log(s: String) { logs.add(s); if (logs.size > 40) logs.removeAt(0) }

    /**
     * Скачивание с проверкой размера и докачкой при обрыве, затем проверка архива и распаковка с реальным прогрессом.
     * Любая ошибка (в том числе нехватка памяти) показывается и пишется в журнал.
     */
    fun download(ctx: Context, m: VoskModel) {
        if (modelBusy) return
        val app = ctx.applicationContext
        modelBusy = true; modelName = m.name; modelError = null; modelProgress = 0f; modelStage = "Подключение…"
        logs.clear(); log("Старт: ${m.name} (${m.sizeText})")
        scope.launch {
            val zip = File(app.cacheDir, "vosk-${m.name}.zip")
            try {
                zip.delete()
                val base = root(app).apply { mkdirs() }
                var done = 0L; var total = -1L; var fails = 0
                while (total < 0 || done < total) {
                    val c = URL(m.url).openConnection() as HttpURLConnection
                    c.connectTimeout = 20000; c.readTimeout = 30000
                    c.setRequestProperty("Accept-Encoding", "identity")
                    if (done > 0) c.setRequestProperty("Range", "bytes=$done-")
                    val code = c.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    if (done > 0 && code != 206) { done = 0; zip.delete(); log("Сервер не поддерживает докачку, начинаем заново") }
                    if (total < 0) {
                        total = if (code == 206) (c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L) else c.contentLengthLong
                        log("Размер: ${total / 1_000_000} МБ")
                    }
                    try {
                        c.inputStream.use { inp ->
                            FileOutputStream(zip, done > 0).use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = inp.read(buf); if (n < 0) break
                                    out.write(buf, 0, n); done += n
                                    if (total > 0) { modelProgress = done.toFloat() / total * 0.5f; modelStage = "Скачивание ${done * 100 / total}%" }
                                }
                            }
                        }
                    } catch (e: IOException) {
                        if (++fails > 15) throw e
                        log("Обрыв (${e.message}) на ${done / 1_000_000} МБ, повтор $fails"); delay(2000); continue
                    }
                    if (total > 0 && done < total) {
                        if (++fails > 15) throw IOException("загрузка оборвалась на ${done * 100 / total}%")
                        log("Сервер закрыл соединение на ${done * 100 / total}%, докачиваем"); delay(1500)
                    } else if (total <= 0) break
                }
                log("Скачано ${done / 1_000_000} МБ, проверка архива")
                modelStage = "Проверка архива"
                ZipFile(zip).use { zf ->
                    val entries = zf.entries().toList()
                    val need = entries.sumOf { maxOf(it.size, 0L) }
                    if (base.usableSpace < need + 50_000_000) throw IOException("не хватает места: нужно ${need / 1_000_000} МБ")
                    val top = entries.first().name.substringBefore('/')
                    File(base, top).deleteRecursively()
                    var written = 0L
                    for (e in entries) {
                        val f = File(base, e.name)
                        if (!f.canonicalPath.startsWith(base.canonicalPath)) throw IOException("повреждённый архив")
                        if (e.isDirectory) { f.mkdirs(); continue }
                        f.parentFile?.mkdirs()
                        zf.getInputStream(e).use { zin ->
                            f.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zin.read(buf); if (n < 0) break
                                    out.write(buf, 0, n); written += n
                                    if (need > 0) { modelProgress = 0.5f + written.toFloat() / need * 0.5f; modelStage = "Распаковка ${written * 100 / need}%" }
                                }
                            }
                        }
                    }
                    val dir = File(base, top)
                    if (!File(dir, "am").isDirectory && !File(dir, "conf").isDirectory)
                        throw IOException("в архиве нет модели Vosk (папка $top: ${dir.list()?.joinToString()})")
                    log("Распаковано в $top")
                    refresh(app); select(app, top)
                }
                zip.delete(); modelProgress = 1f; modelStage = "Готово"; log("Готово")
            } catch (e: Throwable) {
                modelError = "Ошибка: ${e::class.simpleName}: ${e.message}"; log(modelError!!)
            }
            modelBusy = false; modelName = null
        }
    }

    // ---------------- GigaAM (sherpa-onnx) ----------------
    private const val GIGA_BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-transducer-giga-am-russian-2024-10-24/resolve/main/"
    private val GIGA_FILES = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
    private val GIGA_WEIGHTS = listOf(0.97f, 0.015f, 0.01f, 0.005f)
    private fun gigaDir(ctx: Context) = File(ctx.filesDir, "gigaam")

    fun refreshGiga(ctx: Context) {
        gigaReady = GIGA_FILES.all { n -> File(gigaDir(ctx), n).let { it.isFile && it.length() > 0 } }
    }

    fun deleteGiga(ctx: Context) { gigaDir(ctx).deleteRecursively(); refreshGiga(ctx) }

    fun downloadGiga(ctx: Context) {
        if (gigaBusy) return
        val app = ctx.applicationContext
        gigaBusy = true; gigaError = null; gigaProgress = 0f; gigaStage = "Подключение…"
        scope.launch {
            try {
                val dir = gigaDir(app).apply { mkdirs() }
                var base = 0f
                GIGA_FILES.forEachIndexed { i, name ->
                    val dest = File(dir, name)
                    if (!(dest.isFile && dest.length() > 0)) {
                        gigaStage = "Скачивание $name"
                        fetchFile(GIGA_BASE + name, dest) { f ->
                            gigaProgress = base + GIGA_WEIGHTS[i] * f
                            gigaStage = "Скачивание $name ${(f * 100).toInt()}%"
                        }
                    }
                    base += GIGA_WEIGHTS[i]; gigaProgress = base
                }
                gigaStage = "Готово"; refreshGiga(app)
            } catch (e: Throwable) { gigaError = "Ошибка: ${e::class.simpleName}: ${e.message}" }
            gigaBusy = false
        }
    }

    /** Скачивание файла с проверкой размера и докачкой при обрыве. */
    private suspend fun fetchFile(url: String, dest: File, onFrac: (Float) -> Unit) {
        val part = File(dest.path + ".part"); part.delete()
        var done = 0L; var total = -1L; var fails = 0
        while (total < 0 || done < total) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 30000
            c.setRequestProperty("Accept-Encoding", "identity")
            if (done > 0) c.setRequestProperty("Range", "bytes=$done-")
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            if (done > 0 && code != 206) { done = 0; part.delete() }
            if (total < 0) total = if (code == 206) (c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L) else c.contentLengthLong
            try {
                c.inputStream.use { inp ->
                    FileOutputStream(part, done > 0).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) onFrac(done.toFloat() / total)
                        }
                    }
                }
            } catch (e: IOException) {
                if (++fails > 15) throw e
                delay(2000); continue
            }
            if (total > 0 && done < total) {
                if (++fails > 15) throw IOException("загрузка оборвалась на ${done * 100 / total}%")
                delay(1500)
            } else if (total <= 0) break
        }
        if (!part.renameTo(dest)) throw IOException("не удалось сохранить ${dest.name}")
    }

    /** Токены и время токенов -> слова с временем (текст сопоставляется с токенами по символам). */
    private fun wordsFrom(toks: Array<String>, times: FloatArray, text: String, offset: Float): List<Triple<String, Long, Long>> {
        val chars = ArrayList<Pair<Char, Int>>()
        toks.forEachIndexed { i, tok -> for (ch in tok.replace("▁", "")) if (!ch.isWhitespace()) chars.add(ch to i) }
        val out = mutableListOf<Triple<String, Long, Long>>()
        var pos = 0
        for (word in text.split(" ").filter { it.isNotBlank() }) {
            val idx = ArrayList<Int>()
            for (ch in word) {
                while (pos < chars.size && chars[pos].first != ch) pos++
                if (pos < chars.size) { idx.add(chars[pos].second); pos++ }
            }
            if (idx.isNotEmpty() && idx.first() < times.size) {
                val s = times[idx.first()]
                val last = minOf(idx.last(), times.size - 1)
                val e = if (last + 1 < times.size) times[last + 1] else times[last] + 0.2f
                val sMs = ((s + offset) * 1000).toLong()
                out += Triple(word, sMs, maxOf(((e + offset) * 1000).toLong(), sMs + 80))
            }
        }
        return out
    }

    /** GigaAM: трек режется на куски по ~25 с в самых тихих местах, чтобы не резать слова и не раздувать память. */
    private fun transcribeGiga(ctx: Context, uri: Uri, onProgress: (Float) -> Unit): List<LyricLine> {
        val dir = gigaDir(ctx)
        val cfg = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").path,
                    decoder = File(dir, "decoder.onnx").path,
                    joiner = File(dir, "joiner.onnx").path),
                tokens = File(dir, "tokens.txt").path,
                numThreads = 4,
                modelType = "nemo_transducer"))
        val pcm = ByteArrayOutputStream()
        decode(ctx, uri, { b, n -> pcm.write(b, 0, n) }) { onProgress(it * 0.25f) }
        val bytes = pcm.toByteArray()
        val samples = FloatArray(bytes.size / 2) { i -> ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort() / 32768f }
        val sr = 16000; val target = 25 * sr; val frame = sr / 10
        val rec = OfflineRecognizer(config = cfg)
        val words = mutableListOf<Triple<String, Long, Long>>()
        try {
            var start = 0
            while (start < samples.size) {
                var end = minOf(start + target, samples.size)
                if (samples.size - start > target + 5 * sr) {
                    var best = end; var bestE = Float.MAX_VALUE
                    var p = start + 20 * sr
                    val hi = minOf(start + 28 * sr, samples.size)
                    while (p + frame <= hi) {
                        var e = 0f
                        for (k in p until p + frame) e += abs(samples[k])
                        if (e < bestE) { bestE = e; best = p + frame / 2 }
                        p += frame / 2
                    }
                    end = best
                }
                val stream = rec.createStream()
                stream.acceptWaveform(samples.copyOfRange(start, end), sr)
                rec.decode(stream)
                val r = rec.getResult(stream)
                stream.release()
                words += wordsFrom(r.tokens, r.timestamps, r.text, start / sr.toFloat())
                start = end
                onProgress(0.25f + 0.75f * start / samples.size)
            }
        } finally { rec.release() }
        return group(words)
    }

    private fun file(ctx: Context, id: String) = File(File(ctx.filesDir, "subs").apply { mkdirs() }, "$id.json")

    fun load(ctx: Context, id: String): List<LyricLine>? {
        val f = file(ctx, id); if (!f.exists()) return null
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i); val w = o.getJSONArray("w")
                LyricLine(if (o.isNull("t")) null else o.getLong("t"), o.getString("x"),
                    (0 until w.length()).map { k -> w.getJSONArray(k).let { it.getLong(0) to it.getLong(1) } })
            }
        } catch (_: Exception) { null }
    }

    fun save(ctx: Context, id: String, lines: List<LyricLine>) {
        val arr = JSONArray()
        lines.forEach { l ->
            val w = JSONArray()
            l.wordTimes?.forEach { w.put(JSONArray().put(it.first).put(it.second)) }
            arr.put(JSONObject().put("t", l.timeMs ?: JSONObject.NULL).put("x", l.text).put("w", w))
        }
        file(ctx, id).writeText(arr.toString())
    }

    fun clear(ctx: Context, id: String) { file(ctx, id).delete(); version++ }

    /** Файл со словами и временем (секунды): [{"word","start","end"}] или {"words":[...]} (Vosk, наш скрипт для GigaAM). */
    fun parseWordsJson(text: String): List<LyricLine> {
        val s = text.trim().removePrefix("\uFEFF")
        val arr = when {
            s.startsWith("[") -> JSONArray(s)
            s.startsWith("{") -> JSONObject(s).let { it.optJSONArray("words") ?: it.optJSONArray("result") ?: JSONArray() }
            else -> JSONArray()
        }
        val words = mutableListOf<Triple<String, Long, Long>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val w = o.optString("word", o.optString("text", "")).trim()
            if (w.isEmpty()) continue
            val st = (o.optDouble("start", 0.0) * 1000).toLong()
            val en = (o.optDouble("end", st / 1000.0 + 0.3) * 1000).toLong()
            words += Triple(w, st, maxOf(en, st + 80))
        }
        return group(words)
    }

    /** Пересчитывает время слов после правки текста строки. */
    fun retime(old: List<Pair<Long, Long>>?, text: String): List<Pair<Long, Long>>? {
        val words = text.split(" ").filter { it.isNotBlank() }
        if (old == null || old.isEmpty() || words.isEmpty()) return null
        if (old.size == words.size) return old
        val s = old.first().first; val e = old.last().second
        val w = words.map { maxOf(it.length, 2) }; val sum = w.sum().toFloat()
        var cum = 0
        return w.map { len ->
            val a = s + ((e - s) * cum / sum).toLong(); cum += len
            a to s + ((e - s) * cum / sum).toLong()
        }
    }

    fun start(ctx: Context, id: String, uri: Uri) {
        val giga = Style.asrEngine == 1
        if (progress.containsKey(id) || !(if (giga) gigaReady else modelReady)) return
        val app = ctx.applicationContext
        errors.remove(id); progress[id] = 0f
        scope.launch {
            try {
                val lines = if (giga) transcribeGiga(app, uri) { progress[id] = it } else transcribe(app, uri) { progress[id] = it }
                if (lines.isEmpty()) throw IOException("речь не найдена")
                save(app, id, lines)
                version++
            } catch (e: Exception) { errors[id] = e.message ?: "неизвестная ошибка" }
            progress.remove(id)
        }
    }

    private fun transcribe(ctx: Context, uri: Uri, onProgress: (Float) -> Unit): List<LyricLine> {
        val model = Model(modelDir(ctx).absolutePath)
        try {
            val rec = Recognizer(model, 16000f).apply { setWords(true) }
            val words = mutableListOf<Triple<String, Long, Long>>()
            fun parse(json: String) {
                val arr = JSONObject(json).optJSONArray("result") ?: return
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    words += Triple(o.getString("word"), (o.getDouble("start") * 1000).toLong(), (o.getDouble("end") * 1000).toLong())
                }
            }
            decode(ctx, uri, { buf, n -> if (rec.acceptWaveForm(buf, n)) parse(rec.result) }, onProgress)
            parse(rec.finalResult)
            rec.close()
            return group(words)
        } finally { model.close() }
    }

    /** Слова -> строки: до 8 слов, пауза больше 1.5 с или длина больше 6 с начинают новую строку. */
    private fun group(w: List<Triple<String, Long, Long>>): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        var cur = mutableListOf<Triple<String, Long, Long>>()
        fun flush() {
            if (cur.isNotEmpty()) {
                out += LyricLine(cur.first().second, cur.joinToString(" ") { it.first }, cur.map { it.second to it.third })
                cur = mutableListOf()
            }
        }
        for (x in w) {
            if (cur.isNotEmpty() && (cur.size >= 8 || x.second - cur.last().third > 1500 || x.third - cur.first().second > 6000)) flush()
            cur.add(x)
        }
        flush()
        return out
    }

    /** Декодирует любой аудиофайл в 16 кГц моно PCM16 и отдаёт кусками. */
    private fun decode(ctx: Context, uri: Uri, sink: (ByteArray, Int) -> Unit, onProgress: (Float) -> Unit) {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { ex.selectTrack(i); fmt = f; break }
        }
        if (fmt == null) { ex.release(); throw IOException("нет аудиодорожки") }
        val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(fmt, null, null, 0); dec.start()
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        val out = ByteArray(8192); var outN = 0
        var acc = 0f; var accN = 0; var phase = 0.0
        var inDone = false; var outDone = false
        try {
            while (!outDone) {
                if (!inDone) {
                    val ii = dec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val n = ex.readSampleData(dec.getInputBuffer(ii)!!, 0)
                        if (n < 0) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else {
                            dec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            if (durUs > 0) onProgress((ex.sampleTime.toFloat() / durUs).coerceIn(0f, 1f))
                            ex.advance()
                        }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    val ob = dec.getOutputBuffer(oi)!!
                    ob.order(ByteOrder.LITTLE_ENDIAN)
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val sb = ob.asShortBuffer()
                    val step = 16000.0 / sr
                    while (sb.remaining() >= ch) {
                        var m = 0f
                        for (k in 0 until ch) m += sb.get()
                        acc += m / ch; accN++; phase += step
                        if (phase >= 1.0) {
                            phase -= 1.0
                            val s = (acc / accN).toInt().coerceIn(-32768, 32767)
                            acc = 0f; accN = 0
                            out[outN++] = (s and 0xFF).toByte(); out[outN++] = ((s shr 8) and 0xFF).toByte()
                            if (outN >= out.size) { sink(out, outN); outN = 0 }
                        }
                    }
                    dec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = dec.outputFormat
                    sr = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            if (outN > 0) sink(out, outN)
        } finally { dec.stop(); dec.release(); ex.release() }
    }
}
