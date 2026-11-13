package com.foldamp.library

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import org.json.JSONObject

/** One playable file plus every tag we know about it. */
data class Track(
    val uri: String,
    val title: String,
    val artist: String = "",
    val albumArtist: String = "",
    val album: String = "",
    val genre: String = "",
    val composer: String = "",
    val durationMs: Long = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val year: Int = 0,
    val mimeType: String = "",
    val bitrateKbps: Int = 0,
    val sizeBytes: Long = 0,
    val fileName: String = "",
    val path: String = "",
    val albumId: Long = 0,
    /** Remote artwork (a radio station's logo). */
    val artUrl: String = "",
    /** Free-form note; for radio stations, the country and genre tags. */
    val comment: String = "",
) {
    /** Internet radio and other network streams; they have no fixed length and can't be tagged from a file. */
    val isStream: Boolean
        get() = uri.startsWith("http://", true) || uri.startsWith("https://", true)

    /** Winamp-style "Artist - Title" display string. */
    val displayTitle: String
        get() = if (artist.isBlank()) title else "$artist - $title"

    val effectiveAlbumArtist: String
        get() = albumArtist.ifBlank { artist }

    fun toMediaItem(id: String): MediaItem {
        val meta = MediaMetadata.Builder()
            // A stream's title is left unset so the song title the station sends (ICY StreamTitle)
            // shows in the notification, with the station name underneath.
            .setTitle(if (isStream) null else title)
            .setArtist(if (isStream) title else artist.ifBlank { null })
            .setStation(if (isStream) title else null)
            .setAlbumArtist(albumArtist.ifBlank { null })
            .setAlbumTitle(album.ifBlank { null })
            .setGenre(genre.ifBlank { null })
            .setComposer(composer.ifBlank { null })
            .setTrackNumber(trackNumber.takeIf { it > 0 })
            .setDiscNumber(discNumber.takeIf { it > 0 })
            .setRecordingYear(year.takeIf { it > 0 })
            .setIsPlayable(true)
            .setIsBrowsable(false)
        if (albumId > 0) {
            meta.setArtworkUri(Uri.parse("content://media/external/audio/albumart/$albumId"))
        } else if (artUrl.isNotBlank()) {
            meta.setArtworkUri(Uri.parse(artUrl))
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            // HLS streams often lack a .m3u8 extension, so say so explicitly.
            .setMimeType(mimeType.takeIf { it == MimeTypes.APPLICATION_M3U8 })
            .setMediaMetadata(meta.build())
            .build()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri); put("title", title); put("artist", artist)
        put("albumArtist", albumArtist); put("album", album); put("genre", genre)
        put("composer", composer); put("duration", durationMs); put("track", trackNumber)
        put("disc", discNumber); put("year", year); put("mime", mimeType)
        put("bitrate", bitrateKbps); put("size", sizeBytes); put("file", fileName)
        put("path", path); put("albumId", albumId); put("art", artUrl); put("comment", comment)
    }

    companion object {
        fun fromJson(o: JSONObject) = Track(
            uri = o.getString("uri"),
            title = o.optString("title"),
            artist = o.optString("artist"),
            albumArtist = o.optString("albumArtist"),
            album = o.optString("album"),
            genre = o.optString("genre"),
            composer = o.optString("composer"),
            durationMs = o.optLong("duration"),
            trackNumber = o.optInt("track"),
            discNumber = o.optInt("disc"),
            year = o.optInt("year"),
            mimeType = o.optString("mime"),
            bitrateKbps = o.optInt("bitrate"),
            sizeBytes = o.optLong("size"),
            fileName = o.optString("file"),
            path = o.optString("path"),
            albumId = o.optLong("albumId"),
            artUrl = o.optString("art"),
            comment = o.optString("comment"),
        )
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
