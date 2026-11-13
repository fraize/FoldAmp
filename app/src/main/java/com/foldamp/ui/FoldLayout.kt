package com.foldamp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Arranges the skinned windows for whichever Fold screen we're on:
 *
 *  - Cover, portrait:   Player on top at full width, Playlist fills the rest.
 *  - Cover, landscape:  Player stacked over Equalizer, scaled uniformly to fit.
 *  - Inner, landscape:  Player top-left at 50% width, Visualizer below it, Playlist on the right.
 *  - Inner, portrait:   Player on top, a Visualizer strip, then the Playlist.
 *
 * Fixed-size windows (Player, EQ) keep the 275x116 skin ratio; every window shares one scale so
 * their chrome lines up.
 */
@SuppressLint("ViewConstructor")
class FoldLayout(
    context: Context,
    private val main: MainWindowView,
    private val eq: EqWindowView,
    private val playlist: PlaylistWindowView,
    private val vis: VisualizerWindowView,
    private val library: LibraryWindow,
) : ViewGroup(context) {

    enum class Mode { COVER_PORTRAIT, COVER_LANDSCAPE, INNER_LANDSCAPE, INNER_PORTRAIT }

    var mode = Mode.COVER_PORTRAIT
        private set

    /** EQ visibility on every layout except cover-landscape, where it's governed by [coverBottomEq]. */
    var eqShown = false
    var playlistShown = true
    /** Cover-landscape shows either the EQ (default, per the mockup) or the playlist under the player. */
    var coverBottomEq = true
    var libraryShown = false

    val menuAnchor = View(context)
    private var anchorX = 0
    private var anchorY = 0

    private val rects = HashMap<View, Rect>()

    init {
        listOf(main, eq, playlist, vis, library).forEach { addView(it) }
        addView(menuAnchor, LayoutParams(1, 1))
        setBackgroundColor(0xFF000000.toInt())
    }

    fun placeAnchor(view: View, x: Float, y: Float) {
        val loc = IntArray(2); view.getLocationInWindow(loc)
        val mine = IntArray(2); getLocationInWindow(mine)
        anchorX = (loc[0] - mine[0] + x).roundToInt()
        anchorY = (loc[1] - mine[1] + y).roundToInt()
        menuAnchor.layout(anchorX, anchorY, anchorX + 1, anchorY + 1)
    }

    /** Whether the EQ window is currently on screen (drives the EQ button's lit state). */
    fun isEqVisible() = rects.containsKey(eq)
    fun isPlaylistVisible() = rects.containsKey(playlist)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        computeRects(w - paddingLeft - paddingRight, h - paddingTop - paddingBottom)
        for (v in listOf(main, eq, playlist, vis, library)) {
            val r = rects[v]
            if (r == null) {
                v.visibility = GONE
            } else {
                v.visibility = VISIBLE
                v.measure(MeasureSpec.makeMeasureSpec(r.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(r.height(), MeasureSpec.EXACTLY))
            }
        }
        menuAnchor.measure(MeasureSpec.makeMeasureSpec(1, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(1, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for ((v, rect) in rects) {
            v.layout(paddingLeft + rect.left, paddingTop + rect.top, paddingLeft + rect.right, paddingTop + rect.bottom)
        }
        menuAnchor.layout(anchorX, anchorY, anchorX + 1, anchorY + 1)
    }

    private fun computeRects(width: Int, height: Int) {
        rects.clear()
        if (width <= 0 || height <= 0) return
        val inner = resources.configuration.smallestScreenWidthDp >= 600
        val landscape = width > height
        mode = when {
            !inner && !landscape -> Mode.COVER_PORTRAIT
            !inner -> Mode.COVER_LANDSCAPE
            landscape -> Mode.INNER_LANDSCAPE
            else -> Mode.INNER_PORTRAIT
        }
        val W = width.toFloat(); val H = height.toFloat()

        when (mode) {
            Mode.COVER_PORTRAIT -> {
                // With the EQ open, shrink so the playlist below keeps ~100 skin pixels of rows.
                val s = min(W / 275f, if (eqShown) H / (232f + 100f) else H * 0.5f / 116f)
                val x0 = (W - 275 * s) / 2; val cw = 275 * s
                var y = place(main, x0, 0f, cw, 116 * s)
                if (eqShown) y = place(eq, x0, y, cw, 116 * s)
                val bottom = when {
                    libraryShown -> library
                    playlistShown -> playlist
                    else -> vis
                }
                place(bottom, x0, y, cw, H - y)
                applyScale(s)
            }
            Mode.COVER_LANDSCAPE -> {
                val bottom: View = when {
                    libraryShown -> library
                    coverBottomEq -> eq
                    else -> playlist
                }
                if (bottom === eq) {
                    val s = min(W / 275f, H / 232f)
                    val cw = 275 * s
                    val x0 = (W - cw) / 2; val y0 = (H - 232 * s) / 2
                    val y = place(main, x0, y0, cw, 116 * s)
                    place(eq, x0, y, cw, 116 * s)
                    applyScale(s)
                } else {
                    val s = min(W / 275f, H * 0.5f / 116f)
                    val cw = 275 * s
                    val x0 = (W - cw) / 2
                    val y = place(main, x0, 0f, cw, 116 * s)
                    place(bottom, x0, y, cw, H - y)
                    applyScale(s)
                }
            }
            Mode.INNER_LANDSCAPE -> {
                val half = (W / 2f).roundToInt().toFloat()
                val s = min(half / 275f, H * 0.5f / 116f)
                val cw = 275 * s
                val x0 = (half - cw) / 2
                var y = place(main, x0, 0f, cw, 116 * s)
                if (eqShown) y = place(eq, x0, y, cw, 116 * s)
                // Right column: playlist (or whatever it's swapped for); left column: the rest.
                val right: View = when {
                    playlistShown -> playlist
                    libraryShown -> library
                    else -> vis
                }
                val leftBottom: View? = when {
                    right === playlist && libraryShown -> library
                    right !== vis -> vis
                    else -> null
                }
                leftBottom?.let { place(it, x0, y, cw, H - y) }
                place(right, half, 0f, W - half, H)
                applyScale(s)
            }
            Mode.INNER_PORTRAIT -> {
                val s = min(W / 275f, if (eqShown) H / (232f + 120f) else H * 0.4f / 116f)
                val cw = 275 * s
                val x0 = (W - cw) / 2
                var y = place(main, x0, 0f, cw, 116 * s)
                if (eqShown) y = place(eq, x0, y, cw, 116 * s)
                val bottom: View? = when {
                    libraryShown -> library
                    playlistShown -> playlist
                    else -> null
                }
                val visH = ((H - y) * 0.26f).coerceAtLeast(60 * s)
                when {
                    bottom == null -> place(vis, x0, y, cw, H - y)
                    // Not enough room for a useful strip (e.g. EQ open): the playlist/library wins.
                    H - y - visH < 116 * s -> place(bottom, x0, y, cw, H - y)
                    else -> {
                        y = place(vis, x0, y, cw, visH)
                        place(bottom, x0, y, cw, H - y)
                    }
                }
                applyScale(s)
            }
        }
    }

    /** Places [v] in the given float rect (snapped to whole pixels) and returns its bottom edge. */
    private fun place(v: View, x: Float, y: Float, w: Float, h: Float): Float {
        val r = Rect(x.roundToInt(), y.roundToInt(), (x + w).roundToInt(), (y + h).roundToInt())
        if (r.width() > 0 && r.height() > 0) rects[v] = r
        return r.bottom.toFloat()
    }

    private fun applyScale(s: Float) {
        main.scale = s; eq.scale = s; playlist.scale = s; vis.scale = s; library.scale = s
    }
}
