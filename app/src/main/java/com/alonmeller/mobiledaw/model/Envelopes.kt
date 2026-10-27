package com.alonmeller.mobiledaw.model

/** Editing of automation envelopes: lists of points kept sorted by frame. */
object Envelopes {
    /** Adds a point, replacing one already at the same frame. */
    fun add(points: List<EnvPoint>, point: EnvPoint): List<EnvPoint> =
        (points.filterNot { it.frame == point.frame } + point).sortedBy { it.frame }

    /** Moves point [index], keeping it between its neighbours so the order never changes. */
    fun move(points: List<EnvPoint>, index: Int, frame: Long, value: Float): List<EnvPoint> {
        if (index !in points.indices) return points
        val min = if (index > 0) points[index - 1].frame + 1 else 0L
        val max = if (index < points.lastIndex) points[index + 1].frame - 1 else Long.MAX_VALUE
        val clamped = frame.coerceIn(min, maxOf(min, max))
        return points.toMutableList().apply { this[index] = EnvPoint(clamped, value) }
    }

    fun remove(points: List<EnvPoint>, index: Int): List<EnvPoint> =
        if (index in points.indices) points.filterIndexed { i, _ -> i != index } else points

    /** Value at [frame], linear between points; [fallback] when there are none. */
    fun valueAt(points: List<EnvPoint>, frame: Long, fallback: Float): Float {
        if (points.isEmpty()) return fallback
        if (frame <= points.first().frame) return points.first().value
        if (frame >= points.last().frame) return points.last().value
        val i = points.indexOfFirst { it.frame > frame }
        val a = points[i - 1]
        val b = points[i]
        val t = (frame - a.frame).toFloat() / (b.frame - a.frame)
        return a.value + (b.value - a.value) * t
    }
}
