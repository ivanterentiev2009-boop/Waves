package com.example.waves

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
            if (glassCapable) Card("Оверлей") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Преломление в оверлее (красивее, но медленнее). Выключено: быстрый режим.",
                        Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodySmall)
                    Switch(Style.overlayRefract, { Style.overlayRefract = it; Style.save() })
                }
            }
            Card("Оверлей") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Облегчённый режим: быстрее и плавнее, без преломления. Выключи, чтобы увидеть шейдер.",
                        Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodySmall)
                    Switch(Style.lightOverlay, { Style.lightOverlay = it; Style.save() })
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
