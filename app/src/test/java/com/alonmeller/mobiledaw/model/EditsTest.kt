package com.alonmeller.mobiledaw.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditsTest {
    private fun item(position: Long, length: Long, offset: Long = 0) =
        Item(Ids.next(), sourceId = 1, position = position, offset = offset, length = length)

    @Test
    fun splitKeepsAudioContinuous() {
        val (left, right) = Edits.split(item(1000, 4000, offset = 500), 3000)!!
        assertEquals(1000, left.position)
        assertEquals(2000, left.length)
        assertEquals(3000, right.position)
        assertEquals(2500, right.offset)
        assertEquals(2000, right.length)
    }

    @Test
    fun splitOutsideItemIsNull() {
        assertNull(Edits.split(item(1000, 1000), 1000))
        assertNull(Edits.split(item(1000, 1000), 5000))
    }

    @Test
    fun splitAtPrefersSelection() {
        val a = item(0, 10_000)
        val b = item(0, 10_000)
        val p = Project(tracks = listOf(Track(1, "A", 0, items = listOf(a)), Track(2, "B", 1, items = listOf(b))))
        val (onlyA, _) = Edits.splitAt(p, 5000, setOf(a.id))
        assertEquals(2, onlyA.tracks[0].items.size)
        assertEquals(1, onlyA.tracks[1].items.size)
        val (both, right) = Edits.splitAt(p, 5000, emptySet())
        assertEquals(2, both.tracks[1].items.size)
        assertEquals(2, right.size)
    }

    @Test
    fun trimLeftCannotRevealBeforeSourceStart() {
        val t = Edits.trimLeft(item(1000, 4000, offset = 200), 0)
        assertEquals(800, t.position)
        assertEquals(0, t.offset)
        assertEquals(4200, t.length)
    }

    @Test
    fun trimRightLimitedBySource() {
        val t = Edits.trimRight(item(0, 1000), 99_999, sourceLength = 3000)
        assertEquals(3000, t.length)
    }

    @Test
    fun fadesNeverExceedLength() {
        val i = Edits.setFadeIn(item(0, 1000), 5000)
        assertEquals(1000, i.fadeIn)
        val j = Edits.setFadeOut(i, 500)
        assertEquals(0, j.fadeOut)
    }

    @Test
    fun placeOnTopCarvesExistingItems() {
        val old = item(0, 10_000)
        val take = item(4000, 2000)
        val result = Edits.placeOnTop(listOf(old), take)
        assertEquals(3, result.size)
        assertEquals(4000, result[0].length)
        assertEquals(take.id, result[1].id)
        assertEquals(6000, result[2].position)
        assertEquals(6000, result[2].offset)
        assertEquals(4000, result[2].length)
    }

    @Test
    fun placeOnTopRemovesCoveredItems() {
        val result = Edits.placeOnTop(listOf(item(1000, 500)), item(0, 5000))
        assertEquals(1, result.size)
    }

    @Test
    fun snapRoundsToGrid() {
        assertEquals(12_000, Edits.snap(11_000, 6000.0, true))
        assertEquals(11_000, Edits.snap(11_000, 6000.0, false))
    }

    @Test
    fun historyUndoRedo() {
        val h = History<Int>()
        h.push(1)
        assertEquals(1, h.undo(2))
        assertEquals(2, h.redo(1))
        assertTrue(h.canUndo)
    }
}
