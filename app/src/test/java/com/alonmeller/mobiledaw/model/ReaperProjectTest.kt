package com.alonmeller.mobiledaw.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaperProjectTest {
    // 48 kHz at 120 BPM: a second is 48000 frames, a beat 24000.
    private val rate = 48_000

    private val vocal = Track(
        id = 1, name = "Vocal", colorIndex = 0, gain = 0.5f, pan = -0.25f, solo = true,
        items = listOf(
            Item(10, sourceId = 3, position = 48_000, offset = 24_000, length = 96_000, fadeIn = 4_800, fadeOut = 9_600, gain = 0.8f, mute = true),
            // No file for this source: it is left out.
            Item(11, sourceId = 4, position = 0, offset = 0, length = 1_000),
        ),
    )
    private val pattern = Midi.newItem(position = 0, length = 48_000, notes = listOf(Note(0, 12_000, 36), Note(24_000, 12_000, 38, 1f)))
        .copy(length = 96_000)
    private val drums = Track(
        id = 2, name = "Drums", colorIndex = 1,
        items = listOf(pattern),
        instrument = Instrument(5, InstrumentType.DRUMS),
    )
    private val project = Project(
        tracks = listOf(vocal, drums),
        markers = listOf(Marker(7, 96_000, "Chorus \"big\"")),
        tempo = 120.0,
        loopStart = 0,
        loopEnd = 192_000,
        loopOn = true,
    )

    private fun write(p: Project = project, rendered: Map<Long, ReaperProject.RenderedTrack> = emptyMap(), seed: Long = 1) =
        ReaperProject.write(
            project = p,
            sampleRate = rate,
            mediaFile = { if (it == 3) "take 1.wav" else if (it == 6) "take 2.wav" else null },
            rendered = rendered,
            drumNotes = mapOf(2L to IntArray(PAD_COUNT) { if (it == 2) 39 else 36 + it }),
            colors = mapOf(1L to 0x3D9BFF),
            notes = listOf("First line", "Second line"),
            timestamp = 1_700_000_000,
            seed = seed,
        )

    private fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** The lines of the first block that starts with [tag], up to its closing ">". */
    private fun block(text: String, tag: String, nth: Int = 0): List<String> {
        val all = lines(text)
        var found = -1
        var seen = 0
        for (i in all.indices) {
            if (all[i].startsWith("<$tag")) {
                if (seen == nth) {
                    found = i
                    break
                }
                seen++
            }
        }
        check(found >= 0) { "no <$tag #$nth" }
        var depth = 0
        for (i in found until all.size) {
            if (all[i].startsWith("<")) depth++
            if (all[i] == ">") depth--
            if (depth == 0) return all.subList(found, i + 1)
        }
        error("<$tag is not closed")
    }

    @Test
    fun blocksAreBalanced() {
        val text = write()
        var depth = 0
        for (line in lines(text)) {
            if (line.startsWith("<")) depth++
            if (line == ">") depth--
            assertTrue(depth >= 0)
        }
        assertEquals(0, depth)
        assertTrue(text.startsWith("<REAPER_PROJECT 0.1 \"7.0/MobileDAW\" 1700000000\n"))
        assertTrue(text.endsWith(">\n"))
    }

    @Test
    fun projectSettings() {
        val top = lines(write())
        assertTrue("TEMPO 120 4 4" in top)
        assertTrue("SELECTION 0 4" in top)
        assertTrue("SELECTION2 0 4" in top)
        assertTrue("LOOP 1" in top)
        assertTrue("SAMPLERATE 48000 0 0" in top)
        // REAPER has no escapes, so double quotes inside a name become single ones.
        assertTrue("MARKER 1 2 \"Chorus 'big'\" 0 0 1" in top)
        assertEquals(listOf("<NOTES 0 2", "|First line", "|Second line", ">"), block(write(), "NOTES"))
    }

    @Test
    fun audioItemsKeepPlaceTrimFadesGainAndMute() {
        val track = block(write(), "TRACK")
        assertTrue("NAME \"Vocal\"" in track)
        assertTrue("VOLPAN 0.5 -0.25 -1 -1 1" in track)
        assertTrue("MUTESOLO 0 2 0" in track)
        // 0x3D9BFF is stored as 0xBBGGRR with the custom-color flag.
        assertTrue("PEAKCOL ${0x1000000 or 0xFF9B3D}" in track)
        val item = block(write(), "ITEM")
        assertTrue("POSITION 1" in item)
        assertTrue("LENGTH 2" in item)
        assertTrue("SOFFS 0.5" in item)
        assertTrue("FADEIN 0 0.1 0 0 0 0 0" in item)
        assertTrue("FADEOUT 0 0.2 0 0 0 0 0" in item)
        assertTrue("MUTE 1 0" in item)
        assertTrue("VOLPAN 0.8000000119 0 1 -1" in item)
        assertEquals(listOf("<SOURCE WAVE", "FILE \"take 1.wav\"", ">"), block(write(), "SOURCE WAVE"))
        // The item with no file is left out.
        assertEquals(1, track.count { it == "<ITEM" })
    }

    @Test
    fun takesArriveAsReaperTakesWithTheOnePlaying() {
        // Three takes, the second playing; the third's file did not come along.
        val takes = listOf(Take(3, 24_000, 0.8f), Take(6, 48_000, 0.5f), Take(4, 0))
        val item = Takes.withTakes(Item(12, sourceId = 3, position = 0, offset = 0, length = 48_000), takes, active = 1)
        val text = write(project.copy(tracks = listOf(vocal.copy(items = listOf(item)))))
        val lines = block(text, "ITEM")
        // The first take follows the item's own lines; the next starts with "TAKE SEL", as it plays.
        assertEquals(listOf("TAKE SEL"), lines.filter { it.startsWith("TAKE") })
        assertEquals(listOf("NAME \"Vocal take 1\"", "NAME \"Vocal take 2\""), lines.filter { it.startsWith("NAME ") })
        assertEquals(listOf("SOFFS 0.5", "SOFFS 1"), lines.filter { it.startsWith("SOFFS ") })
        assertEquals(listOf("VOLPAN 0.8000000119 0 1 -1", "VOLPAN 0.5 0 1 -1"), lines.filter { it.startsWith("VOLPAN ") })
        assertEquals(listOf("FILE \"take 1.wav\"", "FILE \"take 2.wav\""), lines.filter { it.startsWith("FILE ") })
        assertEquals(1, lines.count { it == "<SOURCE WAVE" } - 1)
        // With the first take playing, no take line is marked.
        val first = write(project.copy(tracks = listOf(vocal.copy(items = listOf(Takes.select(item, 0))))))
        assertEquals(listOf("TAKE"), block(first, "ITEM").filter { it.startsWith("TAKE") })
        // A take that plays but did not come along leaves the first one playing.
        val missing = write(project.copy(tracks = listOf(vocal.copy(items = listOf(Takes.select(item, 2))))))
        assertEquals(listOf("TAKE"), block(missing, "ITEM").filter { it.startsWith("TAKE") })
    }

    @Test
    fun midiItemsLoopTheirPatternInTicks() {
        val item = block(write(), "ITEM", nth = 1)
        assertTrue("POSITION 0" in item)
        assertTrue("LENGTH 2" in item)
        assertTrue("LOOP 1" in item)
        val source = block(write(), "SOURCE MIDI")
        assertEquals("HASDATA 1 960 QN", source[1])
        // The kick, then the snare on pad 2, which the drum map sends to note 39.
        assertEquals(
            listOf(
                "E 0 99 24 66", // kick on, channel 10, velocity 0.8 (102)
                "E 480 89 24 00",
                "E 480 99 27 7f",
                "E 480 89 27 00",
                "E 480 b0 7b 00", // the pattern is two beats: 1920 ticks
            ),
            source.subList(2, source.size - 1),
        )
    }

    @Test
    fun drumPadsAreMappedToTheirNotes() {
        // A note on pad 2 (MIDI 38) plays the note the drum map gives pad 2.
        val events = ReaperProject.midiEvents(listOf(Note(0, 100, 38)), 1000, 24_000.0, 9) { if (it == 38) 39 else it }
        assertEquals("E 0 99 27 66", events[1])
    }

    @Test
    fun panEnvelopesFlipSignAndReplaceTheKnob() {
        val panned = project.copy(tracks = listOf(vocal.copy(panEnv = listOf(EnvPoint(0, -1f), EnvPoint(48_000, 0.5f)))))
        val track = block(write(panned), "TRACK")
        assertTrue("VOLPAN 0.5 0 -1 -1 1" in track)
        val env = block(write(panned), "PANENV2")
        assertTrue("PT 0 1 0" in env)
        assertTrue("PT 1 -0.5 0" in env)
    }

    @Test
    fun volumeMovesKeepTheirCurve() {
        val points = ReaperProject.volumePoints(listOf(EnvPoint(0, 1f), EnvPoint(80_000, 0.01f)))
        // The ends, and seven points between them on the fader's dB scale.
        assertEquals(9, points.size)
        assertEquals(0L to 1.0, points.first())
        assertEquals(80_000L, points.last().first)
        assertEquals(0.01, points.last().second, 1e-6)
        assertTrue(points.zipWithNext().all { (a, b) -> b.first > a.first && b.second < a.second })
        // Halfway through the move is halfway in dB (-20 dB), not halfway in amplitude.
        val middle = points[4]
        assertEquals(40_000L, middle.first)
        assertEquals(0.1, middle.second, 0.002)
        // A tiny move needs no extra points.
        assertEquals(2, ReaperProject.volumePoints(listOf(EnvPoint(0, 1f), EnvPoint(1000, 0.99f))).size)
    }

    @Test
    fun instrumentTracksArriveAsAudioWithTheirNotesBelow() {
        val text = write(rendered = mapOf(2L to ReaperProject.RenderedTrack("Drums (instrument).wav", 96_000)))
        val audio = block(text, "TRACK", nth = 1)
        assertTrue("NAME \"Drums\"" in audio)
        assertTrue("FILE \"Drums (instrument).wav\"" in audio)
        assertTrue("LENGTH 2" in audio)
        assertFalse(audio.any { it.startsWith("<SOURCE MIDI") })
        val notes = block(text, "TRACK", nth = 2)
        assertTrue("NAME \"Drums MIDI\"" in notes)
        assertTrue(notes.any { it.startsWith("<SOURCE MIDI") })
        // The notes track has default mix settings.
        assertTrue("VOLPAN 1 0 -1 -1 1" in notes)
    }

    /** Each track's name, ISBUS and AUXRECV lines, in REAPER's track order. */
    private fun routing(text: String): List<Triple<String, String, List<String>>> {
        val count = lines(text).count { it.startsWith("<TRACK ") }
        return (0 until count).map { n ->
            val t = block(text, "TRACK", nth = n)
            Triple(
                t.first { it.startsWith("NAME ") }.removePrefix("NAME ").trim('"'),
                t.first { it.startsWith("ISBUS ") },
                t.filter { it.startsWith("AUXRECV ") },
            )
        }
    }

    @Test
    fun foldersBecomeReaperFolders() {
        // Band > (Drums > Kick), Bass; then Vocal at the top again.
        val band = Track(20, "Band", 0)
        val kit = drums.copy(indent = 1)
        val kick = Track(21, "Kick", 0, indent = 2)
        val bass = Track(22, "Bass", 0, indent = 1)
        val song = project.copy(tracks = listOf(band, kit, kick, bass, vocal))
        assertEquals(
            listOf("Band" to "ISBUS 1 1", "Drums" to "ISBUS 1 1", "Kick" to "ISBUS 2 -1", "Bass" to "ISBUS 2 -1", "Vocal" to "ISBUS 0 0"),
            routing(write(song)).map { it.first to it.second },
        )
        // A rendered instrument folder keeps its notes track inside, so its tracks stay in the folder.
        val rendered = mapOf(2L to ReaperProject.RenderedTrack("Drums (instrument).wav", 96_000))
        assertEquals(
            listOf(
                "Band" to "ISBUS 1 1", "Drums" to "ISBUS 1 1", "Drums MIDI" to "ISBUS 0 0", "Kick" to "ISBUS 2 -1",
                "Bass" to "ISBUS 2 -1", "Vocal" to "ISBUS 0 0",
            ),
            routing(write(song, rendered)).map { it.first to it.second },
        )
        // An instrument track that is not a folder has its notes track next to it, closing the folder after it.
        val last = project.copy(tracks = listOf(band, kit.copy(indent = 1)))
        assertEquals(
            listOf("Band" to "ISBUS 1 1", "Drums" to "ISBUS 0 0", "Drums MIDI" to "ISBUS 2 -1"),
            routing(write(last, rendered)).map { it.first to it.second },
        )
    }

    @Test
    fun sendsArriveOnTheReceivingTrack() {
        val plainBus = Track(30, "Group", 0)
        val reverbBus = Track(31, "Reverb", 0, fx = listOf(Effect(32, EffectType.REVERB)))
        val singer = vocal.copy(
            sends = listOf(Send(1, target = 30, gain = 0.5f), Send(2, target = 31, gain = 0.25f, preFader = true)),
        )
        val kit = drums.copy(sends = listOf(Send(3, target = 30, gain = 1f, mute = true)))
        val song = project.copy(tracks = listOf(singer, kit, plainBus, reverbBus))
        val rendered = mapOf(2L to ReaperProject.RenderedTrack("Drums (instrument).wav", 96_000))
        val tracks = routing(write(song, rendered))
        assertEquals(listOf("Vocal", "Drums", "Drums MIDI", "Group", "Reverb"), tracks.map { it.first })
        // The drums' audio is track 1 in REAPER: the notes track below it takes index 2.
        assertEquals(
            listOf("AUXRECV 0 0 0.5 0 0 0 0 0 0 -1 0 -1", "AUXRECV 1 0 1 0 1 0 0 0 0 -1 0 -1"),
            tracks[3].third,
        )
        // Pre-fader is REAPER's mode 3; into a track with effects REAPER cannot play, the send arrives muted.
        assertEquals(listOf("AUXRECV 0 3 0.25 0 1 0 0 0 0 -1 0 -1"), tracks[4].third)
        assertTrue(tracks.take(3).all { it.third.isEmpty() })
    }

    @Test
    fun guidsAreWellFormedAndRepeatable() {
        val guid = Regex("\\{[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\\}")
        val text = write()
        val ids = lines(text).filter { it.startsWith("<TRACK ") || it.startsWith("IGUID ") || it.startsWith("GUID ") }
            .map { it.substringAfter(' ') }
        assertTrue(ids.isNotEmpty())
        assertTrue(ids.all { guid.matches(it) })
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(text, write())
        assertNotEquals(text, write(seed = 2))
    }

    @Test
    fun numbersAreWrittenPlainly() {
        assertEquals("0.1", ReaperProject.num(0.1))
        assertEquals("2", ReaperProject.num(2.0))
        assertEquals("0", ReaperProject.num(-0.0))
        assertEquals("0", ReaperProject.num(1e-12))
        assertEquals("0.3333333333", ReaperProject.num(1.0 / 3))
        assertEquals("-1.5", ReaperProject.num(-1.5))
        assertEquals("0", ReaperProject.num(Double.NaN))
    }

    @Test
    fun midiEventsStopAtTheEndOfTheSource() {
        val events = ReaperProject.midiEvents(
            listOf(Note(0, 30_000, 60), Note(24_000, 1_000, 62), Note(10_000, 100, 64)),
            length = 24_000,
            framesPerBeat = 24_000.0,
            channel = 0,
        )
        // The first note is cut at the end; the one starting at the end is dropped.
        assertEquals(
            listOf("HASDATA 1 960 QN", "E 0 90 3c 66", "E 400 90 40 66", "E 4 80 40 00", "E 556 80 3c 00", "E 0 b0 7b 00"),
            events,
        )
    }
}
