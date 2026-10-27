package com.alonmeller.mobiledaw.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiFileTest {
    // At 120 BPM and 48 kHz a beat is 24000 frames, which is 960 ticks.
    private val rate = 48_000
    private val beat = 24_000L

    private class Event(val tick: Long, val bytes: List<Int>)

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF shl 8) or (b[at + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, at: Int) = (u16(b, at) shl 16) or u16(b, at + 2)

    /** The chunks of a file as (id, body), checking that their sizes add up to the whole file. */
    private fun chunks(file: ByteArray): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        var at = 0
        while (at < file.size) {
            val id = String(file, at, 4, Charsets.US_ASCII)
            val size = u32(file, at + 4)
            out += id to file.copyOfRange(at + 8, at + 8 + size)
            at += 8 + size
        }
        assertEquals(file.size, at)
        return out
    }

    private fun readVarLength(b: ByteArray, start: Int): Pair<Long, Int> {
        var value = 0L
        var at = start
        while (true) {
            val byte = b[at++].toInt() and 0xFF
            value = (value shl 7) or (byte and 0x7F).toLong()
            if (byte and 0x80 == 0) return value to at
        }
    }

    /** The events of a track body, with absolute ticks. The writer never uses running status. */
    private fun events(track: ByteArray): List<Event> {
        val out = mutableListOf<Event>()
        var at = 0
        var tick = 0L
        while (at < track.size) {
            val (delta, next) = readVarLength(track, at)
            tick += delta
            at = next
            val status = track[at].toInt() and 0xFF
            val length = when (status and 0xF0) {
                0xF0 -> {
                    assertEquals(0xFF, status)
                    val (dataLength, dataStart) = readVarLength(track, at + 2)
                    dataStart - at + dataLength.toInt()
                }
                0x80, 0x90 -> 3
                else -> error("unexpected status $status")
            }
            out += Event(tick, (at until at + length).map { track[it].toInt() and 0xFF })
            at += length
        }
        return out
    }

    private fun write(parts: List<MidiFile.Part>, markers: List<Marker> = emptyList(), tempo: Double = 120.0, title: String = "Song") =
        MidiFile.write(title, tempo, rate, markers, parts)

    @Test
    fun headerDescribesATypeOneFileWithATempoTrack() {
        val file = write(listOf(MidiFile.Part("Synth", 0, listOf(Note(0, beat, 60))), MidiFile.Part("Drums", 9, listOf(Note(0, beat, 36)))))
        val chunks = chunks(file)
        assertEquals(listOf("MThd", "MTrk", "MTrk", "MTrk"), chunks.map { it.first })
        val header = chunks[0].second
        assertEquals(6, header.size)
        assertEquals(1, u16(header, 0))
        assertEquals(3, u16(header, 2))
        assertEquals(MidiFile.TICKS_PER_BEAT, u16(header, 4))
        // Every track ends with end-of-track.
        chunks.drop(1).forEach { (_, body) -> assertEquals(listOf(0xFF, 0x2F, 0), events(body).last().bytes) }
    }

    @Test
    fun tempoTrackHoldsTitleTempoMeterAndMarkers() {
        val file = write(emptyList(), markers = listOf(Marker(1, 2 * beat, "Chorus")), title = "My Song")
        val conductor = events(chunks(file)[1].second)
        assertEquals(listOf(0xFF, 0x03, 7) + "My Song".map { it.code }, conductor[0].bytes)
        // 120 BPM is 500000 microseconds per beat.
        assertEquals(listOf(0xFF, 0x51, 3, 0x07, 0xA1, 0x20), conductor[1].bytes)
        assertEquals(listOf(0xFF, 0x58, 4, 4, 2, 24, 8), conductor[2].bytes)
        assertEquals(1920L, conductor[3].tick)
        assertEquals(listOf(0xFF, 0x06, 6) + "Chorus".map { it.code }, conductor[3].bytes)
    }

    @Test
    fun otherTempos() {
        val conductor = events(chunks(write(emptyList(), tempo = 90.0))[1].second)
        // 60000000 / 90 = 666667 = 0x0A2C2B
        assertEquals(listOf(0xFF, 0x51, 3, 0x0A, 0x2C, 0x2B), conductor[1].bytes)
    }

    @Test
    fun notesLandOnTheirTicks() {
        val file = write(listOf(MidiFile.Part("Synth", 0, listOf(Note(beat, beat / 2, 60, 1f), Note(3 * beat, beat, 64, 0.5f)))))
        val notes = events(chunks(file)[2].second).filter { it.bytes[0] != 0xFF }
        assertEquals(listOf(960L, 1440L, 2880L, 3840L), notes.map { it.tick })
        assertEquals(listOf(0x90, 60, 127), notes[0].bytes)
        assertEquals(listOf(0x80, 60, 0), notes[1].bytes)
        assertEquals(listOf(0x90, 64, 64), notes[2].bytes)
        assertEquals(listOf(0x80, 64, 0), notes[3].bytes)
        // The part's name comes first.
        assertEquals(listOf(0xFF, 0x03, 5) + "Synth".map { it.code }, events(chunks(file)[2].second)[0].bytes)
    }

    @Test
    fun aNoteEndsBeforeTheNextOneStartsOnTheSameTick() {
        val file = write(listOf(MidiFile.Part("Synth", 0, listOf(Note(0, beat, 60), Note(beat, beat, 60)))))
        val atBeat = events(chunks(file)[2].second).filter { it.tick == 960L && it.bytes[0] != 0xFF }
        assertEquals(listOf(0x80, 0x90), atBeat.map { it.bytes[0] })
    }

    @Test
    fun drumsPlayOnChannelTen() {
        val file = write(listOf(MidiFile.Part("Drums", MidiFile.DRUM_CHANNEL, listOf(Note(0, 100, 36)))))
        val notes = events(chunks(file)[2].second).filter { it.bytes[0] != 0xFF }
        assertEquals(listOf(0x99, 0x89), notes.map { it.bytes[0] })
    }

    @Test
    fun aZeroLengthNoteStillLastsATick() {
        val file = write(listOf(MidiFile.Part("Synth", 0, listOf(Note(beat, 0, 60)))))
        val notes = events(chunks(file)[2].second).filter { it.bytes[0] != 0xFF }
        assertEquals(listOf(960L, 961L), notes.map { it.tick })
    }

    @Test
    fun longTitlesAreCutWithoutBreakingCharacters() {
        val file = write(emptyList(), title = "שיר ".repeat(100))
        val title = events(chunks(file)[1].second)[0].bytes
        val (length, start) = readVarLength(title.map { it.toByte() }.toByteArray(), 2)
        assertTrue(length <= 255)
        val text = String(title.drop(start).map { it.toByte() }.toByteArray(), Charsets.UTF_8)
        assertFalse(text.contains('�'))
        assertTrue(text.startsWith("שיר שיר"))
    }

    @Test
    fun variableLengthNumbersMatchTheStandard() {
        fun vlq(v: Long) = MidiFile.varLength(v).map { it.toInt() and 0xFF }
        assertEquals(listOf(0x00), vlq(0))
        assertEquals(listOf(0x40), vlq(0x40))
        assertEquals(listOf(0x7F), vlq(0x7F))
        assertEquals(listOf(0x81, 0x00), vlq(0x80))
        assertEquals(listOf(0xC0, 0x00), vlq(0x2000))
        assertEquals(listOf(0xFF, 0x7F), vlq(0x3FFF))
        assertEquals(listOf(0x81, 0x80, 0x00), vlq(0x4000))
        assertEquals(listOf(0xFF, 0xFF, 0x7F), vlq(0x1FFFFF))
        assertEquals(listOf(0x81, 0x80, 0x80, 0x00), vlq(0x200000))
        assertEquals(listOf(0xFF, 0xFF, 0xFF, 0x7F), vlq(0x0FFFFFFF))
    }

    @Test
    fun kitSoundsGetGeneralMidiNotes() {
        // Pads 0-2 hold the kick, snare and clap; pad 3 holds a recording; the rest are empty.
        val notes = DrumKit.midiNotes(listOf(0, 1, 2, null))
        assertEquals(36, notes[0])
        assertEquals(38, notes[1])
        assertEquals(39, notes[2])
        // Pad 3 keeps its own note, 39, but the clap took it: the next free note.
        assertEquals(40, notes[3])
        assertEquals(PAD_COUNT, notes.size)
        assertEquals(PAD_COUNT, notes.toSet().size)
        assertTrue(notes.all { it in 0..127 })
    }

    @Test
    fun theDefaultKitMapsEveryPadToItsSound() {
        val notes = DrumKit.midiNotes(DrumKit.sounds.indices.toList())
        assertArrayEquals(DrumKit.sounds.map { it.gmNote }.toIntArray(), notes)
    }
}
