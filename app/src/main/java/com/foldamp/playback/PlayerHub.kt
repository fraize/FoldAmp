package com.foldamp.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.foldamp.library.Track
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

/**
 * Owns the single ExoPlayer instance and the playlist that mirrors its media items 1:1. Everything
 * here runs on the main thread except [balance], which the audio thread reads.
 */
@OptIn(UnstableApi::class)
object PlayerHub {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = mutableSetOf<() -> Unit>()

    var player: ExoPlayer? = null
        private set
    /** The playlist. Index i is always media item i in the player. */
    val tracks = ArrayList<Track>()
    private var nextId = 0L

    @Volatile var balance = 0f
        private set
    var volume = 0.8f
        private set
    /** Winamp's Stop leaves the player paused at 0:00 rather than releasing it. */
    var stopped = true
        private set
    private var consecutiveErrors = 0

    /** "Artist - Title" the current internet radio station last announced (ICY StreamTitle), if any. */
    var streamTitle: String? = null
        private set

    private val prefs by lazy { app.getSharedPreferences("player", Context.MODE_PRIVATE) }

    fun init(context: Context) {
        app = context.applicationContext
        volume = prefs.getFloat("volume", 0.8f)
        balance = prefs.getFloat("balance", 0f)
        runCatching {
            val f = File(app.filesDir, "nowplaying.json")
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) tracks += Track.fromJson(arr.getJSONObject(i))
            }
        }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyChanged() { listeners.toList().forEach { it() } }

    fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val eq = EqAudioProcessor()
        @Suppress("DEPRECATION")
        val renderers = object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(eq))
                    // A short, fixed buffer keeps the visualizer close to what's audible.
                    .setAudioTrackBufferSizeProvider(
                        DefaultAudioTrackBufferSizeProvider.Builder()
                            .setMinPcmBufferDurationUs(200_000)
                            .setMaxPcmBufferDurationUs(250_000)
                            .build()
                    )
                    .build()
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val p = ExoPlayer.Builder(app, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            // Network wake mode also holds a Wi-Fi lock, so radio keeps streaming with the screen off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        p.volume = perceptualVolume(volume)
        p.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
        p.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
        p.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                notifyChanged()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                streamTitle = null
                current?.let { Equalizer.autoApplyForGenre(it.genre) }
                savePosition()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { stopped = false; consecutiveErrors = 0 }
                savePosition()
            }

            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val title = (metadata[i] as? IcyInfo)?.title?.trim()
                    if (!title.isNullOrEmpty()) streamTitle = title
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val name = current?.displayTitle ?: "track"
                Toast.makeText(app, "Can't play $name: ${error.errorCodeName}", Toast.LENGTH_SHORT).show()
                if (current?.isStream == true) {
                    // A dropped or dead stream: stop on it (PLAY reconnects) rather than racing through the playlist.
                    stopped = true
                    p.prepare()
                    return
                }
                consecutiveErrors++
                if (consecutiveErrors < tracks.size && tracks.size > 1) {
                    val next = (p.currentMediaItemIndex + 1) % tracks.size
                    p.seekTo(next, 0)
                    p.prepare()
                    p.play()
                } else {
                    stopped = true
                    p.prepare()
                }
            }
        })
        player = p
        if (tracks.isNotEmpty()) {
            val idx = prefs.getInt("index", 0).coerceIn(0, tracks.size - 1)
            p.setMediaItems(tracks.map { it.toMediaItem((nextId++).toString()) }, idx, prefs.getLong("position", 0))
            stopped = prefs.getLong("position", 0) == 0L
            p.prepare()
        }
        return p
    }

    fun release() {
        savePosition()
        player?.release()
        player = null
    }

    // ---- State ----

    val currentIndex: Int get() = player?.takeIf { tracks.isNotEmpty() }?.currentMediaItemIndex?.coerceIn(0, tracks.size - 1) ?: -1
    val current: Track? get() = tracks.getOrNull(currentIndex)
    val isPlaying: Boolean get() = player?.isPlaying == true || (player?.playWhenReady == true && player?.playbackState == Player.STATE_BUFFERING)
    val isPaused: Boolean get() = !isPlaying && !stopped && current != null
    val positionMs: Long get() = if (stopped) 0 else player?.currentPosition ?: 0
    /** 0 for streams: live radio has no length, and an HLS window's length isn't one either. */
    val durationMs: Long get() = if (current?.isStream == true) 0
        else player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: current?.durationMs ?: 0
    val shuffle: Boolean get() = player?.shuffleModeEnabled == true
    val repeatMode: Int get() = player?.repeatMode ?: Player.REPEAT_MODE_OFF
    val audioFormat: Format? get() = player?.audioFormat

    val totalDurationMs: Long get() = tracks.sumOf { it.durationMs }

    fun bitrateKbps(): Int {
        val f = audioFormat
        val fromFormat = f?.let { if (it.averageBitrate > 0) it.averageBitrate else it.bitrate }?.takeIf { it > 0 }?.div(1000)
        val t = current ?: return fromFormat ?: 0
        return t.bitrateKbps.takeIf { it > 0 } ?: fromFormat
            ?: if (t.sizeBytes > 0 && t.durationMs > 0) (t.sizeBytes * 8 / t.durationMs).toInt() else 0
    }

    fun sampleRateKhz(): Int = audioFormat?.sampleRate?.takeIf { it > 0 }?.let { (it + 500) / 1000 } ?: 0
    fun channelCount(): Int = audioFormat?.channelCount?.takeIf { it > 0 } ?: 0

    // ---- Transport ----

    fun play() {
        val p = ensurePlayer()
        if (tracks.isEmpty()) return
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(p.currentMediaItemIndex, 0)
        if (stopped) seekToStart(p, p.currentMediaItemIndex)
        stopped = false
        p.play()
        notifyChanged()
    }

    fun pause() {
        val p = player ?: return
        if (stopped) return
        if (p.playWhenReady) p.pause() else p.play()
        notifyChanged()
    }

    fun togglePlay() = if (isPlaying) pause() else play()

    fun stop() {
        val p = player ?: return
        p.pause()
        p.seekTo(p.currentMediaItemIndex, 0)
        stopped = true
        notifyChanged()
        savePosition()
    }

    fun next() {
        val p = player ?: return
        if (tracks.isEmpty()) return
        if (p.hasNextMediaItem()) p.seekToNextMediaItem() else p.seekTo(0, 0)
        if (!stopped) p.play()
    }

    fun previous() {
        val p = player ?: return
        if (tracks.isEmpty()) return
        if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem() else p.seekTo(tracks.size - 1, 0)
        if (!stopped) p.play()
    }

    fun playAt(index: Int) {
        val p = ensurePlayer()
        if (index !in tracks.indices) return
        seekToStart(p, index)
        stopped = false
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
    }

    /** Start of a file, or the live edge of a stream (seeking to 0 would replay an HLS window). */
    private fun seekToStart(p: ExoPlayer, index: Int) {
        if (tracks.getOrNull(index)?.isStream == true) p.seekToDefaultPosition(index) else p.seekTo(index, 0)
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        if (current?.isStream == true) return
        if (stopped) { stopped = false; p.seekTo(ms); p.play() } else p.seekTo(ms)
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        player?.volume = perceptualVolume(volume)
        prefs.edit().putFloat("volume", volume).apply()
        notifyChanged()
    }

    fun setBalance(b: Float) {
        balance = if (kotlin.math.abs(b) < 0.08f) 0f else b.coerceIn(-1f, 1f)
        prefs.edit().putFloat("balance", balance).apply()
        notifyChanged()
    }

    fun toggleShuffle() {
        val p = ensurePlayer()
        p.shuffleModeEnabled = !p.shuffleModeEnabled
        prefs.edit().putBoolean("shuffle", p.shuffleModeEnabled).apply()
    }

    fun cycleRepeat() {
        val p = ensurePlayer()
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        prefs.edit().putInt("repeat", p.repeatMode).apply()
    }

    private fun perceptualVolume(v: Float) = v * v

    // ---- Playlist editing ----

    fun add(list: List<Track>, playFirst: Boolean = false) {
        if (list.isEmpty()) return
        val p = ensurePlayer()
        val start = tracks.size
        tracks += list
        p.addMediaItems(list.map { it.toMediaItem((nextId++).toString()) })
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        if (playFirst) playAt(start)
        playlistChanged()
    }

    fun replace(list: List<Track>, startIndex: Int = 0, play: Boolean = true) {
        val p = ensurePlayer()
        tracks.clear()
        tracks += list
        if (list.isEmpty()) {
            p.clearMediaItems(); stopped = true
        } else {
            p.setMediaItems(list.map { it.toMediaItem((nextId++).toString()) }, startIndex.coerceIn(0, list.size - 1), 0)
            p.prepare()
            if (play) { stopped = false; p.play() } else stopped = true
        }
        playlistChanged()
    }

    fun remove(indices: Collection<Int>) {
        val p = player ?: return
        for (i in indices.filter { it in tracks.indices }.sortedDescending()) {
            tracks.removeAt(i)
            p.removeMediaItem(i)
        }
        if (tracks.isEmpty()) stopped = true
        playlistChanged()
    }

    fun clear() = remove(tracks.indices.toList())

    /** Re-orders the playlist. [order] lists old indices in their new positions. */
    fun reorder(order: List<Int>) {
        val p = player ?: return
        if (order.size != tracks.size) return
        val cur = currentIndex
        val reordered = order.map { tracks[it] }
        val newCur = order.indexOf(cur).coerceAtLeast(0)
        val pos = p.currentPosition
        val wasPlaying = p.playWhenReady
        tracks.clear(); tracks += reordered
        p.setMediaItems(reordered.map { it.toMediaItem((nextId++).toString()) }, newCur, pos)
        p.prepare()
        p.playWhenReady = wasPlaying
        playlistChanged()
    }

    fun move(from: Int, to: Int) {
        val p = player ?: return
        if (from !in tracks.indices || to !in tracks.indices || from == to) return
        tracks.add(to, tracks.removeAt(from))
        p.moveMediaItem(from, to)
        playlistChanged()
    }

    private fun playlistChanged() {
        notifyChanged()
        val snapshot = tracks.map { it.toJson() }
        io.execute {
            runCatching {
                val arr = JSONArray().apply { snapshot.forEach { put(it) } }
                val tmp = File(app.filesDir, "nowplaying.json.tmp")
                tmp.writeText(arr.toString())
                tmp.renameTo(File(app.filesDir, "nowplaying.json"))
            }
        }
        savePosition()
    }

    fun savePosition() {
        val p = player ?: return
        prefs.edit()
            .putInt("index", p.currentMediaItemIndex)
            .putLong("position", if (stopped) 0 else p.currentPosition)
            .apply()
    }

    fun post(r: () -> Unit) = main.post(r)
}
