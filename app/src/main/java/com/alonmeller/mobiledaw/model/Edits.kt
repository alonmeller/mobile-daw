package com.alonmeller.mobiledaw.model

import kotlin.math.roundToLong

/** Pure editing operations on the project model, in the spirit of REAPER's item editing. */
object Edits {
    /** Shortest item that trimming may leave behind, in frames. */
    const val MIN_LENGTH = 256L

    fun snap(frame: Long, gridFrames: Double, enabled: Boolean): Long {
        if (!enabled || gridFrames <= 0.0) return frame.coerceAtLeast(0)
        return ((frame / gridFrames).roundToLong() * gridFrames).roundToLong().coerceAtLeast(0)
    }

    fun clampFades(item: Item): Item {
        val fadeIn = item.fadeIn.coerceIn(0, item.length)
        val fadeOut = item.fadeOut.coerceIn(0, item.length - fadeIn)
        return item.copy(fadeIn = fadeIn, fadeOut = fadeOut)
    }

    /** Splits [item] at timeline frame [at]; null when [at] is not strictly inside it. Both halves keep every take. */
    fun split(item: Item, at: Long): Pair<Item, Item>? {
        if (!item.contains(at)) return null
        val leftLen = at - item.position
        val left = clampFades(item.copy(length = leftLen, fadeOut = 0))
        val right = clampFades(
            Takes.shift(item, leftLen).copy(
                id = Ids.next(),
                position = at,
                length = item.length - leftLen,
                fadeIn = 0,
            )
        )
        return left to right
    }

    /**
     * REAPER's split: splits the selected items under [at], or every item
     * under [at] when none of the selected ones is there.
     * Returns the new project and the ids of the right-hand halves.
     */
    fun splitAt(project: Project, at: Long, selected: Set<Long>): Pair<Project, Set<Long>> {
        val under = project.tracks.flatMap { it.items }.filter { it.contains(at) }
        val targets = under.filter { it.id in selected }.ifEmpty { under }.map { it.id }.toSet()
        if (targets.isEmpty()) return project to emptySet()
        val rightIds = mutableSetOf<Long>()
        val tracks = project.tracks.map { t ->
            if (t.items.none { it.id in targets }) t
            else t.copy(items = t.items.flatMap { item ->
                if (item.id !in targets) listOf(item)
                else split(item, at)?.let { (l, r) -> rightIds += r.id; listOf(l, r) } ?: listOf(item)
            })
        }
        return project.copy(tracks = tracks) to rightIds
    }

    /** Moves the left edge to [newPosition], revealing or hiding source audio (of every take). */
    fun trimLeft(item: Item, newPosition: Long): Item {
        val minPos = item.position - Takes.earliestOffset(item) // cannot reveal before a source's start
        val maxPos = item.end - MIN_LENGTH
        val pos = newPosition.coerceIn(minPos.coerceAtLeast(0), maxOf(minPos.coerceAtLeast(0), maxPos))
        val delta = pos - item.position
        return clampFades(Takes.shift(item, delta).copy(position = pos, length = item.length - delta))
    }

    /** Moves the right edge to [newEnd], limited by the source length. */
    fun trimRight(item: Item, newEnd: Long, sourceLength: Long): Item {
        val maxEnd = item.position + (sourceLength - item.offset)
        val end = newEnd.coerceIn(item.position + MIN_LENGTH, maxOf(item.position + MIN_LENGTH, maxEnd))
        return clampFades(item.copy(length = end - item.position))
    }

    fun setFadeIn(item: Item, frames: Long) =
        item.copy(fadeIn = frames.coerceIn(0, item.length - item.fadeOut))

    fun setFadeOut(item: Item, frames: Long) =
        item.copy(fadeOut = frames.coerceIn(0, item.length - item.fadeIn))

    /**
     * Puts [newItem] on top of [items]: anything it overlaps is trimmed,
     * split or removed, the way a new recording replaces what was under it.
     */
    fun placeOnTop(items: List<Item>, newItem: Item): List<Item> {
        val s = newItem.position
        val e = newItem.end
        val result = mutableListOf<Item>()
        for (item in items) {
            when {
                item.end <= s || item.position >= e -> result += item
                item.position >= s && item.end <= e -> Unit // fully covered
                item.position < s && item.end > e -> {
                    val (left, rest) = split(item, s)!!
                    result += left
                    if (item.end - e >= MIN_LENGTH) result += trimLeft(rest, e)
                }
                item.position < s -> result += clampFades(item.copy(length = s - item.position))
                item.end - e >= MIN_LENGTH -> result += trimLeft(item, e)
                else -> Unit // only a sliver would remain
            }
        }
        result += newItem
        return result.sortedBy { it.position }
    }

    /** Copy of [item] placed right after it, like REAPER's duplicate. */
    fun duplicate(item: Item): Item = item.copy(id = Ids.next(), position = item.end)

    /**
     * Start and end frames of the sound in a fresh microphone sample: the
     * silence before and after it is skipped, keeping 5 ms before the first
     * loud sample and 100 ms after the last. An end of 0 means "to the end".
     */
    fun soundRegion(samples: FloatArray, sampleRate: Int, threshold: Float = 0.02f): Pair<Long, Long> {
        val first = samples.indexOfFirst { kotlin.math.abs(it) > threshold }
        if (first < 0) return 0L to 0L
        val last = samples.indexOfLast { kotlin.math.abs(it) > threshold }
        val start = (first - sampleRate / 200).coerceAtLeast(0)
        val end = last + 1 + sampleRate / 10
        return start.toLong() to (if (end >= samples.size) 0L else end.toLong())
    }

    /** Gain that brings [peak] up (or down) to full scale, capped at +20 dB. */
    fun normalizeGain(peak: Float): Float = if (peak <= 1e-4f) 1f else (1f / peak).coerceAtMost(10f)
}
