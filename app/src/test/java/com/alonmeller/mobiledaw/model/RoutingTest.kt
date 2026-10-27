package com.alonmeller.mobiledaw.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {
    private fun track(name: String, indent: Int = 0, sends: List<Send> = emptyList(), items: Boolean = false) =
        Track(
            Ids.next(), name, 0, indent = indent, sends = sends,
            items = if (items) listOf(Item(Ids.next(), 1, 0, 0, 1000)) else emptyList(),
        )

    private fun project(vararg tracks: Track) = Project(tracks = tracks.toList())

    private fun Project.names() = tracks.map { "${"  ".repeat(it.indent)}${it.name}" }

    private fun Project.id(name: String) = tracks.first { it.name == name }.id

    @Test
    fun parentsFollowTheIndents() {
        // Drums > Kick, Snare; Vocal; Bus > Inner > Deep
        val p = project(
            track("Drums"), track("Kick", 1), track("Snare", 1), track("Vocal"),
            track("Bus"), track("Inner", 1), track("Deep", 2),
        )
        assertArrayEquals(intArrayOf(-1, 0, 0, -1, -1, 4, 5), Routing.parents(p.tracks))
        assertEquals(3, Routing.blockEnd(p.tracks, 0))
        assertEquals(7, Routing.blockEnd(p.tracks, 4))
        assertEquals(2, Routing.blockEnd(p.tracks, 1))
        assertTrue(Routing.isFolder(p.tracks, 0))
        assertFalse(Routing.isFolder(p.tracks, 3))
    }

    @Test
    fun indentMakesTheTrackAboveAFolder() {
        val p = project(track("A"), track("B"), track("C"))
        val once = Routing.indent(p, p.id("B"))!!
        assertEquals(listOf("A", "  B", "C"), once.names())
        // B is the first track in A's folder, so it cannot go deeper.
        assertNull(Routing.indent(once, once.id("B")))
        // C joins A's folder after B.
        val twice = Routing.indent(once, once.id("C"))!!
        assertEquals(listOf("A", "  B", "  C"), twice.names())
        assertNull(Routing.indent(p, p.id("A")))
    }

    @Test
    fun indentTakesTheFolderAlong() {
        val p = project(track("A"), track("F"), track("x", 1), track("y", 1), track("B"))
        assertEquals(listOf("A", "  F", "    x", "    y", "B"), Routing.indent(p, p.id("F"))!!.names())
    }

    @Test
    fun outdentMovesBelowTheFolder() {
        val p = project(track("F"), track("a", 1), track("b", 1), track("b1", 2), track("c", 1), track("Z"))
        val out = Routing.outdent(p, p.id("b"))!!
        assertEquals(listOf("F", "  a", "  c", "b", "  b1", "Z"), out.names())
        assertNull(Routing.outdent(p, p.id("F")))
        // Out of a nested folder, into the one around it.
        val nested = project(track("F"), track("G", 1), track("g", 2), track("h", 1))
        assertEquals(listOf("F", "  G", "  g", "  h"), Routing.outdent(nested, nested.id("g"))!!.names())
    }

    @Test
    fun wrapPutsTheTrackInANewFolder() {
        val p = project(track("A"), track("B"), track("b", 1), track("C"))
        val folder = track("Folder")
        val wrapped = Routing.wrapInFolder(p, p.id("B"), folder)!!
        assertEquals(listOf("A", "Folder", "  B", "    b", "C"), wrapped.names())
    }

    @Test
    fun moveSwapsWithTheNextTrackInTheSameFolder() {
        val p = project(track("A"), track("F"), track("x", 1), track("y", 1), track("B"))
        assertEquals(listOf("F", "  x", "  y", "A", "B"), Routing.move(p, p.id("A"), 1)!!.names())
        assertEquals(listOf("A", "B", "F", "  x", "  y"), Routing.move(p, p.id("B"), -1)!!.names())
        assertEquals(listOf("A", "F", "  y", "  x", "B"), Routing.move(p, p.id("x"), 1)!!.names())
        // At the edges of a folder or of the song, nothing moves.
        assertNull(Routing.move(p, p.id("x"), -1))
        assertNull(Routing.move(p, p.id("y"), 1))
        assertNull(Routing.move(p, p.id("A"), -1))
        assertNull(Routing.move(p, p.id("B"), 1))
    }

    @Test
    fun removingAFolderKeepsItsTracks() {
        val bus = track("Bus")
        val p = project(track("A"), track("F", sends = emptyList()), track("x", 1), track("x1", 2), track("y", 1), bus)
            .let { it.updateTrack(it.id("A")) { t -> t.copy(sends = listOf(Send(Ids.next(), it.id("F")))) } }
        val removed = Routing.removeTrack(p, p.id("F"))
        assertEquals(listOf("A", "x", "  x1", "y", "Bus"), removed.names())
        assertTrue("sends to a deleted track go", removed.tracks[0].sends.isEmpty())
    }

    @Test
    fun insertAfterSkipsTheFolder() {
        val p = project(track("F"), track("x", 1), track("B"))
        assertEquals(listOf("F", "  x", "Copy", "B"), Routing.insertAfter(p, p.id("F"), track("Copy")).names())
        assertEquals(listOf("F", "  x", "  Copy", "B"), Routing.insertAfter(p, p.id("x"), track("Copy")).names())
    }

    @Test
    fun sendsNeverLoop() {
        val p = project(track("Vocal"), track("Reverb"), track("Delay"))
        val toReverb = Routing.addSend(p, p.id("Vocal"), p.id("Reverb"))!!
        val chain = Routing.addSend(toReverb, p.id("Reverb"), p.id("Delay"))!!
        assertNull("delay back to the vocal loops", Routing.addSend(chain, p.id("Delay"), p.id("Vocal")))
        assertNull(Routing.addSend(chain, p.id("Vocal"), p.id("Vocal")))
        // The vocal already sends to the reverb; the delay hears both, so it can send to neither.
        assertEquals(listOf("Delay"), Routing.sendTargets(chain, p.id("Vocal")).map { it.name })
        assertTrue(Routing.sendTargets(chain, p.id("Delay")).isEmpty())
        assertTrue(Routing.reaches(chain, p.id("Vocal"), p.id("Delay")))
        assertFalse(Routing.reaches(chain, p.id("Delay"), p.id("Vocal")))
        assertEquals(listOf(p.id("Vocal")), Routing.receives(chain, p.id("Reverb")).map { it.first.id })
    }

    @Test
    fun aFolderCannotSendIntoItsOwnTracks() {
        val p = project(track("F"), track("x", 1))
        assertNull(Routing.addSend(p, p.id("F"), p.id("x")))
        // A track may send to its own folder: both paths lead the same way.
        assertNotNull(Routing.addSend(p, p.id("x"), p.id("F")))
        // Indenting A would put it in Bus's folder, playing into Bus, which sends to A.
        val q = project(track("Bus"), track("A"))
        val sent = Routing.addSend(q, q.id("Bus"), q.id("A"))!!
        assertNull(Routing.indent(sent, sent.id("A")))
        assertNotNull(Routing.indent(q, q.id("A")))
    }

    @Test
    fun hiddenTracksAreInCollapsedFolders() {
        val p = project(
            track("F").copy(collapsed = true), track("x", 1), track("x1", 2), track("G"),
            track("H"), track("h", 1).copy(collapsed = true), track("h1", 2), track("h2", 1),
        )
        assertEquals(setOf("x", "x1", "h1"), Routing.hidden(p.tracks).map { id -> p.tracks.first { it.id == id }.name }.toSet())
    }

    @Test
    fun audibleFollowsTheSound() {
        val p0 = project(
            track("Folder"), track("Kick", 1, items = true), track("Empty"),
            track("Bus"), track("Vocal", items = true), track("Muted bus").copy(mute = true),
        )
        val p = Routing.addSend(Routing.addSend(p0, p0.id("Vocal"), p0.id("Bus"))!!, p0.id("Vocal"), p0.id("Muted bus"))!!
        assertEquals(listOf(true, true, false, true, true, false), Routing.audible(p).toList())
        val mutedSend = p.updateTrack(p.id("Vocal")) { t -> t.copy(sends = t.sends.map { it.copy(mute = true) }) }
        assertFalse(Routing.audible(mutedSend)[3])
        val mutedFolder = p.updateTrack(p.id("Folder")) { it.copy(mute = true) }
        assertFalse(Routing.audible(mutedFolder)[0])
    }

    @Test
    fun normalizeRepairsBadRouting() {
        val a = track("A", indent = 2)
        val b = track("B", indent = 3)
        val c = track("C", indent = 0)
        val bad = project(a, b, c).let { p ->
            p.copy(tracks = p.tracks.map { t ->
                when (t.name) {
                    "A" -> t.copy(sends = listOf(Send(1, c.id), Send(2, t.id), Send(3, 999_999)))
                    "C" -> t.copy(sends = listOf(Send(4, a.id)))
                    else -> t
                }
            })
        }
        val fixed = Routing.normalize(bad)
        assertEquals(listOf("A", "  B", "C"), fixed.names())
        assertEquals("the first send stays, the loop-closing and broken ones go", listOf(1L), fixed.tracks[0].sends.map { it.id })
        assertTrue(fixed.tracks[2].sends.isEmpty())
        assertFalse(Routing.hasLoop(fixed))
        assertTrue(Routing.hasLoop(bad))
    }
}
