package com.timtam.clapfinder

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Finds claps in 16 kHz audio, fed in frames of about 256 samples.
 *
 * A clap is a sudden, spiky, bright sound that dies away within ~130 ms.
 * Talking and music last longer and are less spiky, so they don't count.
 * The alarm only fires when the right number of claps arrive 0.12 to 1 s apart
 * and are followed by half a second without more claps (so typing or a drum
 * beat doesn't set it off).
 */
class ClapDetector(var sensitivity: Int, var clapsNeeded: Int) {
    var background = 0.002f
        private set
    var lastPeak = 0f
        private set
    var clapCount = 0
        private set

    private var prevPeak = 0f
    private var state = 0          // 0 waiting, 1 inside a possible clap, 2 inside a long sound
    private var clapPeak = 0f
    private var frames = 0
    private var refractory = 0
    private val times = ArrayList<Double>()
    private var pending = -1.0

    private fun updateBackground(rms: Float) {
        background += (if (rms > background) 0.02f else 0.05f) * (rms - background)
        if (background < 0.0003f) background = 0.0003f
    }

    /** Returns 0 for nothing, 1 when a clap was heard, 2 when the alarm should go off. */
    fun process(x: FloatArray, n: Int, t: Double): Int {
        var sum = 0.0
        var diffSum = 0.0
        var pk = 0f
        for (i in 0 until n) {
            val v = x[i]
            sum += (v * v).toDouble()
            val a = abs(v)
            if (a > pk) pk = a
            if (i > 0) {
                val d = v - x[i - 1]
                diffSum += (d * d).toDouble()
            }
        }
        val rms = sqrt(sum / n).toFloat()
        val brightness = (diffSum / (sum + 1e-12)).toFloat()
        lastPeak = pk

        val ratio = 14f - 0.1f * sensitivity
        val minPeak = 0.03f - 0.00027f * sensitivity
        var result = 0

        when (state) {
            0 -> {
                if (refractory > 0) {
                    refractory--
                } else if (pk > max(minPeak, background * ratio) && pk > prevPeak * 2.5f && brightness > 0.35f) {
                    state = 1
                    clapPeak = pk
                    frames = 0
                }
                if (state == 0) updateBackground(rms)
            }
            1 -> {
                frames++
                if (pk > clapPeak) clapPeak = pk
                if (pk < max(clapPeak * 0.3f, background * 5f)) {
                    state = 0
                    refractory = 4
                    result = 1
                    times.removeAll { t - it > 1.0 }
                    if (times.isNotEmpty()) {
                        val gap = t - times[times.size - 1]
                        if (gap < 0.12 || gap > 1.0) times.clear()
                    }
                    times.add(t)
                    clapCount = times.size
                    pending = if (times.size >= clapsNeeded && times.size <= clapsNeeded + 1) t + 0.5 else -1.0
                } else if (frames > 8) {
                    state = 2
                    times.clear()
                    clapCount = 0
                    pending = -1.0
                }
            }
            else -> {
                updateBackground(rms)
                if (pk < max(clapPeak * 0.3f, background * 5f)) {
                    state = 0
                    refractory = 4
                }
            }
        }

        if (pending >= 0 && state == 0 && t >= pending) {
            result = 2
            pending = -1.0
            times.clear()
            clapCount = 0
        }
        prevPeak = pk
        return result
    }
}
