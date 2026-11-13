package com.foldamp.ui

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.media3.common.Player
import com.foldamp.library.formatTime
import com.foldamp.playback.PlayerHub
import com.foldamp.playback.SpectrumAnalyzer
import kotlin.math.abs
import kotlin.math.roundToInt

/** The 275x116 main window: transport, time, marquee, sliders and the mini analyzer. */
class MainWindowView(context: Context, host: UiHost) : SkinView(context, host) {

    companion object {
        const val W = 275
        const val H = 116
    }

    private val hits = listOf(
        Hit("options", 6, 3, 9, 9), Hit("minimize", 244, 3, 9, 9),
        Hit("shade", 254, 3, 9, 9), Hit("close", 264, 3, 9, 9),
        Hit("clutterO", 10, 25, 8, 8), Hit("clutterA", 10, 33, 8, 7), Hit("clutterI", 10, 40, 8, 7),
        Hit("clutterD", 10, 47, 8, 8), Hit("clutterV", 10, 55, 8, 7),
        Hit("time", 36, 26, 63, 13), Hit("vis", 24, 43, 76, 16), Hit("marquee", 111, 23, 154, 14),
        Hit("volume", 107, 57, 68, 13), Hit("balance", 177, 57, 38, 13),
        Hit("eq", 219, 58, 23, 12), Hit("pl", 242, 58, 23, 12),
        Hit("pos", 16, 72, 248, 10),
        Hit("prev", 16, 88, 23, 18), Hit("play", 39, 88, 23, 18), Hit("pause", 62, 88, 23, 18),
        Hit("stop", 85, 88, 23, 18), Hit("next", 108, 88, 22, 18), Hit("eject", 136, 89, 22, 16),
        Hit("shuffle", 164, 89, 47, 15), Hit("repeat", 210, 89, 28, 15),
        Hit("about", 253, 91, 13, 15),
    )

    var analyzer: SpectrumAnalyzer? = null
    /** 0 = spectrum, 1 = oscilloscope, 2 = off. */
    var visMode = 0
    private var showRemaining = false
    private var marqueeOffset = 0f
    private var lastFrame = 0L
    private var dragValue = -1f

    override fun drawNative(c: Canvas) {
        val s = skin
        c.sprite(s.main, 0, 0, W, H, 0, 0)
        c.sprite(s.titlebar, 27, 0, W, 14, 0, 0)
        c.sprite(s.titlebar, 304, 0, 8, 43, 10, 22)
        when (pressed) {
            "options" -> c.sprite(s.titlebar, 0, 9, 9, 9, 6, 3)
            "minimize" -> c.sprite(s.titlebar, 9, 9, 9, 9, 244, 3)
            "shade" -> c.sprite(s.titlebar, 9, 18, 9, 9, 254, 3)
            "close" -> c.sprite(s.titlebar, 18, 9, 9, 9, 264, 3)
        }
        clutterPressed(c)

        val track = PlayerHub.current
        // Play state indicator
        when {
            PlayerHub.isPlaying -> c.sprite(s.playpaus, 0, 0, 9, 9, 26, 28)
            PlayerHub.isPaused -> c.sprite(s.playpaus, 9, 0, 9, 9, 26, 28)
            else -> c.sprite(s.playpaus, 18, 0, 9, 9, 26, 28)
        }

        drawTime(c)
        drawMiniVis(c)
        drawMarquee(c, track?.let {
            val n = PlayerHub.currentIndex + 1
            when {
                // Radio: what the station says is playing, then the station, like Winamp's "[Title] (Station)".
                it.isStream -> PlayerHub.streamTitle?.let { song -> "$n. $song (${it.title})" } ?: "$n. ${it.title}"
                else -> "$n. ${it.displayTitle} (${formatTime(PlayerHub.durationMs)})"
            }
        })

        if (track != null && !PlayerHub.stopped) {
            val kbps = PlayerHub.bitrateKbps()
            val khz = PlayerHub.sampleRateKhz()
            if (kbps > 0) c.skinText((if (kbps > 999) "${kbps / 1000}k${(kbps % 1000) / 100}" else kbps.toString()).padStart(3), 111, 43)
            if (khz > 0) c.skinText(khz.toString().padStart(2).takeLast(2), 156, 43)
        }

        val ch = if (track != null && !PlayerHub.stopped) PlayerHub.channelCount() else 0
        c.sprite(s.monoster, 0, if (ch >= 2) 0 else 12, 29, 12, 239, 41)
        c.sprite(s.monoster, 29, if (ch == 1) 0 else 12, 27, 12, 212, 41)

        drawVolume(c)
        drawBalance(c)

        c.sprite(s.shufrep, if (pressed == "eq") 46 else 0, if (host.isEqShown()) 73 else 61, 23, 12, 219, 58)
        c.sprite(s.shufrep, if (pressed == "pl") 69 else 23, if (host.isPlaylistShown()) 73 else 61, 23, 12, 242, 58)

        // Seek bar
        c.sprite(s.posbar, 0, 0, 248, 10, 16, 72)
        val dur = PlayerHub.durationMs
        if (track != null && dur > 0) {
            val frac = if (pressed == "pos" && dragValue >= 0) dragValue else PlayerHub.positionMs.toFloat() / dur
            val x = 16 + (frac.coerceIn(0f, 1f) * 219).roundToInt()
            c.sprite(s.posbar, if (pressed == "pos") 278 else 248, 0, 29, 10, x, 72)
        }

        // Transport buttons
        val btn = listOf("prev" to 0, "play" to 23, "pause" to 46, "stop" to 69)
        for ((id, sx) in btn) {
            val hit = hits.first { it.id == id }
            c.sprite(s.cbuttons, sx, if (pressed == id) 18 else 0, 23, 18, hit.x, hit.y)
        }
        c.sprite(s.cbuttons, 92, if (pressed == "next") 18 else 0, 22, 18, 108, 88)
        c.sprite(s.cbuttons, 114, if (pressed == "eject") 16 else 0, 22, 16, 136, 89)

        val shuffleOn = PlayerHub.shuffle
        val repeatOn = PlayerHub.repeatMode != Player.REPEAT_MODE_OFF
        c.sprite(s.shufrep, 28, (if (shuffleOn) 30 else 0) + (if (pressed == "shuffle") 15 else 0), 47, 15, 164, 89)
        c.sprite(s.shufrep, 0, (if (repeatOn) 30 else 0) + (if (pressed == "repeat") 15 else 0), 28, 15, 210, 89)
        if (PlayerHub.repeatMode == Player.REPEAT_MODE_ONE) c.skinText("1", 232, 94)
    }

    private fun clutterPressed(c: Canvas) {
        val p = pressed ?: return
        if (!p.startsWith("clutter")) return
        val hit = hits.first { it.id == p }
        // Each depressed clutter letter is its own sprite below the bar in titlebar.bmp.
        val (sx, sy) = when (p) {
            "clutterO" -> 304 to 47
            "clutterA" -> 312 to 55
            "clutterI" -> 320 to 62
            "clutterD" -> 328 to 69
            else -> 336 to 77
        }
        c.sprite(skin.titlebar, sx, sy, 8, hit.h, 10, hit.y)
    }

    private fun drawTime(c: Canvas) {
        val s = skin
        val nums = s.numbers ?: return
        if (PlayerHub.current == null || PlayerHub.stopped) return
        // Winamp blinks the clock while paused.
        if (PlayerHub.isPaused && (SystemClock.uptimeMillis() / 500) % 2 == 1L) return
        val pos = PlayerHub.positionMs
        // Streams have no length, so they always count up.
        val remaining = showRemaining && PlayerHub.durationMs > 0
        val ms = if (remaining) (PlayerHub.durationMs - pos).coerceAtLeast(0) else pos
        val totalSec = ms / 1000
        val m = (totalSec / 60).coerceAtMost(99).toInt()
        val sec = (totalSec % 60).toInt()
        if (remaining) {
            if (s.numbersEx) c.sprite(nums, 99, 0, 9, 13, 36, 26) else c.sprite(nums, 20, 6, 5, 1, 38, 32)
        }
        val digits = intArrayOf(m / 10, m % 10, sec / 10, sec % 10)
        val xs = intArrayOf(48, 60, 78, 90)
        for (i in 0 until 4) c.sprite(nums, digits[i] * 9, 0, 9, 13, xs[i], 26)
    }

    private fun drawMiniVis(c: Canvas) {
        val colors = skin.visColors
        val x0 = 24; val y0 = 43; val w = 76; val h = 16
        fillPaint.color = colors[0]
        c.drawRect(x0.toFloat(), y0.toFloat(), (x0 + w).toFloat(), (y0 + h).toFloat(), fillPaint)
        fillPaint.color = colors[1]
        for (yy in 1 until h step 2) for (xx in 1 until w step 2) {
            c.drawRect((x0 + xx).toFloat(), (y0 + yy).toFloat(), (x0 + xx + 1).toFloat(), (y0 + yy + 1).toFloat(), fillPaint)
        }
        val a = analyzer ?: return
        when (visMode) {
            0 -> {
                val bars = a.bars
                if (bars.size < 19) return
                for (i in 0 until 19) {
                    val bh = (bars[i] * h).roundToInt().coerceIn(0, h)
                    val bx = (x0 + i * 4).toFloat()
                    for (k in 0 until bh) {
                        fillPaint.color = colors[17 - k.coerceAtMost(15)]
                        val py = (y0 + h - 1 - k).toFloat()
                        c.drawRect(bx, py, bx + 3, py + 1, fillPaint)
                    }
                    val pk = (a.peaks[i] * h).roundToInt()
                    if (pk > 0) {
                        fillPaint.color = colors[23]
                        val py = (y0 + h - pk).toFloat().coerceAtLeast(y0.toFloat())
                        c.drawRect(bx, py, bx + 3, py + 1, fillPaint)
                    }
                }
            }
            1 -> {
                val wave = a.wave
                var prev = -1
                for (xx in 0 until w) {
                    val v = wave[xx * wave.size / w]
                    val yy = (h / 2 - v * h).roundToInt().coerceIn(0, h - 1)
                    fillPaint.color = colors[18 + (abs(yy - h / 2) * 5 / (h / 2 + 1)).coerceIn(0, 4)]
                    val top = if (prev < 0) yy else minOf(prev, yy)
                    val bottom = if (prev < 0) yy else maxOf(prev, yy)
                    c.drawRect((x0 + xx).toFloat(), (y0 + top).toFloat(), (x0 + xx + 1).toFloat(), (y0 + bottom + 1).toFloat(), fillPaint)
                    prev = yy
                }
            }
        }
    }

    private fun drawMarquee(c: Canvas, title: String?) {
        val override = when (pressed) {
            "volume" -> "VOLUME: ${(PlayerHub.volume * 100).roundToInt()}%"
            "balance" -> PlayerHub.balance.let { b ->
                val pct = (abs(b) * 100).roundToInt()
                if (pct == 0) "BALANCE: CENTER" else "BALANCE: $pct% ${if (b < 0) "LEFT" else "RIGHT"}"
            }
            "pos" -> {
                val d = PlayerHub.durationMs
                "SEEK TO: ${formatTime((dragValue * d).toLong())}/${formatTime(d)} (${(dragValue * 100).roundToInt()}%)"
            }
            else -> null
        }
        val text = override ?: title ?: "FOLDAMP 0.1"
        val width = text.length * 5
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0 else now - lastFrame
        lastFrame = now
        if (override == null && width > 154) {
            val loop = "$text  ***  "
            marqueeOffset = (marqueeOffset + dt * 0.03f) % (loop.length * 5)
            // Scroll in whole skin pixels, like the original.
            c.skinText(loop + loop, 111, 27, 154, marqueeOffset.toInt())
        } else {
            marqueeOffset = 0f
            c.skinText(text, 111, 27, 154)
        }
    }

    private fun drawVolume(c: Canvas) {
        val s = skin
        val v = PlayerHub.volume
        val frame = (v * 27).roundToInt().coerceIn(0, 27)
        c.sprite(s.volume, 0, frame * 15, 68, 13, 107, 57)
        if ((s.volume?.height ?: 0) >= 433) {
            c.sprite(s.volume, if (pressed == "volume") 0 else 15, 422, 14, 11, 107 + (v * 54).roundToInt(), 58)
        }
    }

    private fun drawBalance(c: Canvas) {
        val s = skin
        val b = PlayerHub.balance
        val frame = (abs(b) * 27).roundToInt().coerceIn(0, 27)
        c.sprite(s.balance, 9, frame * 15, 38, 13, 177, 57)
        if ((s.balance?.height ?: 0) >= 433) {
            c.sprite(s.balance, if (pressed == "balance") 0 else 15, 422, 14, 11, 177 + ((b + 1f) / 2f * 24).roundToInt(), 58)
        }
    }

    // ---- Touch ----

    override fun onDown(e: MotionEvent) {
        val hit = findHit(hits, touchX, touchY) ?: return
        pressed = hit.id
        when (hit.id) {
            "volume", "balance", "pos" -> drag(hit.id)
        }
        invalidate()
    }

    override fun onMove(e: MotionEvent) {
        val p = pressed ?: return
        if (p == "volume" || p == "balance" || p == "pos") {
            drag(p)
        } else {
            val hit = hits.first { it.id == p }
            if (!hit.contains(touchX, touchY, 8f)) { pressed = null; invalidate() }
        }
    }

    private fun drag(id: String) {
        when (id) {
            "volume" -> PlayerHub.setVolume(((touchX - 107 - 7) / 54f).coerceIn(0f, 1f))
            "balance" -> PlayerHub.setBalance((((touchX - 177 - 7) / 24f) * 2f - 1f).coerceIn(-1f, 1f))
            "pos" -> dragValue = ((touchX - 16 - 14) / 219f).coerceIn(0f, 1f)
        }
        invalidate()
    }

    override fun onUp(e: MotionEvent) {
        val id = pressed ?: return
        if (id == "pos") {
            if (dragValue >= 0 && PlayerHub.current != null) PlayerHub.seekTo((dragValue * PlayerHub.durationMs).toLong())
            dragValue = -1f
            return
        }
        if (id == "volume" || id == "balance") return
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val x = e.x; val y = e.y
        when (id) {
            "prev" -> PlayerHub.previous()
            "play" -> if (PlayerHub.tracks.isEmpty()) host.openLibrary() else PlayerHub.play()
            "pause" -> PlayerHub.pause()
            "stop" -> PlayerHub.stop()
            "next" -> PlayerHub.next()
            "eject" -> host.openLibrary()
            "shuffle" -> PlayerHub.toggleShuffle()
            "repeat" -> PlayerHub.cycleRepeat()
            "eq" -> host.toggleEq()
            "pl" -> host.togglePlaylist()
            "time" -> showRemaining = !showRemaining
            "vis", "clutterV" -> visMode = (visMode + 1) % 3
            "marquee", "clutterI" -> if (PlayerHub.currentIndex >= 0) host.showFileInfo(PlayerHub.currentIndex)
            "options", "clutterO", "shade", "clutterA", "clutterD", "about" -> host.mainMenu(this, x, y)
            "minimize" -> host.minimize()
            "close" -> host.exit()
        }
        invalidate()
    }
}
