package com.foldamp.playback

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Equalizer state shared between the UI thread and the audio thread. The audio processor polls
 * [version] and rebuilds its filters whenever it changes.
 */
object Equalizer {
    val FREQUENCIES = floatArrayOf(60f, 170f, 310f, 600f, 1000f, 3000f, 6000f, 12000f, 14000f, 16000f)
    val LABELS = arrayOf("60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K")
    const val MAX_DB = 12f

    @Volatile var enabled = true
        private set
    @Volatile var auto = false
        private set
    @Volatile var preampDb = 0f
        private set
    private val bands = FloatArray(10)
    @Volatile var version = 0
        private set

    private var prefs: SharedPreferences? = null
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun band(i: Int) = bands[i]
    fun bandsCopy(): FloatArray = bands.copyOf()

    fun setBand(i: Int, db: Float) { bands[i] = db.coerceIn(-MAX_DB, MAX_DB); changed() }
    fun setPreamp(db: Float) { preampDb = db.coerceIn(-MAX_DB, MAX_DB); changed() }
    fun setEnabled(on: Boolean) { enabled = on; changed() }
    fun setAuto(on: Boolean) { auto = on; changed() }

    fun apply(values: FloatArray, preamp: Float? = null) {
        for (i in 0 until 10) bands[i] = values.getOrElse(i) { 0f }.coerceIn(-MAX_DB, MAX_DB)
        if (preamp != null) preampDb = preamp.coerceIn(-MAX_DB, MAX_DB)
        changed()
    }

    private fun changed() {
        version++
        save()
        listeners.toList().forEach { it() }
    }

    // ---- Presets ----

    /** Classic Winamp presets, in dB. */
    val BUILT_IN: LinkedHashMap<String, FloatArray> = linkedMapOf(
        "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Classical" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, -7.2f, -7.2f, -7.2f, -9.6f),
        "Club" to floatArrayOf(0f, 0f, 8f, 5.6f, 5.6f, 5.6f, 3.2f, 0f, 0f, 0f),
        "Dance" to floatArrayOf(9.6f, 7.2f, 2.4f, 0f, 0f, -5.6f, -7.2f, -7.2f, 0f, 0f),
        "Full Bass" to floatArrayOf(-8f, 9.6f, 9.6f, 5.6f, 1.6f, -4f, -8f, -10.4f, -11.2f, -11.2f),
        "Full Bass & Treble" to floatArrayOf(7.2f, 5.6f, 0f, -7.2f, -4.8f, 1.6f, 8f, 11.2f, 12f, 12f),
        "Full Treble" to floatArrayOf(-9.6f, -9.6f, -9.6f, -4f, 2.4f, 11.2f, 12f, 12f, 12f, 12f),
        "Laptop Speakers / Headphones" to floatArrayOf(4.8f, 11.2f, 5.6f, -3.2f, -2.4f, 1.6f, 4.8f, 9.6f, 12f, 12f),
        "Large Hall" to floatArrayOf(10.4f, 10.4f, 5.6f, 5.6f, 0f, -4.8f, -4.8f, -4.8f, 0f, 0f),
        "Live" to floatArrayOf(-4.8f, 0f, 4f, 5.6f, 5.6f, 5.6f, 4f, 2.4f, 2.4f, 2.4f),
        "Party" to floatArrayOf(7.2f, 7.2f, 0f, 0f, 0f, 0f, 0f, 0f, 7.2f, 7.2f),
        "Pop" to floatArrayOf(-1.6f, 4.8f, 7.2f, 8f, 5.6f, 0f, -2.4f, -2.4f, -1.6f, -1.6f),
        "Reggae" to floatArrayOf(0f, 0f, 0f, -5.6f, 0f, 6.4f, 6.4f, 0f, 0f, 0f),
        "Rock" to floatArrayOf(8f, 4.8f, -5.6f, -8f, -3.2f, 4f, 8.8f, 11.2f, 11.2f, 11.2f),
        "Ska" to floatArrayOf(-2.4f, -4.8f, -4f, 0f, 4f, 5.6f, 8.8f, 9.6f, 11.2f, 9.6f),
        "Soft" to floatArrayOf(4.8f, 1.6f, 0f, -2.4f, 0f, 4f, 8f, 9.6f, 11.2f, 12f),
        "Soft Rock" to floatArrayOf(4f, 4f, 2.4f, 0f, -4f, -5.6f, -3.2f, 0f, 2.4f, 8.8f),
        "Techno" to floatArrayOf(8f, 5.6f, 0f, -5.6f, -4.8f, 0f, 8f, 9.6f, 9.6f, 8.8f),
    )

    private val userPresets = linkedMapOf<String, Pair<FloatArray, Float>>()

    fun userPresetNames(): List<String> = userPresets.keys.toList()

    fun loadPreset(name: String) {
        userPresets[name]?.let { (b, p) -> apply(b, p); return }
        BUILT_IN[name]?.let { apply(it, 0f) }
    }

    fun saveUserPreset(name: String) {
        userPresets[name] = bands.copyOf() to preampDb
        save()
    }

    fun deleteUserPreset(name: String) {
        userPresets.remove(name)
        save()
    }

    /** "Auto" mode: pick a built-in preset whose name matches the track's genre. */
    fun autoApplyForGenre(genre: String) {
        if (!auto || genre.isBlank()) return
        val g = genre.lowercase()
        val match = BUILT_IN.keys.firstOrNull { it.lowercase() == g }
            ?: BUILT_IN.keys.firstOrNull { it != "Flat" && g.contains(it.lowercase()) }
        if (match != null) loadPreset(match)
    }

    // ---- Persistence ----

    fun init(context: Context) {
        val p = context.getSharedPreferences("eq", Context.MODE_PRIVATE)
        enabled = p.getBoolean("enabled", true)
        auto = p.getBoolean("auto", false)
        preampDb = p.getFloat("preamp", 0f)
        for (i in 0 until 10) bands[i] = p.getFloat("band$i", 0f)
        runCatching {
            val o = JSONObject(p.getString("userPresets", "{}")!!)
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                val arr = e.getJSONArray("bands")
                userPresets[k] = FloatArray(10) { arr.optDouble(it, 0.0).toFloat() } to e.optDouble("preamp", 0.0).toFloat()
            }
        }
        prefs = p
        version++
    }

    private fun save() {
        val p = prefs ?: return
        val users = JSONObject()
        userPresets.forEach { (k, v) ->
            users.put(k, JSONObject().put("bands", JSONArray(v.first.map { it.toDouble() })).put("preamp", v.second.toDouble()))
        }
        p.edit().apply {
            putBoolean("enabled", enabled); putBoolean("auto", auto); putFloat("preamp", preampDb)
            for (i in 0 until 10) putFloat("band$i", bands[i])
            putString("userPresets", users.toString())
        }.apply()
    }
}
