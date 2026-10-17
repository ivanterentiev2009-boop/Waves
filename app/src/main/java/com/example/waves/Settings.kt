package com.example.waves

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().panel(RoundedCornerShape(Style.corner.dp), refract = true).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Color(0xB3FFFFFF), style = MaterialTheme.typography.labelLarge)
        content()
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { Transcriber.refresh(ctx) }
    var showCatalog by remember { mutableStateOf(false) }
    if (showCatalog) ModelCatalogDialog { showCatalog = false }
    val blurOk = android.os.Build.VERSION.SDK_INT >= 31 && ctx.getSystemService(android.view.WindowManager::class.java).isCrossWindowBlurEnabled
    AppBackground(null) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
                Text("Стиль", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light, color = Color.White)
            }
            Text("Сборка ${Style.BUILD}", color = Color(0x80FFFFFF), style = MaterialTheme.typography.bodySmall)
            Card("Тема") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Стекло", "Минимализм", "AMOLED").forEachIndexed { i, n ->
                        FilterChip(Style.theme == i, { Style.theme = i; Style.save() }, { Text(n) })
                    }
                }
            }
            Card("Акцентный цвет") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(0xFFB69CFF, 0xFF7CC4FF, 0xFF6EE7B7, 0xFFFF8AD8, 0xFFFFB86B, 0xFFFFFFFF).forEach { l ->
                        val c = l.toInt()
                        Box(Modifier.size(38.dp).clip(CircleShape).background(Color(c))
                            .border(if (Style.accent == c) 3.dp else 0.dp, Color.White.copy(0.9f), CircleShape)
                            .clickable { Style.accent = c; Style.save() })
                    }
                }
            }
            Card("Прозрачность стекла") {
                Slider(Style.glass, { Style.glass = it }, valueRange = 0.04f..0.30f, onValueChangeFinished = Style::save)
            }
            if (glassCapable) Card("Сила преломления") {
                Slider(Style.refract, { Style.refract = it }, valueRange = 0.4f..2.5f, onValueChangeFinished = Style::save)
            }
            Card("Скругление углов") {
                Slider(Style.corner.toFloat(), { Style.corner = it.toInt() }, valueRange = 8f..36f, onValueChangeFinished = Style::save)
            }
            Card("Плотность оверлея") {
                Slider(Style.overlayAlpha, { Style.overlayAlpha = it }, valueRange = 0.3f..1f, onValueChangeFinished = Style::save)
            }
            Card("Вид «Моей волны»") {
                Text("Меняется сразу: Яндекс Музыка → Моя волна.", color = Color(0x99FFFFFF), style = MaterialTheme.typography.bodySmall)
                WAVE_STYLES.forEachIndexed { i, (name, desc) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { Style.waveStyle = i; Style.save() }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(Style.waveStyle == i, null)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(name, color = Color.White)
                            Text(desc, color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Card("Источники текстов") {
                Text("Если в одном нет текста, приложение пробует следующий. Для любых треков, в том числе локальных.",
                    color = Color(0x99FFFFFF), style = MaterialTheme.typography.bodySmall)
                SwitchRow("Яндекс Музыка (для её треков)", Style.lyYandex) { Style.lyYandex = it; Style.save() }
                SwitchRow("LRCLIB (открытая база, есть таймкоды)", Style.lyLrclib) { Style.lyLrclib = it; Style.save() }
                SwitchRow("lyrics.ovh (только обычный текст)", Style.lyOvh) { Style.lyOvh = it; Style.save() }
            }
            Card("Субтитры (Vosk)") {
                SwitchRow("Распознавать текст песен на устройстве", Style.subsEnabled) { Style.subsEnabled = it; Style.save() }
                Text("Для локальных треков без текста. Работает офлайн; точность зависит от модели и музыки. " +
                    "Исправить текст можно долгим нажатием на строку, а готовый .lrc загрузить кнопкой на экране текста.",
                    color = Color(0x99FFFFFF), style = MaterialTheme.typography.bodySmall)
                if (Style.subsEnabled) {
                    Pill(listOf("Vosk", "GigaAM"), Style.asrEngine, { Style.asrEngine = it; Style.save() })
                }
                if (Style.subsEnabled && Style.asrEngine == 1) {
                    Text("GigaAM (Сбер): для русского заметно точнее Vosk, слова подсвечиваются по точному времени. Модель около 285 МБ. " +
                        "Лицензия модели некоммерческая: только для личного использования.",
                        color = Color(0x99FFFFFF), style = MaterialTheme.typography.bodySmall)
                    Text(if (Transcriber.gigaReady) "Модель установлена" else "Модель не скачана", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    if (Transcriber.gigaBusy) {
                        Text(Transcriber.gigaStage, color = Color.White, style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator({ Transcriber.gigaProgress }, Modifier.fillMaxWidth())
                    }
                    Transcriber.gigaError?.let { Text(it, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall) }
                    if (!Transcriber.gigaReady) Button({ Transcriber.downloadGiga(ctx) }, enabled = !Transcriber.gigaBusy) { Text("Скачать GigaAM") }
                    else TextButton({ Transcriber.deleteGiga(ctx) }) { Text("Удалить модель") }
                }
                if (Style.subsEnabled && Style.asrEngine == 0) {
                    Text("Активная модель: " + Transcriber.activeModel.ifBlank { "не выбрана" }, color = Color.White, style = MaterialTheme.typography.bodySmall)
                    if (Transcriber.modelBusy) {
                        Text("${Transcriber.modelName}: ${Transcriber.modelStage}", color = Color.White, style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator({ Transcriber.modelProgress }, Modifier.fillMaxWidth())
                    }
                    Transcriber.modelError?.let { Text(it, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall) }
                    if (Transcriber.logs.isNotEmpty()) Text(Transcriber.logs.takeLast(6).joinToString("\n"),
                        color = Color(0x80FFFFFF), style = MaterialTheme.typography.bodySmall)
                    Button({ showCatalog = true }) { Text("Выбрать модель (весь каталог Vosk)") }
                    Transcriber.installed.forEach { name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name + if (name == Transcriber.activeModel) "  ✓" else "", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodySmall)
                            if (name != Transcriber.activeModel) TextButton({ Transcriber.select(ctx, name) }) { Text("Выбрать") }
                            TextButton({ Transcriber.deleteModel(ctx, name) }) { Text("Удалить") }
                        }
                    }
                }
            }
            Card("Редактор оверлея") {
                Text("Изменения видны сразу, если оверлей включён.", color = Color(0x99FFFFFF), style = MaterialTheme.typography.bodySmall)
                Text("Размер: ${(Style.ovScale * 100).toInt()}%", color = Color.White)
                Slider(Style.ovScale, { Style.ovScale = it }, valueRange = 0.7f..1.4f, onValueChangeFinished = Style::save)
                SwitchRow("Обложка", Style.ovCover) { Style.ovCover = it; Style.save() }
                SwitchRow("Эквалайзер", Style.ovEq) { Style.ovEq = it; Style.save() }
                SwitchRow("Строка текста в компактном виде", Style.ovLyric) { Style.ovLyric = it; Style.save() }
                Text("Вид при запуске", color = Color.White)
                Pill(listOf("Пузырёк", "Компактный", "Полный"), Style.ovStart, { Style.ovStart = it; Style.save() })
                TextButton({ Style.ovScale = 1f; Style.ovCover = true; Style.ovEq = true; Style.ovLyric = true; Style.ovStart = 1; Style.save() }) {
                    Text("Сбросить")
                }
            }
            Card("Эксперимент: размытие под оверлеем") {
                Text(if (blurOk) "Размытие окон на устройстве доступно" else "На этом устройстве размытие окон отключено или не поддерживается",
                    color = if (blurOk) Color(0xFF6EE7B7) else Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Системное размытие фона за оверлеем (Android 12+). Углы размытия могут быть квадратными.",
                        Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodySmall)
                    Switch(Style.blurBehind, { Style.blurBehind = it; Style.save() })
                }
            }
            Card("Эквалайзер") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Показывать визуализацию", Modifier.weight(1f), color = Color.White)
                    Switch(Style.showViz, { Style.showViz = it; Style.save() })
                }
            }
            Text("Waves " + (try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "" }),
                Modifier.fillMaxWidth().padding(8.dp), color = Color(0x80FFFFFF), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Color.White)
        Switch(value, onChange)
    }
}
