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

    external fun startRecording(): Boolean
    /** Returns the id of a new source holding the take, or -1. */
    external fun stopRecording(): Int
    external fun isRecording(): Boolean
    external fun recordedFrames(): Long
    external fun recordStartFrame(): Long
    /** Timeline position of the last take after latency compensation. */
    external fun lastTakeStart(): Long
    external fun inputLevel(): Float
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
        masterGain: Float,
    )
    external fun trackPeak(index: Int): Float

    external fun createEffect(id: Long, type: Int, params: FloatArray): Boolean
    external fun setEffectParam(id: Long, index: Int, value: Float)
    external fun setEffectBypass(id: Long, bypass: Boolean)

    external fun setMetronome(enabled: Boolean)
    external fun setTempo(bpm: Double)
}
