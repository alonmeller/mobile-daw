package com.alonmeller.mobiledaw.model

/**
 * A MIDI note inside a MIDI item, timed in frames from the start of the item's
 * source. [velocity] runs from 0 to 1.
 */
data class Note(val start: Long, val length: Long, val pitch: Int, val velocity: Float = 0.8f) {
    val end: Long get() = start + length
}

const val PAD_COUNT = 16

/** Pad i plays MIDI note FIRST_PAD_NOTE + i, as in General MIDI drum maps (36 is the kick). */
const val FIRST_PAD_NOTE = 36

/**
 * Built-in instruments, like REAPER's ReaSynth and ReaSamplOmatic5000.
 * [code] and the parameter order must match the C++ engine (Instruments.cpp).
 */
enum class InstrumentType(val code: Int, val title: String, val params: List<ParamSpec>) {
    SYNTH(
        0, "Synth", listOf(
            ParamSpec("Wave", 0f, 3f, 2f, choices = listOf("Sine", "Triangle", "Saw", "Square")),
            ParamSpec("Attack", 0.5f, 2000f, 5f, "ms", log = true),
            ParamSpec("Decay", 5f, 3000f, 300f, "ms", log = true),
            ParamSpec("Sustain", 0f, 1f, 0.7f, "%"),
            ParamSpec("Release", 5f, 4000f, 300f, "ms", log = true),
            ParamSpec("Cutoff", 60f, 18000f, 4000f, "Hz", log = true),
            ParamSpec("Resonance", 0f, 1f, 0.2f, "%"),
            ParamSpec("Filter env", 0f, 1f, 0.3f, "%"),
            ParamSpec("Detune", 0f, 50f, 8f, "ct"),
            ParamSpec("Volume", -24f, 6f, -6f, "dB"),
        )
    ),
    DRUMS(
        1, "Drum Pads", listOf(
            ParamSpec("Volume", -24f, 6f, 0f, "dB"),
        )
    );

    fun defaults(): List<Float> = params.map { it.default }

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/**
 * One pad of a drum instrument: a sample (an engine source, -1 when empty)
 * with its level, tuning in semitones and playback region ([end] 0 plays to
 * the end). Pads sharing a [chokeGroup] above 0 cut each other off.
 */
data class Pad(
    val sourceId: Int = -1,
    val name: String = "",
    val gain: Float = 1f,
    val pitch: Float = 0f,
    val start: Long = 0,
    val end: Long = 0,
    val reverse: Boolean = false,
    val oneShot: Boolean = true,
    val chokeGroup: Int = 0,
)

data class Instrument(
    val id: Long,
    val type: InstrumentType,
    val values: List<Float> = type.defaults(),
    val pads: List<Pad> = if (type == InstrumentType.DRUMS) List(PAD_COUNT) { Pad() } else emptyList(),
)
