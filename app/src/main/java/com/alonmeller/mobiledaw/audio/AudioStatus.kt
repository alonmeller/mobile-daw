package com.alonmeller.mobiledaw.audio

/** What the audio streams really got, which can differ from what was asked. */
data class AudioStatus(
    /** 0 closed, 1 AAudio, 2 OpenSL ES. */
    val api: Int = 0,
    val sampleRate: Int = 0,
    val framesPerBurst: Int = 0,
    val bufferSize: Int = 0,
    val bufferCapacity: Int = 0,
    val exclusive: Boolean = false,
    /** 0 low latency, 1 normal, 2 power saving. */
    val performanceMode: Int = 0,
    val deviceId: Int = 0,
    val latencyMs: Double = 0.0,
    /** Glitches since the output opened; -1 when the audio system cannot tell. */
    val xruns: Int = -1,
    /** Share of each buffer's time the mix takes. */
    val cpuLoad: Float = 0f,
    val inputOpen: Boolean = false,
    val inputExclusive: Boolean = false,
    val inputDeviceId: Int = 0,
    val inputLatencyMs: Double = 0.0,
) {
    companion object {
        /** Reads [NativeEngine.audioStatus]'s array, in jni_bridge.cpp's order. */
        fun from(v: DoubleArray): AudioStatus {
            if (v.size < 15) return AudioStatus()
            return AudioStatus(
                api = v[0].toInt(),
                sampleRate = v[1].toInt(),
                framesPerBurst = v[2].toInt(),
                bufferSize = v[3].toInt(),
                bufferCapacity = v[4].toInt(),
                exclusive = v[5] != 0.0,
                performanceMode = v[6].toInt(),
                deviceId = v[7].toInt(),
                latencyMs = v[8],
                xruns = v[9].toInt(),
                cpuLoad = v[10].toFloat(),
                inputOpen = v[11] != 0.0,
                inputExclusive = v[12] != 0.0,
                inputDeviceId = v[13].toInt(),
                inputLatencyMs = v[14],
            )
        }
    }
}
