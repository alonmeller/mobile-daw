package com.alonmeller.mobiledaw.model

/**
 * REAPER's routing: folders and sends.
 *
 * Folders come from the track order, as in REAPER: a track one level deeper
 * ([Track.indent]) than the track above it is the first track in that track's
 * folder. Every track plays into its folder, the folder into its own folder,
 * and so on up to the master; a folder's fader, effects, mute and solo work on
 * everything in it. Sends copy a track's sound to other tracks, such as a
 * reverb bus. Nothing may feed a track's sound back into itself, so edits that
 * would are refused (they return null).
 */
object Routing {
    /** The folder each track plays into, as a track index, or -1 for the master. */
    fun parents(tracks: List<Track>): IntArray {
        val result = IntArray(tracks.size) { -1 }
        val open = ArrayList<Int>() // the latest track at each depth above
        tracks.forEachIndexed { i, t ->
            while (open.size > t.indent) open.removeAt(open.size - 1)
            result[i] = open.lastOrNull() ?: -1
            open.add(i)
        }
        return result
    }

    /** One past the last track in the folder of track [index]; index + 1 when it is not a folder. */
    fun blockEnd(tracks: List<Track>, index: Int): Int {
        var end = index + 1
        while (end < tracks.size && tracks[end].indent > tracks[index].indent) end++
        return end
    }

    fun isFolder(tracks: List<Track>, index: Int): Boolean =
        index + 1 < tracks.size && tracks[index + 1].indent > tracks[index].indent

    /** Where each track's sound goes, by index: its folder, if any, then its sends' tracks. */
    private fun outputs(project: Project, withMutedSends: Boolean = true): List<List<Int>> {
        val parents = parents(project.tracks)
        val index = project.tracks.withIndex().associate { it.value.id to it.index }
        return project.tracks.mapIndexed { i, t ->
            buildList {
                if (parents[i] >= 0) add(parents[i])
                t.sends.forEach { s -> if (withMutedSends || !s.mute) index[s.target]?.let { add(it) } }
            }
        }
    }

    private fun reaches(outputs: List<List<Int>>, from: Int, to: Int): Boolean {
        val seen = BooleanArray(outputs.size)
        val stack = ArrayDeque<Int>()
        stack.addLast(from)
        while (stack.isNotEmpty()) {
            for (j in outputs[stack.removeLast()]) {
                if (j == to) return true
                if (!seen[j]) {
                    seen[j] = true
                    stack.addLast(j)
                }
            }
        }
        return false
    }

    /** True when sound from track [from] reaches track [to] through folders and sends. */
    fun reaches(project: Project, from: Long, to: Long): Boolean {
        val start = project.tracks.indexOfFirst { it.id == from }
        val goal = project.tracks.indexOfFirst { it.id == to }
        return start >= 0 && goal >= 0 && reaches(outputs(project), start, goal)
    }

    /** True when some track's sound comes back to itself. */
    fun hasLoop(project: Project): Boolean {
        val outputs = outputs(project)
        return outputs.indices.any { reaches(outputs, it, it) }
    }

    private fun checked(project: Project): Project? = if (hasLoop(project)) null else project

    /** The tracks [trackId] can send to: those it does not send to yet whose sound never comes back to it. */
    fun sendTargets(project: Project, trackId: Long): List<Track> {
        val i = project.tracks.indexOfFirst { it.id == trackId }
        if (i < 0) return emptyList()
        val track = project.tracks[i]
        val outputs = outputs(project)
        return project.tracks.filterIndexed { j, t ->
            j != i && track.sends.none { it.target == t.id } && !reaches(outputs, j, i)
        }
    }

    /** The sends that arrive at [trackId], with the track each one comes from. */
    fun receives(project: Project, trackId: Long): List<Pair<Track, Send>> =
        project.tracks.flatMap { t -> t.sends.filter { it.target == trackId }.map { t to it } }

    /** Adds a send from [from] to [to] at the default level; null when it would loop. */
    fun addSend(project: Project, from: Long, to: Long): Project? {
        if (from == to || project.tracks.none { it.id == to } || reaches(project, to, from)) return null
        return project.updateTrack(from) { it.copy(sends = it.sends + Send(Ids.next(), to)) }
    }

    fun updateSend(project: Project, trackId: Long, sendId: Long, change: (Send) -> Send): Project =
        project.updateTrack(trackId) { t -> t.copy(sends = t.sends.map { if (it.id == sendId) change(it) else it }) }

    fun removeSend(project: Project, trackId: Long, sendId: Long): Project =
        project.updateTrack(trackId) { t -> t.copy(sends = t.sends.filterNot { it.id == sendId }) }

    /**
     * REAPER's indent: moves a track, with the tracks in its folder, into the
     * track above it at the same depth, which becomes (or already is) a folder.
     * Null when there is no such track, or when the move would make a loop.
     */
    fun indent(project: Project, trackId: Long): Project? {
        val tracks = project.tracks
        val i = tracks.indexOfFirst { it.id == trackId }
        if (i <= 0 || tracks[i - 1].indent < tracks[i].indent) return null
        val end = blockEnd(tracks, i)
        return checked(project.copy(tracks = tracks.mapIndexed { j, t -> if (j in i until end) t.copy(indent = t.indent + 1) else t }))
    }

    /** Moves a track, with the tracks in its folder, out of its folder to just below it; null when it is in none. */
    fun outdent(project: Project, trackId: Long): Project? {
        val tracks = project.tracks
        val i = tracks.indexOfFirst { it.id == trackId }
        if (i < 0 || tracks[i].indent == 0) return null
        val parent = parents(tracks)[i]
        if (parent < 0) return null
        val end = blockEnd(tracks, i)
        val block = tracks.subList(i, end).map { it.copy(indent = it.indent - 1) }
        val rest = tracks.subList(0, i) + tracks.subList(end, tracks.size)
        val at = blockEnd(tracks, parent) - (end - i)
        return checked(project.copy(tracks = rest.subList(0, at) + block + rest.subList(at, rest.size)))
    }

    /** Puts a track, with the tracks in its folder, into the new folder track [folder], which takes its place. */
    fun wrapInFolder(project: Project, trackId: Long, folder: Track): Project? {
        val tracks = project.tracks
        val i = tracks.indexOfFirst { it.id == trackId }
        if (i < 0) return null
        val end = blockEnd(tracks, i)
        val inside = tracks.subList(i, end).map { it.copy(indent = it.indent + 1) }
        return checked(
            project.copy(tracks = tracks.subList(0, i) + folder.copy(indent = tracks[i].indent) + inside + tracks.subList(end, tracks.size))
        )
    }

    /**
     * Swaps a track, with the tracks in its folder, with the track above
     * (direction -1) or below (+1) it in the same folder. Null at the edge of
     * its folder, where [outdent] moves it out instead.
     */
    fun move(project: Project, trackId: Long, direction: Int): Project? {
        val tracks = project.tracks
        val i = tracks.indexOfFirst { it.id == trackId }
        if (i < 0) return null
        val depth = tracks[i].indent
        val end = blockEnd(tracks, i)
        val moved = if (direction < 0) {
            var s = i - 1
            while (s >= 0 && tracks[s].indent > depth) s--
            if (s < 0 || tracks[s].indent < depth) return null
            tracks.subList(0, s) + tracks.subList(i, end) + tracks.subList(s, i) + tracks.subList(end, tracks.size)
        } else {
            if (end >= tracks.size || tracks[end].indent < depth) return null
            val after = blockEnd(tracks, end)
            tracks.subList(0, i) + tracks.subList(end, after) + tracks.subList(i, end) + tracks.subList(after, tracks.size)
        }
        return checked(project.copy(tracks = moved))
    }

    /**
     * Removes a track. The tracks in its folder move up a level, into the
     * folder it was in, and sends to it go.
     */
    fun removeTrack(project: Project, trackId: Long): Project {
        val tracks = project.tracks
        val i = tracks.indexOfFirst { it.id == trackId }
        if (i < 0) return project
        val end = blockEnd(tracks, i)
        val kept = tracks.mapIndexedNotNull { j, t ->
            when {
                j == i -> null
                j in i + 1 until end -> t.copy(indent = t.indent - 1)
                else -> t
            }
        }
        return project.copy(tracks = kept.map { t ->
            if (t.sends.none { it.target == trackId }) t else t.copy(sends = t.sends.filterNot { it.target == trackId })
        })
    }

    /** Puts [track] just below track [afterId] and the tracks in its folder, at the same depth. */
    fun insertAfter(project: Project, afterId: Long, track: Track): Project {
        val tracks = project.tracks
        val i = tracks.indexOfFirst { it.id == afterId }
        if (i < 0) return project.copy(tracks = tracks + track.copy(indent = 0))
        val end = blockEnd(tracks, i)
        return project.copy(tracks = tracks.subList(0, end) + track.copy(indent = tracks[i].indent) + tracks.subList(end, tracks.size))
    }

    /** Tracks the timeline hides because a folder above them is collapsed. */
    fun hidden(tracks: List<Track>): Set<Long> {
        val hidden = mutableSetOf<Long>()
        var depth = Int.MAX_VALUE // tracks deeper than this are in a collapsed folder
        for (t in tracks) {
            if (t.indent > depth) {
                hidden += t.id
                continue
            }
            depth = if (t.collapsed) t.indent else Int.MAX_VALUE
        }
        return hidden
    }

    /**
     * Whether each track passes any sound on: its own unmuted items, or what a
     * track in its folder or an unmuted send brings it. A muted track passes
     * nothing on.
     */
    fun audible(project: Project): BooleanArray {
        val tracks = project.tracks
        val outputs = outputs(project, withMutedSends = false)
        val audible = BooleanArray(tracks.size) { i ->
            val t = tracks[i]
            !t.mute && t.items.any { !it.mute && (!it.isMidi || t.instrument != null) }
        }
        // Sound flows down the outputs; without loops it settles within one pass per track.
        repeat(tracks.size) {
            var changed = false
            outputs.forEachIndexed { i, targets ->
                if (!audible[i]) return@forEachIndexed
                for (j in targets) {
                    if (!audible[j] && !tracks[j].mute) {
                        audible[j] = true
                        changed = true
                    }
                }
            }
            if (!changed) return audible
        }
        return audible
    }

    /**
     * Folder depths that always make sense (the first track at the top, no
     * track more than one level below the one above it), and only sends to
     * other existing tracks that never loop back.
     */
    fun normalize(project: Project): Project {
        val ids = project.tracks.map { it.id }.toSet()
        var previous = -1
        val tracks = project.tracks.map { t ->
            val indent = t.indent.coerceIn(0, previous + 1)
            previous = indent
            val sends = t.sends.filter { it.target in ids && it.target != t.id }
            if (indent == t.indent && sends.size == t.sends.size) t else t.copy(indent = indent, sends = sends)
        }
        val fixed = project.copy(tracks = tracks)
        if (!hasLoop(fixed)) return fixed
        // Folders alone never loop, so the sends are added back one at a time, skipping any that would.
        var p = fixed.copy(tracks = tracks.map { it.copy(sends = emptyList()) })
        for (t in tracks) for (s in t.sends) {
            if (!reaches(p, s.target, t.id)) p = p.updateTrack(t.id) { it.copy(sends = it.sends + s) }
        }
        return p
    }
}
