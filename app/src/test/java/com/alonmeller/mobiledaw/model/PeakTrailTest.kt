package com.alonmeller.mobiledaw.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PeakTrailTest {
    @Test
    fun growsAndFindsWhereTheViewStarts() {
        val trail = PeakTrail()
        // More than its first arrays hold.
        for (i in 0 until 5000) trail.add(i * 100L, (i % 10) / 10f)
        assertEquals(5000, trail.size)
        assertEquals(499_900L, trail.frame(4999))
        assertEquals(0.9f, trail.peak(4999), 0f)
        assertEquals(0, trail.indexAt(-5))
        assertEquals(0, trail.indexAt(0))
        assertEquals(3, trail.indexAt(250))
        assertEquals(3, trail.indexAt(300))
        assertEquals(5000, trail.indexAt(1_000_000))
        trail.clear()
        assertEquals(0, trail.size)
        assertEquals(0, trail.indexAt(300))
    }
}
