package com.alonmeller.mobiledaw.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The built-in drum kit: 16 sounds synthesized on the phone, so a new drum
 * track plays right away, like Koala's starter kit. Pad i of a new drum
 * instrument gets sound i.
 */
object DrumKit {
    class Sound(val name: String, val file: String, val chokeGroup: Int = 0)

    val sounds = listOf(
        Sound("Kick", "kit_kick.wav"),
        Sound("Snare", "kit_snare.wav"),
        Sound("Clap", "kit_clap.wav"),
        // The hi-hats share a choke group, so closing the hat cuts the open one off.
        Sound("Closed Hat", "kit_hat_closed.wav", chokeGroup = 1),
        Sound("Open Hat", "kit_hat_open.wav", chokeGroup = 1),
        Sound("Low Tom", "kit_tom_low.wav"),
        Sound("Mid Tom", "kit_tom_mid.wav"),
        Sound("High Tom", "kit_tom_high.wav"),
        Sound("Rim", "kit_rim.wav"),
        Sound("Cowbell", "kit_cowbell.wav"),
        Sound("Crash", "kit_crash.wav"),
        Sound("Shaker", "kit_shaker.wav"),
        Sound("808 Bass", "kit_808.wav"),
        Sound("Snap", "kit_snap.wav"),
        Sound("Zap", "kit_zap.wav"),
        Sound("Conga", "kit_conga.wav"),
    )

    /** Renders sound [index] as mono samples at [sampleRate]. */
    fun render(index: Int, sampleRate: Int): FloatArray {
        val sr = sampleRate.toDouble()
        val noise = Noise(0x5EED0000L + index)
        return when (index) {
            0 -> { // Kick: a sine that drops from 160 Hz to 48 Hz, with a click on top.
                val click = Biquad.lowPass(4000.0, sr)
                val osc = Osc(sr)
                synth(0.6, sr, peak = 0.98) { t ->
                    val body = osc.sine(48 + 112 * exp(-t / 0.035)) * decay(t, 0.55)
                    tanh(1.6 * (body + click.process(noise.next()) * exp(-t / 0.002) * 0.5))
                }
            }
            1 -> { // Snare: two tuned partials and bright noise.
                val hp = Biquad.highPass(1500.0, sr)
                val a = Osc(sr)
                val b = Osc(sr)
                synth(0.45, sr, peak = 0.9) { t ->
                    val drop = 1 + 0.3 * exp(-t / 0.01)
                    val tone = (a.sine(185 * drop) * 0.6 + b.sine(330 * drop) * 0.4) * decay(t, 0.12)
                    tone * 0.6 + hp.process(noise.next()) * decay(t, 0.25) * 0.8
                }
            }
            2 -> { // Clap: three quick bursts of band-passed noise and a short tail.
                val bp = Biquad.bandPass(1100.0, 1.2, sr)
                synth(0.5, sr, peak = 0.85) { t ->
                    var env = 0.0
                    for (k in 0..2) {
                        val start = k * 0.011
                        if (t >= start) env = maxOf(env, exp(-(t - start) / 0.004))
                    }
                    if (t >= 0.022) env = maxOf(env, 0.8 * exp(-(t - 0.022) / 0.06))
                    bp.process(noise.next()) * env
                }
            }
            3 -> hat(0.18, 0.12, sr, noise, peak = 0.55)
            4 -> hat(0.8, 0.6, sr, noise, peak = 0.5)
            5 -> tom(90.0, 0.45, sr, noise)
            6 -> tom(130.0, 0.4, sr, noise)
            7 -> tom(180.0, 0.35, sr, noise)
            8 -> { // Rim: a short wooden knock.
                val bp = Biquad.bandPass(3000.0, 1.5, sr)
                val a = Osc(sr)
                val b = Osc(sr)
                synth(0.15, sr, peak = 0.75) { t ->
                    (a.sine(1700.0) * 0.5 + b.sine(520.0) * 0.5) * decay(t, 0.03) +
                        bp.process(noise.next()) * exp(-t / 0.003)
                }
            }
            9 -> { // Cowbell: two square waves through a band-pass, as on the 808.
                val bp = Biquad.bandPass(2640.0, 1.0, sr)
                val a = Osc(sr)
                val b = Osc(sr)
                synth(0.5, sr, peak = 0.6) { t ->
                    val env = 0.6 * exp(-t / 0.015) + 0.4 * decay(t, 0.4)
                    bp.process(a.square(587.0) + b.square(845.0)) * env
                }
            }
            10 -> { // Crash: metallic partials and noise with a long ring.
                val metal = Metal(sr, 1.5)
                val hp = Biquad.highPass(5000.0, sr)
                synth(2.0, sr, peak = 0.6) { t ->
                    hp.process(metal.next() * 0.5 + noise.next()) * decay(t, 1.8)
                }
            }
            11 -> { // Shaker: noise with a soft attack.
                val hp = Biquad.highPass(6000.0, sr)
                synth(0.25, sr, peak = 0.5) { t ->
                    val env = if (t < 0.015) t / 0.015 else exp(-(t - 0.015) / 0.04)
                    hp.process(noise.next()) * env
                }
            }
            12 -> { // 808 bass: a long, slightly saturated sub boom.
                val osc = Osc(sr)
                synth(1.6, sr, peak = 0.95) { t ->
                    tanh(2.0 * osc.sine(52 + 18 * exp(-t / 0.06)) * decay(t, 1.5))
                }
            }
            13 -> { // Finger snap.
                val bp = Biquad.bandPass(2300.0, 2.0, sr)
                val osc = Osc(sr)
                synth(0.25, sr, peak = 0.75) { t ->
                    bp.process(noise.next()) * exp(-t / 0.012) + osc.sine(1500.0) * exp(-t / 0.01) * 0.3
                }
            }
            14 -> { // Zap: a fast downward sweep.
                val osc = Osc(sr)
                synth(0.4, sr, peak = 0.6) { t -> osc.sine(100 + 2400 * exp(-t / 0.03)) * decay(t, 0.3) }
            }
            else -> { // Conga: a short tuned skin.
                val a = Osc(sr)
                val b = Osc(sr)
                synth(0.45, sr, peak = 0.8) { t ->
                    val f = 330 * (1 + 0.12 * exp(-t / 0.01))
                    (a.sine(f) + 0.1 * b.sine(2 * f)) * decay(t, 0.3)
                }
            }
        }
    }

    private fun hat(seconds: Double, ring: Double, sr: Double, noise: Noise, peak: Double): FloatArray {
        val metal = Metal(sr, 1.0)
        val bp = Biquad.bandPass(10000.0, 1.0, sr)
        val hp = Biquad.highPass(7000.0, sr)
        return synth(seconds, sr, peak) { t ->
            hp.process(bp.process(metal.next()) + noise.next() * 0.5) * decay(t, ring)
        }
    }

    private fun tom(freq: Double, ring: Double, sr: Double, noise: Noise): FloatArray {
        val lp = Biquad.lowPass(3000.0, sr)
        val osc = Osc(sr)
        return synth(0.7, sr, peak = 0.85) { t ->
            val body = osc.sine(freq * (1 + 0.5 * exp(-t / 0.05))) * decay(t, ring)
            tanh(1.2 * (body + lp.process(noise.next()) * exp(-t / 0.01) * 0.2))
        }
    }

    /** Runs [voice] for every sample, then scales the result to [peak]. */
    private inline fun synth(seconds: Double, sr: Double, peak: Double, voice: (Double) -> Double): FloatArray {
        val out = FloatArray((seconds * sr).toInt())
        var max = 0.0
        for (i in out.indices) {
            val v = voice(i / sr)
            out[i] = v.toFloat()
            max = maxOf(max, abs(v))
        }
        if (max > 0) {
            val scale = (peak / max).toFloat()
            for (i in out.indices) out[i] *= scale
        }
        // A short fade at the end, so a sound cut off early never clicks.
        val fade = minOf(out.size, (0.005 * sr).toInt())
        for (k in 0 until fade) out[out.size - 1 - k] *= k.toFloat() / fade
        return out
    }

    /** Exponential decay that reaches -60 dB after [seconds]. */
    private fun decay(t: Double, seconds: Double) = exp(-6.9 * t / seconds)

    /** Deterministic white noise in -1..1, so the kit sounds the same on every phone. */
    private class Noise(seed: Long) {
        private var state = seed
        fun next(): Double {
            state = state * 6364136223846793005L + 1442695040888963407L
            return (state ushr 33).toDouble() / 1073741824.0 - 1.0
        }
    }

    /** An oscillator whose frequency may change every sample. */
    private class Osc(private val sr: Double) {
        private var phase = 0.0
        private fun advance(freq: Double): Double {
            val p = phase
            phase += freq / sr
            phase -= kotlin.math.floor(phase)
            return p
        }
        fun sine(freq: Double) = sin(2 * PI * advance(freq))
        fun square(freq: Double) = if (advance(freq) < 0.5) 1.0 else -1.0
    }

    /** Six detuned square waves, the 808's recipe for cymbals. */
    private class Metal(sr: Double, scale: Double) {
        private val oscs = List(6) { Osc(sr) }
        private val freqs = listOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0).map { it * scale * 2 }
        fun next(): Double {
            var sum = 0.0
            for (i in oscs.indices) sum += oscs[i].square(freqs[i])
            return sum / oscs.size
        }
    }

    /** RBJ cookbook biquads. */
    private class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        companion object {
            private fun make(freq: Double, q: Double, sr: Double, kind: Int): Biquad {
                val w = 2 * PI * minOf(freq, sr * 0.45) / sr
                val alpha = sin(w) / (2 * q)
                val c = cos(w)
                val a0 = 1 + alpha
                return when (kind) {
                    0 -> Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
                    1 -> Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
                    else -> Biquad(alpha / a0, 0.0, -alpha / a0, -2 * c / a0, (1 - alpha) / a0)
                }
            }

            fun lowPass(freq: Double, sr: Double) = make(freq, 0.707, sr, 0)
            fun highPass(freq: Double, sr: Double) = make(freq, 0.707, sr, 1)
            fun bandPass(freq: Double, q: Double, sr: Double) = make(freq, q, sr, 2)
        }
    }
}
