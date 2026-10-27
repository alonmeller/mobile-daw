package com.alonmeller.mobiledaw.data

import com.alonmeller.mobiledaw.model.Effect
import com.alonmeller.mobiledaw.model.EffectType
import com.alonmeller.mobiledaw.model.Ids
import com.alonmeller.mobiledaw.model.Project
import com.alonmeller.mobiledaw.model.Send
import com.alonmeller.mobiledaw.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun foldersAndSendsSurviveSaveAndLoad() {
        val store = ProjectStore(tmp.newFolder("song"))
        val bus = Track(Ids.next(), "Reverb bus", 1, fx = listOf(Effect(Ids.next(), EffectType.REVERB)))
        val drums = Track(Ids.next(), "Drums", 2, collapsed = true)
        val kick = Track(
            Ids.next(), "Kick", 3, indent = 1,
            sends = listOf(Send(Ids.next(), bus.id, gain = 0.25f, preFader = true, mute = true)),
        )
        val vocal = Track(Ids.next(), "Vocal", 4, sends = listOf(Send(Ids.next(), bus.id), Send(Ids.next(), drums.id, gain = 0.1f)))
        store.save(Project(tracks = listOf(drums, kick, vocal, bus)), ProjectSettings(), 48000) { null }

        val loaded = store.load(48000) { null }!!.project
        val names = loaded.tracks.map { it.name }
        assertEquals(listOf("Drums", "Kick", "Vocal", "Reverb bus"), names)
        assertEquals(listOf(0, 1, 0, 0), loaded.tracks.map { it.indent })
        assertTrue(loaded.tracks[0].collapsed)
        fun target(s: Send) = loaded.tracks.first { it.id == s.target }.name
        val kickSend = loaded.tracks[1].sends.single()
        assertEquals("Reverb bus", target(kickSend))
        assertEquals(0.25f, kickSend.gain, 1e-6f)
        assertTrue(kickSend.preFader && kickSend.mute)
        assertEquals(listOf("Reverb bus", "Drums"), loaded.tracks[2].sends.map(::target))
        assertEquals(0.1f, loaded.tracks[2].sends[1].gain, 1e-6f)
    }

    @Test
    fun anOldSongLoadsWithNoRouting() {
        val dir = tmp.newFolder("old")
        File(dir, "project.json").writeText(
            """{"version": 4, "tempo": 100, "tracks": [{"name": "Voice", "items": []}, {"name": "Keys", "items": []}]}"""
        )
        val loaded = ProjectStore(dir).load(48000) { null }!!.project
        assertEquals(listOf(0, 0), loaded.tracks.map { it.indent })
        assertTrue(loaded.tracks.all { it.sends.isEmpty() && !it.collapsed })
    }

    @Test
    fun brokenRoutingIsRepairedOnLoad() {
        val dir = tmp.newFolder("broken")
        File(dir, "project.json").writeText(
            """{"version": 5, "tracks": [
                {"name": "A", "indent": 3, "sends": [{"to": 1}, {"to": 0}, {"to": 9}]},
                {"name": "B", "indent": 1, "sends": [{"to": 0}]}
            ]}"""
        )
        val loaded = ProjectStore(dir).load(48000) { null }!!.project
        assertEquals(listOf(0, 1), loaded.tracks.map { it.indent })
        // B plays into A, so A may not send to B; B's send to its own folder is fine.
        assertTrue(loaded.tracks[0].sends.isEmpty())
        assertEquals(listOf(loaded.tracks[0].id), loaded.tracks[1].sends.map { it.target })
    }
}
