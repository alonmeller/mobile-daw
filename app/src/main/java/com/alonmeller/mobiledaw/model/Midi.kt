package com.alonmeller.mobiledaw.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/** MIDI items: the notes they play on the timeline, and the note edits of the piano roll. */
object Midi {
    /** A new MIDI item whose pattern repeats every [length] frames. */
    fun newItem(position: Long, length: Long, notes: List<Note> = emptyList()) =
        Item(Ids.next(), sourceId = -1, position = position, offset = 0, length = length, notes = notes, loopLength = length)

    /** Length of the pattern that the piano roll shows. */
    fun patternLength(item: Item): Long = if (item.loopLength > 0) item.loopLength else item.offset + item.length

    /** How far the right edge may be dragged: forever for a looping pattern. */
    fun sourceLength(item: Item): Long = if (item.loopLength > 0) Long.MAX_VALUE / 4 else item.offset + item.length

    /**
     * The notes [item] plays, in timeline frames and sorted by start: its
     * pattern repeated every loopLength, cut to the item's window. Notes are
     * shortened at the end of the item and of each pattern repeat, and notes
     * that start before the window are not played, as in REAPER.
     */
    fun timelineNotes(item: Item): List<Note> {
        val notes = item.notes ?: return emptyList()
        if (notes.isEmpty() || item.length <= 0) return emptyList()
        val windowStart = item.offset
        val windowEnd = item.offset + item.length
        val out = ArrayList<Note>()
        fun emit(sourceStart: Long, note: Note, limit: Long) {
            if (sourceStart < windowStart || sourceStart >= windowEnd) return
            val length = minOf(note.length, limit - sourceStart, windowEnd - sourceStart).coerceAtLeast(1)
            out += Note(item.position + sourceStart - windowStart, length, note.pitch, note.velocity)
        }
        val loop = item.loopLength
        if (loop > 0) {
            for (cycle in Math.floorDiv(windowStart, loop)..Math.floorDiv(windowEnd - 1, loop)) {
                val base = cycle * loop
                for (n in notes) if (n.start in 0 until loop) emit(base + n.start, n, base + loop)
            }
        } else {
            for (n in notes) emit(n.start, n, Long.MAX_VALUE)
        }
        out.sortBy { it.start }
        return out
    }

    fun sorted(notes: List<Note>): List<Note> = notes.sortedWith(compareBy({ it.start }, { it.pitch }))

    /**
     * Adds [note]. Notes of the same pitch that start inside it are replaced,
     * and one that is still sounding when it starts is shortened to end there.
     */
    fun add(notes: List<Note>, note: Note): List<Note> {
        val kept = notes.mapNotNull { n ->
            when {
                n.pitch != note.pitch -> n
                n.start >= note.start && n.start < note.end -> null
                n.start < note.start && n.end > note.start -> n.copy(length = note.start - n.start)
                else -> n
            }
        }
        return sorted(kept + note)
    }

    /** The note of [pitch] sounding at [frame], if any. */
    fun noteAt(notes: List<Note>, frame: Long, pitch: Int): Note? =
        notes.lastOrNull { it.pitch == pitch && frame >= it.start && frame < it.end }

    /**
     * Moves note starts to the nearest multiple of [grid] frames. In a
     * pattern of [loop] frames, a note pulled onto the loop end wraps around
     * to the start, where it is heard.
     */
    fun quantize(notes: List<Note>, grid: Double, loop: Long): List<Note> {
        if (grid <= 0.0) return notes
        val moved = notes.map { n ->
            var start = ((n.start / grid).roundToLong() * grid).roundToLong().coerceAtLeast(0)
            if (loop > 0 && start >= loop) start -= loop
            n.copy(start = start)
        }
        // Two notes pulled onto the same step become one, the louder.
        return sorted(moved.groupBy { it.start to it.pitch }.values.map { same -> same.maxBy { it.velocity } })
    }

    /** Rescales every time in a MIDI item by [ratio], so it keeps its place in bars when the tempo changes. */
    fun scaleTime(item: Item, ratio: Double): Item {
        fun scale(frames: Long) = (frames * ratio).roundToLong()
        return item.copy(
            position = scale(item.position),
            offset = scale(item.offset),
            length = scale(item.length).coerceAtLeast(1),
            loopLength = scale(item.loopLength),
            notes = item.notes?.map { it.copy(start = scale(it.start), length = scale(it.length).coerceAtLeast(1)) },
        )
    }

    /** Changes the pattern length; an item showing exactly one pattern grows or shrinks with it. */
    fun setPatternLength(item: Item, length: Long): Item {
        val showsOnePattern = item.offset == 0L && item.length == patternLength(item)
        return item.copy(loopLength = length, length = if (showsOnePattern) length else item.length)
    }

    /**
     * Puts [recorded] notes, timed on the timeline, into [item]'s pattern,
     * wrapping them around its loop: recording over a looping pattern
     * overdubs it, as in GarageBand's cycle recording. Notes outside the
     * item are ignored.
     */
    fun mergeRecorded(item: Item, recorded: List<Note>): Item {
        val loop = item.loopLength
        var notes = item.notes ?: emptyList()
        for (n in recorded) {
            if (n.start < item.position || n.start >= item.end) continue
            var start = n.start - item.position + item.offset
            var length = n.length
            if (loop > 0) {
                start = Math.floorMod(start, loop)
                length = length.coerceAtMost(loop - start)
            }
            notes = add(notes, n.copy(start = start, length = length.coerceAtLeast(1)))
        }
        return item.copy(notes = notes)
    }

    /** First frame of the bar containing [frame], and of the bar after it. */
    fun barAround(frame: Long, barFrames: Double): Pair<Long, Long> {
        val bar = floor(frame / barFrames)
        return (bar * barFrames).roundToLong() to ((bar + 1) * barFrames).roundToLong()
    }

    /** Frame where the bar at or after [frame] starts. */
    fun barEndAfter(frame: Long, barFrames: Double): Long = (ceil(frame / barFrames) * barFrames).roundToLong()
}
