package com.example.waves

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Style.init(this)
        YandexApi.init(this)
        setContent { WavesTheme { var st by remember { mutableStateOf(false) }; if (st) SettingsScreen { st = false } else Home { st = true } } }
    }
}

@Composable
fun Home(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val c = rememberController()
    val np = rememberNowPlaying(c)
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var tick by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    LaunchedEffect(tick) {
        val need = listOf(perm, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { ctx.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) tracks = LocalSource(ctx).tracks()
        if (need.isNotEmpty() && tick == 0) launcher.launch(need.toTypedArray())
    }
    var tab by remember { mutableIntStateOf(0) }
    var showLyrics by remember { mutableStateOf(false) }
    Crossfade(showLyrics, animationSpec = tween(350), label = "screen") { lyr ->
    if (lyr) LyricsScreen(c, np) { showLyrics = false } else AppBackground(np.art) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(20.dp, 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Waves", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Light, color = Color.White)
                IconButton(onSettings) { Icon(Icons.Default.Tune, null, tint = Color.White) }
                FilledTonalButton({
                    if (Settings.canDrawOverlays(ctx)) ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
                    else ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Icon(Icons.Default.PictureInPicture, null); Spacer(Modifier.width(6.dp)); Text("Оверлей") }
            }
            Pill(listOf("Устройство", "Яндекс Музыка"), tab, { tab = it }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            val startPlay: (List<Track>, Int) -> Unit = { list, i ->
                c?.run { setMediaItems(list.map { it.toMediaItem() }, i, 0); prepare(); play() }
            }
            if (tab == 0) TrackList(tracks, np.index, { i -> startPlay(tracks, i) }, Modifier.weight(1f))
            else YandexTab(np.index, startPlay, Modifier.weight(1f))
            Box(Modifier.navigationBarsPadding()) {
                Column {
                    Equalizer(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 20.dp), np.playing)
                    MiniPlayer(c, np) { showLyrics = true }
                }
            }
        }
    }
    }
}
