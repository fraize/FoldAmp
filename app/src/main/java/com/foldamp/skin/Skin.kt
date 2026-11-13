package com.foldamp.skin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

data class PlaylistColors(
    val normal: Int = Color.GREEN,
    val current: Int = Color.WHITE,
    val normalBg: Int = Color.BLACK,
    val selectedBg: Int = 0xFF0000C6.toInt(),
    val font: String = "Arial",
)

/**
 * A parsed classic (2.x) skin. Any bitmap the skin doesn't ship falls back to the base skin, the
 * same way Winamp itself behaves.
 */
class Skin(
    val name: String,
    private val images: Map<String, Bitmap>,
    val playlistColors: PlaylistColors,
    val visColors: IntArray,
    private val fallback: Skin?,
) {
    fun img(key: String): Bitmap? = images[key] ?: fallback?.img(key)

    /** True when the archive actually contained a main window bitmap. */
    val hasMain: Boolean get() = images.containsKey("main")

    val main get() = img("main")
    val titlebar get() = img("titlebar")
    val cbuttons get() = img("cbuttons")
    val shufrep get() = img("shufrep")
    val text get() = img("text")
    val volume get() = img("volume")
    val balance: Bitmap? get() = images["balance"] ?: images["volume"] ?: fallback?.balance
    val posbar get() = img("posbar")
    val playpaus get() = img("playpaus")
    val monoster get() = img("monoster")
    val eqmain get() = img("eqmain")
    val pledit get() = img("pledit")
    val gen get() = img("gen")

    /** nums_ex.bmp (with real minus glyphs) wins over numbers.bmp when the skin provides it. */
    val numbersEx: Boolean get() = images.containsKey("nums_ex") || (!images.containsKey("numbers") && fallback?.numbersEx == true)
    val numbers: Bitmap? get() = if (images.containsKey("nums_ex")) images["nums_ex"] else images["numbers"] ?: fallback?.numbers

    /** Letter sprites for the gen.bmp title font, found by scanning for background-colored gaps. */
    val genLetters: Map<Char, Rect> by lazy { scanGenFont() }

    private fun scanGenFont(): Map<Char, Rect> {
        val g = gen ?: return emptyMap()
        if (g.height < 96 + 7) return emptyMap()
        val out = HashMap<Char, Rect>()
        val y = 88
        val bg = g.getPixel(0, y)
        var x = 1
        for (c in 'A'..'Z') {
            var next = x
            while (next < g.width && g.getPixel(next, y) != bg) next++
            if (next <= x) break
            out[c] = Rect(x, y, next, y + 7)
            x = next + 1
        }
        return out
    }

    companion object {
        val DEFAULT_VIS_COLORS = longArrayOf(
            0xFF000000, 0xFF182129, 0xFFEF3110, 0xFFCE2910, 0xFFD65A00, 0xFFD66600, 0xFFD67300, 0xFFC67B08,
            0xFFDEA518, 0xFFD6B521, 0xFFBDDE29, 0xFF94DE21, 0xFF29CE10, 0xFF32BE10, 0xFF39B510, 0xFF319C08,
            0xFF299400, 0xFF188408, 0xFFFFFFFF, 0xFFD6D6DE, 0xFFB5BDBD, 0xFFA0AAAF, 0xFF949CA5, 0xFF969696,
        ).map { it.toInt() }.toIntArray()

        private val BITMAPS = setOf(
            "main", "titlebar", "cbuttons", "shufrep", "text", "volume", "balance", "posbar", "playpaus",
            "monoster", "eqmain", "pledit", "gen", "numbers", "nums_ex",
        )

        fun load(name: String, input: InputStream, fallback: Skin?): Skin {
            val images = HashMap<String, Bitmap>()
            val texts = HashMap<String, String>()
            val depth = HashMap<String, Int>()
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val path = e.name.replace('\\', '/')
                    val file = path.substringAfterLast('/').lowercase()
                    val base = file.substringBeforeLast('.')
                    val ext = file.substringAfterLast('.', "")
                    val d = path.count { it == '/' }
                    // Prefer the shallowest copy when a skin nests its files in a folder.
                    if (depth[file]?.let { it <= d } == true) continue
                    if (ext == "bmp" && base in BITMAPS) {
                        BmpDecoder.decode(zip.readBytes())?.let { images[base] = it; depth[file] = d }
                    } else if (ext == "txt" && (base == "pledit" || base == "viscolor")) {
                        texts[base] = zip.readBytes().toString(Charsets.ISO_8859_1); depth[file] = d
                    }
                }
            }
            return Skin(
                name = name,
                images = images,
                playlistColors = texts["pledit"]?.let(::parsePledit) ?: fallback?.playlistColors ?: PlaylistColors(),
                visColors = texts["viscolor"]?.let(::parseVisColors) ?: fallback?.visColors ?: DEFAULT_VIS_COLORS,
                fallback = fallback,
            )
        }

        private fun parsePledit(txt: String): PlaylistColors {
            val kv = txt.lines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
            }.toMap()
            fun color(k: String, def: Int): Int = kv[k]?.let {
                runCatching { Color.parseColor(if (it.startsWith("#")) it.take(7) else "#${it.take(6)}") }.getOrNull()
            } ?: def
            val d = PlaylistColors()
            return PlaylistColors(
                normal = color("normal", d.normal),
                current = color("current", d.current),
                normalBg = color("normalbg", d.normalBg),
                selectedBg = color("selectedbg", d.selectedBg),
                font = kv["font"] ?: d.font,
            )
        }

        private fun parseVisColors(txt: String): IntArray {
            val colors = DEFAULT_VIS_COLORS.copyOf()
            var i = 0
            for (line in txt.lines()) {
                if (i >= 24) break
                val nums = Regex("\\d+").findAll(line.substringBefore("//")).map { it.value.toInt().coerceIn(0, 255) }.take(3).toList()
                if (nums.size == 3) colors[i++] = Color.rgb(nums[0], nums[1], nums[2])
            }
            return colors
        }
    }
}

/** Finds, loads and switches skins: the three bundled .wsz files plus any the user imports. */
object SkinManager {
    const val BASE = "base-2.91"
    private lateinit var app: Context
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    lateinit var base: Skin
        private set
    lateinit var current: Skin
        private set

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun importedDir() = File(app.filesDir, "skins").apply { mkdirs() }

    fun init(context: Context) {
        app = context.applicationContext
        base = Skin.load(BASE, app.assets.open("skins/$BASE.wsz"), null)
        val chosen = prefs().getString("skin", "Winamp5_Classified_v5.5") ?: BASE
        current = runCatching { open(chosen) }.getOrDefault(base)
    }

    private fun prefs() = app.getSharedPreferences("skin", Context.MODE_PRIVATE)

    fun available(): List<String> {
        val bundled = app.assets.list("skins").orEmpty().filter { it.endsWith(".wsz", true) }.map { it.dropLast(4) }
        val imported = importedDir().listFiles().orEmpty().filter { it.name.endsWith(".wsz", true) }.map { it.name.dropLast(4) }
        return (bundled + imported).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    private fun open(name: String): Skin {
        if (name == BASE) return base
        val f = File(importedDir(), "$name.wsz")
        val stream = if (f.exists()) f.inputStream() else app.assets.open("skins/$name.wsz")
        return Skin.load(name, stream, base)
    }

    fun select(name: String) {
        io.execute {
            val skin = runCatching { open(name) }.getOrNull()
            main.post {
                if (skin != null) {
                    current = skin
                    prefs().edit().putString("skin", name).apply()
                    listeners.toList().forEach { it() }
                }
            }
        }
    }

    /** Copies a picked .wsz into app storage, then switches to it. Returns false if it isn't a skin. */
    fun import(name: String, input: InputStream, done: (Boolean) -> Unit) {
        io.execute {
            val clean = name.removeSuffix(".wsz").removeSuffix(".zip").replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val dest = File(importedDir(), "$clean.wsz")
            val ok = runCatching {
                input.use { i -> dest.outputStream().use { i.copyTo(it) } }
                Skin.load(clean, dest.inputStream(), base).hasMain
            }.getOrDefault(false)
            main.post {
                if (ok) select(clean) else dest.delete()
                done(ok)
            }
        }
    }
}
