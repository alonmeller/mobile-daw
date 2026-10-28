package com.alonmeller.mobiledaw.model

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The timeline's zoom: how many dp a second of the song takes across the
 * screen, and how tall each track is.
 */
object Zoom {
    /** dp per second at the widest and the closest zoom; the widest fits a 20-minute song on a phone. */
    const val MIN_DP_PER_SECOND = 0.5f
    const val MAX_DP_PER_SECOND = 600f
    const val DEFAULT_DP_PER_SECOND = 60f

    /** Track heights in dp: the lowest, the highest, and the steps the zoom buttons walk through. */
    const val MIN_TRACK_HEIGHT = 56
    const val MAX_TRACK_HEIGHT = 240
    val TRACK_HEIGHT_STEPS = listOf(56, 72, 92, 120, 160, 200, 240)

    /** How far the fingers must spread or close, about 6%, before a pinch starts to zoom. */
    private const val DECIDE = 0.06f

    /**
     * The zoom that shows a song [frames] long at [sampleRate] across
     * [widthDp], with a little room after its end: REAPER's "zoom out project".
     */
    fun fit(frames: Long, sampleRate: Int, widthDp: Float): Float {
        val seconds = max(frames, 1L).toDouble() / sampleRate
        return (widthDp * 0.94 / seconds).toFloat().coerceIn(MIN_DP_PER_SECOND, MAX_DP_PER_SECOND)
    }

    /** The next track height [up] or down the steps from [height]. */
    fun stepHeight(height: Int, up: Boolean): Int =
        if (up) TRACK_HEIGHT_STEPS.firstOrNull { it > height } ?: MAX_TRACK_HEIGHT
        else TRACK_HEIGHT_STEPS.lastOrNull { it < height } ?: MIN_TRACK_HEIGHT

    /** What a two-finger pinch zooms: time, the tracks' height, or both. */
    enum class Axes(val time: Boolean, val tracks: Boolean) { Time(true, false), Tracks(false, true), Both(true, true) }

    /**
     * Decides what a pinch zooms once the fingers have clearly spread or
     * closed: sideways zooms time, up and down sizes the tracks, and along a
     * slant both. The spans are how far apart the fingers are across and
     * down, when the pinch began ([startX], [startY]) and now ([x], [y]); a
     * span under [minSpan] is too short to tell from a shaking finger.
     * Null while it is too early to tell.
     */
    fun pinchAxes(startX: Float, startY: Float, x: Float, y: Float, minSpan: Float): Axes? {
        val across = if (min(startX, x) >= minSpan) abs(ln(x / startX)) else 0f
        val down = if (min(startY, y) >= minSpan) abs(ln(y / startY)) else 0f
        if (max(across, down) < DECIDE) return null
        return when {
            across > down * 2 -> Axes.Time
            down > across * 2 -> Axes.Tracks
            else -> Axes.Both
        }
    }

    /** How much a span grew from [before] to [now], or 1 when either is too short to measure. */
    fun scale(before: Float, now: Float, minSpan: Float): Float = if (min(before, now) >= minSpan) now / before else 1f
}
