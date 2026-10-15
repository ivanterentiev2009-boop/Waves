package com.example.waves

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Мост между сервисом (Visualizer) и интерфейсом (в одном процессе). */
object VizBus {
    const val BANDS = 28
    val bars = MutableStateFlow(FloatArray(BANDS))
    private var prev = FloatArray(BANDS)

    fun push(fft: ByteArray) {
        val n = fft.size / 2
        if (n < 4) return
        val out = FloatArray(BANDS)
        for (b in 0 until BANDS) {
            val lo = (n - 1).toDouble().pow(b.toDouble() / BANDS).toInt().coerceAtLeast(1)
            val hi = (n - 1).toDouble().pow((b + 1).toDouble() / BANDS).toInt().coerceAtLeast(lo).coerceAtMost(n - 1)
            var m = 0f
            for (k in lo..hi) m = max(m, hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()))
            val v = sqrt(m / 181f).coerceIn(0f, 1f)
            out[b] = max(v, prev[b] * 0.85f)   // плавное затухание
        }
        prev = out
        bars.value = out
    }

    fun clear() { prev = FloatArray(BANDS); bars.value = prev }
}
