package com.foldamp.library

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MimeTypes
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Internet radio directory backed by radio-browser.info: a free, community-run catalogue with an
 * open API and no key. Stations come back as [Track]s whose uri is the stream URL.
 *
 * The API asks clients to pick one of its mirror servers at runtime and to identify themselves
 * with a User-Agent, so we do both.
 */
object RadioBrowser {
    private const val USER_AGENT = "FoldAmp/1.0 (Android; +https://www.radio-browser.info)"
    private const val ROUND_ROBIN = "all.api.radio-browser.info"
    private const val LIMIT = 150
    const val GENRE_FALLBACK = "Internet Radio"

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var server: String? = null
    private var generation = 0

    /** Top stations by votes, or those whose name or tags match [query]; working streams only. */
    fun stations(query: String, done: (Result<List<Track>>) -> Unit) {
        val gen = ++generation
        io.execute {
            val result = runCatching {
                val base = "order=votes&reverse=true&hidebroken=true&limit=$LIMIT"
                if (query.isBlank()) {
                    parse(get("/json/stations/search?$base"))
                } else {
                    val q = URLEncoder.encode(query.trim(), "UTF-8")
                    // Name and tag searches merged, so "jazz" finds jazz stations as well as "Jazz FM".
                    val byName = parse(get("/json/stations/search?name=$q&$base"))
                    val byTag = parse(get("/json/stations/search?tag=$q&$base"))
                    (byName + byTag).distinctBy { it.uri }.sortedByDescending { votes[it.uri] ?: 0 }
                }
            }
            // Drop results from a search the user has already replaced.
            main.post { if (gen == generation) done(result) }
        }
    }

    /** Tells the directory a station was played; it feeds the popularity rankings. Fire and forget. */
    fun countClick(track: Track) {
        val id = uuids[track.uri] ?: return
        io.execute { runCatching { get("/json/url/$id") } }
    }

    // Keyed by stream URL; written on the io thread, read from the main thread by countClick.
    private val votes = ConcurrentHashMap<String, Int>()
    private val uuids = ConcurrentHashMap<String, String>()

    private fun parse(json: String): List<Track> {
        val arr = JSONArray(json)
        return List(arr.length()) { arr.getJSONObject(it) }
            .filter { it.optInt("lastcheckok") == 1 && it.optString("url_resolved").isNotBlank() }
            .map { o ->
                val url = o.getString("url_resolved")
                votes[url] = o.optInt("votes")
                o.optString("stationuuid").takeIf { it.isNotBlank() }?.let { uuids[url] = it }
                stationTrack(o, url)
            }
    }

    private fun stationTrack(o: JSONObject, url: String): Track {
        val tags = o.optString("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val codec = o.optString("codec").uppercase()
        return Track(
            uri = url,
            title = o.optString("name").trim().ifBlank { url },
            albumArtist = GENRE_FALLBACK,
            album = GENRE_FALLBACK,
            genre = tags.firstOrNull()?.replaceFirstChar { it.titlecase() } ?: GENRE_FALLBACK,
            comment = listOf(o.optString("country"), tags.take(4).joinToString(", ")).filter { it.isNotBlank() }.joinToString(" · "),
            mimeType = when {
                o.optInt("hls") == 1 -> MimeTypes.APPLICATION_M3U8
                codec == "MP3" -> MimeTypes.AUDIO_MPEG
                codec.startsWith("AAC") -> MimeTypes.AUDIO_AAC
                codec == "OGG" || codec == "VORBIS" -> MimeTypes.AUDIO_OGG
                codec == "OPUS" -> MimeTypes.AUDIO_OPUS
                codec == "FLAC" -> MimeTypes.AUDIO_FLAC
                else -> ""
            },
            bitrateKbps = o.optInt("bitrate"),
            artUrl = o.optString("favicon"),
        )
    }

    private fun get(path: String): String {
        val first = server ?: pickServer()
        return try {
            fetch("https://$first$path")
        } catch (e: Exception) {
            // That mirror may be down: pick again and retry once.
            server = null
            val second = pickServer()
            if (second == first) throw e
            fetch("https://$second$path")
        }
    }

    private fun pickServer(): String {
        val name = runCatching {
            val arr = JSONArray(fetch("https://$ROUND_ROBIN/json/servers"))
            List(arr.length()) { arr.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }.distinct().random()
        }.getOrDefault(ROUND_ROBIN)
        server = name
        return name
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 12000
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} from $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** A few SomaFM channels (listener-supported, and they welcome third-party players), shown when the directory can't be reached. */
    val fallback: List<Track> = listOf(
        Triple("Groove Salad", "ambient", "groovesalad-256-mp3"),
        Triple("Drone Zone", "ambient", "dronezone-256-mp3"),
        Triple("Deep Space One", "ambient", "deepspaceone-128-mp3"),
        Triple("Secret Agent", "lounge", "secretagent-128-mp3"),
        Triple("Lush", "electronic", "lush-128-mp3"),
        Triple("Beat Blender", "electronic", "beatblender-128-mp3"),
        Triple("DEF CON Radio", "electronic", "defcon-256-mp3"),
        Triple("Mission Control", "ambient", "missioncontrol-128-mp3"),
        Triple("Indie Pop Rocks!", "alternative", "indiepop-128-mp3"),
        Triple("Boot Liquor", "americana", "bootliquor-320-mp3"),
        Triple("Left Coast 70s", "70s", "seventies-320-mp3"),
        Triple("Fluid", "electronic", "fluid-128-mp3"),
    ).map { (name, genre, path) ->
        Track(
            uri = "https://ice6.somafm.com/$path",
            title = "SomaFM: $name",
            albumArtist = GENRE_FALLBACK,
            album = GENRE_FALLBACK,
            genre = genre.replaceFirstChar { it.titlecase() },
            comment = "United States · $genre",
            mimeType = MimeTypes.AUDIO_MPEG,
            bitrateKbps = path.split('-')[1].toInt(),
        )
    }
}
