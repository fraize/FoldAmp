package com.foldamp.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The DSP stage inserted into ExoPlayer's audio sink: 10 peaking biquads + preamp (when the EQ is
 * on), stereo balance, and a tap that feeds the spectrum analyzer.
 */
class EqAudioProcessor : BaseAudioProcessor() {

    private var channels = 0
    private var sampleRate = 0
    private var isFloat = false
    private var seenVersion = -1

    // Per band: b0, b1, b2, a1, a2 (normalized). Per band per channel: z1, z2.
    private val coef = Array(10) { DoubleArray(5) }
    private val activeBand = BooleanArray(10)
    private var state = Array(10) { DoubleArray(0) }
    private var preampGain = 1.0
    private var eqOn = false

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        state = Array(10) { DoubleArray(channels * 2) }
        seenVersion = -1
        VisTap.configure(sampleRate)
    }

    override fun onReset() {
        channels = 0
        sampleRate = 0
    }

    private fun rebuildFilters() {
        seenVersion = Equalizer.version
        eqOn = Equalizer.enabled
        preampGain = 10.0.pow(Equalizer.preampDb / 20.0)
        val q = 1.41
        for (b in 0 until 10) {
            val f = Equalizer.FREQUENCIES[b].toDouble()
            val g = Equalizer.band(b).toDouble()
            activeBand[b] = eqOn && kotlin.math.abs(g) > 0.05 && f < sampleRate * 0.45
            if (!activeBand[b]) continue
            val a = 10.0.pow(g / 40.0)
            val w0 = 2.0 * PI * f / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val cw = cos(w0)
            val a0 = 1.0 + alpha / a
            coef[b][0] = (1.0 + alpha * a) / a0
            coef[b][1] = (-2.0 * cw) / a0
            coef[b][2] = (1.0 - alpha * a) / a0
            coef[b][3] = (-2.0 * cw) / a0
            coef[b][4] = (1.0 - alpha / a) / a0
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        if (channels == 0) onFlush()
        if (seenVersion != Equalizer.version) rebuildFilters()

        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(size)
        val bytesPerSample = if (isFloat) 4 else 2
        val frames = size / (bytesPerSample * channels)
        val balance = PlayerHub.balance.toDouble()
        val leftGain = if (balance > 0) 1.0 - balance else 1.0
        val rightGain = if (balance < 0) 1.0 + balance else 1.0
        val pre = if (eqOn) preampGain else 1.0
        var pos = input.position()

        for (frame in 0 until frames) {
            var mono = 0.0
            for (ch in 0 until channels) {
                var x = if (isFloat) input.getFloat(pos).toDouble() else input.getShort(pos) / 32768.0
                if (eqOn) {
                    x *= pre
                    for (b in 0 until 10) {
                        if (!activeBand[b]) continue
                        val c = coef[b]
                        val z = state[b]
                        val zi = ch * 2
                        // Transposed direct form II.
                        val y = c[0] * x + z[zi]
                        z[zi] = c[1] * x - c[3] * y + z[zi + 1]
                        z[zi + 1] = c[2] * x - c[4] * y
                        x = y
                    }
                }
                if (channels >= 2) {
                    if (ch == 0) x *= leftGain else if (ch == 1) x *= rightGain
                }
                if (x > 1.0) x = 1.0 else if (x < -1.0) x = -1.0
                mono += x
                if (isFloat) out.putFloat(x.toFloat()) else out.putShort((x * 32767.0).toInt().toShort())
                pos += bytesPerSample
            }
            VisTap.write((mono / channels).toFloat())
        }
        input.position(input.limit())
        out.flip()
    }
}
