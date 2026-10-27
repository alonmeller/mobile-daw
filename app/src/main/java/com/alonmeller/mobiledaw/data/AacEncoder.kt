package com.alonmeller.mobiledaw.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Encodes audio as AAC in an .m4a file with the phone's own encoder. Android
 * has no MP3 encoder; AAC is what phones, GarageBand and REAPER play anyway,
 * and it sounds better than MP3 at the same size.
 */
object AacEncoder {
    private const val MIME = "audio/mp4a-latm"
    private const val TIMEOUT_US = 10_000L

    /** Encodes [wav], a 32-bit float WAV, into [out], multiplied by [gain]. */
    fun encode(wav: File, out: File, gain: Float, bitRate: Int = 256_000): Boolean {
        val layout = AudioExport.layout(wav) ?: return false
        if (!layout.isFloat) return false
        val channels = layout.channels
        val rate = layout.sampleRate
        val format = MediaFormat.createAudioFormat(MIME, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = runCatching { MediaCodec.createEncoderByType(MIME) }.getOrNull() ?: return false
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var muxerStopped = false
        val reader = FloatChunks(wav, layout, gain)
        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            var track = -1
            var wroteSamples = false
            val info = MediaCodec.BufferInfo()

            // Samples converted to 16-bit, a chunk at a time as the encoder asks for them.
            var pending = ShortArray(0)
            var pendingPos = 0
            var framesQueued = 0L
            var inputDone = false
            var outputDone = false
            var idle = 0

            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear()
                        buffer.order(ByteOrder.nativeOrder())
                        // Whole frames only, so every buffer starts on the left channel.
                        val room = buffer.remaining() / (2 * channels) * channels
                        var written = 0
                        while (written < room) {
                            if (pendingPos == pending.size) {
                                val chunk = reader.next() ?: break
                                pending = ShortArray(chunk.size) { (chunk[it].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort() }
                                pendingPos = 0
                            }
                            val n = minOf(pending.size - pendingPos, room - written)
                            for (i in 0 until n) buffer.putShort(pending[pendingPos + i])
                            pendingPos += n
                            written += n
                        }
                        val timeUs = framesQueued * 1_000_000L / rate
                        framesQueued += written / channels
                        inputDone = pendingPos == pending.size && reader.done
                        val flags = if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        codec.queueInputBuffer(index, 0, written * 2, timeUs, flags)
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = mux.addTrack(codec.outputFormat)
                        mux.start()
                        muxerStarted = true
                    }
                    index >= 0 -> {
                        idle = 0
                        val data = codec.getOutputBuffer(index)
                        // The codec setup reaches the muxer with the format, not as a sample.
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (data != null && info.size > 0 && !isConfig && muxerStarted) {
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            mux.writeSampleData(track, data, info)
                            wroteSamples = true
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    // An encoder that stops answering after the last input must not hang the export.
                    inputDone && ++idle > 500 -> outputDone = true
                }
            }
            if (muxerStarted) {
                mux.stop()
                muxerStopped = true
            }
            wroteSamples
        } catch (e: Exception) {
            false
        } finally {
            reader.close()
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted && !muxerStopped) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    /** Hands out the samples of a float WAV, multiplied by a gain, a chunk at a time. */
    private class FloatChunks(file: File, layout: AudioExport.WavLayout, private val gain: Float) {
        private val input = RandomAccessFile(file, "r").apply { seek(layout.dataOffset) }
        private var remaining = layout.frames * layout.channels
        private val chunk = 8192 / layout.channels * layout.channels
        private val bytes = ByteArray(chunk * 4)

        val done: Boolean get() = remaining <= 0

        fun next(): FloatArray? {
            if (remaining <= 0) return null
            val count = minOf(remaining, chunk.toLong()).toInt()
            input.readFully(bytes, 0, count * 4)
            val floats = ByteBuffer.wrap(bytes, 0, count * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            remaining -= count
            return FloatArray(count) { floats.get(it) * gain }
        }

        fun close() = runCatching { input.close() }
    }
}
