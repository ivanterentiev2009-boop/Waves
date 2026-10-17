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
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/** Распознавание речи на устройстве (Vosk): создаёт текст с таймкодами и временем слов для локальных треков. */
object Transcriber {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    private const val MODEL_DIR = "vosk-model-small-ru-0.22"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val progress = mutableStateMapOf<String, Float>()
    val errors = mutableStateMapOf<String, String>()
    var version by mutableIntStateOf(0)
    var modelReady by mutableStateOf(false)
    var modelBusy by mutableStateOf(false)
    var modelProgress by mutableFloatStateOf(0f)
    var modelError by mutableStateOf<String?>(null)

    private fun modelDir(ctx: Context) = File(ctx.filesDir, "vosk/$MODEL_DIR")
    fun refresh(ctx: Context) { modelReady = File(modelDir(ctx), "am").isDirectory }
    fun deleteModel(ctx: Context) { File(ctx.filesDir, "vosk").deleteRecursively(); refresh(ctx) }

    fun downloadModel(ctx: Context) {
        if (modelBusy) return
        val app = ctx.applicationContext
        modelBusy = true; modelError = null; modelProgress = 0f
        scope.launch {
            try {
                val base = File(app.filesDir, "vosk").apply { mkdirs() }
                val zip = File(app.cacheDir, "vosk-model.zip")
                val c = URL(MODEL_URL).openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 30000
                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                val total = c.contentLengthLong.coerceAtLeast(1L)
                c.inputStream.use { inp ->
                    zip.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var done = 0L
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            modelProgress = done.toFloat() / total * 0.9f
                        }
                    }
                }
                ZipInputStream(zip.inputStream().buffered()).use { zin ->
                    var e = zin.nextEntry
                    while (e != null) {
                        val f = File(base, e.name)
                        if (!f.canonicalPath.startsWith(base.canonicalPath)) throw IOException("повреждённый архив")
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zin.copyTo(it) } }
                        e = zin.nextEntry
                    }
                }
                zip.delete(); modelProgress = 1f
                refresh(app)
            } catch (e: Exception) { modelError = "Ошибка загрузки: ${e.message}" }
            modelBusy = false
        }
    }

    private fun file(ctx: Context, id: String) = File(File(ctx.filesDir, "subs").apply { mkdirs() }, "$id.json")

    fun load(ctx: Context, id: String): List<LyricLine>? {
        val f = file(ctx, id); if (!f.exists()) return null
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i); val w = o.getJSONArray("w")
                LyricLine(o.getLong("t"), o.getString("x"),
                    (0 until w.length()).map { k -> w.getJSONArray(k).let { it.getLong(0) to it.getLong(1) } })
            }
        } catch (_: Exception) { null }
    }

    fun start(ctx: Context, id: String, uri: Uri) {
        if (progress.containsKey(id) || !modelReady) return
        val app = ctx.applicationContext
        errors.remove(id); progress[id] = 0f
        scope.launch {
            try {
                val lines = transcribe(app, uri) { progress[id] = it }
                if (lines.isEmpty()) throw IOException("речь не найдена")
                val arr = JSONArray()
                lines.forEach { l ->
                    val w = JSONArray()
                    l.wordTimes?.forEach { w.put(JSONArray().put(it.first).put(it.second)) }
                    arr.put(JSONObject().put("t", l.timeMs).put("x", l.text).put("w", w))
                }
                file(app, id).writeText(arr.toString())
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
