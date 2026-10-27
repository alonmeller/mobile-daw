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
 *
 * A MIDI item has [notes] instead of an audio source (its [sourceId] is -1).
 * Its notes are timed from the start of its source, like audio, and when
 * [loopLength] is above 0 that source repeats every [loopLength] frames, so
 * stretching the item repeats the pattern, as REAPER's "loop source" does.
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
    val notes: List<Note>? = null,
    val loopLength: Long = 0,
) {
    val end: Long get() = position + length
    val isMidi: Boolean get() = notes != null
    fun contains(frame: Long) = frame > position && frame < end
}

/**
 * A copy of a track's sound sent to another track, such as a reverb bus, as
 * in REAPER's routing window. A post-fader send follows the track's fader and
 * pan; a pre-fader one leaves before them, so it keeps its level when the
 * track is turned down.
 */
data class Send(
    val id: Long,
    /** The receiving track's id. */
    val target: Long,
    /** Linear gain; the default is -6 dB. */
    val gain: Float = 0.5f,
    val preFader: Boolean = false,
    val mute: Boolean = false,
)

data class Track(
    val id: Long,
    val name: String,
    val colorIndex: Int,
    val gain: Float = 1f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val items: List<Item> = emptyList(),
    val fx: List<Effect> = emptyList(),
    val volumeEnv: List<EnvPoint> = emptyList(),
    val panEnv: List<EnvPoint> = emptyList(),
    /** Set on instrument tracks, which play their MIDI items through it. */
    val instrument: Instrument? = null,
    /** Folder depth, as REAPER keeps it: see [Routing]. */
    val indent: Int = 0,
    /** A folder whose tracks are hidden in the timeline. */
    val collapsed: Boolean = false,
    val sends: List<Send> = emptyList(),
) {
    fun envelope(kind: EnvKind) = if (kind == EnvKind.Volume) volumeEnv else panEnv
    fun withEnvelope(kind: EnvKind, points: List<EnvPoint>) =
        if (kind == EnvKind.Volume) copy(volumeEnv = points) else copy(panEnv = points)
}

/** Effect chains are addressed by track id, or by [MASTER] for the master bus. */
const val MASTER = -2L

data class Marker(val id: Long, val position: Long, val name: String)

/** Everything that undo/redo covers. */
data class Project(
    val tracks: List<Track> = emptyList(),
    val markers: List<Marker> = emptyList(),
    val tempo: Double = 120.0,
    val loopStart: Long = 0,
    val loopEnd: Long = 0,
    val loopOn: Boolean = false,
    val masterFx: List<Effect> = emptyList(),
    val masterGain: Float = 1f,
) {
    fun fxChain(target: Long): List<Effect> =
        if (target == MASTER) masterFx else tracks.firstOrNull { it.id == target }?.fx ?: emptyList()

    fun withFxChain(target: Long, chain: List<Effect>): Project =
        if (target == MASTER) copy(masterFx = chain) else updateTrack(target) { it.copy(fx = chain) }

    val allEffects: List<Effect> get() = masterFx + tracks.flatMap { it.fx }

    val instruments: List<Instrument> get() = tracks.mapNotNull { it.instrument }

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
