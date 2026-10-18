package com.example.waves

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Последние поисковые запросы, общие для всех источников. */
object SearchHistory {
    private var sp: SharedPreferences? = null
    val items = mutableStateListOf<String>()

    fun init(ctx: Context) {
        if (sp != null) return
        val s = ctx.applicationContext.getSharedPreferences("searchhist", 0); sp = s
        items.addAll(s.getString("items", "").orEmpty().split("\n").filter { it.isNotBlank() })
    }

    fun add(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        items.removeAll { it.equals(t, ignoreCase = true) }
        items.add(0, t)
        while (items.size > 12) items.removeAt(items.lastIndex)
        sp?.edit()?.putString("items", items.joinToString("\n"))?.apply()
    }
}

/** Ряд «таблеток» с необязательным значком: фильтры результатов и переключатель источников. */
@Composable
fun ChipRow(items: List<Pair<ImageVector?, String>>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { i, (icon, label) ->
            val on = i == selected
            val bg by animateColorAsState(if (on) Color(Style.accent) else Color.Transparent, tween(250), label = "cb")
            val fg by animateColorAsState(if (on) Color.Black else Color.White, tween(250), label = "cf")
            val line by animateColorAsState(if (on) Color.Transparent else Color.White.copy(0.18f), tween(250), label = "cl")
            Row(Modifier.clip(CircleShape).background(bg).border(1.dp, line, CircleShape).clickable { onSelect(i) }
                .padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { Icon(icon, null, Modifier.size(20.dp), tint = fg); Spacer(Modifier.width(8.dp)) }
                Text(label, color = fg, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SourceBadge(text: String) {
    Box(Modifier.padding(end = 8.dp).size(38.dp).clip(CircleShape).background(Color.White.copy(0.10f)), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Поисковая строка: стеклянная «таблетка». По нажатию поверх экрана открывается панель с подсказками
 * и полем ввода над клавиатурой. badge: короткая метка источника справа, suggest: подсказки (может быть null).
 */
@Composable
fun SearchBox(value: String, onValueChange: (String) -> Unit, hint: String, badge: String,
              suggest: ((String) -> List<String>)?, modifier: Modifier = Modifier, onSearch: () -> Unit = {}) {
    val ctx = LocalContext.current
    remember { SearchHistory.init(ctx) }
    var open by remember { mutableStateOf(false) }
    Frosted(CircleShape, modifier.fillMaxWidth().height(56.dp)) {
        Row(Modifier.fillMaxSize().clickable { open = true }.padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, tint = Color.White.copy(0.7f))
            Spacer(Modifier.width(12.dp))
            Text(if (value.isBlank()) hint else value, Modifier.weight(1f),
                color = if (value.isBlank()) Color.White.copy(0.5f) else Color.White,
                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (value.isNotBlank()) IconButton({ onValueChange("") }) { Icon(Icons.Default.Close, "Очистить", tint = Color.White.copy(0.8f)) }
            else SourceBadge(badge)
        }
    }
    if (open) SearchOverlay(value, hint, badge, suggest,
        onSubmit = { q -> SearchHistory.add(q); onValueChange(q); onSearch(); open = false },
        onDismiss = { open = false })
}

@Composable
private fun SearchOverlay(initial: String, hint: String, badge: String, suggest: ((String) -> List<String>)?,
                          onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var sugg by remember { mutableStateOf<List<String>>(emptyList()) }
    val focus = remember { FocusRequester() }
    val accent = Color(Style.accent)
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: IllegalStateException) { } }
    LaunchedEffect(text.text) {
        val q = text.text.trim()
        if (q.isBlank() || suggest == null) { sugg = emptyList(); return@LaunchedEffect }
        delay(250)
        sugg = try { withContext(Dispatchers.IO) { suggest(q) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { emptyList() }
    }
    fun submit(q: String) { val t = q.trim(); if (t.isEmpty()) onDismiss() else onSubmit(t) }

    val q = text.text.trim()
    val fromHistory = q.isBlank() || sugg.isEmpty()
    val list = when {
        q.isBlank() -> SearchHistory.items.take(6)
        sugg.isNotEmpty() -> sugg.take(6)
        else -> SearchHistory.items.filter { it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) }.take(6)
    }
    val shape = RoundedCornerShape(32.dp)
    val noRipple = remember { MutableInteractionSource() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = noRipple, indication = null, onClick = onDismiss)) {
            Column(Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(12.dp).fillMaxWidth()
                .clip(shape).background(Color(0xF01E1E24)).border(1.dp, Color.White.copy(0.10f), shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }) {
                list.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { submit(s) }.padding(start = 22.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (fromHistory) Icons.Default.History else Icons.Default.Search, null, tint = Color.White.copy(0.75f))
                        Spacer(Modifier.width(18.dp))
                        val starts = q.isNotEmpty() && s.startsWith(q, ignoreCase = true)
                        Text(buildAnnotatedString {
                            if (starts) {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(s.take(q.length)) }
                                withStyle(SpanStyle(color = Color.White.copy(0.65f))) { append(s.drop(q.length)) }
                            } else withStyle(SpanStyle(color = Color.White)) { append(s) }
                        }, Modifier.weight(1f).padding(vertical = 14.dp), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton({ val n = s + " "; text = TextFieldValue(n, TextRange(n.length)) }) {
                            Icon(Icons.Default.NorthWest, "Вставить в строку", tint = Color.White.copy(0.6f))
                        }
                    }
                }
                if (list.isNotEmpty()) HorizontalDivider(color = Color.White.copy(0.08f))
                Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 22.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, tint = accent)
                    Spacer(Modifier.width(18.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (text.text.isEmpty()) Text(hint, color = Color.White.copy(0.45f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        BasicTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = Color.White),
                            cursorBrush = SolidColor(accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submit(text.text) }))
                    }
                    if (text.text.isNotEmpty()) IconButton({ text = TextFieldValue("") }) {
                        Icon(Icons.Default.Close, "Очистить", tint = Color.White.copy(0.8f))
                    }
                    SourceBadge(badge)
                }
            }
        }
    }
}
