package com.foldamp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import com.foldamp.playback.SpectrumAnalyzer
import kotlin.math.max
import kotlin.math.roundToInt

/** A resizable "general purpose" window framed with gen.bmp, as used by Winamp's plugin windows. */
open class GenWindowView(context: Context, host: UiHost, var title: String) : SkinView(context, host) {

    var onClose: (() -> Unit)? = null

    /** Client area in device pixels. */
    val clientLeft get() = 11 * scale
    val clientTop get() = 20 * scale
    val clientRight get() = width - 8 * scale
    val clientBottom get() = height - 14 * scale

    private fun titleWidth(): Int {
        val letters = skin.genLetters
        if (letters.isEmpty()) return title.length * 5
        return title.sumOf { ch -> (letters[ch.uppercaseChar()]?.width() ?: 5) + 1 }
    }

    override fun drawNative(c: Canvas) {
        val g = skin.gen
        val w = nativeWidth; val h = nativeHeight
        val tw = titleWidth() + 4
        val leftEnd = ((w - tw) / 2 - 25).coerceAtLeast(25)

        c.tile(g, 104, 0, 25, 20, 25, 0, w - 50, 20)
        c.sprite(g, 0, 0, 25, 20, 0, 0)
        c.sprite(g, 26, 0, 25, 20, leftEnd, 0)
        c.tile(g, 52, 0, 25, 20, leftEnd + 25, 0, tw, 20)
        c.sprite(g, 78, 0, 25, 20, leftEnd + 25 + tw, 0)
        c.sprite(g, 130, 0, 25, 20, w - 25, 0)
        drawTitle(c, leftEnd + 27, 4)
        if (pressed == "close") c.sprite(g, 148, 42, 9, 9, w - 11, 3)

        val side = h - 34
        c.tile(g, 127, 42, 11, 29, 0, 20, 11, side - 24)
        c.sprite(g, 158, 42, 11, 24, 0, 20 + side - 24)
        c.tile(g, 139, 42, 8, 29, w - 8, 20, 8, side - 24)
        c.sprite(g, 170, 42, 8, 24, w - 8, 20 + side - 24)

        c.tile(g, 127, 72, 25, 14, 125, h - 14, w - 250, 14)
        c.sprite(g, 0, 42, 125, 14, 0, h - 14)
        c.sprite(g, 0, 57, 125, 14, w - 125, h - 14)
    }

    private fun drawTitle(c: Canvas, x0: Int, y: Int) {
        val letters = skin.genLetters
        if (letters.isEmpty()) { c.skinText(title, x0, y + 1); return }
        var x = x0
        for (ch in title) {
            val r = letters[ch.uppercaseChar()]
            if (r == null) { x += 5; continue }
            c.sprite(skin.gen, r.left, r.top, r.width(), r.height(), x, y)
            x += r.width() + 1
        }
    }

    override fun onDown(e: MotionEvent) {
        if (touchY < 14 && touchX > nativeWidth - 14) pressed = "close"
        invalidate()
    }

    override fun onUp(e: MotionEvent) {
        if (pressed == "close" && touchY < 18 && touchX > nativeWidth - 18) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClose?.invoke()
        } else if (pressed == null) {
            onClientTap(e.x, e.y)
        }
    }

    protected open fun onClientTap(x: Float, y: Float) {}
}

/** The big spectrum analyzer window shown on the inner screen. Tap to cycle styles. */
class VisualizerWindowView(context: Context, host: UiHost) : GenWindowView(context, host, "FOLDAMP SPECTRUM") {
    var analyzer: SpectrumAnalyzer? = null
    /** 0 = rainbow blocks, 1 = skin colors, 2 = oscilloscope. */
    var mode = 0

    private val dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)
    private val rect = RectF()
    private val barPaint = Paint()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private var gradientFor = 0f

    val barCount: Int
        get() = ((clientRight - clientLeft) / (16 * dp)).toInt().coerceIn(12, 64)

    override fun drawOverlay(c: Canvas) {
        val l = clientLeft; val t = clientTop; val r = clientRight; val b = clientBottom
        if (r <= l || b <= t) return
        val vc = skin.visColors
        fillPaint.color = if (mode == 0) 0xFF000000.toInt() else vc[0]
        c.drawRect(l, t, r, b, fillPaint)
        val a = analyzer ?: return
        c.save()
        c.clipRect(l, t, r, b)
        when (mode) {
            0 -> drawBlocks(c, a, l, t, r, b)
            1 -> drawSkinBars(c, a, l, t, r, b, vc)
            else -> drawScope(c, a, l, t, r, b, vc)
        }
        c.restore()
    }

    private fun drawBlocks(c: Canvas, a: SpectrumAnalyzer, l: Float, t: Float, r: Float, b: Float) {
        val n = a.bars.size
        if (n == 0) return
        val pad = 6 * dp
        val gap = max(2f, 3 * dp)
        val bw = (r - l - pad * 2 - gap * (n - 1)) / n
        val segH = 6 * dp
        val segGap = 2 * dp
        val segs = ((b - t - pad * 2) / (segH + segGap)).toInt().coerceAtLeast(4)
        val usableH = segs * (segH + segGap)
        val bottom = b - pad
        if (gradientFor != usableH) {
            barPaint.shader = LinearGradient(
                0f, bottom, 0f, bottom - usableH,
                intArrayOf(0xFF2A3CFF.toInt(), 0xFF14A8E8.toInt(), 0xFF1EE07A.toInt(), 0xFFB8E830.toInt(), 0xFFFF8A1E.toInt()),
                floatArrayOf(0f, 0.28f, 0.55f, 0.8f, 1f), Shader.TileMode.CLAMP,
            )
            gradientFor = usableH
        }
        for (i in 0 until n) {
            val x = l + pad + i * (bw + gap)
            val lit = (a.bars[i] * segs).roundToInt()
            for (s in 0 until lit) {
                val y = bottom - (s + 1) * (segH + segGap) + segGap
                rect.set(x, y, x + bw, y + segH)
                c.drawRect(rect, barPaint)
            }
            val pk = (a.peaks[i] * segs).roundToInt()
            if (pk > 0) {
                val y = bottom - pk * (segH + segGap) - segH * 0.2f
                fillPaint.color = 0xFFFFFFFF.toInt()
                c.drawRect(x, y, x + bw, y + segH, fillPaint)
            }
        }
    }

    private fun drawSkinBars(c: Canvas, a: SpectrumAnalyzer, l: Float, t: Float, r: Float, b: Float, vc: IntArray) {
        val n = a.bars.size
        if (n == 0) return
        val bw = (r - l) / n
        val h = b - t
        val rows = 16
        for (i in 0 until n) {
            val x = l + i * bw
            val lit = a.bars[i] * h
            for (k in 0 until rows) {
                val y0 = b - (k + 1) * h / rows
                val y1 = b - k * h / rows
                val top = max(y0, b - lit)
                if (top >= y1) break
                fillPaint.color = vc[17 - k]
                c.drawRect(x + 1, top, x + bw - 1, y1, fillPaint)
            }
            fillPaint.color = vc[23]
            val py = b - a.peaks[i] * h
            c.drawRect(x + 1, py - 2 * dp, x + bw - 1, py, fillPaint)
        }
    }

    private fun drawScope(c: Canvas, a: SpectrumAnalyzer, l: Float, t: Float, r: Float, b: Float, vc: IntArray) {
        val wave = a.wave
        val mid = (t + b) / 2
        val amp = (b - t) * 0.45f
        linePaint.strokeWidth = 2.5f * dp
        linePaint.color = vc[18]
        var px = l; var py = mid
        val steps = wave.size
        for (i in 0 until steps) {
            val x = l + (r - l) * i / (steps - 1)
            val y = mid - wave[i].coerceIn(-1f, 1f) * amp
            if (i > 0) c.drawLine(px, py, x, y, linePaint)
            px = x; py = y
        }
    }

    override fun onClientTap(x: Float, y: Float) {
        mode = (mode + 1) % 3
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }
}
