package com.example.waves

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Вкладка «Коллекция»: карточка «Мне нравится», переключатель Треки / Плейлисты / Исполнители / Альбомы,
 * поиск по коллекции и сортировка. Все данные берутся из существующих Likes, Playlists и списка треков устройства.
 * Исполнители и альбомы строятся из этих же треков: в Track нет названия альбома, поэтому альбом = треки с общей обложкой.
 */

private class CollOpen(val kind: Int, val key: String)   // 0 исполнитель, 1 альбом, 2 плейлист

private fun artistKey(t: Track) = t.artist.trim().ifBlank { "Неизвестный исполнитель" }

private fun collTracks(n: Int): String {
    val w = if (n % 100 in 11..14) "треков" else when (n % 10) { 1 -> "трек"; 2, 3, 4 -> "трека"; else -> "треков" }
    return "$n $w"
}

private fun albumTitle(ts: List<Track>): String =
    ts.groupingBy { artistKey(it) }.eachCount().maxByOrNull { it.value }?.key ?: "Альбом"

@Composable
fun CollectionScreen(np: NowPlaying, device: List<Track>, startPlay: (List<Track>, Int) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var source by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf<CollOpen?>(null) }
    var newOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    LaunchedEffect(YandexApi.token) {
        if (YandexApi.token.isNotBlank() && YState.likes.isEmpty())
            try { YState.likes = withContext(Dispatchers.IO) { YandexApi.likes() } } catch (_: Exception) { }
    }
    BackHandler(enabled = open != null) { open = null }

    val liked = Likes.all()
    val library = (liked + device).distinctBy { it.id }
    val q = query.trim()

    val o = open
    if (o != null) {
        CollectionDetail(o, library, np, startPlay) { open = null }
    } else {
        // данные текущего раздела с учётом поиска и сортировки
        val nameOrder = String.CASE_INSENSITIVE_ORDER
        val tracks = (if (source == 0) liked else device)
            .filter { q.isEmpty() || it.title.contains(q, true) || it.artist.contains(q, true) }
            .let { l ->
                when (sort) {
                    1 -> l.sortedWith(compareBy<Track, String>(nameOrder) { it.title })
                    2 -> l.sortedWith(compareBy<Track, String>(nameOrder) { it.artist })
                    3 -> l.sortedByDescending { it.durationMs }
                    else -> l
                }
            }
        val playlists = Playlists.all.filter { q.isEmpty() || it.name.contains(q, true) }
            .let { l -> if (sort == 1) l.sortedWith(compareBy<Playlist, String>(nameOrder) { it.name }) else l.toList() }
        val artists = library.groupBy { artistKey(it) }.toList()
            .filter { q.isEmpty() || it.first.contains(q, true) }
            .let { l -> if (sort == 1) l.sortedWith(compareBy<Pair<String, List<Track>>, String>(nameOrder) { it.first }) else l.sortedByDescending { it.second.size } }
        val albums = library.filter { it.art != null }.groupBy { it.art.toString() }.toList()
            .map { (key, ts) -> Triple(key, albumTitle(ts), ts) }
            .filter { q.isEmpty() || it.second.contains(q, true) }
            .let { l -> if (sort == 1) l.sortedWith(compareBy<Triple<String, String, List<Track>>, String>(nameOrder) { it.second }) else l.sortedByDescending { it.third.size } }

        val sortNames = when (tab) {
            0 -> listOf("Как добавлено", "По названию", "По исполнителю", "По длительности")
            1 -> listOf("Как добавлено", "По названию")
            else -> listOf("По числу треков", "По названию")
        }
        val hints = listOf("Поиск по трекам", "Поиск по плейлистам", "Поиск по исполнителям", "Поиск по альбомам")

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset())) {
            item {
                Text("Коллекция", Modifier.padding(start = DS.padL, top = DS.padM, bottom = DS.padXS), color = WP.text,
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            item { LikedCard(liked, startPlay) }
            item { CollTabs(tab) { tab = it; sort = 0; query = "" } }
            item {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = DS.padL, end = DS.padS, top = DS.padS, bottom = DS.padXS),
                        verticalAlignment = Alignment.CenterVertically) {
                        CompactSearch(query, { query = it }, hints[tab], Modifier.weight(1f))
                        Box {
                            IconButton({ sortOpen = true }, Modifier.size(DS.btn)) {
                                Icon(Icons.AutoMirrored.Filled.Sort, "Сортировка", Modifier.size(DS.iconM), tint = if (sort != 0) WP.accentInk else WP.text2)
                            }
                            DropdownMenu(sortOpen, { sortOpen = false }) {
                                sortNames.forEachIndexed { i, n ->
                                    DropdownMenuItem({ Text(n + if (i == sort) "  ✓" else "") }, { sort = i; sortOpen = false })
                                }
                            }
                        }
                    }
                    if (tab == 0) Pill(listOf("Мне нравится", "На устройстве"), source, { source = it; sort = 0 },
                        Modifier.padding(horizontal = DS.padL, vertical = DS.padXS))
                }
            }
            when (tab) {
                0 -> {
                    if (tracks.isEmpty()) item {
                        CollHint(when {
                            q.isNotEmpty() -> "Ничего не найдено"
                            source == 0 -> "Здесь будут треки, которые вы отметили сердечком"
                            else -> "На устройстве не найдено музыки"
                        })
                    }
                    itemsIndexed(tracks, key = { _, t -> t.id }) { i, t -> TrackRow(t, t.id == np.id) { startPlay(tracks, i) } }
                }
                1 -> {
                    val tiles: List<Playlist?> = (if (q.isEmpty()) listOf<Playlist?>(null) else emptyList()) + playlists
                    if (tiles.isEmpty()) item { CollHint("Ничего не найдено") }
                    val rows = tiles.chunked(2)
                    items(rows.size) { r ->
                        GridRow(rows[r].size) { idx ->
                            val p = rows[r][idx]
                            if (p == null) GridTile("Создать плейлист", "Новая подборка", { newOpen = true }) {
                                Icon(Icons.Default.Add, null, Modifier.align(Alignment.Center).size(36.dp), tint = WP.accentInk)
                            } else GridTile(p.name, collTracks(p.tracks.size), { open = CollOpen(2, p.id) }) {
                                ArtGrid(p.tracks.mapNotNull { it.art }.distinct().take(4), Icons.Default.LibraryMusic)
                            }
                        }
                    }
                }
                2 -> {
                    if (artists.isEmpty()) item { CollHint(if (q.isNotEmpty()) "Ничего не найдено" else "Исполнители появятся, когда в коллекции будут треки") }
                    items(artists, key = { it.first }) { (name, ts) ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = DS.padM).clip(DS.shapeM)
                            .clickable { open = CollOpen(0, name) }.padding(horizontal = DS.padS, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Cover(ts.firstNotNullOfOrNull { it.art }, DS.coverRow, CircleShape); Spacer(Modifier.width(DS.padM))
                            Column(Modifier.weight(1f)) {
                                Text(name, color = WP.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(collTracks(ts.size), color = WP.text2, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            Icon(Icons.Default.ChevronRight, null, Modifier.size(DS.iconM), tint = WP.text3)
                        }
                    }
                }
                else -> {
                    if (albums.isEmpty()) item { CollHint(if (q.isNotEmpty()) "Ничего не найдено" else "Альбомы появятся, когда у треков будут обложки") }
                    val rows = albums.chunked(2)
                    items(rows.size) { r ->
                        GridRow(rows[r].size) { idx ->
                            val (key, title, ts) = rows[r][idx]
                            GridTile(title, collTracks(ts.size), { open = CollOpen(1, key) }) {
                                ArtGrid(listOfNotNull(ts.firstOrNull()?.art), Icons.Default.LibraryMusic)
                            }
                        }
                    }
                }
            }
        }
    }
    if (newOpen) NewPlaylistDialog({ Playlists.create(it); newOpen = false }, { newOpen = false })
}

/** Страница исполнителя, альбома или плейлиста: заголовок, «Играть / Перемешать» и список треков. */
@Composable
private fun CollectionDetail(o: CollOpen, library: List<Track>, np: NowPlaying, startPlay: (List<Track>, Int) -> Unit, onBack: () -> Unit) {
    val pl = if (o.kind == 2) Playlists.all.find { it.id == o.key } else null
    if (o.kind == 2 && pl == null) { LaunchedEffect(Unit) { onBack() }; return }
    val tracks: List<Track> = when (o.kind) {
        0 -> library.filter { artistKey(it) == o.key }
        1 -> library.filter { it.art?.toString() == o.key }
        else -> pl!!.tracks.toList()
    }
    val title = when (o.kind) { 0 -> o.key; 1 -> albumTitle(tracks); else -> pl!!.name }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset())) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = DS.padS, vertical = DS.padXS), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = WP.text) }
                Column(Modifier.weight(1f)) {
                    Text(title, color = WP.text, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(collTracks(tracks.size), color = WP.text2, style = MaterialTheme.typography.bodySmall)
                }
                if (pl != null) TextButton({ Playlists.delete(pl.id); onBack() }) { Text("Удалить", color = WP.error) }
            }
        }
        item { PlayShuffleRow(tracks, startPlay, Modifier.fillMaxWidth().padding(horizontal = DS.padL, vertical = DS.padS)) }
        if (tracks.isEmpty()) item { CollHint("Пусто. Нажмите и удерживайте трек, затем выберите «Добавить в плейлист».") }
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            val extra = if (pl != null) listOf("Удалить из плейлиста" to { Playlists.removeTrack(pl.id, t) }) else emptyList()
            TrackRow(t, t.id == np.id, extra) { startPlay(tracks, i) }
        }
    }
}

/** Компактная карточка «Мне нравится»: мозаика из реальных обложек, число треков, «Играть» и «Перемешать». */
@Composable
private fun LikedCard(liked: List<Track>, startPlay: (List<Track>, Int) -> Unit) {
    val arts = liked.mapNotNull { it.art }.distinct().take(4)
    Column(Modifier.fillMaxWidth().padding(horizontal = DS.padL, vertical = DS.padS).clip(DS.shapeL).background(WP.surface).padding(DS.padM)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp).clip(DS.shapeM).background(WP.fill)) { ArtGrid(arts, Icons.Default.Favorite) }
            Spacer(Modifier.width(DS.padL))
            Column(Modifier.weight(1f)) {
                Text("Мне нравится", color = WP.text, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(collTracks(liked.size), color = WP.text2, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
        PlayShuffleRow(liked, startPlay, Modifier.fillMaxWidth().padding(top = DS.padM))
    }
}

@Composable
private fun PlayShuffleRow(tracks: List<Track>, startPlay: (List<Track>, Int) -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(DS.padS)) {
        Button({ startPlay(tracks, 0) }, Modifier.weight(1f).height(40.dp), enabled = tracks.isNotEmpty(), shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = WP.accent, contentColor = WP.onAccent),
            contentPadding = PaddingValues(horizontal = DS.padM)) {
            Icon(Icons.Default.PlayArrow, null, Modifier.size(DS.iconS)); Spacer(Modifier.width(6.dp))
            Text("Играть", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        Button({ startPlay(tracks.shuffled(), 0) }, Modifier.weight(1f).height(40.dp), enabled = tracks.isNotEmpty(), shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = WP.fill, contentColor = WP.text, disabledContainerColor = WP.fill, disabledContentColor = WP.text3),
            contentPadding = PaddingValues(horizontal = DS.padM)) {
            Icon(Icons.Default.Shuffle, null, Modifier.size(DS.iconS)); Spacer(Modifier.width(6.dp))
            Text("Перемешать", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Переключатель разделов: текст с акцентной чертой под активным, без рамок и заливок. */
@Composable
private fun CollTabs(sel: Int, onSel: (Int) -> Unit) {
    val names = listOf("Треки", "Плейлисты", "Исполнители", "Альбомы")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = DS.padL),
        horizontalArrangement = Arrangement.spacedBy(DS.padL)) {
        names.forEachIndexed { i, n ->
            val on = i == sel
            Column(Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null) { onSel(i) }.padding(top = DS.padS), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(n, color = if (on) WP.text else WP.text3, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.width(20.dp).height(2.dp).clip(CircleShape).background(if (on) WP.accentInk else Color.Transparent))
            }
        }
    }
}

@Composable
private fun CompactSearch(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier) {
    Row(modifier.height(40.dp).clip(CircleShape).background(WP.fill).padding(start = DS.padM, end = DS.padXS),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Search, null, Modifier.size(DS.iconS), tint = WP.text3); Spacer(Modifier.width(DS.padS))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(hint, color = WP.text3, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true,
                textStyle = TextStyle(color = WP.text, fontSize = 15.sp), cursorBrush = SolidColor(WP.accent))
        }
        if (value.isNotEmpty()) IconButton({ onChange("") }, Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, "Очистить", Modifier.size(DS.iconS), tint = WP.text2)
        }
    }
}

/** Ряд сетки в две колонки; в неполном ряду вторая половина остаётся пустой. */
@Composable
private fun GridRow(count: Int, cell: @Composable (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = DS.padL, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(DS.padM)) {
        for (i in 0 until count) Box(Modifier.weight(1f)) { cell(i) }
        if (count == 1) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun GridTile(title: String, subtitle: String, onClick: () -> Unit, art: @Composable BoxScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(DS.shapeM).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(DS.shapeM).background(WP.fill), content = art)
        Text(title, Modifier.padding(top = DS.padS), color = WP.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = WP.text2, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Обложка из реальных картинок: одна — на весь квадрат, несколько — мозаика 2×2; без картинок — значок. */
@Composable
private fun BoxScope.ArtGrid(arts: List<Uri>, empty: ImageVector) {
    when {
        arts.isEmpty() -> Icon(empty, null, Modifier.align(Alignment.Center).size(32.dp), tint = WP.text3)
        arts.size == 1 -> AsyncImage(arts[0], null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else -> Column(Modifier.fillMaxSize()) {
            for (r in 0..1) Row(Modifier.weight(1f).fillMaxWidth()) {
                for (c in 0..1) Box(Modifier.weight(1f).fillMaxHeight()) {
                    AsyncImage(arts[(r * 2 + c) % arts.size], null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
    }
}

@Composable
private fun CollHint(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = DS.padL, vertical = DS.padXL), color = WP.text2, style = MaterialTheme.typography.bodyMedium)
}
