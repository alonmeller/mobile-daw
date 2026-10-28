package com.alonmeller.mobiledaw.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZoomTest {
    private val rate = 48_000
    private val minSpan = 40f

    @Test
    fun fitShowsTheWholeSongWithRoomAfterIt() {
        // A minute of song across 600 dp: 9.4 dp a second, so it ends at 94% of the width.
        assertEquals(9.4f, Zoom.fit(60L * rate, rate, 600f), 0.001f)
        // A very long song stops at the widest zoom; a click of a song at the closest.
        assertEquals(Zoom.MIN_DP_PER_SECOND, Zoom.fit(3600L * rate, rate, 600f), 0f)
        assertEquals(Zoom.MAX_DP_PER_SECOND, Zoom.fit(10L, rate, 600f), 0f)
        assertEquals(Zoom.MAX_DP_PER_SECOND, Zoom.fit(0L, rate, 600f), 0f)
    }

    @Test
    fun theHeightButtonsWalkTheStepsAndStopAtTheEnds() {
        assertEquals(120, Zoom.stepHeight(92, up = true))
        assertEquals(72, Zoom.stepHeight(92, up = false))
        // A height a pinch left between steps goes to the next step either way.
        assertEquals(120, Zoom.stepHeight(100, up = true))
        assertEquals(92, Zoom.stepHeight(100, up = false))
        assertEquals(Zoom.MAX_TRACK_HEIGHT, Zoom.stepHeight(Zoom.MAX_TRACK_HEIGHT, up = true))
        assertEquals(Zoom.MIN_TRACK_HEIGHT, Zoom.stepHeight(Zoom.MIN_TRACK_HEIGHT, up = false))
        assertEquals(Zoom.MIN_TRACK_HEIGHT, Zoom.TRACK_HEIGHT_STEPS.first())
        assertEquals(Zoom.MAX_TRACK_HEIGHT, Zoom.TRACK_HEIGHT_STEPS.last())
    }

    @Test
    fun aPinchZoomsTheWayTheFingersSpread() {
        // Too early to tell: the fingers have barely moved.
        assertNull(Zoom.pinchAxes(200f, 100f, 205f, 101f, minSpan))
        // Side by side, spreading sideways: time.
        assertEquals(Zoom.Axes.Time, Zoom.pinchAxes(200f, 10f, 260f, 12f, minSpan))
        // One above the other, closing: the tracks.
        assertEquals(Zoom.Axes.Tracks, Zoom.pinchAxes(15f, 160f, 14f, 120f, minSpan))
        // Along a slant, both grow alike: both.
        assertEquals(Zoom.Axes.Both, Zoom.pinchAxes(100f, 100f, 130f, 125f, minSpan))
        // Set at a slant but spread sideways: time only.
        assertEquals(Zoom.Axes.Time, Zoom.pinchAxes(150f, 150f, 190f, 152f, minSpan))
        // A short span is a shaking finger, not a pinch: 10 to 20 dp down is not doubling the tracks.
        assertNull(Zoom.pinchAxes(200f, 10f, 205f, 20f, minSpan))
    }

    @Test
    fun aSpanTooShortToMeasureDoesNotZoom() {
        assertEquals(1.5f, Zoom.scale(100f, 150f, minSpan), 0.0001f)
        assertEquals(1f, Zoom.scale(30f, 150f, minSpan), 0f)
        assertEquals(1f, Zoom.scale(100f, 20f, minSpan), 0f)
    }
}
