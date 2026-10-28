package com.alonmeller.mobiledaw.model

/**
 * REAPER's takes: an item can hold several recordings of the same moment
 * and play one of them. Loop recording makes a take of each pass, and a new
 * recording over an item becomes another take of it, so nothing recorded is
 * lost and the best one can be picked later. Splitting keeps every take in
 * both halves, which is how REAPER comps: split where the best take changes,
 * then pick one in each piece.
 *
 * The take an item plays is mirrored in its sourceId, offset and gain, which
 * the engine, the editor and the exports read; [all] puts the mirror back in
 * its place among the others.
 */
object Takes {
    /** Every take of [item], in order, with the one it plays at [active]. */
    fun all(item: Item): List<Take> {
        val playing = Take(item.sourceId, item.offset, item.gain)
        return if (item.takes.size < 2) listOf(playing)
        else item.takes.mapIndexed { i, t -> if (i == active(item)) playing else t }
    }

    fun count(item: Item): Int = if (item.takes.size < 2) 1 else item.takes.size

    /** Index of the take [item] plays. */
    fun active(item: Item): Int = if (item.takes.size < 2) 0 else item.activeTake.coerceIn(item.takes.indices)

    /** [item] holding [takes] and playing the one at [active]. */
    fun withTakes(item: Item, takes: List<Take>, active: Int): Item {
        require(takes.isNotEmpty()) { "an item needs a take" }
        val a = active.coerceIn(takes.indices)
        val playing = takes[a]
        val many = takes.size > 1
        return item.copy(
            sourceId = playing.sourceId,
            offset = playing.offset,
            gain = playing.gain,
            takes = if (many) takes else emptyList(),
            activeTake = if (many) a else 0,
        )
    }

    /** Plays take [index] instead (REAPER's "set active take"). */
    fun select(item: Item, index: Int): Item = withTakes(item, all(item), index)

    /** The next (+1) or previous (-1) take, going round, like REAPER's T and Shift+T. */
    fun step(item: Item, direction: Int): Item = select(item, Math.floorMod(active(item) + direction, count(item)))

    /** Removes the take [item] plays and plays the next one; null when it was the only take. */
    fun deleteActive(item: Item): Item? {
        val list = all(item)
        if (list.size < 2) return null
        val a = active(item)
        val rest = list.filterIndexed { i, _ -> i != a }
        return withTakes(item, rest, minOf(a, rest.lastIndex))
    }

    /** Keeps only the take [item] plays (REAPER's "crop to active take"). */
    fun crop(item: Item): Item = withTakes(item, listOf(all(item)[active(item)]), 0)

    /** The take [item] plays at [gain], as Normalize sets it; the other takes keep theirs. */
    fun withGain(item: Item, gain: Float): Item =
        if (item.takes.size < 2) item.copy(gain = gain)
        else withTakes(item, all(item).mapIndexed { i, t -> if (i == active(item)) t.copy(gain = gain) else t }, active(item))

    /** Moves where every take starts in its source by [delta] frames, as trimming or splitting does. */
    fun shift(item: Item, delta: Long): Item =
        if (item.takes.size < 2) item.copy(offset = item.offset + delta)
        else withTakes(item, all(item).map { it.copy(offset = it.offset + delta) }, active(item))

    /** How far back into its sources the item's left edge can go: the smallest offset of its takes. */
    fun earliestOffset(item: Item): Long = all(item).minOf { it.offset }

    /** What a finished recording becomes: see [layout]. */
    class Recorded(
        /** Timeline frame and length of the item. */
        val position: Long,
        val length: Long,
        /** Where each take starts in the (padded) source, oldest first. */
        val offsets: List<Long>,
        /** Silence the source needs before and after the captured samples, for passes cut short. */
        val padBefore: Long,
        val padAfter: Long,
    )

    /**
     * Where a finished recording goes. The engine captured [frames] samples,
     * starting as the transport started at timeline frame [start]; what the
     * performer played while hearing that first frame was captured [lead]
     * samples in (the count-in plus the round-trip latency). With the [loop]
     * range on, the transport went from its end back to its start each time.
     *
     * A recording that never went round the loop is one take, from [start].
     * One that did becomes an item over the loop with a take per pass, the
     * newest last. As in REAPER, a pass that does not cover the whole loop
     * (the first, when recording started inside the loop, or the last, cut
     * short by stopping) is dropped when at least one pass is whole, unless
     * [discardPartial] is false. Otherwise those passes stay, silent where
     * nothing was recorded, which the source then needs some silence for.
     * Null when nothing was recorded.
     */
    fun layout(frames: Long, start: Long, lead: Long, loop: Pair<Long, Long>?, discardPartial: Boolean = true): Recorded? {
        // Timeline frames the performer played over, counted from the start.
        val heard = frames - lead
        if (heard < Edits.MIN_LENGTH) return null
        val wraps = loop != null && loop.second > loop.first && start < loop.second
        if (loop == null || !wraps || heard <= loop.second - start) {
            return Recorded(start, heard, listOf(lead), 0, 0)
        }
        val (loopStart, loopEnd) = loop
        val length = loopEnd - loopStart
        val firstPass = loopEnd - start
        // Each pass: where the loop start is in the samples, and whether the pass covers the whole loop.
        val passes = mutableListOf<Pair<Long, Boolean>>()
        if (loopEnd - maxOf(start, loopStart) >= Edits.MIN_LENGTH) {
            passes += (lead + loopStart - start) to (start <= loopStart)
        }
        var passStart = firstPass
        while (passStart < heard) {
            val whole = passStart + length <= heard
            if (whole || heard - passStart >= Edits.MIN_LENGTH) passes += (lead + passStart) to whole
            passStart += length
        }
        val kept = (if (discardPartial && passes.any { it.second }) passes.filter { it.second } else passes).map { it.first }
        if (kept.isEmpty()) return null
        val padBefore = maxOf(0L, -kept.min())
        val padAfter = maxOf(0L, kept.max() + length - frames)
        return Recorded(loopStart, length, kept.map { it + padBefore }, padBefore, padAfter)
    }

    /**
     * Puts [recorded], a new recording, onto a track's [items] the way REAPER
     * does by default: an item it overlaps is split where the recording
     * starts and ends, and the part under it gets the recording's takes, the
     * newest one playing, so the old recording stays one tap away. Where
     * nothing was underneath, the recording becomes an item of its own. With
     * [keepOld] false it replaces what it overlaps instead, like tape.
     * Returns the items and the ids of those that play the recording.
     */
    fun place(items: List<Item>, recorded: Item, keepOld: Boolean): Pair<List<Item>, Set<Long>> {
        if (!keepOld) return Edits.placeOnTop(items, recorded) to setOf(recorded.id)
        val s = recorded.position
        val e = recorded.end
        val newTakes = all(recorded)
        val result = mutableListOf<Item>()
        val holding = mutableSetOf<Long>()
        val covered = mutableListOf<Pair<Long, Long>>()
        for (item in items) {
            if (item.isMidi || item.end <= s || item.position >= e) {
                result += item
                continue
            }
            var under = item
            if (under.position < s) {
                val (left, right) = Edits.split(under, s)!!
                result += left
                under = right
            }
            var after: Item? = null
            if (under.end > e) {
                val (inside, right) = Edits.split(under, e)!!
                under = inside
                after = right
            }
            val delta = under.position - s
            val list = all(under) + newTakes.map { it.copy(offset = it.offset + delta) }
            val withNew = withTakes(under, list, list.lastIndex)
            result += withNew
            holding += withNew.id
            covered += under.position to under.end
            after?.let { result += it }
        }
        var cursor = s
        for ((from, to) in covered.sortedBy { it.first }) {
            if (from - cursor >= Edits.MIN_LENGTH) result += piece(recorded, cursor, from).also { holding += it.id }
            cursor = maxOf(cursor, to)
        }
        if (e - cursor >= Edits.MIN_LENGTH) result += piece(recorded, cursor, e).also { holding += it.id }
        return result.sortedBy { it.position } to holding
    }

    /** The part [from, to) of [recorded] as an item of its own. */
    private fun piece(recorded: Item, from: Long, to: Long): Item {
        if (from == recorded.position && to == recorded.end) return recorded
        return shift(recorded, from - recorded.position).copy(id = Ids.next(), position = from, length = to - from)
    }
}
