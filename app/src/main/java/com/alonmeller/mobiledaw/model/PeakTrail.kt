package com.alonmeller.mobiledaw.model

/**
 * Levels along the timeline, in frame order: each [add] is the loudest
 * level up to its frame since the one before. Kept in plain arrays, so a
 * long recording costs no garbage and is drawn from where the view starts.
 */
class PeakTrail {
    var size = 0
        private set
    private var frames = LongArray(1024)
    private var peaks = FloatArray(1024)

    fun frame(i: Int): Long = frames[i]
    fun peak(i: Int): Float = peaks[i]

    fun add(frame: Long, peak: Float) {
        if (size == frames.size) {
            frames = frames.copyOf(size * 2)
            peaks = peaks.copyOf(size * 2)
        }
        frames[size] = frame
        peaks[size] = peak
        size++
    }

    fun clear() {
        size = 0
    }

    /** The first entry at or after [frame], or [size] when there is none. */
    fun indexAt(frame: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (frames[mid] < frame) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
