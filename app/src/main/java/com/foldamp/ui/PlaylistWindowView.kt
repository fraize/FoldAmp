package com.foldamp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.OverScroller
import com.foldamp.library.MediaLibrary
import com.foldamp.library.Track
import com.foldamp.library.formatTime
import com.foldamp.playback.PlayerHub
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Resizable playlist editor drawn from pledit.bmp, with a touch-scrollable track list. */
class PlaylistWindowView(context: Context, host: UiHost) : SkinView(context, host) {

    val selected = sortedSetOf<Int>()
    private var scrollPx = 0f
    private val scroller = OverScroller(context)
    private var draggingHandle = false
    private var lastCurrent = -1

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }
    private val rowHeight get() = (textPaint.textSize * 1.45f)
    private var fontFor: String? = null

    // List area in device pixels.
    private val listLeft get() = 12 * scale + 3
    private val listTop get() = 20 * scale
    private val listRight get() = width - 20 * scale - 3
    private val listBottom get() = height - 38 * scale

    private val maxScroll get() = max(0f, PlayerHub.tracks.size * rowHeight - (listBottom - listTop))

    private fun chromeHits(): List<Hit> {
        val w = nativeWidth; val h = nativeHeight
        val br = w - 150; val by = h - 38
        return listOf(
            Hit("close", w - 11, 3, 9, 9),
            Hit("add", 11, by + 12, 25, 18),
            Hit("rem", 40, by + 12, 25, 18),
            Hit("sel", 69, by + 12, 25, 18),
            Hit("misc", 98, by + 12, 25, 18),
            Hit("opts", w - 46, by + 12, 25, 18),
            Hit("mprev", br + 3, by + 22, 8, 9),
            Hit("mplay", br + 11, by + 22, 9, 9),
            Hit("mpause", br + 20, by + 22, 9, 9),
            Hit("mstop", br + 29, by + 22, 9, 9),
            Hit("mnext", br + 38, by + 22, 8, 9),
            Hit("meject", br + 46, by + 22, 10, 9),
            Hit("scroll", w - 17, 20, 12, h - 58),
        )
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollPx = (scrollPx + dy).coerceIn(0f, maxScroll); invalidate(); return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, scrollPx.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll.toInt())
            postInvalidateOnAnimation(); return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val i = rowAt(e.y) ?: run { selected.clear(); invalidate(); return true }
            selected.clear(); selected += i; invalidate(); return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val i = rowAt(e.y) ?: return true
            selected.clear(); selected += i
            PlayerHub.playAt(i)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val i = rowAt(e.y) ?: return
            // Long-press toggles multi-selection, then offers row actions.
            if (i in selected && selected.size > 1) selected -= i else selected += i
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
            host.showMenu(this@PlaylistWindowView, e.x, e.y, rowMenu(i))
        }
    })

    private fun rowAt(y: Float): Int? {
        if (y < listTop || y > listBottom) return null
        val i = ((y - listTop + scrollPx) / rowHeight).toInt()
        return i.takeIf { it in PlayerHub.tracks.indices }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPx = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        }
    }

    /** Called when the playlist or playback changes. */
    fun onPlayerChanged() {
        selected.removeAll { it >= PlayerHub.tracks.size }
        val cur = PlayerHub.currentIndex
        if (cur != lastCurrent && cur >= 0) {
            lastCurrent = cur
            ensureVisible(cur)
        }
        scrollPx = scrollPx.coerceIn(0f, maxScroll)
        invalidate()
    }

    private fun ensureVisible(i: Int) {
        val top = i * rowHeight
        val visible = listBottom - listTop
        if (visible <= 0) return
        if (top < scrollPx) scrollPx = top
        else if (top + rowHeight > scrollPx + visible) scrollPx = top + rowHeight - visible
        scrollPx = scrollPx.coerceIn(0f, maxScroll)
    }

    override fun drawNative(c: Canvas) {
        val pl = skin.pledit
        val w = nativeWidth; val h = nativeHeight
        // Top bar: corners, tiles, centered title.
        c.tile(pl, 127, 0, 25, 20, 25, 0, w - 50, 20)
        c.sprite(pl, 0, 0, 25, 20, 0, 0)
        c.sprite(pl, 26, 0, 100, 20, (w - 100) / 2, 0)
        c.sprite(pl, 153, 0, 25, 20, w - 25, 0)
        if (pressed == "close") c.sprite(pl, 52, 42, 9, 9, w - 11, 3)
        // Sides.
        c.tile(pl, 0, 42, 12, 29, 0, 20, 12, h - 58)
        c.tile(pl, 31, 42, 20, 29, w - 20, 20, 20, h - 58)
        // Bottom.
        c.tile(pl, 179, 0, 25, 38, 125, h - 38, w - 275, 38)
        if (w - 275 >= 75) c.sprite(pl, 205, 0, 75, 38, w - 225, h - 38)
        c.sprite(pl, 0, 72, 125, 38, 0, h - 38)
        c.sprite(pl, 126, 72, 150, 38, w - 150, h - 38)

        // Scroll handle.
        val trackLen = (h - 58 - 18).coerceAtLeast(1)
        val frac = if (maxScroll > 0) scrollPx / maxScroll else 0f
        c.sprite(pl, if (draggingHandle) 61 else 52, 53, 8, 18, w - 15, 20 + (frac * trackLen).roundToInt())

        // Running time ("selected / total") and the mini clock.
        val selDur = selected.sumOf { PlayerHub.tracks.getOrNull(it)?.durationMs ?: 0 }
        c.skinText("${formatTime(selDur).let { if (selected.isEmpty()) "0:00" else it }}/${formatTime(PlayerHub.totalDurationMs)}", w - 143, h - 28, 67)
        if (PlayerHub.current != null && !PlayerHub.stopped) {
            val t = formatTime(PlayerHub.positionMs)
            c.skinText(t.padStart(5), w - 84, h - 15, 25)
        }
    }

    override fun drawOverlay(c: Canvas) {
        val colors = skin.playlistColors
        if (fontFor != colors.font) {
            textPaint.typeface = Typeface.create(colors.font.takeIf { it.isNotBlank() }, Typeface.NORMAL)
            fontFor = colors.font
        }
        val l = listLeft - 3; val t = listTop; val r = listRight + 3; val b = listBottom
        if (r <= l || b <= t) return
        fillPaint.color = colors.normalBg
        c.drawRect(l, t, r, b, fillPaint)
        c.save()
        c.clipRect(l, t, r, b)
        val tracks = PlayerHub.tracks
        val rh = rowHeight
        val first = (scrollPx / rh).toInt().coerceAtLeast(0)
        val last = min(tracks.size - 1, ((scrollPx + (b - t)) / rh).toInt())
        val cur = PlayerHub.currentIndex
        val baseline = (rh - textPaint.descent() - textPaint.ascent()) / 2f
        for (i in first..last) {
            val y = t + i * rh - scrollPx
            if (i in selected) {
                fillPaint.color = colors.selectedBg
                c.drawRect(l, y, r, y + rh, fillPaint)
            }
            val tr = tracks[i]
            textPaint.color = if (i == cur) colors.current else colors.normal
            val dur = if (tr.isStream) "" else formatTime(tr.durationMs)
            val dw = textPaint.measureText(dur)
            c.drawText(dur, listRight - dw, y + baseline, textPaint)
            c.save()
            c.clipRect(listLeft, y, listRight - dw - textPaint.textSize * 0.6f, y + rh)
            c.drawText("${i + 1}. ${tr.displayTitle}", listLeft, y + baseline, textPaint)
            c.restore()
        }
        if (tracks.isEmpty()) {
            textPaint.color = colors.normal
            textPaint.alpha = 140
            c.drawText("Tap ADD or ⏏ to open the library", listLeft, t + baseline, textPaint)
            textPaint.alpha = 255
        }
        c.restore()
    }

    // ---- Touch ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val nx = event.x / scale; val ny = event.y / scale
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val hit = findHit(chromeHits(), nx, ny)
            pressed = hit?.id
            draggingHandle = hit?.id == "scroll"
        }
        val p = pressed
        if (p == null) {
            gestures.onTouchEvent(event)
            return true
        }
        touchX = nx; touchY = ny
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (draggingHandle) dragHandle(ny)
            MotionEvent.ACTION_UP -> {
                if (draggingHandle) dragHandle(ny)
                else if (chromeHits().first { it.id == p }.contains(nx, ny, 8f)) click(p, event.x, event.y)
                pressed = null; draggingHandle = false
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; draggingHandle = false }
        }
        invalidate()
        return true
    }

    private fun dragHandle(ny: Float) {
        val trackLen = (nativeHeight - 58 - 18).coerceAtLeast(1)
        val frac = ((ny - 20 - 9) / trackLen).coerceIn(0f, 1f)
        scrollPx = frac * maxScroll
    }

    private fun click(id: String, x: Float, y: Float) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (id) {
            "close" -> host.togglePlaylist()
            "add" -> host.showMenu(this, x, y, listOf(
                MenuEntry("Library…") { host.openLibrary() },
                MenuEntry("Files…") { (host as? FileSource)?.pickFiles() },
                MenuEntry("Folder…") { (host as? FileSource)?.pickFolder() },
            ))
            "rem" -> host.showMenu(this, x, y, listOf(
                MenuEntry("Remove selected", enabled = selected.isNotEmpty()) { removeSelected() },
                MenuEntry("Crop (keep selected)", enabled = selected.isNotEmpty()) {
                    PlayerHub.remove(PlayerHub.tracks.indices.filter { it !in selected }); selected.clear()
                },
                MenuEntry("Remove duplicates") { removeDuplicates() },
                MenuEntry("Clear playlist") { selected.clear(); PlayerHub.clear() },
            ))
            "sel" -> host.showMenu(this, x, y, listOf(
                MenuEntry("Select all") { selected.clear(); selected += PlayerHub.tracks.indices; invalidate() },
                MenuEntry("Select none") { selected.clear(); invalidate() },
                MenuEntry("Invert selection") {
                    val inv = PlayerHub.tracks.indices.filter { it !in selected }
                    selected.clear(); selected += inv; invalidate()
                },
            ))
            "misc" -> host.showMenu(this, x, y, listOf(
                MenuEntry("Sort list", sub = listOf(
                    MenuEntry("By title") { sortBy(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }) },
                    MenuEntry("By artist") { sortBy(compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }) },
                    MenuEntry("By album") {
                        sortBy(compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.album }.thenBy { it.discNumber }.thenBy { it.trackNumber })
                    },
                    MenuEntry("By file name") { sortBy(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName }) },
                    MenuEntry("By path and file name") { sortBy(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path.ifBlank { it.uri } }) },
                    MenuEntry("Reverse list") { PlayerHub.reorder(PlayerHub.tracks.indices.reversed().toList()); selected.clear() },
                    MenuEntry("Randomize list") { PlayerHub.reorder(PlayerHub.tracks.indices.shuffled()); selected.clear() },
                )),
                MenuEntry("File info…", enabled = selected.isNotEmpty() || PlayerHub.currentIndex >= 0) {
                    host.showFileInfo(selected.firstOrNull() ?: PlayerHub.currentIndex)
                },
                MenuEntry("Move selected up", enabled = selected.size == 1) { moveSelected(-1) },
                MenuEntry("Move selected down", enabled = selected.size == 1) { moveSelected(1) },
            ))
            "opts" -> {
                val saved = MediaLibrary.savedPlaylists(context)
                host.showMenu(this, x, y, listOf(
                    MenuEntry("Save playlist…", enabled = PlayerHub.tracks.isNotEmpty()) {
                        host.promptText("Save playlist", "Playlist") { name ->
                            if (name.isNotBlank()) {
                                MediaLibrary.savePlaylist(context, name, PlayerHub.tracks.toList())
                                host.toast("Saved \"$name\"")
                            }
                        }
                    },
                    MenuEntry("Load playlist", enabled = saved.isNotEmpty(), sub = saved.map { n ->
                        MenuEntry(n) { PlayerHub.replace(MediaLibrary.loadPlaylist(context, n), play = false); selected.clear() }
                    }.ifEmpty { null }),
                    MenuEntry("New playlist") { selected.clear(); PlayerHub.clear() },
                ))
            }
            "mprev" -> PlayerHub.previous()
            "mplay" -> PlayerHub.play()
            "mpause" -> PlayerHub.pause()
            "mstop" -> PlayerHub.stop()
            "mnext" -> PlayerHub.next()
            "meject" -> host.openLibrary()
        }
    }

    private fun rowMenu(i: Int): List<MenuEntry> = listOf(
        MenuEntry("Play") { PlayerHub.playAt(i) },
        MenuEntry("File info…") { host.showFileInfo(i) },
        MenuEntry(if (selected.size > 1) "Remove ${selected.size} tracks" else "Remove") { removeSelected() },
        MenuEntry("Move up", enabled = i > 0) { PlayerHub.move(i, i - 1); selected.clear(); selected += i - 1 },
        MenuEntry("Move down", enabled = i < PlayerHub.tracks.size - 1) { PlayerHub.move(i, i + 1); selected.clear(); selected += i + 1 },
    )

    private fun removeSelected() {
        PlayerHub.remove(selected.toList())
        selected.clear()
    }

    private fun removeDuplicates() {
        val seen = HashSet<String>()
        PlayerHub.remove(PlayerHub.tracks.indices.filter { !seen.add(PlayerHub.tracks[it].uri) })
        selected.clear()
    }

    private fun moveSelected(delta: Int) {
        val i = selected.firstOrNull() ?: return
        val to = i + delta
        if (to !in PlayerHub.tracks.indices) return
        PlayerHub.move(i, to)
        selected.clear(); selected += to
    }

    private fun sortBy(cmp: Comparator<Track>) {
        val order = PlayerHub.tracks.indices.sortedWith { a, b -> cmp.compare(PlayerHub.tracks[a], PlayerHub.tracks[b]) }
        PlayerHub.reorder(order)
        selected.clear()
    }
}

/** Implemented by the activity: system file / folder pickers and the audio permission. */
interface FileSource {
    fun pickFiles()
    fun pickFolder()
    fun pickSkin()
    fun hasAudioPermission(): Boolean
    fun requestAudioPermission()
}
