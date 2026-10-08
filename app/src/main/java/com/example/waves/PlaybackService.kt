package com.example.waves

import android.Manifest
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var player: ExoPlayer? = null
    private var viz: Visualizer? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        YandexApi.init(this)
        val http = DefaultDataSource.Factory(this)
        val resolving = ResolvingDataSource.Factory(http) { spec ->
            if (spec.uri.scheme == "ymusic")
                spec.withUri(Uri.parse(YandexApi.streamUrl(spec.uri.toString().removePrefix("ymusic://"))))
            else spec
        }
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true).build()
        p.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = startViz()
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && viz == null) startViz()
                if (!isPlaying) VizBus.clear()
            }
        })
        player = p
        session = MediaSession.Builder(this, p).build()
    }

    private fun startViz() {
        stopViz()
        val id = player?.audioSessionId ?: return
        if (id == C.AUDIO_SESSION_ID_UNSET) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try {
            viz = Visualizer(id).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, r: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, r: Int) { fft?.let(VizBus::push) }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (_: Exception) { viz = null }
    }

    private fun stopViz() { try { viz?.release() } catch (_: Exception) {}; viz = null }

    override fun onGetSession(info: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        stopViz()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
