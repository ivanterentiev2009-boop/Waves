package com.example.waves

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val dimText = Color(0xB3FFFFFF)
private val faintText = Color(0x80FFFFFF)
private val errText = Color(0xFFFFB4AB)

private val THEME_NAMES = listOf("Стекло", "Матовое стекло", "Минимализм", "AMOLED")
private val THEME_IDS = listOf(0, 3, 1, 2)

private fun themeName(id: Int) = THEME_NAMES[THEME_IDS.indexOf(id).coerceAtLeast(0)]

// ---------- каркас ----------

/** Группа настроек: стеклянная карточка с заголовком, как «Недавние» на главной. */
@Composable
private fun Group(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().panel(RoundedCornerShape(Style.corner.dp), refract = true).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (title != null) Text(title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

/** Внутренняя страница: стрелка назад и крупный заголовок, как у экрана «Недавние». */
@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
            Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = dimText, style = MaterialTheme.typography.bodySmall)
}

/** Строка раздела на главной странице настроек: значок, название, краткое текущее значение. */
@Composable
private fun NavRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().panel(RoundedCornerShape(Style.corner.dp), refract = true)
        .clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(Color(Style.accent)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.Black)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(summary, color = dimText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(0.7f))
    }
}

// ---------- элементы ----------

@Composable
private fun SwitchRow(label: String, value: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onChange(!value) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = Color.White)
            if (hint != null) Text(hint, color = dimText, style = MaterialTheme.typography.bodySmall)
        }
        Switch(value, onChange)
    }
}

@Composable
private fun SliderRow(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>,
                      onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = Color.White)
            Text(valueText, color = dimText, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value, onChange, valueRange = range, onValueChangeFinished = Style::save)
    }
}

/** Плитка темы с миниатюрой её фона. */
@Composable
private fun ThemeTile(id: Int, modifier: Modifier) {
    val sel = Style.theme == id
    val accent = Color(Style.accent)
    val preview: Brush = when (id) {
        0 -> Brush.linearGradient(listOf(accent.copy(0.75f), Color(0xFFFF8AD8).copy(0.55f), Color(0xFF5CC8FF).copy(0.55f)))
        3 -> Brush.verticalGradient(listOf(Color(0xFF2C2C36), Color(0xFF1B1B22)))
        1 -> SolidColor(Color(0xFF17171C))
        else -> SolidColor(Color.Black)
    }
    val border by animateColorAsState(if (sel) accent else Color.White.copy(0.12f), tween(250), label = "tb")
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.clip(shape).border(if (sel) 2.dp else 1.dp, border, shape)
        .clickable { Style.theme = id; Style.save() }.padding(6.dp)) {
        Box(Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(14.dp)).background(preview)) {
            // «панелька» внутри миниатюры показывает, как выглядят карточки в теме
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).fillMaxWidth(0.62f).height(14.dp)
                .clip(CircleShape).background(Color.White.copy(if (id == 0) 0.28f else 0.12f)))
        }
        Row(Modifier.padding(start = 6.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(themeName(id), Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sel) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = accent)
        }
    }
}

// ---------- главная страница настроек ----------

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { Transcriber.refresh(ctx) }
    var page by remember { mutableIntStateOf(-1) }
    BackHandler { if (page >= 0) page = -1 else onBack() }
    AppBackground(null) {
        Crossfade(page, animationSpec = tween(220), label = "sp") { p ->
            when (p) {
                0 -> LookPage { page = -1 }
                1 -> WavePage { page = -1 }
                2 -> OverlayPage { page = -1 }
                3 -> LyricsPage { page = -1 }
                5 -> ServicesPage { page = -1 }
                4 -> RecognitionPage { page = -1 }
                else -> Hub(onBack) { page = it }
            }
        }
    }
}

@Composable
private fun Hub(onBack: () -> Unit, open: (Int) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    remember { SoundCloud.init(ctx) }
    val lyricSources = listOf(Style.lyYandex, Style.lyLrclib, Style.lyOvh).count { it }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
        }
        Text("Настройки", Modifier.padding(start = 20.dp, bottom = 16.dp), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NavRow(Icons.Default.Palette, "Оформление", "${themeName(Style.theme)}, скругление ${Style.corner}") { open(0) }
            NavRow(Icons.Default.GraphicEq, "Моя волна", WAVE_STYLES[Style.waveStyle.coerceIn(0, WAVE_STYLES.lastIndex)].first) { open(1) }
            NavRow(Icons.Default.PictureInPicture, "Оверлей",
                "Размер ${(Style.ovScale * 100).toInt()}%, старт: " + listOf("пузырёк", "компактный", "полный")[Style.ovStart.coerceIn(0, 2)]) { open(2) }
            NavRow(Icons.Default.LibraryMusic, "Музыкальные сервисы",
                "Яндекс: " + (if (YandexApi.token.isNotBlank()) "вход выполнен" else "не выполнен") +
                    ", SoundCloud: " + (if (SoundCloud.hasKey) "ключ есть" else "нет ключа")) { open(5) }
            NavRow(Icons.Default.Subtitles, "Тексты песен", "Включено источников: $lyricSources из 3") { open(3) }
            NavRow(Icons.Default.Mic, "Распознавание текста",
                if (!Style.subsEnabled) "Выключено" else if (Style.asrEngine == 1) "GigaAM" else "Vosk") { open(4) }
        }
        Text("Waves " + (try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "" }) + ", сборка ${Style.BUILD}",
            Modifier.fillMaxWidth().padding(24.dp), color = faintText, style = MaterialTheme.typography.bodySmall)
    }
}

// ---------- страницы ----------

@Composable
private fun LookPage(onBack: () -> Unit) {
    Page("Оформление", onBack) {
        Group("Тема") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeTile(THEME_IDS[0], Modifier.weight(1f)); ThemeTile(THEME_IDS[1], Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeTile(THEME_IDS[2], Modifier.weight(1f)); ThemeTile(THEME_IDS[3], Modifier.weight(1f))
            }
        }
        Group("Акцентный цвет") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0xFFB69CFF, 0xFF7CC4FF, 0xFF6EE7B7, 0xFFFF8AD8, 0xFFFFB86B, 0xFFFFFFFF).forEach { l ->
                    val c = l.toInt()
                    Box(Modifier.size(42.dp).clip(CircleShape).background(Color(c))
                        .border(if (Style.accent == c) 3.dp else 0.dp, Color.White.copy(0.9f), CircleShape)
                        .clickable { Style.accent = c; Style.save() }, contentAlignment = Alignment.Center) {
                        if (Style.accent == c) Icon(Icons.Default.Check, null, tint = Color.Black)
                    }
                }
            }
        }
        Group("Стекло и формы") {
            SliderRow("Прозрачность стекла", "${(Style.glass / 0.30f * 100).toInt()}%", Style.glass, 0.04f..0.30f) { Style.glass = it }
            if (glassCapable) SliderRow("Сила преломления", "×" + "%.1f".format(Style.refract), Style.refract, 0.4f..2.5f) { Style.refract = it }
            SliderRow("Скругление углов", "${Style.corner} dp", Style.corner.toFloat(), 8f..36f) { Style.corner = it.toInt() }
        }
        Group("Эквалайзер") {
            SwitchRow("Показывать визуализацию", Style.showViz, "В плеере, оверлее и на «Моей волне»") { Style.showViz = it; Style.save() }
        }
    }
}

@Composable
private fun WavePage(onBack: () -> Unit) {
    Page("Моя волна", onBack) {
        Group("Откуда брать музыку") {
            Hint("Можно включить несколько источников сразу: треки из них будут чередоваться. Волны по артисту и по песне всегда из Яндекса.")
            SwitchRow("Яндекс Музыка", Style.waveYandex, "Рекомендации «Моей волны» (нужен вход)") { Style.waveYandex = it; Style.save() }
            SwitchRow("SoundCloud", Style.waveSc, "Похожие на играющий трек или популярное") { Style.waveSc = it; Style.save() }
            SwitchRow("Файлы на телефоне", Style.waveLocal, "Случайные треки из твоей памяти") { Style.waveLocal = it; Style.save() }
            SwitchRow("Свой сервер", Style.waveSub, "Случайные треки с Navidrome и похожих") { Style.waveSub = it; Style.save() }
            SwitchRow("Мне нравится из Яндекса", Style.waveLikes, "Случайные из понравившихся (нужен вход)") { Style.waveLikes = it; Style.save() }
        }
        Hint("Вид меняется сразу на главной вкладке «Волна». Нужен вход в Яндекс Музыку.")
        Group {
            WAVE_STYLES.forEachIndexed { i, (name, desc) ->
                val sel = Style.waveStyle == i
                val bg by animateColorAsState(if (sel) Color(Style.accent).copy(0.22f) else Color.Transparent, tween(250), label = "wb")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape((Style.corner * 0.7f).dp)).background(bg)
                    .clickable { Style.waveStyle = i; Style.save() }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, color = Color.White, fontWeight = FontWeight.Medium)
                        Text(desc, color = dimText, style = MaterialTheme.typography.bodySmall)
                    }
                    if (sel) Icon(Icons.Default.Check, null, tint = Color(Style.accent))
                }
            }
        }
    }
}

@Composable
private fun OverlayPage(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val blurOk = android.os.Build.VERSION.SDK_INT >= 31 && ctx.getSystemService(android.view.WindowManager::class.java).isCrossWindowBlurEnabled
    Page("Оверлей", onBack) {
        Hint("Изменения видны сразу, если оверлей включён.")
        Group("Вид") {
            Text("Вид при запуске", color = Color.White)
            Pill(listOf("Пузырёк", "Компактный", "Полный"), Style.ovStart, { Style.ovStart = it; Style.save() })
            SliderRow("Размер", "${(Style.ovScale * 100).toInt()}%", Style.ovScale, 0.7f..1.4f) { Style.ovScale = it }
            SliderRow("Плотность", "${(Style.overlayAlpha * 100).toInt()}%", Style.overlayAlpha, 0.3f..1f) { Style.overlayAlpha = it }
        }
        Group("Что показывать") {
            SwitchRow("Обложка", Style.ovCover) { Style.ovCover = it; Style.save() }
            SwitchRow("Эквалайзер", Style.ovEq) { Style.ovEq = it; Style.save() }
            SwitchRow("Строка текста", Style.ovLyric, "В компактном виде") { Style.ovLyric = it; Style.save() }
        }
        Group("Эксперимент: размытие под оверлеем") {
            Text(if (blurOk) "Размытие окон на устройстве доступно" else "На этом устройстве размытие окон отключено или не поддерживается",
                color = if (blurOk) Color(0xFF6EE7B7) else errText, style = MaterialTheme.typography.bodySmall)
            SwitchRow("Системное размытие фона", Style.blurBehind, "Android 12+. Углы размытия могут быть квадратными.") { Style.blurBehind = it; Style.save() }
        }
        TextButton({ Style.ovScale = 1f; Style.ovCover = true; Style.ovEq = true; Style.ovLyric = true; Style.ovStart = 1; Style.save() },
            Modifier.align(Alignment.CenterHorizontally)) { Text("Сбросить оверлей") }
    }
}

@Composable
private fun LyricsPage(onBack: () -> Unit) {
    Page("Тексты песен", onBack) {
        Hint("Если в одном источнике нет текста, приложение пробует следующий. Работает для любых треков, в том числе локальных.")
        Group {
            SwitchRow("Яндекс Музыка", Style.lyYandex, "Для её треков") { Style.lyYandex = it; Style.save() }
            SwitchRow("LRCLIB", Style.lyLrclib, "Открытая база, есть таймкоды") { Style.lyLrclib = it; Style.save() }
            SwitchRow("lyrics.ovh", Style.lyOvh, "Только обычный текст") { Style.lyOvh = it; Style.save() }
            TextButton({ LyricsCache.clearDisk(); android.widget.Toast.makeText(AppData.app, "Сохранённые тексты удалены", android.widget.Toast.LENGTH_SHORT).show() }) { Text("Очистить сохранённые тексты") }
        }
    }
}

@Composable
private fun RecognitionPage(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var showCatalog by remember { mutableStateOf(false) }
    if (showCatalog) ModelCatalogDialog { showCatalog = false }
    Page("Распознавание текста", onBack) {
        Group {
            SwitchRow("Распознавать текст песен на устройстве", Style.subsEnabled,
                "Для локальных треков без текста. Работает офлайн, точность зависит от модели и музыки.") { Style.subsEnabled = it; Style.save() }
            Hint("Исправить текст можно долгим нажатием на строку, а готовый .lrc загрузить кнопкой на экране текста.")
        }
        if (Style.subsEnabled) {
            Group("Движок") {
                Pill(listOf("Vosk", "GigaAM"), Style.asrEngine, { Style.asrEngine = it; Style.save() })
            }        }
        if (Style.subsEnabled && Style.asrEngine == 1) {
            Group("GigaAM") {
                Hint("Для русского заметно точнее Vosk, слова подсвечиваются по точному времени. Модель около 285 МБ. " +
                    "Лицензия модели некоммерческая: только для личного использования.")
                Text(if (Transcriber.gigaReady) "Модель установлена" else "Модель не скачана", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                if (Transcriber.gigaBusy) {
                    Text(Transcriber.gigaStage, color = Color.White, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator({ Transcriber.gigaProgress }, Modifier.fillMaxWidth())
                }
                Transcriber.gigaError?.let { Text(it, color = errText, style = MaterialTheme.typography.bodySmall) }
                if (!Transcriber.gigaReady) Button({ Transcriber.downloadGiga(ctx) }, enabled = !Transcriber.gigaBusy) { Text("Скачать GigaAM") }
                else TextButton({ Transcriber.deleteGiga(ctx) }) { Text("Удалить модель") }
            }
        }
        if (Style.subsEnabled && Style.asrEngine == 0) {
            Group("Vosk") {
                Text("Активная модель: " + Transcriber.activeModel.ifBlank { "не выбрана" }, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                if (Transcriber.modelBusy) {
                    Text("${Transcriber.modelName}: ${Transcriber.modelStage}", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator({ Transcriber.modelProgress }, Modifier.fillMaxWidth())
                }
                Transcriber.modelError?.let { Text(it, color = errText, style = MaterialTheme.typography.bodySmall) }
                if (Transcriber.logs.isNotEmpty()) Text(Transcriber.logs.takeLast(6).joinToString("\n"), color = faintText, style = MaterialTheme.typography.bodySmall)
                Button({ showCatalog = true }) { Text("Выбрать модель из каталога Vosk") }
            }
            if (Transcriber.installed.isNotEmpty()) Group("Установленные модели") {
                Transcriber.installed.forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name + if (name == Transcriber.activeModel) "  ✓" else "", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        if (name != Transcriber.activeModel) TextButton({ Transcriber.select(ctx, name) }) { Text("Выбрать") }
                        TextButton({ Transcriber.deleteModel(ctx, name) }) { Text("Удалить") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SoundCloudWebDialog(onKey: (String) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    webViewClient = object : WebViewClient() {
                        private var done = false
                        // сайт сам шлёт запросы с ключом: ловим первый такой запрос
                        override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                            val k = SoundCloud.fromUrl(r.url.toString())
                            if (!done && k != null) { done = true; v.post { onKey(k) } }
                            return null
                        }
                    }
                    loadUrl("https://soundcloud.com")
                }
            })
            Text("Подожди, пока страница загрузится: ключ поймается сам", Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 16.dp, top = 12.dp, end = 64.dp),
                color = Color.White, style = MaterialTheme.typography.bodySmall)
            IconButton(onClose, Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
                Icon(Icons.Default.Close, null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun ServicesPage(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    remember { SoundCloud.init(ctx) }
    val scope = rememberCoroutineScope()
    var yLogin by remember { mutableStateOf(false) }
    var scWeb by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(true) }
    val green = Color(0xFF6EE7B7)
    if (yLogin) LoginDialog(onToken = { url -> YandexApi.saveToken(url); yLogin = false }, onClose = { yLogin = false })
    if (scWeb) SoundCloudWebDialog(
        onKey = { k -> SoundCloud.setKey(k, "через сайт"); scWeb = false; msg = "Ключ получен с сайта"; ok = true },
        onClose = { scWeb = false })
    Page("Музыкальные сервисы", onBack) {
        Group("Яндекс Музыка") {
            val logged = YandexApi.token.isNotBlank()
            Text(if (logged) "Вход выполнен" else "Вход не выполнен", color = if (logged) green else dimText)
            Hint("Вход открывается во встроенном окне Яндекса. Приложение не видит пароль: оно получает только токен и хранит его на телефоне.")
            Button({ yLogin = true }) { Text(if (logged) "Войти заново" else "Войти через Яндекс") }
            if (logged) TextButton({
                YandexApi.logout(); YState.likes = emptyList(); YState.results = emptyList(); YState.wave = emptyList(); Wave.active = false
            }) { Text("Выйти") }
        }
        Group("SoundCloud") {
            Text(if (SoundCloud.hasKey) "Ключ есть" + (if (SoundCloud.how.isNotBlank()) " (${SoundCloud.how})" else "") else "Ключа пока нет",
                color = if (SoundCloud.hasKey) green else dimText)
            Hint("Ключ нужен для поиска и воспроизведения. Вход в аккаунт не требуется. Выбери любой способ, начни с первого.")
            Text("Способ 1. Автоматически", color = Color.White, fontWeight = FontWeight.Medium)
            Hint("Приложение само найдёт ключ на сайте SoundCloud.")
            Button({
                scope.launch {
                    busy = true; msg = null
                    try {
                        withContext(Dispatchers.IO) { SoundCloud.refreshAuto(); SoundCloud.check() }
                        msg = "Готово: ключ получен и проверен"; ok = true
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { msg = "Не вышло: ${e.message}"; ok = false }
                    busy = false
                }
            }, enabled = !busy) { Text(if (busy) "Ищу…" else "Получить автоматически") }
            HorizontalDivider(color = Color.White.copy(0.10f))
            Text("Способ 2. Через сайт", color = Color.White, fontWeight = FontWeight.Medium)
            Hint("Откроется soundcloud.com. Дождись загрузки страницы, ключ поймается сам, нажимать ничего не нужно.")
            Button({ scWeb = true }) { Text("Открыть soundcloud.com") }
            HorizontalDivider(color = Color.White.copy(0.10f))
            Text("Способ 3. Вставить вручную", color = Color.White, fontWeight = FontWeight.Medium)
            Hint("Вставь ключ из 32 символов или любую ссылку из браузера, где есть client_id=.")
            OutlinedTextField(manual, { manual = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Ключ или ссылка") })
            Button({
                val k = SoundCloud.parseKey(manual)
                if (k == null) { msg = "Не нашёл ключ в этом тексте"; ok = false }
                else { SoundCloud.setKey(k, "вручную"); msg = "Ключ сохранён"; ok = true; manual = "" }
            }, enabled = manual.isNotBlank()) { Text("Сохранить ключ") }
            msg?.let { Text(it, color = if (ok) green else errText, style = MaterialTheme.typography.bodySmall) }
            if (SoundCloud.hasKey) TextButton({ SoundCloud.reset(); msg = "Ключ удалён"; ok = true }) { Text("Удалить ключ") }
        }
    }
}
