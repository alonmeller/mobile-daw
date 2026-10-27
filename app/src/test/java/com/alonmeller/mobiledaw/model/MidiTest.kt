package com.alonmeller.mobiledaw.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MidiTest {
    private val kick = Note(0, 100, 36)
    private val snare = Note(500, 100, 38)

    @Test
    fun loopingPatternRepeatsAcrossTheItem() {
        val item = Midi.newItem(position = 10_000, length = 1000, notes = listOf(kick, snare)).copy(length = 2500)
        val played = Midi.timelineNotes(item)
        assertEquals(listOf(10_000L, 10_500L, 11_000L, 11_500L, 12_000L), played.map { it.start })
        assertEquals(listOf(36, 38, 36, 38, 36), played.map { it.pitch })
    }

    @Test
    fun trimmedItemPlaysOnlyItsWindow() {
        // Trimmed from the left by 600 frames: the first kick and snare are gone.
        val item = Midi.newItem(0, 1000, listOf(kick, snare)).let { Edits.trimLeft(it.copy(length = 2000), 600) }
        assertEquals(listOf(1000L, 1500L), Midi.timelineNotes(item).map { it.start })
    }

    @Test
    fun notesAreCutAtTheItemAndPatternEnds() {
        val long = Note(900, 400, 60)
        val item = Midi.newItem(0, 1000, listOf(long)).copy(length = 1950)
        val played = Midi.timelineNotes(item)
        assertEquals(listOf(Note(900, 100, 60), Note(1900, 50, 60)), played.map { it.copy(velocity = 0.8f) })
        // Notes past the end of the pattern are not played.
        val outside = Midi.newItem(0, 1000, listOf(Note(1200, 10, 60)))
        assertTrue(Midi.timelineNotes(outside.copy(length = 3000)).isEmpty())
    }

    @Test
    fun splitHalvesPlayTheSameNotes() {
        val item = Midi.newItem(0, 1000, listOf(kick, snare)).copy(length = 3000)
        val (left, right) = Edits.split(item, 1200)!!
        val whole = Midi.timelineNotes(item)
        assertEquals(whole, Midi.timelineNotes(left) + Midi.timelineNotes(right))
    }

    @Test
    fun addReplacesAndShortensNotesOfTheSamePitch() {
        var notes = Midi.add(emptyList(), Note(0, 400, 60))
        notes = Midi.add(notes, Note(200, 100, 60))
        assertEquals(listOf(Note(0, 200, 60), Note(200, 100, 60)), notes)
        notes = Midi.add(notes, Note(200, 50, 64))
        notes = Midi.add(notes, Note(150, 300, 60))
        assertEquals(listOf(Note(0, 150, 60), Note(150, 300, 60), Note(200, 50, 64)), notes)
    }

    @Test
    fun noteAtFindsTheSoundingNote() {
        val notes = listOf(Note(0, 100, 60), Note(100, 100, 62))
        assertEquals(notes[0], Midi.noteAt(notes, 99, 60))
        assertNull(Midi.noteAt(notes, 100, 60))
        assertEquals(notes[1], Midi.noteAt(notes, 150, 62))
    }

    @Test
    fun quantizeSnapsWrapsAndMerges() {
        val notes = listOf(Note(110, 50, 36), Note(390, 50, 38, 0.5f), Note(410, 50, 38, 0.9f), Note(980, 50, 42))
        val q = Midi.quantize(notes, grid = 100.0, loop = 1000)
        assertEquals(listOf(Note(0, 50, 42), Note(100, 50, 36), Note(400, 50, 38, 0.9f)), q)
    }

    @Test
    fun scaleTimeKeepsBarsAtANewTempo() {
        val item = Midi.newItem(48_000, 96_000, listOf(Note(24_000, 12_000, 60)))
        val slower = Midi.scaleTime(item, 120.0 / 60.0)
        assertEquals(96_000L, slower.position)
        assertEquals(192_000L, slower.length)
        assertEquals(192_000L, slower.loopLength)
        assertEquals(Note(48_000, 24_000, 60), slower.notes!!.single())
    }

    @Test
    fun patternLengthChangesOnePatternItems() {
        val item = Midi.newItem(0, 1000)
        assertEquals(2000L, Midi.setPatternLength(item, 2000).length)
        val stretched = item.copy(length = 4000)
        assertEquals(4000L, Midi.setPatternLength(stretched, 2000).length)
        assertEquals(2000L, Midi.setPatternLength(stretched, 2000).loopLength)
    }

    @Test
    fun recordingOverALoopOverdubsThePattern() {
        val item = Midi.newItem(10_000, 1000, listOf(kick)).copy(length = 3000)
        // Played in the third repeat; lands in the pattern at 500.
        val merged = Midi.mergeRecorded(item, listOf(Note(12_500, 80, 38), Note(20_000, 80, 40)))
        assertEquals(listOf(kick, Note(500, 80, 38)), merged.notes)
        // A long note is cut at the end of the pattern.
        val cut = Midi.mergeRecorded(item, listOf(Note(10_900, 500, 60)))
        assertEquals(Note(900, 100, 60), cut.notes!!.last())
    }

    @Test
    fun barsAroundAFrame() {
        assertEquals(96_000L to 192_000L, Midi.barAround(100_000, 96_000.0))
        assertEquals(192_000L, Midi.barEndAfter(100_000, 96_000.0))
        assertEquals(96_000L, Midi.barEndAfter(96_000, 96_000.0))
    }

    @Test
    fun drumKitSoundsAreUsable() {
        assertEquals(PAD_COUNT, DrumKit.sounds.size)
        val rate = 48_000
        for (i in DrumKit.sounds.indices) {
            val s = DrumKit.render(i, rate)
            val name = DrumKit.sounds[i].name
            assertTrue("$name has sound", s.size > rate / 20)
            assertTrue("$name is finite", s.all { it.isFinite() })
            val peak = s.maxOf { abs(it) }
            assertTrue("$name peak $peak", peak in 0.3f..1f)
            // Loud near the start, silent at the end: a hit, not a drone.
            val head = s.copyOfRange(0, rate / 20).maxOf { abs(it) }
            val tail = s.copyOfRange(s.size - s.size / 20, s.size).maxOf { abs(it) }
            assertTrue("$name starts loud", head > peak * 0.5f)
            assertTrue("$name dies away ($tail)", tail < peak * 0.05f)
        }
        // The same sound every time, on every phone.
        assertTrue(DrumKit.render(1, rate).contentEquals(DrumKit.render(1, rate)))
    }

    @Test
    fun hatsAreBrighterThanTheKick() {
        fun crossings(s: FloatArray) = (1 until s.size).count { (s[it - 1] < 0) != (s[it] < 0) }
        val rate = 48_000
        val kick = DrumKit.render(0, rate).copyOfRange(0, rate / 10)
        val hat = DrumKit.render(3, rate).copyOfRange(0, rate / 10)
        assertTrue(crossings(hat) > crossings(kick) * 20)
    }
}
