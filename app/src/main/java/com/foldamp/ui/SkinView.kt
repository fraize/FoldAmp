package com.foldamp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.foldamp.skin.Skin
import com.foldamp.skin.SkinManager
import java.util.WeakHashMap
import kotlin.math.min

/** A rectangle in the window's native (unscaled skin pixel) coordinate space. */
data class Hit(val id: String, val x: Int, val y: Int, val w: Int, val h: Int) {
    fun contains(px: Float, py: Float, slop: Float = 0f) =
        px >= x - slop && px < x + w + slop && py >= y - slop && py < y + h + slop
}

/**
 * Sprites cut out as their own bitmaps. Drawing a sub-rect of a sheet at fractional scales lets the
 * GPU sample neighbouring pixels (skins put coloured separator lines between sprites), so we slice.
 */
private object SpriteCache {
    private val cache = WeakHashMap<Bitmap, HashMap<Long, Bitmap>>()

    fun slice(bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Bitmap {
        if (x0 == 0 && y0 == 0 && x1 == bmp.width && y1 == bmp.height) return bmp
        val key = (x0.toLong() shl 48) or (y0.toLong() shl 32) or (x1.toLong() shl 16) or y1.toLong()
        return cache.getOrPut(bmp) { HashMap() }.getOrPut(key) { Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0) }
    }
}

/** Things the skinned windows ask the activity to do. */
interface UiHost {
    fun showMenu(from: View, x: Float, y: Float, items: List<MenuEntry>)
    fun toggleEq()
    fun togglePlaylist()
    fun isEqShown(): Boolean
    fun isPlaylistShown(): Boolean
    fun openLibrary()
    fun closeLibrary()
    fun showFileInfo(index: Int)
    fun mainMenu(from: View, x: Float, y: Float)
    fun minimize()
    fun exit()
    fun promptText(title: String, initial: String, done: (String) -> Unit)
    fun toast(msg: String)
}

data class MenuEntry(
    val title: String,
    val checked: Boolean? = null,
    val enabled: Boolean = true,
    val sub: List<MenuEntry>? = null,
    val action: (() -> Unit)? = null,
)

/**
 * Base for windows painted from skin sprites. Subclasses draw in native skin pixels; the view
 * scales everything uniformly with nearest-neighbour sampling so the pixel art stays crisp.
 */
abstract class SkinView(context: Context, protected val host: UiHost) : View(context) {
    var scale = 1f
        set(v) { if (field != v) { field = v; invalidate() } }

    protected val skin: Skin get() = SkinManager.current
    protected val nativeWidth: Int get() = (width / scale).toInt()
    protected val nativeHeight: Int get() = (height / scale).toInt()

    protected val bmpPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    protected val fillPaint = Paint().apply { isAntiAlias = false }
    private val dst = RectF()

    /** Native coordinates of the active touch. */
    protected var touchX = 0f
    protected var touchY = 0f
    protected var pressed: String? = null

    final override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(scale, scale)
        drawNative(canvas)
        canvas.restore()
        drawOverlay(canvas)
    }

    protected abstract fun drawNative(c: Canvas)

    /** Optional pass in real device pixels (used for smooth text and the big visualizer). */
    protected open fun drawOverlay(c: Canvas) {}

    // ---- Sprite helpers ----

    /** Draws sprite (sx,sy,w,h) of [bmp] at native (dx,dy), stretched to (dw,dh) if given. */
    protected fun Canvas.sprite(
        bmp: Bitmap?, sx: Int, sy: Int, w: Int, h: Int,
        dx: Float, dy: Float, dw: Float = w.toFloat(), dh: Float = h.toFloat(),
    ) {
        if (bmp == null || w <= 0 || h <= 0) return
        // Clip the source to the bitmap, shrinking the destination to match, so short sheets in
        // older skins don't smear edge pixels across the window.
        val x0 = sx.coerceIn(0, bmp.width); val y0 = sy.coerceIn(0, bmp.height)
        val x1 = (sx + w).coerceIn(0, bmp.width); val y1 = (sy + h).coerceIn(0, bmp.height)
        if (x1 <= x0 || y1 <= y0) return
        val kx = dw / w; val ky = dh / h
        dst.set(dx + (x0 - sx) * kx, dy + (y0 - sy) * ky, dx + (x1 - sx) * kx, dy + (y1 - sy) * ky)
        drawBitmap(SpriteCache.slice(bmp, x0, y0, x1, y1), null, dst, bmpPaint)
    }

    protected fun Canvas.sprite(bmp: Bitmap?, sx: Int, sy: Int, w: Int, h: Int, dx: Int, dy: Int) =
        sprite(bmp, sx, sy, w, h, dx.toFloat(), dy.toFloat())

    /** Repeats a sprite to fill the area, cropping the last repetition. */
    protected fun Canvas.tile(bmp: Bitmap?, sx: Int, sy: Int, w: Int, h: Int, dx: Int, dy: Int, dw: Int, dh: Int) {
        if (bmp == null || dw <= 0 || dh <= 0) return
        var y = 0
        while (y < dh) {
            val th = min(h, dh - y)
            var x = 0
            while (x < dw) {
                val tw = min(w, dw - x)
                sprite(bmp, sx, sy, tw, th, dx + x, dy + y)
                x += w
            }
            y += h
        }
    }

    /** Draws [text] with the skin's text.bmp 5x6 bitmap font, clipped to [maxWidth]. */
    protected fun Canvas.skinText(text: String, x: Int, y: Int, maxWidth: Int = Int.MAX_VALUE, offset: Int = 0) {
        val bmp = skin.text ?: return
        save()
        clipRect(x, y, if (maxWidth == Int.MAX_VALUE) Int.MAX_VALUE / 4 else x + maxWidth, y + 6)
        var cx = x - offset
        for (ch in text) {
            if (cx > x + maxWidth.toLong()) break
            if (cx + 5 > x) {
                val (row, col) = textGlyph(ch)
                sprite(bmp, col * 5, row * 6, 5, 6, cx, y)
            }
            cx += 5
        }
        restore()
    }

    /** Native-pixel hit testing with a little slop, since fingers are bigger than mouse pointers. */
    protected fun findHit(hits: List<Hit>, x: Float, y: Float): Hit? =
        hits.firstOrNull { it.contains(x, y) } ?: hits.firstOrNull { it.contains(x, y, 3f) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        touchX = event.x / scale
        touchY = event.y / scale
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); onDown(event) }
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_UP -> { onUp(event); pressed = null; invalidate() }
            MotionEvent.ACTION_CANCEL -> { onCancel(); pressed = null; invalidate() }
        }
        return true
    }

    protected open fun onDown(e: MotionEvent) {}
    protected open fun onMove(e: MotionEvent) {}
    protected open fun onUp(e: MotionEvent) {}
    protected open fun onCancel() {}

    companion object {
        private const val ROW0 = "abcdefghijklmnopqrstuvwxyz\"@"
        private const val ROW1 = "0123456789….:()-'!_+\\/[]^&%,=\$#"
        private const val ROW2 = "ÅÖÄ?*"

        /** Row/column of a character in text.bmp (Winamp's layout, matching Webamp's table). */
        fun textGlyph(ch: Char): Pair<Int, Int> {
            val c = ch.lowercaseChar()
            ROW0.indexOf(c).takeIf { it >= 0 }?.let { return 0 to it }
            ROW1.indexOf(c).takeIf { it >= 0 }?.let { return 1 to it }
            ROW2.indexOf(ch.uppercaseChar()).takeIf { it >= 0 }?.let { return 2 to it }
            return when (c) {
                '<', '{' -> 1 to 22
                '>', '}' -> 1 to 23
                ';' -> 1 to 12
                '`' -> 1 to 16
                '|' -> 1 to 21
                '~' -> 1 to 15
                'å' -> 2 to 0
                'ö' -> 2 to 1
                'ä' -> 2 to 2
                else -> 0 to 30
            }
        }
    }
}
