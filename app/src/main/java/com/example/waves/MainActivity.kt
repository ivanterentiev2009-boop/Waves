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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashFile = java.io.File(filesDir, "crash.txt")
        val report = try { if (crashFile.exists()) crashFile.readText() else null } catch (_: Throwable) { null }
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try { crashFile.writeText(android.util.Log.getStackTraceString(e)) } catch (_: Throwable) { }
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
        if (report != null) {
            // прошлый запуск закончился сбоем: показываем текст ошибки, чтобы его можно было скопировать
            setContent { WavesTheme { CrashScreen(report) {
                crashFile.delete()
                Style.init(this); YandexApi.init(this)
                setContent { WavesTheme { var st by remember { mutableStateOf(false) }; if (st) SettingsScreen { st = false } else Home { st = true } } }
            } } }
            return
        }
        Style.init(this)
        YandexApi.init(this)
        setContent { WavesTheme { var st by remember { mutableStateOf(false) }; if (st) SettingsScreen { st = false } else Home { st = true } } }
    }
}

@Composable
private fun CrashScreen(text: String, onContinue: () -> Unit) {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    Column(Modifier.fillMaxSize().background(Color(0xFF08080D)).statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Text("Приложение закрылось с ошибкой", color = Color.White, fontSize = 22.sp)
        Text("Нажми «Скопировать» и пришли текст в чат.", color = Color(0xB3FFFFFF), modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ clip.setText(androidx.compose.ui.text.AnnotatedString(text)) }) { Text("Скопировать") }
            OutlinedButton(onContinue) { Text("Продолжить") }
        }
        androidx.compose.foundation.text.selection.SelectionContainer(
            Modifier.weight(1f).padding(top = 12.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text(text.take(6000), color = Color(0xFFFFB4AB), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
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

