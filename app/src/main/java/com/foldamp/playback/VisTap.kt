package com.foldamp.playback

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Ring buffer of mono samples written by the audio thread. The audio processor runs ahead of the
 * speaker by roughly the AudioTrack buffer, so readers look [LATENCY_MS] behind the write head.
 */
object VisTap {
    private const val SIZE = 1 shl 16
    private const val LATENCY_MS = 260
    private val ring = FloatArray(SIZE)
    @Volatile private var writePos = 0L
    @Volatile var sampleRate = 44100
        private set

    fun configure(rate: Int) { if (rate > 0) sampleRate = rate }

    fun write(sample: Float) {
        val p = writePos
        ring[(p and (SIZE - 1).toLong()).toInt()] = sample
        writePos = p + 1
    }

    /** Copies the [n] samples that are (approximately) audible right now into [dst]. */
    fun read(dst: FloatArray, n: Int = dst.size) {
        val end = writePos - LATENCY_MS * sampleRate / 1000
        val start = end - n
        for (i in 0 until n) {
            val p = start + i
            dst[i] = if (p < 0) 0f else ring[(p and (SIZE - 1).toLong()).toInt()]
        }
    }
}

/** Turns the tapped samples into smoothed, log-spaced bar heights and peak markers. */
class SpectrumAnalyzer(private val fftSize: Int = 2048) {
    private val samples = FloatArray(fftSize)
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val window = FloatArray(fftSize) { (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / (fftSize - 1))).toFloat() }
    private val mags = FloatArray(fftSize / 2)
    val wave = FloatArray(576)

    var bars = FloatArray(0); private set
    var peaks = FloatArray(0); private set
    private var peakHold = IntArray(0)
    private var peakVel = FloatArray(0)

    /** Recomputes [bars]/[peaks]. Call once per frame. [active] false makes everything decay. */
    fun update(barCount: Int, active: Boolean) {
        if (bars.size != barCount) {
            bars = FloatArray(barCount); peaks = FloatArray(barCount)
            peakHold = IntArray(barCount); peakVel = FloatArray(barCount)
        }
        val target = FloatArray(barCount)
        if (active) {
            VisTap.read(samples)
            for (i in wave.indices) wave[i] = samples[fftSize - wave.size + i]
            for (i in 0 until fftSize) { re[i] = samples[i] * window[i]; im[i] = 0f }
            fft(re, im)
            for (i in mags.indices) mags[i] = sqrt(re[i] * re[i] + im[i] * im[i])
            val rate = VisTap.sampleRate.toFloat()
            val fLo = 40f
            val fHi = min(18000f, rate / 2f)
            for (b in 0 until barCount) {
                val f0 = fLo * (fHi / fLo).pow(b.toFloat() / barCount)
                val f1 = fLo * (fHi / fLo).pow((b + 1).toFloat() / barCount)
                val i0 = max(1, (f0 / rate * fftSize).toInt())
                val i1 = max(i0 + 1, (f1 / rate * fftSize).toInt())
                var m = 0f
                for (i in i0 until min(i1, mags.size)) m = max(m, mags[i])
                // Gentle tilt so treble isn't dwarfed by bass, like Winamp's analyzer.
                val tilt = 1f + 0.6f * ln(1f + b.toFloat() / barCount * 6f)
                val db = 20f * log10(m * tilt / (fftSize / 8f) + 1e-9f)
                target[b] = ((db + 60f) / 60f).coerceIn(0f, 1f)
            }
        } else {
            wave.fill(0f)
        }
        for (b in 0 until barCount) {
            bars[b] = if (target[b] > bars[b]) target[b] else max(target[b], bars[b] - 0.035f)
            if (bars[b] >= peaks[b]) {
                peaks[b] = bars[b]; peakHold[b] = 18; peakVel[b] = 0f
            } else if (peakHold[b] > 0) {
                peakHold[b]--
            } else {
                peakVel[b] += 0.0012f
                peaks[b] = max(bars[b], peaks[b] - peakVel[b])
            }
        }
    }

    private fun fft(x: FloatArray, y: FloatArray) {
        val n = x.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = x[i]; x[i] = x[j]; x[j] = t
                t = y[i]; y[i] = y[j]; y[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = kotlin.math.cos(ang).toFloat()
            val wi = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = x[b] * cr - y[b] * ci
                    val ti = x[b] * ci + y[b] * cr
                    x[b] = x[a] - tr; y[b] = y[a] - ti
                    x[a] += tr; y[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
