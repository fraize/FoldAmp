package com.foldamp.library

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

/**
 * The on-device music collection. Tags come from MediaStore (which parses ID3, Vorbis comments,
 * MP4 atoms, etc. from the files); files opened through the system picker are read directly with
 * MediaMetadataRetriever.
 */
object MediaLibrary {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    /** Everything in the library: scanned files plus favorite radio stations. */
    val tracks: List<Track> get() = files + favorites
    @Volatile private var files: List<Track> = emptyList()
    @Volatile var favorites: List<Track> = emptyList()
        private set
    @Volatile var scanning = false
        private set
    var scanned = false
        private set

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyChanged() = main.post { listeners.toList().forEach { it() } }

    fun scan(context: Context) {
        if (scanning) return
        scanning = true
        notifyChanged()
        val app = context.applicationContext
        io.execute {
            val result = try { queryMediaStore(app) } catch (e: SecurityException) { emptyList() }
            files = result.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER, Track::effectiveAlbumArtist)
                    .thenBy(String.CASE_INSENSITIVE_ORDER, Track::album)
                    .thenBy(Track::discNumber)
                    .thenBy(Track::trackNumber)
                    .thenBy(String.CASE_INSENSITIVE_ORDER, Track::title)
            )
            scanning = false
            scanned = true
            notifyChanged()
            fillGapsFromFiles(app)
        }
    }

    // ---- Tags read straight from the files ----
    //
    // MediaStore leaves some tags empty (notably the year for ID3v2.4 TDRC and Vorbis/FLAC DATE),
    // so after a scan we read those files directly and cache the result by uri + size.

    private data class FileTags(val year: Int, val genre: String, val composer: String, val albumArtist: String, val disc: Int, val track: Int)

    private fun needsFileTags(t: Track) = t.year == 0 || t.genre.isBlank() || t.albumArtist.isBlank()

    private fun fillGapsFromFiles(context: Context) {
        val cacheFile = File(context.filesDir, "tagcache.json")
        val cache = HashMap<String, FileTags>()
        runCatching {
            val o = org.json.JSONObject(cacheFile.readText())
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                cache[k] = FileTags(e.optInt("y"), e.optString("g"), e.optString("c"), e.optString("aa"), e.optInt("d"), e.optInt("t"))
            }
        }
        var dirty = false
        val updated = files.map { t ->
            if (!needsFileTags(t)) return@map t
            val key = "${t.uri}|${t.sizeBytes}"
            val tags = cache[key] ?: readTags(context, Uri.parse(t.uri)).also { cache[key] = it; dirty = true }
            merge(t, tags)
        }
        files = updated
        if (dirty) {
            runCatching {
                val o = org.json.JSONObject()
                cache.forEach { (k, v) ->
                    o.put(k, org.json.JSONObject().put("y", v.year).put("g", v.genre).put("c", v.composer).put("aa", v.albumArtist).put("d", v.disc).put("t", v.track))
                }
                cacheFile.writeText(o.toString())
            }
        }
        notifyChanged()
    }

    private fun merge(t: Track, f: FileTags) = t.copy(
        year = t.year.takeIf { it > 0 } ?: f.year,
        genre = t.genre.ifBlank { f.genre },
        composer = t.composer.ifBlank { f.composer },
        albumArtist = t.albumArtist.ifBlank { f.albumArtist },
        discNumber = t.discNumber.takeIf { it > 0 } ?: f.disc,
        trackNumber = t.trackNumber.takeIf { it > 0 } ?: f.track,
    )

    private fun readTags(context: Context, uri: Uri): FileTags {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            fun m(key: Int) = r.extractMetadata(key).orEmpty()
            FileTags(
                year = yearOf(r),
                genre = m(MediaMetadataRetriever.METADATA_KEY_GENRE),
                composer = m(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                albumArtist = m(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                disc = parseLeadingInt(m(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                track = parseLeadingInt(m(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
            )
        } catch (e: Exception) {
            FileTags(0, "", "", "", 0, 0)
        } finally {
            runCatching { r.release() }
        }
    }

    /** YEAR is often empty; DATE carries it for Vorbis comments and ID3v2.4 (e.g. "2023" or "20230101T000000.000Z"). */
    private fun yearOf(r: MediaMetadataRetriever): Int {
        parseLeadingInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR).orEmpty()).takeIf { it in 1000..9999 }?.let { return it }
        val date = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE).orEmpty().trim()
        return date.take(4).toIntOrNull()?.takeIf { it in 1000..9999 } ?: 0
    }

    /** Fills in any tags missing from [t] by reading its file. Blocking; call off the main thread. */
    fun withFileTags(context: Context, t: Track): Track = if (!t.isStream && needsFileTags(t)) merge(t, readTags(context, Uri.parse(t.uri))) else t

    private fun queryMediaStore(context: Context): List<Track> {
        val uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.GENRE,
            MediaStore.Audio.Media.COMPOSER,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.CD_TRACK_NUMBER,
            MediaStore.Audio.Media.DISC_NUMBER,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.BITRATE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
        )
        val out = ArrayList<Track>()
        context.contentResolver.query(
            uri, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null
        )?.use { c ->
            val col = projection.associateWith { c.getColumnIndexOrThrow(it) }
            fun s(name: String) = c.getString(col.getValue(name)).orEmpty().let { if (it == "<unknown>") "" else it }
            fun l(name: String) = c.getLong(col.getValue(name))
            while (c.moveToNext()) {
                val id = l(MediaStore.Audio.Media._ID)
                // TRACK is encoded as disc * 1000 + track on most devices.
                val rawTrack = c.getInt(col.getValue(MediaStore.Audio.Media.TRACK))
                val trackNo = parseLeadingInt(s(MediaStore.Audio.Media.CD_TRACK_NUMBER)).takeIf { it > 0 } ?: (rawTrack % 1000)
                val discNo = parseLeadingInt(s(MediaStore.Audio.Media.DISC_NUMBER)).takeIf { it > 0 } ?: (rawTrack / 1000)
                val name = s(MediaStore.Audio.Media.DISPLAY_NAME)
                out += Track(
                    uri = ContentUris.withAppendedId(uri, id).toString(),
                    title = s(MediaStore.Audio.Media.TITLE).ifBlank { name.substringBeforeLast('.') },
                    artist = s(MediaStore.Audio.Media.ARTIST),
                    albumArtist = s(MediaStore.Audio.Media.ALBUM_ARTIST),
                    album = s(MediaStore.Audio.Media.ALBUM),
                    genre = s(MediaStore.Audio.Media.GENRE),
                    composer = s(MediaStore.Audio.Media.COMPOSER),
                    durationMs = l(MediaStore.Audio.Media.DURATION),
                    trackNumber = trackNo,
                    discNumber = discNo,
                    year = c.getInt(col.getValue(MediaStore.Audio.Media.YEAR)),
                    mimeType = s(MediaStore.Audio.Media.MIME_TYPE),
                    bitrateKbps = (l(MediaStore.Audio.Media.BITRATE) / 1000).toInt(),
                    sizeBytes = l(MediaStore.Audio.Media.SIZE),
                    fileName = name,
                    path = s(MediaStore.Audio.Media.RELATIVE_PATH) + name,
                    albumId = l(MediaStore.Audio.Media.ALBUM_ID),
                )
            }
        }
        return out
    }

    /** Reads tags straight from a file the user picked (content:// from the document picker). */
    fun readFile(context: Context, uri: Uri): Track {
        tracks.firstOrNull { it.uri == uri.toString() }?.let { return it }
        val r = MediaMetadataRetriever()
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Unknown"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c: Cursor ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                }
            }
        }
        return try {
            r.setDataSource(context, uri)
            fun m(key: Int) = r.extractMetadata(key).orEmpty()
            Track(
                uri = uri.toString(),
                title = m(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { name.substringBeforeLast('.') },
                artist = m(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = m(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = m(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                genre = m(MediaMetadataRetriever.METADATA_KEY_GENRE),
                composer = m(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                durationMs = m(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0,
                trackNumber = parseLeadingInt(m(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                discNumber = parseLeadingInt(m(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                year = yearOf(r),
                mimeType = m(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                bitrateKbps = ((m(MediaMetadataRetriever.METADATA_KEY_BITRATE).toLongOrNull() ?: 0) / 1000).toInt(),
                sizeBytes = size,
                fileName = name,
            )
        } catch (e: Exception) {
            Track(uri = uri.toString(), title = name.substringBeforeLast('.'), fileName = name, sizeBytes = size)
        } finally {
            runCatching { r.release() }
        }
    }

    /** Runs [work] off the main thread and delivers the result on it. */
    fun <T> background(work: () -> T, done: (T) -> Unit) {
        io.execute { val v = work(); main.post { done(v) } }
    }

    private fun parseLeadingInt(s: String): Int = s.trim().takeWhile { it.isDigit() }.toIntOrNull() ?: 0

    // ---- Favorite radio stations (stored as JSON in app storage, listed alongside songs) ----

    private fun favoritesFile(context: Context) = File(context.filesDir, "radio_favorites.json")

    fun init(context: Context) {
        favorites = runCatching {
            val arr = JSONArray(favoritesFile(context).readText())
            List(arr.length()) { Track.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun isFavorite(t: Track) = favorites.any { it.uri == t.uri }

    /** Adds or removes a station from the library; returns true if it's now a favorite. */
    fun toggleFavorite(context: Context, t: Track): Boolean {
        val adding = !isFavorite(t)
        favorites = if (adding) {
            (favorites + t).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Track::title))
        } else {
            favorites.filterNot { it.uri == t.uri }
        }
        val arr = JSONArray().apply { favorites.forEach { put(it.toJson()) } }
        runCatching { favoritesFile(context).writeText(arr.toString()) }
        notifyChanged()
        return adding
    }

    // ---- Saved playlists (stored as JSON in app storage) ----

    private fun playlistDir(context: Context) = File(context.filesDir, "playlists").apply { mkdirs() }

    fun savedPlaylists(context: Context): List<String> =
        playlistDir(context).listFiles { f -> f.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
            .orEmpty()

    fun savePlaylist(context: Context, name: String, list: List<Track>) {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Playlist" }
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        File(playlistDir(context), "$safe.json").writeText(arr.toString())
    }

    fun loadPlaylist(context: Context, name: String): List<Track> {
        val f = File(playlistDir(context), "$name.json")
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            List(arr.length()) { Track.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun deletePlaylist(context: Context, name: String) {
        File(playlistDir(context), "$name.json").delete()
    }
}
