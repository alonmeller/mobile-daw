package com.alonmeller.mobiledaw.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryTest {
    @Test
    fun undoKeepsAtMostTheLimit() {
        val h = History<Int>(limit = 3)
        for (state in 1..5) h.push(state)
        // Only the last three states before 6 can come back.
        assertEquals(5, h.undo(6))
        assertEquals(4, h.undo(5))
        assertEquals(3, h.undo(4))
        assertNull(h.undo(3))
        assertEquals(4, h.redo(3))
    }

    @Test
    fun loweringTheLimitForgetsTheOldestAtOnce() {
        val h = History<Int>()
        for (state in 1..10) h.push(state)
        h.limit = 2
        assertEquals(10, h.undo(11))
        assertEquals(9, h.undo(10))
        assertNull(h.undo(9))
        // Never below one step.
        h.limit = 0
        assertEquals(1, h.limit)
        h.clear()
        assertFalse(h.canUndo)
    }
}
