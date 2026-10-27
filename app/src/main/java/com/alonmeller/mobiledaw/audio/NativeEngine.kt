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

    external fun startRecording(): Boolean
    external fun stopRecording(targetId: Int): Int
    external fun isRecording(): Boolean
    external fun recordedFrames(): Long
    external fun recordStartFrame(): Long
    external fun inputLevel(): Float
    external fun masterPeak(): Float

    external fun addTrack(samples: FloatArray, startFrame: Long): Int
    external fun removeTrack(id: Int)
    external fun setTrackGain(id: Int, gain: Float)
    external fun setTrackPan(id: Int, pan: Float)
    external fun setTrackMute(id: Int, mute: Boolean)
    external fun setTrackSolo(id: Int, solo: Boolean)
    external fun setTrackStart(id: Int, startFrame: Long)
    external fun trackLength(id: Int): Long
    external fun trackStart(id: Int): Long
    external fun trackPeak(id: Int): Float
    external fun trackPeaks(id: Int, framesPerBucket: Int): FloatArray
    external fun trackSamples(id: Int): FloatArray

    external fun setMetronome(enabled: Boolean)
    external fun setTempo(bpm: Double)
}
