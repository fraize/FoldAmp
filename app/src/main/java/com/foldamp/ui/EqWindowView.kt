package com.foldamp.ui

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import com.foldamp.playback.Equalizer
import kotlin.math.roundToInt

/** The 275x116 equalizer window: ON/AUTO/PRESETS, preamp and ten band sliders, plus the curve graph. */
class EqWindowView(context: Context, host: UiHost) : SkinView(context, host) {

    private val hits = buildList {
        add(Hit("close", 264, 3, 9, 9))
        add(Hit("on", 14, 18, 26, 12))
        add(Hit("auto", 40, 18, 32, 12))
        add(Hit("presets", 217, 18, 44, 12))
        add(Hit("preamp", 19, 36, 18, 67))
        for (i in 0 until 10) add(Hit("band$i", 76 + i * 18, 36, 18, 67))
    }

    private var lastTapId: String? = null
    private var lastTapTime = 0L
    private var lineColors: IntArray? = null
    private var lineColorsFor: Any? = null

    override fun drawNative(c: Canvas) {
        val s = skin
        val eq = s.eqmain
        c.sprite(eq, 0, 0, 275, 116, 0, 0)
        c.sprite(eq, 0, 134, 275, 14, 0, 0)
        if (pressed == "close") c.sprite(eq, 0, 125, 9, 9, 264, 3)

        val on = Equalizer.enabled
        val auto = Equalizer.auto
        val onX = when { on && pressed == "on" -> 187; on -> 69; pressed == "on" -> 128; else -> 10 }
        c.sprite(eq, onX, 119, 26, 12, 14, 18)
        val autoX = when { auto && pressed == "auto" -> 213; auto -> 95; pressed == "auto" -> 154; else -> 36 }
        c.sprite(eq, autoX, 119, 32, 12, 40, 18)
        c.sprite(eq, 224, if (pressed == "presets") 176 else 164, 44, 12, 217, 18)

        drawGraph(c)

        drawSlider(c, 21, Equalizer.preampDb, pressed == "preamp")
        for (i in 0 until 10) drawSlider(c, 78 + i * 18, Equalizer.band(i), pressed == "band$i")
    }

    private fun drawSlider(c: Canvas, x: Int, db: Float, active: Boolean) {
        val eq = skin.eqmain
        val frame = (((db + 12f) / 24f) * 27f).roundToInt().coerceIn(0, 27)
        c.sprite(eq, 13 + (frame % 14) * 15, 164 + (frame / 14) * 65, 14, 63, x, 38)
        val ty = (((12f - db) / 24f) * 51f).roundToInt().coerceIn(0, 51)
        c.sprite(eq, 0, if (active) 176 else 164, 11, 11, x + 1, 38 + ty)
    }

    private fun drawGraph(c: Canvas) {
        val eq = skin.eqmain ?: return
        c.sprite(eq, 0, 294, 113, 19, 86, 17)
        if (lineColorsFor !== eq) {
            lineColors = IntArray(19) { y -> if (eq.height > 294 + y && eq.width > 115) eq.getPixel(115, 294 + y) else 0xFF00FF00.toInt() }
            lineColorsFor = eq
        }
        val colors = lineColors ?: return
        // Preamp marker line.
        val py = 17 + (((12f - Equalizer.preampDb) / 24f) * 18f).roundToInt().coerceIn(0, 18)
        c.sprite(eq, 0, 314, 113, 1, 86, py)
        // Catmull-Rom spline through the ten band values.
        val v = FloatArray(10) { Equalizer.band(it) }
        var prevY = -1
        for (x in 0 until 113) {
            val t = x / 112f * 9f
            val i = t.toInt().coerceAtMost(8)
            val f = t - i
            val p0 = v[(i - 1).coerceAtLeast(0)]; val p1 = v[i]; val p2 = v[i + 1]; val p3 = v[(i + 2).coerceAtMost(9)]
            val y = 0.5f * (2 * p1 + (-p0 + p2) * f + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f * f + (-p0 + 3 * p1 - 3 * p2 + p3) * f * f * f)
            val gy = (((12f - y.coerceIn(-12f, 12f)) / 24f) * 18f).roundToInt().coerceIn(0, 18)
            val top = if (prevY < 0) gy else minOf(prevY, gy)
            val bottom = if (prevY < 0) gy else maxOf(prevY, gy)
            for (yy in top..bottom) {
                fillPaint.color = colors[yy]
                c.drawRect((86 + x).toFloat(), (17 + yy).toFloat(), (87 + x).toFloat(), (18 + yy).toFloat(), fillPaint)
            }
            prevY = gy
        }
    }

    override fun onDown(e: MotionEvent) {
        val hit = findHit(hits, touchX, touchY) ?: return
        pressed = hit.id
        val now = SystemClock.uptimeMillis()
        val doubleTap = lastTapId == hit.id && now - lastTapTime < 350
        lastTapId = hit.id; lastTapTime = now
        if (hit.id == "preamp" || hit.id.startsWith("band")) {
            // Double-tap a slider to snap it back to 0 dB.
            if (doubleTap) setSlider(hit.id, 0f) else dragSlider(hit.id)
        }
        invalidate()
    }

    override fun onMove(e: MotionEvent) {
        val id = pressed ?: return
        if (id == "preamp" || id.startsWith("band")) dragSlider(id)
    }

    private fun dragSlider(id: String) {
        val ty = (touchY - 38f - 5.5f).coerceIn(0f, 51f)
        var db = 12f - ty / 51f * 24f
        if (kotlin.math.abs(db) < 0.7f) db = 0f
        setSlider(id, db)
    }

    private fun setSlider(id: String, db: Float) {
        if (id == "preamp") Equalizer.setPreamp(db) else Equalizer.setBand(id.removePrefix("band").toInt(), db)
        invalidate()
    }

    override fun onUp(e: MotionEvent) {
        val id = pressed ?: return
        val hit = hits.first { it.id == id }
        if (!hit.contains(touchX, touchY, 8f)) return
        when (id) {
            "on" -> Equalizer.setEnabled(!Equalizer.enabled)
            "auto" -> Equalizer.setAuto(!Equalizer.auto)
            "close" -> host.toggleEq()
            "presets" -> host.showMenu(this, e.x, e.y, presetMenu())
            else -> return
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun presetMenu(): List<MenuEntry> {
        val builtIn = Equalizer.BUILT_IN.keys.map { name -> MenuEntry(name) { Equalizer.loadPreset(name) } }
        val user = Equalizer.userPresetNames()
        return listOf(
            MenuEntry("Load", sub = builtIn + user.map { n -> MenuEntry("★ $n") { Equalizer.loadPreset(n) } }),
            MenuEntry("Save preset…") {
                host.promptText("Save EQ preset", "My preset") { name ->
                    if (name.isNotBlank()) { Equalizer.saveUserPreset(name.trim()); host.toast("Saved \"${name.trim()}\"") }
                }
            },
            MenuEntry("Delete preset", enabled = user.isNotEmpty(), sub = user.map { n -> MenuEntry(n) { Equalizer.deleteUserPreset(n) } }.ifEmpty { null }),
            MenuEntry("Reset to flat") { Equalizer.apply(FloatArray(10), 0f) },
        )
    }
}
