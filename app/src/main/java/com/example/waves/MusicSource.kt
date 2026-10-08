package com.example.waves

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Track(val id: String, val title: String, val artist: String, val uri: Uri, val art: Uri?)

fun Track.toMediaItem() = MediaItem.Builder()
    .setMediaId(id).setUri(uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setArtworkUri(art).build())
    .build()

/** Общий интерфейс. Позже добавим YandexSource с такой же сигнатурой. */
interface MusicSource {
    suspend fun tracks(): List<Track>
}

class LocalSource(private val ctx: Context) : MusicSource {
    override suspend fun tracks(): List<Track> {
        val out = mutableListOf<Track>()
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID)
        ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Audio.Media.IS_MUSIC}!=0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += Track(
                    id.toString(), c.getString(1) ?: "Без названия", c.getString(2) ?: "Неизвестный исполнитель",
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), c.getLong(3))
                )
            }
        }
        return out
    }
}
