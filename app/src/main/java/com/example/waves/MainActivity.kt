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
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Cloud
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
    val startPlayTop: (List<Track>, Int) -> Unit = { list, i ->
        Wave.active = false
        c?.run { setMediaItems(list.map { it.toMediaItem() }, i, 0); prepare(); play() }
    }
    WaveFeeder(c, np)
    Crossfade(showLyrics, animationSpec = tween(350), label = "screen") { lyr ->
    if (lyr) PlayerScreen(c, np) { showLyrics = false } else AppBackground(np.art) {
        StreamingHome(c, np, tracks, startPlayTop, onSettings) { showLyrics = true }
    }
    }
}

