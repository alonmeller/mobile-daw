package com.alonmeller.mobiledaw.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal mono WAV reader/writer (32-bit float out; float or 16-bit PCM in). */
object WavFile {
    data class Audio(val samples: FloatArray, val sampleRate: Int)

    fun writeFloatMono(file: File, samples: FloatArray, sampleRate: Int) {
        val dataBytes = samples.size * 4
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16)
            .putShort(3) // IEEE float
            .putShort(1) // mono
            .putInt(sampleRate)
            .putInt(sampleRate * 4)
            .putShort(4)
            .putShort(32)
        buf.put("data".toByteArray()).putInt(dataBytes)
        buf.asFloatBuffer().put(samples)
        file.writeBytes(buf.array())
    }

    fun read(file: File): Audio? {
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 12) return null
        val riff = ByteArray(4).also { buf.get(it) }
        buf.int
        val wave = ByteArray(4).also { buf.get(it) }
        if (String(riff) != "RIFF" || String(wave) != "WAVE") return null

        var format = 0
        var channels = 1
        var sampleRate = 48000
        var bits = 16
        while (buf.remaining() >= 8) {
            val id = String(ByteArray(4).also { buf.get(it) })
            val size = buf.int
            val chunkStart = buf.position()
            when (id) {
                "fmt " -> {
                    format = buf.short.toInt()
                    channels = buf.short.toInt()
                    sampleRate = buf.int
                    buf.int
                    buf.short
                    bits = buf.short.toInt()
                }
                "data" -> {
                    val size0 = minOf(size, buf.remaining())
                    val bytesPerSample = bits / 8
                    val frames = size0 / (bytesPerSample * channels)
                    val out = FloatArray(frames)
                    for (f in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until channels) {
                            sum += when {
                                format == 3 && bits == 32 -> buf.float
                                bits == 16 -> buf.short / 32768f
                                else -> return null
                            }
                        }
                        out[f] = sum / channels
                    }
                    return Audio(out, sampleRate)
                }
            }
            buf.position(minOf(buf.limit(), chunkStart + size + (size and 1)))
        }
        return null
    }

    /** Linear resampling, used when a project is opened at a different device rate. */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = fromRate.toDouble() / toRate
        val outLen = (input.size / ratio).toInt()
        return FloatArray(outLen) { i ->
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (pos - i0).toFloat()
            input[i0] * (1 - frac) + input[i1] * frac
        }
    }
}
