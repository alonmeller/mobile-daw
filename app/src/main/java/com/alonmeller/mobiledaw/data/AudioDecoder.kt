package com.alonmeller.mobiledaw.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes an audio file the user picked (MP3, M4A/AAC, WAV, FLAC, OGG, ...)
 * with Android's own decoders, mixed down to mono like every source here.
 */
object AudioDecoder {
    /** Longest import, so a whole album cannot run the phone out of memory. */
    private const val MAX_SECONDS = 10 * 60

    fun decode(context: Context, uri: Uri): ImportedAudio? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val samples = decodeTrack(extractor, format, mime) ?: return null
            ImportedAudio(displayName(context, uri), samples.first, samples.second)
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun decodeTrack(extractor: MediaExtractor, format: MediaFormat, mime: String): Pair<FloatArray, Int>? {
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val out = Floats()
        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: return null
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                // A decoder that stops answering after the input ran out is done.
                idle = if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) idle + 1 else 0
                if (idle > 300) break
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // The decoder knows best: HE-AAC, for one, doubles the rate and the channels.
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else if (outIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0) {
                        // Through Buffer, whose position(int) exists on every Android version.
                        (buffer as Buffer).position(info.offset)
                        (buffer as Buffer).limit(info.offset + info.size)
                        mixDown(buffer.slice().order(ByteOrder.nativeOrder()), channels, encoding, out)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    if (out.size > MAX_SECONDS.toLong() * sampleRate) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        if (out.size == 0L) return null
        return out.toArray() to sampleRate
    }

    /** Appends one decoded buffer, averaging its channels into mono. */
    private fun mixDown(buffer: ByteBuffer, channels: Int, encoding: Int, out: Floats) {
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buffer.asFloatBuffer()
                repeat(fb.remaining() / channels) {
                    var sum = 0f
                    repeat(channels) { sum += fb.get() }
                    out.add(sum / channels)
                }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                val ib = buffer.asIntBuffer()
                repeat(ib.remaining() / channels) {
                    var sum = 0f
                    repeat(channels) { sum += ib.get() / 2147483648f }
                    out.add(sum / channels)
                }
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                repeat(buffer.remaining() / (3 * channels)) {
                    var sum = 0f
                    repeat(channels) {
                        val b0 = buffer.get().toInt() and 0xFF
                        val b1 = buffer.get().toInt() and 0xFF
                        val b2 = buffer.get().toInt()
                        sum += ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
                    }
                    out.add(sum / channels)
                }
            }
            else -> {
                val sb = buffer.asShortBuffer()
                repeat(sb.remaining() / channels) {
                    var sum = 0f
                    repeat(channels) { sum += sb.get() }
                    out.add(sum / channels / 32768f)
                }
            }
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return (fromProvider ?: uri.lastPathSegment ?: "Import").substringBeforeLast('.')
    }

    /** A growable float array, kept in chunks so growing never copies a whole song. */
    private class Floats {
        private val chunks = ArrayList<FloatArray>()
        private var current = FloatArray(CHUNK)
        private var used = 0
        var size = 0L
            private set

        fun add(v: Float) {
            if (used == current.size) {
                chunks += current
                current = FloatArray(CHUNK)
                used = 0
            }
            current[used++] = v
            size++
        }

        fun toArray(): FloatArray {
            val out = FloatArray(size.toInt())
            var pos = 0
            for (c in chunks) {
                c.copyInto(out, pos)
                pos += c.size
            }
            current.copyInto(out, pos, 0, used)
            return out
        }

        private companion object {
            const val CHUNK = 1 shl 18
        }
    }
}
