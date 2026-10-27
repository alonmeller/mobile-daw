package com.alonmeller.mobiledaw.model

import java.util.concurrent.atomic.AtomicLong

/** Unique ids for tracks, items and markers within a session. */
object Ids {
    private val counter = AtomicLong(1)
    fun next(): Long = counter.getAndIncrement()
    fun ensureAbove(value: Long) {
        while (true) {
            val current = counter.get()
            if (current > value || counter.compareAndSet(current, value + 1)) return
        }
    }
}

/**
 * A media item: the window [offset, offset + length) of an audio source,
 * placed at [position] on the timeline. All times are in sample frames.
 */
data class Item(
    val id: Long,
    val sourceId: Int,
    val position: Long,
    val offset: Long,
    val length: Long,
    val fadeIn: Long = 0,
    val fadeOut: Long = 0,
    val gain: Float = 1f,
    val mute: Boolean = false,
) {
    val end: Long get() = position + length
    fun contains(frame: Long) = frame > position && frame < end
}

data class Track(
    val id: Long,
    val name: String,
    val colorIndex: Int,
    val gain: Float = 1f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val items: List<Item> = emptyList(),
)

data class Marker(val id: Long, val position: Long, val name: String)

/** Everything that undo/redo covers. */
data class Project(
    val tracks: List<Track> = emptyList(),
    val markers: List<Marker> = emptyList(),
    val tempo: Double = 120.0,
    val loopStart: Long = 0,
    val loopEnd: Long = 0,
    val loopOn: Boolean = false,
) {
    fun findItem(itemId: Long): Pair<Track, Item>? {
        for (t in tracks) for (i in t.items) if (i.id == itemId) return t to i
        return null
    }

    fun updateTrack(trackId: Long, change: (Track) -> Track) =
        copy(tracks = tracks.map { if (it.id == trackId) change(it) else it })

    fun updateItem(itemId: Long, change: (Item) -> Item) =
        copy(tracks = tracks.map { t ->
            if (t.items.none { it.id == itemId }) t
            else t.copy(items = t.items.map { if (it.id == itemId) change(it) else it })
        })

    fun removeItems(ids: Set<Long>) =
        copy(tracks = tracks.map { t -> t.copy(items = t.items.filterNot { it.id in ids }) })

    val endFrame: Long get() = tracks.flatMap { it.items }.maxOfOrNull { it.end } ?: 0L
}
