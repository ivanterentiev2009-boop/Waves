package com.example.waves

import android.content.ComponentName
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

data class NowPlaying(val title: String? = null, val artist: String? = null,
                      val art: Uri? = null, val playing: Boolean = false, val index: Int = 0, val id: String? = null,
                      val shuffle: Boolean = false, val repeat: Int = 0)

@Composable
fun rememberController(): MediaController? {
    val ctx = LocalContext.current
    var c by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val f = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync()
        f.addListener({ c = f.get() }, ContextCompat.getMainExecutor(ctx))
        onDispose { MediaController.releaseFuture(f) }
    }
    return c
}

@Composable
fun rememberNowPlaying(c: MediaController?): NowPlaying {
    var s by remember { mutableStateOf(NowPlaying()) }
    DisposableEffect(c) {
        if (c == null) return@DisposableEffect onDispose {}
        fun upd() { s = NowPlaying(c.mediaMetadata.title?.toString(), c.mediaMetadata.artist?.toString(),
            c.mediaMetadata.artworkUri, c.isPlaying, c.currentMediaItemIndex, c.currentMediaItem?.mediaId, c.shuffleModeEnabled, c.repeatMode) }
        val l = object : Player.Listener { override fun onEvents(p: Player, e: Player.Events) = upd() }
        c.addListener(l); upd()
        onDispose { c.removeListener(l) }
    }
    return s
}
