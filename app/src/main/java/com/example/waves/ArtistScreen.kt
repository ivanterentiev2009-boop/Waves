package com.example.waves

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private val dimText get() = WP.text2

@Composable
fun Avatar(uri: Uri?, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(WP.fill), contentAlignment = Alignment.Center) {
        if (uri != null) AsyncImage(uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.Person, null, Modifier.size((size / 2).dp), tint = WP.text)
    }
}

@Composable
fun ArtistChip(a: ArtistItem, onClick: () -> Unit) {
    Column(Modifier.width(96.dp).clip(DS.shapeM).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(a.cover, 80)
        Text(a.name, Modifier.padding(top = 6.dp), color = WP.text, style = MaterialTheme.typography.bodySmall,
            maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AlbumCard(al: AlbumItem, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clip(DS.shapeM).clickable(onClick = onClick)) {
        Cover(al.cover, 140)
        Text(al.title, Modifier.padding(top = 8.dp), color = WP.text, fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (al.year.isNotBlank()) Text(al.year, color = dimText, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 20.dp, top = 20.dp, bottom = 10.dp), color = WP.text, style = MaterialTheme.typography.titleMedium)
}

/** Профиль исполнителя: популярные треки, альбомы (тап запускает альбом), похожие исполнители. */
@Composable
fun ArtistScreen(cur: String, onBack: () -> Unit, onPlay: (List<Track>, Int) -> Unit, onOpenArtist: (String) -> Unit,
                 onAlbum: (AlbumItem) -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val p = YState.profile
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
            item {
                Box(Modifier.fillMaxWidth()) {
                    AsyncImage(p?.artist?.cover, null, Modifier.matchParentSize().blur(24.dp).alpha(0.30f), contentScale = ContentScale.Crop)
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(p?.artist?.cover, 140)
                        Spacer(Modifier.height(12.dp))
                        Text(p?.artist?.name ?: "Загрузка…", color = WP.text, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center)
                        val meta = listOf(p?.artist?.genres.orEmpty(), p?.artist?.sub.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, Modifier.padding(top = 4.dp), color = dimText, style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center)
                        if (p != null && p.tracks.isNotEmpty()) Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button({ onPlay(p.tracks, 0) }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Слушать") }
                            FilledTonalButton({ onPlay(p.tracks.shuffled(), 0) }) { Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Вперемешку") }
                        }
                    }
                }
            }
            if (p == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        val err = YState.profileError
                        if (err != null) Text(err, color = WP.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        else CircularProgressIndicator()
                    }
                }
            } else {
                if (p.tracks.isNotEmpty()) {
                    item { SectionTitle("Популярные треки") }
                    itemsIndexed(p.tracks, key = { _, t -> t.id }) { i, t -> TrackRow(t, t.id == cur) { onPlay(p.tracks, i) } }
                }
                if (p.albums.isNotEmpty()) {
                    item { SectionTitle("Альбомы") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(p.albums, key = { it.id }) { al -> AlbumCard(al) { onAlbum(al) } }
                        }
                    }
                }
                if (p.similar.isNotEmpty()) {
                    item { SectionTitle("Похожие исполнители") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(p.similar, key = { it.id }) { a -> ArtistChip(a) { onOpenArtist(a.id) } }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
        IconButton(onBack, Modifier.align(Alignment.TopStart).padding(4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = WP.text)
        }
    }
}

/** Исполнитель в списке результатов: аватар, имя, подпись. */
@Composable
fun ArtistRow(a: ArtistItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = DS.padM).clip(DS.shapeM)
        .clickable(onClick = onClick).padding(DS.padS), verticalAlignment = Alignment.CenterVertically) {
        Avatar(a.cover, 56); Spacer(Modifier.width(DS.padM))
        Column(Modifier.weight(1f)) {
            Text(a.name, color = WP.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (a.sub.isNotBlank()) Text(a.sub, color = WP.text2, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
