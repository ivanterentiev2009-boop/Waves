package com.example.waves

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Откуда берётся музыка в «Моей волне». В настройках можно включить несколько источников сразу:
 * тогда треки из них чередуются. Волны по артисту и по песне всегда строятся только из Яндекса.
 */
object WaveMix {
    private fun enabled(): Set<String> {
        val s = mutableSetOf<String>()
        if (Style.waveYandex) s += "ym"
        if (Style.waveSc) s += "sc"
        if (Style.waveLocal) s += "local"
        if (Style.waveSub) s += "sub"
        if (Style.waveLikes) s += "likes"
        if (s.isEmpty()) s += "ym"   // хоть что-то должно быть включено
        return s
    }

    /** Первая подборка. seedId: трек, от которого строить похожие (для SoundCloud). */
    suspend fun start(ctx: Context, station: String, seedId: String?): List<Track> {
        if (station != "user:onyourwave") return withContext(Dispatchers.IO) { YandexApi.wave(null, station) }
        return mix(ctx, null, seedId, emptySet(), 12)
    }

    /** Продолжение, когда очередь подходит к концу. have: id уже стоящих в очереди треков. */
    suspend fun more(ctx: Context, station: String, lastYmId: String?, seedId: String?, have: Set<String>): List<Track> {
        if (station != "user:onyourwave") return withContext(Dispatchers.IO) { YandexApi.wave(lastYmId, station) }.filter { it.id !in have }
        return mix(ctx, lastYmId, seedId, have, 8).filter { it.id !in have }
    }

    private suspend fun mix(ctx: Context, lastYm: String?, seedId: String?, have: Set<String>, n: Int): List<Track> {
        Subsonic.init(ctx); SoundCloud.init(ctx)
        val src = enabled()
        val lists = mutableListOf<List<Track>>()
        var firstErr: Exception? = null
        suspend fun part(block: suspend () -> List<Track>) {
            try { lists += block().filter { it.id !in have }.take(n) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (firstErr == null) firstErr = e }
        }
        val yandexOk = YandexApi.token.isNotBlank()
        if ("ym" in src && yandexOk) part { withContext(Dispatchers.IO) { YandexApi.wave(lastYm, "user:onyourwave") } }
        if ("sc" in src) part {
            withContext(Dispatchers.IO) {
                val sid = seedId?.takeIf { it.startsWith("sc") }
                (if (sid != null) SoundCloud.related(sid.removePrefix("sc")) else emptyList()).ifEmpty { SoundCloud.search("") }
            }
        }
        if ("local" in src) part { LocalSource(ctx).tracks().shuffled() }
        if ("sub" in src && Subsonic.configured) part { withContext(Dispatchers.IO) { Subsonic.tracks("") } }
        if ("likes" in src && yandexOk) part { YState.likes.shuffled() }
        val out = interleave(lists)
        val err = firstErr
        if (out.isEmpty() && err != null) throw err
        return out
    }

    /** По одному треку из каждого источника по кругу. */
    private fun interleave(lists: List<List<Track>>): List<Track> {
        val out = ArrayList<Track>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) l.getOrNull(i)?.let { out += it }
        return out.distinctBy { it.id }
    }
}
