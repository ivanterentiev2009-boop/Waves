package com.example.waves

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Упрощённое выделение голоса перед распознаванием. Это не нейросетевое разделение на вокал и музыку:
 * оно оставляет то, что звучит в центре стереокартины (у большинства песен там голос), и убирает
 * инструменты, разнесённые по сторонам, а также низкие частоты бита и баса и самые высокие частоты.
 */
object VocalPrep {
    private const val N = 1024
    private const val HOP = 256
    private const val RATE = 16000
    private val win = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / N)).toFloat() }
    private val cosT = FloatArray(N / 2) { cos(2.0 * PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { sin(2.0 * PI * it / N).toFloat() }

    /** Быстрое преобразование Фурье на месте, длина степень двойки. Обратное без деления на длину. */
    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val step = N / len
            var i = 0
            while (i < n) {
                for (k in 0 until len / 2) {
                    val wr = cosT[k * step]
                    val wi = if (inverse) sinT[k * step] else -sinT[k * step]
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Центр стереокартины. Для каждой частоты в каждом коротком кусочке считается, насколько левый и правый
     * канал совпадают: у звука строго по центру они одинаковы (маска 1), у звука сбоку различаются (маска около 0).
     * Левый и правый — моно 16 кГц одинаковой длины.
     */
    fun centerVocal(l: FloatArray, r: FloatArray, onProgress: (Float) -> Unit = {}): FloatArray {
        val len = min(l.size, r.size)
        if (len < N) return FloatArray(len) { (l[it] + r[it]) / 2f }
        val out = FloatArray(len); val norm = FloatArray(len)
        val lr = FloatArray(N); val li = FloatArray(N); val rr = FloatArray(N); val ri = FloatArray(N)
        val loBin = (100f * N / RATE).toInt()
        val hiBin = (7600f * N / RATE).toInt()
        val total = (len - N) / HOP + 1
        var pos = 0; var frame = 0
        while (pos + N <= len) {
            for (i in 0 until N) {
                val w = win[i]
                lr[i] = l[pos + i] * w; rr[i] = r[pos + i] * w; li[i] = 0f; ri[i] = 0f
            }
            fft(lr, li, false); fft(rr, ri, false)
            for (k in 0..N / 2) {
                val ar = lr[k]; val ai = li[k]; val br = rr[k]; val bi = ri[k]
                val pa = ar * ar + ai * ai; val pb = br * br + bi * bi
                var m = if (pa + pb < 1e-12f) 0f else 2f * (ar * br + ai * bi) / (pa + pb)
                m = m.coerceIn(0f, 1f); m *= m
                if (k < loBin || k > hiBin) m = 0f
                val mr = (ar + br) / 2f * m; val mi = (ai + bi) / 2f * m
                lr[k] = mr; li[k] = if (k == 0 || k == N / 2) 0f else mi
                if (k in 1 until N / 2) { lr[N - k] = mr; li[N - k] = -mi }
            }
            fft(lr, li, true)
            for (i in 0 until N) {
                out[pos + i] += lr[i] / N * win[i]
                norm[pos + i] += win[i] * win[i]
            }
            pos += HOP; frame++
            if (frame % 64 == 0) onProgress(frame.toFloat() / total)
        }
        for (i in 0 until len) if (norm[i] > 1e-6f) out[i] /= norm[i]
        onProgress(1f)
        return out
    }

    /** Приводит громкость к пику около 0.9, чтобы модель получила привычный уровень. Усиление не больше 12 раз. */
    fun normalize(x: FloatArray): FloatArray {
        var peak = 0f
        for (v in x) peak = maxOf(peak, abs(v))
        if (peak < 1e-5f) return x
        val g = min(0.9f / peak, 12f)
        return FloatArray(x.size) { x[it] * g }
    }
}
