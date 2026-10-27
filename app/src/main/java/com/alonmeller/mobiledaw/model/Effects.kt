package com.alonmeller.mobiledaw.model

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/** One effect parameter. [log] sliders move exponentially, [toggle] ones are on/off. */
data class ParamSpec(
    val name: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = "",
    val log: Boolean = false,
    val toggle: Boolean = false,
) {
    /** Slider position 0..1 for [value]. */
    fun toPosition(value: Float): Float {
        val v = value.coerceIn(min, max)
        return if (log && min > 0f) (ln(v / min) / ln(max / min)) else (v - min) / (max - min)
    }

    fun fromPosition(position: Float): Float {
        val p = position.coerceIn(0f, 1f)
        return if (log && min > 0f) min * exp(p * ln(max / min)) else min + p * (max - min)
    }

    fun format(value: Float): String = when {
        toggle -> if (value > 0.5f) "On" else "Off"
        unit == "%" -> "${(value * 100).toInt()}%"
        unit == "Hz" && value >= 1000f -> "%.1f kHz".format(value / 1000f)
        unit == "Hz" -> "${value.toInt()} Hz"
        unit == ":1" -> "%.1f:1".format(value)
        unit == "ms" && value < 10f -> "%.1f ms".format(value)
        unit == "ms" -> "${value.toInt()} ms"
        unit == "dB" -> "%+.1f dB".format(value)
        else -> "%.2f".format(value)
    }
}

/**
 * Built-in effects, the everyday set found in REAPER's ReaPlugs.
 * [code] and the parameter order must match the C++ engine (Effects.cpp).
 */
enum class EffectType(val code: Int, val title: String, val params: List<ParamSpec>) {
    EQ(
        0, "EQ", listOf(
            ParamSpec("Low gain", -18f, 18f, 0f, "dB"),
            ParamSpec("Low freq", 30f, 500f, 100f, "Hz", log = true),
            ParamSpec("Mid gain", -18f, 18f, 0f, "dB"),
            ParamSpec("Mid freq", 150f, 8000f, 1000f, "Hz", log = true),
            ParamSpec("Mid Q", 0.3f, 8f, 1f, log = true),
            ParamSpec("High gain", -18f, 18f, 0f, "dB"),
            ParamSpec("High freq", 1500f, 16000f, 8000f, "Hz", log = true),
            ParamSpec("Low cut", 20f, 1000f, 20f, "Hz", log = true),
            ParamSpec("High cut", 1000f, 20000f, 20000f, "Hz", log = true),
        )
    ),
    COMPRESSOR(
        1, "Compressor", listOf(
            ParamSpec("Threshold", -60f, 0f, -18f, "dB"),
            ParamSpec("Ratio", 1f, 20f, 4f, ":1", log = true),
            ParamSpec("Attack", 0.1f, 100f, 10f, "ms", log = true),
            ParamSpec("Release", 10f, 1000f, 120f, "ms", log = true),
            ParamSpec("Knee", 0f, 12f, 6f, "dB"),
            ParamSpec("Makeup", 0f, 24f, 0f, "dB"),
        )
    ),
    REVERB(
        2, "Reverb", listOf(
            ParamSpec("Room size", 0f, 1f, 0.6f, "%"),
            ParamSpec("Damping", 0f, 1f, 0.5f, "%"),
            ParamSpec("Width", 0f, 1f, 1f, "%"),
            ParamSpec("Wet", 0f, 1f, 0.25f, "%"),
            ParamSpec("Dry", 0f, 1f, 1f, "%"),
        )
    ),
    DELAY(
        3, "Delay", listOf(
            ParamSpec("Time", 1f, 2000f, 375f, "ms", log = true),
            ParamSpec("Feedback", 0f, 0.95f, 0.35f, "%"),
            ParamSpec("Mix", 0f, 1f, 0.3f, "%"),
            ParamSpec("High cut", 500f, 20000f, 8000f, "Hz", log = true),
            ParamSpec("Ping-pong", 0f, 1f, 0f, toggle = true),
        )
    ),
    CHORUS(
        4, "Chorus", listOf(
            ParamSpec("Rate", 0.05f, 5f, 0.8f, "Hz", log = true),
            ParamSpec("Depth", 0f, 10f, 3f, "ms"),
            ParamSpec("Delay", 5f, 30f, 12f, "ms"),
            ParamSpec("Mix", 0f, 1f, 0.5f, "%"),
        )
    ),
    DISTORTION(
        5, "Distortion", listOf(
            ParamSpec("Drive", 0f, 40f, 12f, "dB"),
            ParamSpec("Tone", 500f, 12000f, 5000f, "Hz", log = true),
            ParamSpec("Mix", 0f, 1f, 1f, "%"),
            ParamSpec("Output", -24f, 6f, -6f, "dB"),
        )
    ),
    GATE(
        6, "Gate", listOf(
            ParamSpec("Threshold", -80f, 0f, -40f, "dB"),
            ParamSpec("Attack", 0.1f, 50f, 1f, "ms", log = true),
            ParamSpec("Hold", 0f, 500f, 50f, "ms"),
            ParamSpec("Release", 5f, 1000f, 100f, "ms", log = true),
            ParamSpec("Range", -80f, 0f, -80f, "dB"),
        )
    ),
    LIMITER(
        7, "Limiter", listOf(
            ParamSpec("Gain", 0f, 24f, 0f, "dB"),
            ParamSpec("Ceiling", -12f, 0f, -0.3f, "dB"),
            ParamSpec("Release", 1f, 500f, 50f, "ms", log = true),
        )
    );

    fun defaults(): List<Float> = params.map { it.default }

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
    }
}

data class Effect(
    val id: Long,
    val type: EffectType,
    val values: List<Float> = type.defaults(),
    val bypass: Boolean = false,
)

data class EnvPoint(val frame: Long, val value: Float)

enum class EnvKind { Volume, Pan }

/** Vertical scales for drawing envelopes and faders, as positions 0 (bottom) .. 1 (top). */
object Scales {
    const val MAX_DB = 6f
    const val MIN_DB = -60f

    fun gainToPosition(gain: Float): Float {
        if (gain <= 0f) return 0f
        val db = 20f * log10(gain)
        return ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
    }

    fun positionToGain(position: Float): Float {
        if (position <= 0.005f) return 0f
        val db = MIN_DB + position.coerceIn(0f, 1f) * (MAX_DB - MIN_DB)
        return 10f.pow(db / 20f)
    }

    /** Pan: top is left (-1), bottom is right (+1), as in REAPER's pan envelope. */
    fun panToPosition(pan: Float): Float = (1f - (pan.coerceIn(-1f, 1f) + 1f) / 2f)
    fun positionToPan(position: Float): Float = (1f - position.coerceIn(0f, 1f)) * 2f - 1f

    fun gainLabel(gain: Float): String =
        if (gain <= 0f) "-inf" else "%+.1f dB".format(20f * log10(gain))

    fun panLabel(pan: Float): String = when {
        kotlin.math.abs(pan) < 0.01f -> "C"
        pan < 0 -> "${(-pan * 100).toInt()}L"
        else -> "${(pan * 100).toInt()}R"
    }
}
