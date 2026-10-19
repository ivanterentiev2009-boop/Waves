package com.example.waves

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Каталог всех моделей Vosk: список берётся с сайта Vosk, поэтому в нём всегда актуальные модели всех языков. */
@Composable
fun ModelCatalogDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Transcriber.refresh(ctx); if (Transcriber.catalog.isEmpty()) Transcriber.fetchCatalog() }
    var q by remember { mutableStateOf("") }
    var kind by remember { mutableIntStateOf(0) }   // 0 все, 1 лёгкие, 2 большие
    val list = Transcriber.catalog.filter { m ->
        (kind == 0 || (kind == 1 && m.type == "small") || (kind == 2 && m.type != "small")) &&
            (q.isBlank() || listOf(m.name, m.lang, m.langText).any { it.contains(q.trim(), ignoreCase = true) })
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0B10)).statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Модели Vosk: ${list.size} из ${Transcriber.catalog.size}", Modifier.weight(1f), color = Color.White,
                    style = MaterialTheme.typography.titleMedium)
                IconButton(onClose) { Icon(Icons.Default.Close, null, tint = Color.White) }
            }
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), singleLine = true,
                placeholder = { Text("Язык или название (russian, ru, en, de…)") })
            Pill(listOf("Все", "Лёгкие", "Большие"), kind, { kind = it })
            Text("Большие модели весят сотни мегабайт и до гигабайт, на телефоне могут не запуститься. Лёгкие (small) быстрее, но менее точны.",
                Modifier.padding(vertical = 8.dp), color = Color(0x99FFFFFF), style = MaterialTheme.typography.bodySmall)
            if (Transcriber.catalogLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Transcriber.catalogError?.let {
                Text(it, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall)
                TextButton({ Transcriber.fetchCatalog() }) { Text("Повторить") }
            }
            Transcriber.modelError?.let { Text(it, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall) }
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.name }) { m ->
                    val have = m.name in Transcriber.installed
                    val loading = Transcriber.modelBusy && Transcriber.modelName == m.name
                    val voice = m.name.contains("spk") || m.lang == "all"
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(m.name, color = Color.White)
                        Text(m.langText + " · " + m.sizeText + " · " + m.type + (if (m.obsolete) " · устарела" else "") +
                            (if (voice) " · модель голоса, для текста не подходит" else ""),
                            color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when {
                                loading -> Column(Modifier.weight(1f)) {
                                    Text(Transcriber.modelStage, color = Color.White, style = MaterialTheme.typography.bodySmall)
                                    LinearProgressIndicator({ Transcriber.modelProgress }, Modifier.fillMaxWidth())
                                }
                                have && m.name == Transcriber.activeModel -> Text("Активна ✓", Modifier.weight(1f), color = Color(Style.accent))
                                have -> { Spacer(Modifier.weight(1f)); TextButton({ Transcriber.select(ctx, m.name) }) { Text("Выбрать") }
                                    TextButton({ Transcriber.deleteModel(ctx, m.name) }) { Text("Удалить") } }
                                else -> { Spacer(Modifier.weight(1f))
                                    FilledTonalButton({ Transcriber.download(ctx, m) }, enabled = !Transcriber.modelBusy) { Text("Скачать") } }
                            }
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(0.08f))
                }
            }
        }
    }
}
