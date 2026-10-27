package com.alonmeller.mobiledaw.audio

/** Thin JNI wrapper around the C++ Oboe audio engine. */
object NativeEngine {
    init {
        System.loadLibrary("mobiledaw")
    }

    external fun start(): Boolean
    external fun shutdown()
    external fun sampleRate(): Int

    external fun setPlaying(playing: Boolean)
    external fun isPlaying(): Boolean
    external fun seek(frame: Long)
    external fun position(): Long
    external fun setLoop(start: Long, end: Long, enabled: Boolean)

    /** With [withTransport] false only the input is captured (sampling into a pad). */
    external fun startRecording(withTransport: Boolean): Boolean
    /** Returns the id of a new source holding the take, or -1. */
    external fun stopRecording(): Int
    external fun isRecording(): Boolean
    external fun recordedFrames(): Long
    external fun recordStartFrame(): Long
    /** Timeline position of the last take after latency compensation. */
    external fun lastTakeStart(): Long
    external fun inputLevel(): Float
    /** Frames between the render position and what the speaker plays now. */
    external fun outputLatency(): Long
    external fun masterPeak(): Float

    external fun createSource(samples: FloatArray): Int
    external fun sourceLength(id: Int): Long
    external fun sourcePeaks(id: Int, framesPerBucket: Int): FloatArray
    external fun sourceSamples(id: Int): FloatArray

    /** See jni_bridge.cpp for the layout of these flattened arrays. */
    external fun setArrangement(
        trackParams: FloatArray,
        itemCounts: IntArray,
        itemParams: LongArray,
        itemGains: FloatArray,
        fxCounts: IntArray,
        fxIds: LongArray,
        envCounts: IntArray,
        envFrames: LongArray,
        envValues: FloatArray,
        instIds: LongArray,
        noteCounts: IntArray,
        noteTimes: LongArray,
        noteValues: FloatArray,
        parents: IntArray,
        sendCounts: IntArray,
        sendTargets: IntArray,
        sendParams: FloatArray,
        masterGain: Float,
    )
    external fun trackPeak(index: Int): Float

    external fun createEffect(id: Long, type: Int, params: FloatArray): Boolean
    external fun setEffectParam(id: Long, index: Int, value: Float)
    external fun setEffectBypass(id: Long, bypass: Boolean)

    external fun createInstrument(id: Long, type: Int, params: FloatArray): Boolean
    external fun setInstrumentParam(id: Long, index: Int, value: Float)
    /** 16 pads: [sources], [gain, pitch, reverse, oneShot, chokeGroup] and [start, end] per pad. */
    external fun setDrumPads(id: Long, sources: IntArray, params: FloatArray, ranges: LongArray)
    /** Plays (velocity > 0) or releases (velocity 0) a note right now. */
    external fun liveNote(id: Long, pitch: Int, velocity: Float)

    external fun setMetronome(enabled: Boolean)
    external fun setTempo(bpm: Double)

    /**
     * Renders what the engine plays now, faster than real time, into 32-bit
     * float WAV files (plain ASCII paths; empty ones are skipped): the stereo
     * mix, a stereo stem per track and the mono pre-effect output per track.
     * With [ignoreSolo] it renders as if no track were soloed, so every stem is whole.
     * Blocks until done and returns [ok, cancelled, frames, peak, loudness in LUFS].
     */
    external fun render(
        start: Long,
        end: Long,
        maxTail: Long,
        mixPath: String,
        stemPaths: Array<String>,
        dryPaths: Array<String>,
        ignoreSolo: Boolean,
    ): DoubleArray
    external fun renderProgress(): Float
    external fun cancelRender()

    /** Forgets every source, effect and instrument, before another project opens. */
    external fun clearSession()
}
