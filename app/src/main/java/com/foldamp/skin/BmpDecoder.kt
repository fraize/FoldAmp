package com.foldamp.skin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes skin bitmaps. Android's decoder handles almost everything, but some hand-made skins
 * carry BMPs with sloppy headers that Skia rejects, so we fall back to a small tolerant decoder
 * covering 1/4/8/24/32-bit and RLE4/RLE8.
 */
object BmpDecoder {
    fun decode(bytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inPremultiplied = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { return it }
        return runCatching { decodeManually(bytes) }.getOrNull()
    }

    private fun decodeManually(data: ByteArray): Bitmap? {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (data.size < 54 || data[0] != 'B'.code.toByte() || data[1] != 'M'.code.toByte()) return null
        val pixelOffset = b.getInt(10)
        val headerSize = b.getInt(14)
        val width = b.getInt(18)
        val rawHeight = b.getInt(22)
        val bpp = b.getShort(28).toInt()
        val compression = b.getInt(30)
        var colorsUsed = b.getInt(46)
        val height = kotlin.math.abs(rawHeight)
        val topDown = rawHeight < 0
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return null

        if (colorsUsed == 0 && bpp <= 8) colorsUsed = 1 shl bpp
        val palette = IntArray(colorsUsed) { i ->
            val p = 14 + headerSize + i * 4
            if (p + 3 >= data.size) 0xFF000000.toInt()
            else 0xFF000000.toInt() or ((data[p + 2].toInt() and 0xFF) shl 16) or
                ((data[p + 1].toInt() and 0xFF) shl 8) or (data[p].toInt() and 0xFF)
        }
        val px = IntArray(width * height)
        fun set(x: Int, row: Int, c: Int) {
            if (x !in 0 until width || row !in 0 until height) return
            val y = if (topDown) row else height - 1 - row
            px[y * width + x] = c
        }
        fun pal(i: Int) = palette.getOrElse(i) { 0xFF000000.toInt() }

        when (compression) {
            1, 2 -> { // RLE8 / RLE4
                val rle4 = compression == 2
                var p = pixelOffset; var x = 0; var row = 0
                while (p + 1 < data.size && row < height) {
                    val n = data[p].toInt() and 0xFF
                    val v = data[p + 1].toInt() and 0xFF
                    p += 2
                    if (n > 0) {
                        for (i in 0 until n) set(x++, row, pal(if (rle4) (if (i % 2 == 0) v shr 4 else v and 0xF) else v))
                    } else when (v) {
                        0 -> { x = 0; row++ }
                        1 -> break
                        2 -> { x += data[p].toInt() and 0xFF; row += data[p + 1].toInt() and 0xFF; p += 2 }
                        else -> {
                            for (i in 0 until v) {
                                val idx = if (rle4) {
                                    val byte = data[p + i / 2].toInt() and 0xFF
                                    if (i % 2 == 0) byte shr 4 else byte and 0xF
                                } else data[p + i].toInt() and 0xFF
                                set(x++, row, pal(idx))
                            }
                            val consumed = if (rle4) (v + 1) / 2 else v
                            p += consumed + (consumed and 1)
                        }
                    }
                }
            }
            else -> {
                val stride = ((width * bpp + 31) / 32) * 4
                for (row in 0 until height) {
                    val base = pixelOffset + row * stride
                    for (x in 0 until width) {
                        val c = when (bpp) {
                            1 -> pal((data[base + x / 8].toInt() shr (7 - x % 8)) and 1)
                            4 -> pal((data[base + x / 2].toInt() shr (if (x % 2 == 0) 4 else 0)) and 0xF)
                            8 -> pal(data[base + x].toInt() and 0xFF)
                            24, 32 -> {
                                val o = base + x * (bpp / 8)
                                0xFF000000.toInt() or ((data[o + 2].toInt() and 0xFF) shl 16) or
                                    ((data[o + 1].toInt() and 0xFF) shl 8) or (data[o].toInt() and 0xFF)
                            }
                            16 -> {
                                val v = (data[base + x * 2].toInt() and 0xFF) or ((data[base + x * 2 + 1].toInt() and 0xFF) shl 8)
                                0xFF000000.toInt() or (((v shr 10) and 31) * 255 / 31 shl 16) or
                                    (((v shr 5) and 31) * 255 / 31 shl 8) or ((v and 31) * 255 / 31)
                            }
                            else -> return null
                        }
                        set(x, row, c)
                    }
                }
            }
        }
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }
}
