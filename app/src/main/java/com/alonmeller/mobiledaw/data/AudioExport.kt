package com.alonmeller.mobiledaw.data

import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import kotlin.random.Random

/** Turns the engine's 32-bit float renders into the files people keep and share. */
object AudioExport {
    /** Where a WAV file keeps its samples, and in what form. */
    class WavLayout(
        val format: Int,
        val channels: Int,
        val sampleRate: Int,
        val bits: Int,
        val dataOffset: Long,
        val dataBytes: Long,
    ) {
        val frames: Long get() = dataBytes / (channels * (bits / 8))
        val isFloat: Boolean get() = format == 3 && bits == 32
    }

    fun layout(file: File): WavLayout? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(12)
            raf.readFully(head)
            if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") {
                return@use null
            }
            var format = 0
            var channels = 0
            var sampleRate = 0
            var bits = 0
            val chunk = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(chunk)
                val id = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val start = raf.filePointer
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(16)
                        raf.readFully(fmt)
                        val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        format = b.short.toInt() and 0xFFFF
                        channels = b.short.toInt()
                        sampleRate = b.int
                        b.int
                        b.short
                        bits = b.short.toInt()
                    }
                    "data" -> {
                        if (channels <= 0 || bits <= 0) return@use null
                        return@use WavLayout(format, channels, sampleRate, bits, start, minOf(size, raf.length() - start))
                    }
                }
                raf.seek(start + size + (size and 1))
            }
            null
        }
    }.getOrNull()

    /** A plain PCM WAV header for [dataBytes] of audio. */
    fun pcmHeader(sampleRate: Int, channels: Int, bits: Int, dataBytes: Long): ByteArray {
        val size = dataBytes.coerceAtMost(0xFFFFFFFFL - 36).toInt()
        val blockAlign = channels * bits / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + size).put("WAVE".toByteArray(Charsets.US_ASCII))
            .put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
            .putShort(1) // PCM
            .putShort(channels.toShort())
            .putInt(sampleRate)
            .putInt(sampleRate * blockAlign)
            .putShort(blockAlign.toShort())
            .putShort(bits.toShort())
            .put("data".toByteArray(Charsets.US_ASCII)).putInt(size)
            .array()
    }

    /**
     * Reads [src], a 32-bit float WAV, in chunks of interleaved samples
     * multiplied by [gain], and hands each chunk with its sample count to [block].
     */
    fun readFloat(src: File, gain: Float = 1f, chunkSamples: Int = 8192, block: (FloatArray, Int) -> Unit): WavLayout {
        val layout = layout(src)
        require(layout != null && layout.isFloat) { "${src.name} is not a float WAV file" }
        RandomAccessFile(src, "r").use { input ->
            input.seek(layout.dataOffset)
            val bytes = ByteArray(chunkSamples * 4)
            val samples = FloatArray(chunkSamples)
            var remaining = layout.frames * layout.channels
            while (remaining > 0) {
                val count = minOf(remaining, chunkSamples.toLong()).toInt()
                input.readFully(bytes, 0, count * 4)
                val floats = ByteBuffer.wrap(bytes, 0, count * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                for (i in 0 until count) samples[i] = floats.get(i) * gain
                block(samples, count)
                remaining -= count
            }
        }
        return layout
    }

    /**
     * Writes [src], a 32-bit float WAV, to [dst] as 16- or 24-bit PCM, multiplied
     * by [gain]. Samples past full scale are clipped, and 16-bit files get
     * triangular dither so quiet fades stay smooth.
     */
    fun toPcm(src: File, dst: File, bits: Int, gain: Float = 1f) {
        require(bits == 16 || bits == 24)
        val layout = layout(src)
        require(layout != null && layout.isFloat) { "${src.name} is not a float WAV file" }
        val bytesPerSample = bits / 8
        val scale = if (bits == 16) 32767f else 8388607f
        val lsb = 1f / scale
        val random = Random(0x5EED)
        BufferedOutputStream(dst.outputStream(), 1 shl 16).use { out ->
            out.write(pcmHeader(layout.sampleRate, layout.channels, bits, layout.frames * layout.channels * bytesPerSample))
            val bytes = ByteArray(8192 * bytesPerSample)
            readFloat(src, gain) { samples, count ->
                var o = 0
                for (i in 0 until count) {
                    var v = samples[i]
                    if (bits == 16) v += (random.nextFloat() - random.nextFloat()) * lsb
                    val q = (v.coerceIn(-1f, 1f) * scale).roundToInt()
                    bytes[o++] = q.toByte()
                    bytes[o++] = (q shr 8).toByte()
                    if (bits == 24) bytes[o++] = (q shr 16).toByte()
                }
                out.write(bytes, 0, o)
            }
        }
    }

    /**
     * Packs [entries] (path inside the archive to file) into [zip]. Audio
     * hardly compresses, so the quickest compression is used.
     */
    fun zip(zip: File, entries: List<Pair<String, File>>) {
        ZipOutputStream(BufferedOutputStream(zip.outputStream(), 1 shl 16)).use { out ->
            out.setLevel(Deflater.BEST_SPEED)
            for ((name, file) in entries) {
                out.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(out, 1 shl 16) }
                out.closeEntry()
            }
        }
    }

    /** [name] made safe for a file name on any system; [fallback] when nothing is left. */
    fun safeFileName(name: String, fallback: String = "Untitled"): String {
        val cleaned = name
            .map { c -> if (c < ' ' || c in "\\/:*?\"<>|") '_' else c }
            .joinToString("")
            .trim()
            .trimEnd('.')
            .take(60)
            .trim()
        return cleaned.ifEmpty { fallback }
    }

    /** Like [safeFileName], but plain ASCII, for files that older tools must open. */
    fun asciiFileName(name: String, fallback: String): String {
        val ascii = safeFileName(name, "").filter { it.code in 32..126 }.replace(Regex(" +"), " ").trim()
        return ascii.ifEmpty { fallback }
    }

    /** [base], or "base 2", "base 3", ... whichever is not in [taken] yet (ignoring case). */
    fun unique(base: String, taken: MutableSet<String>, extension: String = ""): String {
        var n = 1
        var name = base + extension
        while (name.lowercase() in taken) name = "$base ${++n}$extension"
        taken += name.lowercase()
        return name
    }
}
