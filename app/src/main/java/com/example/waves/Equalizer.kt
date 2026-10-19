package com.example.waves

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.Equalizer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Системный эквалайзер Android (android.media.audiofx.Equalizer), подключённый к аудиосессии плеера.
 * Сервис воспроизведения вызывает attach() при смене сессии; интерфейс работает с тем же объектом (один процесс, как VizBus).
 * Настройки (включён, уровни полос, пресет) хранятся в SharedPreferences «eq» и применяются при каждом подключении.
 */
object Eq {
    var available by mutableStateOf(false)
        private set
    var enabled by mutableStateOf(false)
        private set
    var preset by mutableIntStateOf(-1)
        private set
    var minLevel by mutableIntStateOf(-1500)
        private set
    var maxLevel by mutableIntStateOf(1500)
        private set
    var presets by mutableStateOf<List<String>>(emptyList())
        private set
    val freqs = mutableStateListOf<Int>()     // центральные частоты, Гц
    val levels = mutableStateListOf<Int>()    // уровни полос, милли-децибелы

    private var eq: Equalizer? = null
    private var sp: SharedPreferences? = null
    private var saved: List<Int> = emptyList()

    fun init(ctx: Context) {
        if (sp != null) return
        val s = ctx.applicationContext.getSharedPreferences("eq", 0); sp = s
        enabled = s.getBoolean("on", false)
        preset = s.getInt("pr", -1)
        saved = s.getString("lv", "").orEmpty().split(",").mapNotNull { it.toIntOrNull() }
    }

    /** Подключает эквалайзер к аудиосессии плеера. Вызывается сервисом воспроизведения. */
    fun attach(session: Int) {
        release()
        if (session <= 0) return   // -1 = сессия ещё не создана, 0 = глобальный микс (не трогаем)
        try {
            val e = Equalizer(0, session)
            val n = e.numberOfBands.toInt()
            val range = e.bandLevelRange
            minLevel = range[0].toInt(); maxLevel = range[1].toInt()
            freqs.clear(); for (b in 0 until n) freqs.add(e.getCenterFreq(b.toShort()) / 1000)
            presets = (0 until e.numberOfPresets.toInt()).map { e.getPresetName(it.toShort()) }
            if (saved.size == n) saved.forEachIndexed { b, v -> e.setBandLevel(b.toShort(), v.coerceIn(minLevel, maxLevel).toShort()) }
            levels.clear(); for (b in 0 until n) levels.add(e.getBandLevel(b.toShort()).toInt())
            e.enabled = enabled
            eq = e; available = true
        } catch (_: Exception) { eq = null; available = false }
    }

    fun release() {
        try { eq?.release() } catch (_: Exception) { }
        eq = null; available = false
    }

    fun enable(on: Boolean) {
        enabled = on
        try { eq?.enabled = on } catch (_: Exception) { }
        persist()
    }

    fun setLevel(band: Int, mB: Int) {
        if (band !in levels.indices) return
        if (!enabled) setEnabled(true)
        val v = mB.coerceIn(minLevel, maxLevel)
        levels[band] = v; preset = -1
        try { eq?.setBandLevel(band.toShort(), v.toShort()) } catch (_: Exception) { }
        persist()
    }

    fun usePreset(i: Int) {
        if (i !in presets.indices) return
        if (!enabled) setEnabled(true)
        try {
            eq?.usePreset(i.toShort())
            eq?.let { e -> for (b in levels.indices) levels[b] = e.getBandLevel(b.toShort()).toInt() }
        } catch (_: Exception) { }
        preset = i
        persist()
    }

    fun reset() {
        for (b in levels.indices) {
            levels[b] = 0
            try { eq?.setBandLevel(b.toShort(), 0) } catch (_: Exception) { }
        }
        preset = -1
        persist()
    }

    private fun persist() {
        saved = levels.toList()
        sp?.edit()?.putBoolean("on", enabled)?.putString("lv", saved.joinToString(","))?.putInt("pr", preset)?.apply()
    }
}

private fun freqLabel(hz: Int): String =
    if (hz >= 1000) (hz / 1000f).let { if (it % 1f == 0f) "${it.toInt()} кГц" else "%.1f кГц".format(it) } else "$hz Гц"

private fun dbLabel(mB: Int): String = "%+.1f дБ".format(mB / 100f)

/** Нижний лист «Эквалайзер»: переключатель, пресеты устройства и ползунки по полосам. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = WP.surfaceHi) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = DS.padL).padding(bottom = DS.padL).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Эквалайзер", Modifier.weight(1f), color = WP.text, style = MaterialTheme.typography.titleLarge)
                Switch(Eq.enabled, { Eq.setEnabled(it) }, enabled = Eq.available)
            }
            if (!Eq.available) {
                Text("Эквалайзер подключается к плееру: запустите любой трек. Если он не появился, устройство его не поддерживает.",
                    Modifier.padding(vertical = DS.padL), color = WP.text2, style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = DS.padM),
                    horizontalArrangement = Arrangement.spacedBy(DS.padS)) {
                    Eq.presets.forEachIndexed { i, name ->
                        EqChip(name, Eq.preset == i) { Eq.usePreset(i) }
                    }
                    EqChip("Сброс", false) { Eq.reset() }
                }
                Eq.levels.forEachIndexed { i, v ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(freqLabel(Eq.freqs.getOrNull(i) ?: 0), Modifier.width(64.dp), color = WP.text2, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Slider(v.toFloat(), { Eq.setLevel(i, it.toInt()) }, Modifier.weight(1f),
                            valueRange = Eq.minLevel.toFloat()..Eq.maxLevel.toFloat())
                        Text(dbLabel(v), Modifier.width(68.dp), color = WP.text2, style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.End, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun EqChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, Modifier.clip(CircleShape).background(if (on) WP.selFill else WP.fill).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (on) WP.accentInk else WP.text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
}
