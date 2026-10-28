package com.alonmeller.mobiledaw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs

class WavFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val samples = floatArrayOf(0f, 0.5f, -0.5f, 0.25f, -1f, 1f, 0.001f, -0.123456f)

    private fun roundTrip(bits: Int): FloatArray {
        val file = tmp.newFile("take-$bits.wav")
        WavFile.writeMono(file, samples, 44100, bits)
        val bytes = if (bits == 32) 4 else bits / 8
        assertEquals(44L + samples.size * bytes, file.length())
        val audio = WavFile.read(file)!!
        assertEquals(44100, audio.sampleRate)
        return audio.samples
    }

    private fun assertClose(expected: FloatArray, actual: FloatArray, tolerance: Float) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertTrue("sample $i: ${expected[i]} came back as ${actual[i]}", abs(expected[i] - actual[i]) <= tolerance)
        }
    }

    @Test
    fun everyRecordingFormatReadsBackWhatWasWritten() {
        assertClose(samples, roundTrip(32), 0f)
        assertClose(samples, roundTrip(24), 2f / 8_388_608)
        assertClose(samples, roundTrip(16), 2f / 32_768)
    }

    @Test
    fun pcmClipsAtFullScaleAndSilencesBadSamples() {
        val file = tmp.newFile("loud.wav")
        WavFile.writeMono(file, floatArrayOf(1.7f, -3f, Float.NaN), 48000, 16)
        assertClose(floatArrayOf(32767f / 32768, -32767f / 32768, 0f), WavFile.read(file)!!.samples, 0f)
    }
}
