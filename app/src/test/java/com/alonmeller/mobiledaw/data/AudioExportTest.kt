package com.alonmeller.mobiledaw.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.roundToInt

class AudioExportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A 32-bit float WAV like the engine writes, optionally with an extra chunk before the samples. */
    private fun floatWav(samples: FloatArray, channels: Int = 2, rate: Int = 48_000, extraChunk: Boolean = false): File {
        val extra = if (extraChunk) 8 + 6 else 0
        val b = ByteBuffer.allocate(44 + extra + samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + extra + samples.size * 4).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(3).putShort(channels.toShort())
            .putInt(rate).putInt(rate * channels * 4).putShort((channels * 4).toShort()).putShort(32)
        if (extraChunk) b.put("LIST".toByteArray()).putInt(6).put(ByteArray(6))
        b.put("data".toByteArray()).putInt(samples.size * 4)
        samples.forEach { b.putFloat(it) }
        return tmp.newFile().apply { writeBytes(b.array()) }
    }

    private fun int24(bytes: ByteArray, at: Int) =
        (bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() and 0xFF shl 8) or (bytes[at + 2].toInt() shl 16)

    private fun int16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)

    private val samples = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f, 1.5f, -2f, 0.25f, 1e-4f, -1e-4f)

    @Test
    fun readsTheLayoutOfAFloatWav() {
        val layout = AudioExport.layout(floatWav(samples, extraChunk = true))!!
        assertTrue(layout.isFloat)
        assertEquals(2, layout.channels)
        assertEquals(48_000, layout.sampleRate)
        assertEquals(5L, layout.frames)
        assertEquals(44L + 14, layout.dataOffset)
        assertNull(AudioExport.layout(tmp.newFile().apply { writeText("not a wav file at all") }))
    }

    @Test
    fun convertsTo24BitPcm() {
        val out = File(tmp.root, "out.wav")
        AudioExport.toPcm(floatWav(samples), out, 24)
        val bytes = out.readBytes()
        val header = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(36 + samples.size * 3, header.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(1, header.getShort(20).toInt()) // PCM
        assertEquals(2, header.getShort(22).toInt())
        assertEquals(48_000, header.getInt(24))
        assertEquals(48_000 * 6, header.getInt(28))
        assertEquals(6, header.getShort(32).toInt())
        assertEquals(24, header.getShort(34).toInt())
        assertEquals("data", String(bytes, 36, 4))
        assertEquals(samples.size * 3, header.getInt(40))
        assertEquals(44 + samples.size * 3, bytes.size)
        // Full scale is 8388607, and anything past it is clipped.
        val expected = samples.map { (it.coerceIn(-1f, 1f) * 8388607f).roundToInt() }
        assertEquals(expected, samples.indices.map { int24(bytes, 44 + it * 3) })
        // It reads back as the same layout.
        val layout = AudioExport.layout(out)!!
        assertEquals(5L, layout.frames)
        assertEquals(24, layout.bits)
    }

    @Test
    fun convertsTo16BitPcmWithGainAndDither() {
        val out = File(tmp.root, "out16.wav")
        AudioExport.toPcm(floatWav(samples), out, 16, gain = 0.5f)
        val bytes = out.readBytes()
        assertEquals(44 + samples.size * 2, bytes.size)
        samples.forEachIndexed { i, s ->
            val exact = (s * 0.5f).coerceIn(-1f, 1f) * 32767f
            // Dither moves a sample by at most one step.
            assertTrue("sample $i", abs(int16(bytes, 44 + i * 2) - exact) <= 1.5f)
        }
        // The dither is repeatable, so the same export makes the same file.
        val again = File(tmp.root, "again.wav")
        AudioExport.toPcm(floatWav(samples), again, 16, gain = 0.5f)
        assertArrayEquals(bytes, again.readBytes())
    }

    @Test
    fun readsInChunks() {
        val chunks = mutableListOf<List<Float>>()
        AudioExport.readFloat(floatWav(samples), gain = 2f, chunkSamples = 4) { chunk, count -> chunks += chunk.take(count) }
        assertEquals(listOf(4, 4, 2), chunks.map { it.size })
        assertEquals(samples.map { it * 2f }, chunks.flatten())
    }

    @Test
    fun zipsFilesUnderTheirPaths() {
        val a = tmp.newFile("a.wav").apply { writeBytes(ByteArray(1000) { it.toByte() }) }
        val b = tmp.newFile("b.txt").apply { writeText("hello") }
        val zip = File(tmp.root, "out.zip")
        AudioExport.zip(zip, listOf("Song/a.wav" to a, "Song/README.txt" to b))
        ZipFile(zip).use { z ->
            assertEquals(listOf("Song/a.wav", "Song/README.txt"), z.entries().toList().map { it.name })
            assertArrayEquals(a.readBytes(), z.getInputStream(z.getEntry("Song/a.wav")).readBytes())
            assertEquals("hello", z.getInputStream(z.getEntry("Song/README.txt")).reader().readText())
        }
    }

    @Test
    fun fileNamesAreSafeEverywhere() {
        assertEquals("a_b_c__ d", AudioExport.safeFileName("a/b:c*? d"))
        assertEquals("שיר חדש", AudioExport.safeFileName("  שיר חדש  "))
        assertEquals("Untitled", AudioExport.safeFileName(" ... "))
        assertEquals("Song", AudioExport.safeFileName("", "Song"))
        assertEquals(60, AudioExport.safeFileName("x".repeat(100)).length)
        assertEquals("tab_name", AudioExport.safeFileName("tab\tname"))
        assertEquals("Song 1", AudioExport.asciiFileName("שיר Song  1", "x"))
        assertEquals("Fallback", AudioExport.asciiFileName("שיר", "Fallback"))
    }

    @Test
    fun uniqueNamesIgnoreCase() {
        val taken = mutableSetOf<String>()
        assertEquals("Drums.wav", AudioExport.unique("Drums", taken, ".wav"))
        assertEquals("drums 2.wav", AudioExport.unique("drums", taken, ".wav"))
        assertEquals("Drums 3.wav", AudioExport.unique("Drums", taken, ".wav"))
        assertEquals(setOf("drums.wav", "drums 2.wav", "drums 3.wav"), taken)
    }
}
