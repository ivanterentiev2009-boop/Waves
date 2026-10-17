package com.example.waves

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun YandexTab(current: Int, onPlay: (List<Track>, Int) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var showLogin by remember { mutableStateOf(false) }

    fun load(q: String?) = scope.launch {
        loading = true; status = null
        try {
            tracks = withContext(Dispatchers.IO) { if (q.isNullOrBlank()) YandexApi.likes() else YandexApi.search(q) }
            if (tracks.isEmpty()) status = "Ничего не найдено"
        } catch (e: Exception) { status = "Ошибка: ${e.message}" }
        loading = false
    }
    LaunchedEffect(YandexApi.token) { if (YandexApi.token.isNotBlank()) load(null) }

    if (showLogin) LoginDialog(
        onToken = { url -> YandexApi.saveToken(url); showLogin = false },
        onClose = { showLogin = false })

    Column(modifier) {
        if (YandexApi.token.isBlank()) {
            Column(Modifier.padding(16.dp).fillMaxWidth().panel(RoundedCornerShape(Style.corner.dp), refract = true).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Вход в Яндекс Музыку", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("Нажми «Войти через Яндекс», войди в аккаунт и разреши доступ: токен подхватится сам. Если не получилось, вставь адрес страницы или токен вручную ниже.",
                    color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodySmall)
                FilledTonalButton({ showLogin = true }) { Text("Войти через Яндекс") }
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Адрес или токен") })
                Button({ if (input.isNotBlank()) YandexApi.saveToken(input) }, enabled = input.isNotBlank()) { Text("Войти") }
            }
        } else {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Поиск (пусто: «Мне нравится»)") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { load(query) }))
                IconButton({ load(query) }) { Icon(Icons.Default.Search, null, tint = Color.White) }
                IconButton({ YandexApi.logout(); tracks = emptyList() }) { Icon(Icons.Default.Logout, null, tint = Color.White) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp, 8.dp))
            status?.let { Text(it, Modifier.padding(16.dp), color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall) }
            TrackList(tracks, current, { i -> onPlay(tracks, i) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LoginDialog(onToken: (String) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = object : WebViewClient() {
                        private var done = false
                        private fun check(url: String?): Boolean {
                            if (!done && url != null && url.contains("access_token=")) { done = true; onToken(url); return true }
                            return false
                        }
                        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = check(r.url.toString())
                        override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) { check(url) }
                        override fun onPageStarted(v: WebView, url: String?, f: Bitmap?) { check(url) }
                    }
                    loadUrl(YandexApi.AUTH_URL)
                }
            })
            IconButton(onClose, Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
                Icon(Icons.Default.Close, null, tint = Color.White)
            }
        }
    }
}
