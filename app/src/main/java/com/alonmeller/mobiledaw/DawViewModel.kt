package com.alonmeller.mobiledaw

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alonmeller.mobiledaw.audio.NativeEngine
import com.alonmeller.mobiledaw.data.ProjectSettings
import com.alonmeller.mobiledaw.data.ProjectStore
import com.alonmeller.mobiledaw.data.WavFile
import com.alonmeller.mobiledaw.model.Edits
import com.alonmeller.mobiledaw.model.History
import com.alonmeller.mobiledaw.model.Ids
import com.alonmeller.mobiledaw.model.Item
import com.alonmeller.mobiledaw.model.Marker
import com.alonmeller.mobiledaw.model.Project
import com.alonmeller.mobiledaw.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/** Frames per waveform bucket; fine enough for the deepest zoom level. */
const val PEAK_BUCKET = 256

/** An audio buffer in the engine plus what the UI needs to draw it. */
class SourceInfo(val fileName: String, val length: Long, val peaks: FloatArray)

/** What a drag on an item changes, decided by where the finger lands. */
enum class DragMode { Move, TrimLeft, TrimRight, FadeIn, FadeOut }

/** Grid choices: notes per bar. */
val GRID_DIVISIONS = listOf(1, 2, 4, 8, 16)

class DawViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProjectStore(app.filesDir)
    private val engineStarted = NativeEngine.start()
    val sampleRate: Int = NativeEngine.sampleRate()

    // ------------------------------------------------------------ state

    var project by mutableStateOf(Project())
        private set
    var sources by mutableStateOf(mapOf<Int, SourceInfo>())
        private set
    var settings by mutableStateOf(ProjectSettings())
        private set

    var selectedItems by mutableStateOf(setOf<Long>())
        private set
    var selectedTrackId by mutableLongStateOf(-1L)
        private set
    /** Track that the next recording goes into (REAPER's record arm), or -1 for a new track. */
    var armedTrackId by mutableLongStateOf(-1L)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    private var clipboard: Item? = null

    var playing by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var recordedFrames by mutableLongStateOf(0L)
        private set
    var recordStart by mutableLongStateOf(0L)
        private set
    var inputLevel by mutableFloatStateOf(0f)
        private set
    var masterLevel by mutableFloatStateOf(0f)
        private set
    var trackLevels by mutableStateOf(mapOf<Long, Float>())
        private set
    var loading by mutableStateOf(true)
        private set
    var message by mutableStateOf<String?>(if (engineStarted) null else "Audio engine failed to start")

    /** Timeline zoom in dp per second, and horizontal scroll in px. */
    var zoomDp by mutableFloatStateOf(60f)
        private set
    var scrollPx by mutableFloatStateOf(0f)
    var laneWidthPx by mutableFloatStateOf(0f)

    private val history = History<Project>()
    private var gestureBase: Project? = null
    private var saveJob: Job? = null
    private var nextTrackNumber = 1

    init {
        viewModelScope.launch { loadProject() }
    }

    // ------------------------------------------------------------ load / save / sync

    private suspend fun loadProject() {
        val loaded = withContext(Dispatchers.IO) {
            val newSources = mutableMapOf<Int, SourceInfo>()
            store.sourceLengthOf = { id -> NativeEngine.sourceLength(id) }
            val result = store.load(sampleRate) { file ->
                val audio = WavFile.read(store.audioFile(file)) ?: return@load null
                val samples = WavFile.resample(audio.samples, audio.sampleRate, sampleRate)
                val id = NativeEngine.createSource(samples)
                newSources[id] = SourceInfo(file, samples.size.toLong(), NativeEngine.sourcePeaks(id, PEAK_BUCKET))
                id
            }
            if (result != null) store.deleteUnused(newSources.values.map { it.fileName }.toSet())
            result to newSources
        }
        val (result, newSources) = loaded
        sources = newSources
        if (result != null) {
            settings = result.settings
            NativeEngine.setMetronome(settings.metronome)
            setProject(result.project)
            nextTrackNumber = result.project.tracks.size + 1
        }
        loading = false
    }

    /** Pushes the project to the audio engine. */
    private fun sync(p: Project) {
        val trackParams = FloatArray(p.tracks.size * 4)
        val counts = IntArray(p.tracks.size)
        val items = p.tracks.map { t -> t.items.filter { !it.mute && it.sourceId in sources } }
        val total = items.sumOf { it.size }
        val itemParams = LongArray(total * 6)
        val itemGains = FloatArray(total)
        var k = 0
        p.tracks.forEachIndexed { i, t ->
            trackParams[i * 4] = t.gain
            trackParams[i * 4 + 1] = t.pan
            trackParams[i * 4 + 2] = if (t.mute) 1f else 0f
            trackParams[i * 4 + 3] = if (t.solo) 1f else 0f
            counts[i] = items[i].size
            items[i].forEach { item ->
                itemParams[k * 6] = item.sourceId.toLong()
                itemParams[k * 6 + 1] = item.position
                itemParams[k * 6 + 2] = item.offset
                itemParams[k * 6 + 3] = item.length
                itemParams[k * 6 + 4] = item.fadeIn
                itemParams[k * 6 + 5] = item.fadeOut
                itemGains[k] = item.gain
                k++
            }
        }
        NativeEngine.setArrangement(trackParams, counts, itemParams, itemGains)
        NativeEngine.setTempo(p.tempo)
        NativeEngine.setLoop(p.loopStart, p.loopEnd, p.loopOn)
    }

    private fun setProject(p: Project) {
        project = p
        sync(p)
        canUndo = history.canUndo
        canRedo = history.canRedo
        val itemIds = p.tracks.flatMap { t -> t.items.map { it.id } }.toSet()
        if (selectedItems.any { it !in itemIds }) selectedItems = selectedItems.intersect(itemIds)
        if (p.tracks.none { it.id == armedTrackId }) armedTrackId = -1
        if (p.tracks.none { it.id == selectedTrackId }) selectedTrackId = -1
    }

    /** Applies an undoable edit. */
    private fun commit(newProject: Project) {
        if (newProject == project) return
        history.push(project)
        setProject(newProject)
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            saveNow()
        }
    }

    private fun saveNow() {
        val p = project
        val s = settings
        val files = sources.mapValues { it.value.fileName }
        viewModelScope.launch(Dispatchers.IO) { store.save(p, s, sampleRate) { files[it] } }
    }

    fun undo() {
        history.undo(project)?.let { setProject(it); scheduleSave() }
    }

    fun redo() {
        history.redo(project)?.let { setProject(it); scheduleSave() }
    }

    // ------------------------------------------------------------ per-frame polling

    /** Called every UI frame to pull transport and meter state from the engine. */
    fun tick() {
        position = NativeEngine.position()
        masterLevel = NativeEngine.masterPeak()
        if (recording) {
            recordedFrames = NativeEngine.recordedFrames()
            inputLevel = NativeEngine.inputLevel()
        }
        if (playing) {
            trackLevels = project.tracks.withIndex().associate { (i, t) -> t.id to NativeEngine.trackPeak(i) }
            followPlayhead()
        } else if (trackLevels.isNotEmpty()) {
            trackLevels = emptyMap()
        }
    }

    // ------------------------------------------------------------ timeline geometry

    private var density = 1f
    fun setDensity(value: Float) {
        density = value
    }

    fun framesToPx(frames: Long): Float = frames.toFloat() / sampleRate * zoomDp * density
    fun pxToFrames(px: Float): Long = (px / (zoomDp * density) * sampleRate).toLong()
    fun screenX(frame: Long): Float = framesToPx(frame) - scrollPx
    fun frameAtScreen(x: Float): Long = pxToFrames(x + scrollPx).coerceAtLeast(0)

    val framesPerBeat: Double get() = sampleRate * 60.0 / project.tempo
    val gridFrames: Double get() = framesPerBeat * 4 / settings.gridDivision
    fun snapped(frame: Long) = Edits.snap(frame, gridFrames, settings.snap)

    private fun followPlayhead() {
        if (laneWidthPx <= 0f) return
        val x = framesToPx(position)
        if (x > scrollPx + laneWidthPx * 0.85f || x < scrollPx) {
            scrollPx = (x - laneWidthPx * 0.15f).coerceAtLeast(0f)
        }
    }

    fun scrollBy(deltaPx: Float) {
        scrollPx = (scrollPx + deltaPx).coerceAtLeast(0f)
    }

    fun zoom(factor: Float) {
        // Keep the playhead in the same place on screen while zooming.
        val anchorScreen = screenX(position)
        zoomDp = (zoomDp * factor).coerceIn(5f, 600f)
        scrollPx = (framesToPx(position) - anchorScreen).coerceAtLeast(0f)
    }

    // ------------------------------------------------------------ transport

    fun togglePlay() {
        if (recording) {
            stopRecording()
            return
        }
        playing = !playing
        NativeEngine.setPlaying(playing)
    }

    /** Stop button: first press stops, a second press returns to the start. */
    fun stop() {
        if (recording) stopRecording()
        if (!playing) {
            seekFrames(0)
            scrollPx = 0f
        }
        playing = false
        NativeEngine.setPlaying(false)
    }

    fun rewind() {
        seekFrames(if (project.loopOn) project.loopStart else 0)
        scrollPx = (framesToPx(position) - laneWidthPx * 0.15f).coerceAtLeast(0f)
    }

    fun seekFrames(frame: Long) {
        NativeEngine.seek(frame.coerceAtLeast(0))
        position = NativeEngine.position()
    }

    fun seekToScreenX(x: Float) = seekFrames(snapped(frameAtScreen(x)))

    fun toggleMetronome() {
        settings = settings.copy(metronome = !settings.metronome)
        NativeEngine.setMetronome(settings.metronome)
        scheduleSave()
    }

    fun changeTempo(delta: Double) = commit(project.copy(tempo = (project.tempo + delta).coerceIn(30.0, 300.0)))

    fun toggleSnap() {
        settings = settings.copy(snap = !settings.snap)
        scheduleSave()
    }

    fun cycleGrid() {
        val i = GRID_DIVISIONS.indexOf(settings.gridDivision)
        settings = settings.copy(gridDivision = GRID_DIVISIONS[(i + 1) % GRID_DIVISIONS.size])
        scheduleSave()
    }

    // ------------------------------------------------------------ loop and markers

    fun toggleLoop() {
        val p = project
        if (p.loopEnd <= p.loopStart) {
            // No loop range yet: loop the bar under the playhead.
            val bar = framesPerBeat * 4
            val start = (position / bar).toLong() * bar
            commit(p.copy(loopStart = start.toLong(), loopEnd = (start + bar).toLong(), loopOn = true))
        } else {
            commit(p.copy(loopOn = !p.loopOn))
        }
    }

    private var loopDragStart = 0L
    fun beginLoopDrag(x: Float) {
        gestureBase = project
        loopDragStart = snapped(frameAtScreen(x))
    }

    fun dragLoop(x: Float) {
        val a = loopDragStart
        val b = snapped(frameAtScreen(x))
        val p = project.copy(loopStart = minOf(a, b), loopEnd = maxOf(a, b), loopOn = abs(b - a) > 0)
        project = p
        sync(p)
    }

    fun endGesture() {
        val base = gestureBase ?: return
        gestureBase = null
        if (base != project) {
            history.push(base)
            canUndo = true
            canRedo = false
            scheduleSave()
        }
    }

    fun addMarker() {
        val n = project.markers.size + 1
        val m = Marker(Ids.next(), position, "$n")
        commit(project.copy(markers = (project.markers + m).sortedBy { it.position }))
    }

    fun deleteMarker(id: Long) = commit(project.copy(markers = project.markers.filterNot { it.id == id }))

    fun jumpMarker(forward: Boolean) {
        val targets = project.markers.map { it.position } + listOf(0L, project.endFrame)
        val next = if (forward) targets.filter { it > position + 1 }.minOrNull()
        else targets.filter { it < position - 1 }.maxOrNull()
        next?.let { seekFrames(it) }
    }

    /** Marker within [slopPx] of screen x, if any. */
    fun markerAt(x: Float, slopPx: Float): Marker? =
        project.markers.minByOrNull { abs(screenX(it.position) - x) }
            ?.takeIf { abs(screenX(it.position) - x) <= slopPx }

    // ------------------------------------------------------------ recording

    fun toggleRecord() {
        if (recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (!NativeEngine.startRecording()) {
            message = "Could not open the microphone"
            return
        }
        recordStart = NativeEngine.recordStartFrame()
        recordedFrames = 0
        recording = true
        playing = true
    }

    private fun stopRecording() {
        val sourceId = NativeEngine.stopRecording()
        recording = false
        inputLevel = 0f
        if (sourceId < 0) return
        val length = NativeEngine.sourceLength(sourceId)
        val info = SourceInfo("take_${UUID.randomUUID()}.wav", length, NativeEngine.sourcePeaks(sourceId, PEAK_BUCKET))
        sources = sources + (sourceId to info)
        viewModelScope.launch(Dispatchers.IO) {
            WavFile.writeFloatMono(store.audioFile(info.fileName), NativeEngine.sourceSamples(sourceId), sampleRate)
        }

        val item = Item(Ids.next(), sourceId, NativeEngine.lastTakeStart(), 0, length)
        var p = project
        var trackId = armedTrackId
        if (p.tracks.none { it.id == trackId }) {
            val t = newTrack()
            p = p.copy(tracks = p.tracks + t)
            trackId = t.id
        }
        // Like a punch-in: the new take replaces whatever it overlaps.
        p = p.updateTrack(trackId) { it.copy(items = Edits.placeOnTop(it.items, item)) }
        commit(p)
        armedTrackId = trackId
        selectedTrackId = trackId
        selectedItems = setOf(item.id)
    }

    /** True while a take is being recorded into this track. */
    fun isRecordingInto(track: Track) = recording && track.id == armedTrackId

    // ------------------------------------------------------------ tracks

    private fun newTrack(): Track {
        val number = nextTrackNumber++
        return Track(Ids.next(), "Audio $number", number - 1)
    }

    /** Adds an empty track and arms it, so the next recording lands there. */
    fun addTrack() {
        val t = newTrack()
        commit(project.copy(tracks = project.tracks + t))
        armedTrackId = t.id
        selectedTrackId = t.id
    }

    fun deleteTrack(id: Long) = commit(project.copy(tracks = project.tracks.filterNot { it.id == id }))

    fun toggleArm(id: Long) {
        if (recording) return
        armedTrackId = if (armedTrackId == id) -1 else id
    }

    fun toggleMute(id: Long) = commit(project.updateTrack(id) { it.copy(mute = !it.mute) })
    fun toggleSolo(id: Long) = commit(project.updateTrack(id) { it.copy(solo = !it.solo) })

    /** Live slider change; [endGesture] makes it one undo step. */
    fun setTrackGain(id: Long, gain: Float) {
        if (gestureBase == null) gestureBase = project
        val p = project.updateTrack(id) { it.copy(gain = gain) }
        project = p
        sync(p)
    }

    // ------------------------------------------------------------ item selection and dragging

    fun selectItem(id: Long?, trackId: Long) {
        selectedItems = if (id == null) emptySet() else setOf(id)
        selectedTrackId = trackId
    }

    /** Finds the item and drag mode for a touch at screen x, y within a lane of height [laneH]. */
    fun hitTest(track: Track, x: Float, y: Float, laneH: Float): Pair<Item, DragMode>? {
        val item = track.items.lastOrNull { x >= screenX(it.position) && x <= screenX(it.end) } ?: return null
        val x0 = screenX(item.position)
        val x1 = screenX(item.end)
        val edge = 16 * density
        val handleZone = laneH * 0.35f
        val mode = when {
            item.id !in selectedItems -> DragMode.Move
            y < handleZone && abs(x - (x0 + framesToPx(item.fadeIn))) < edge * 1.4f -> DragMode.FadeIn
            y < handleZone && abs(x - (x1 - framesToPx(item.fadeOut))) < edge * 1.4f -> DragMode.FadeOut
            x - x0 < edge && x1 - x0 > edge * 3 -> DragMode.TrimLeft
            x1 - x < edge && x1 - x0 > edge * 3 -> DragMode.TrimRight
            else -> DragMode.Move
        }
        return item to mode
    }

    private var dragItem: Item? = null
    private var dragMode = DragMode.Move
    private var dragPx = 0f

    fun beginItemDrag(item: Item, mode: DragMode, trackId: Long) {
        gestureBase = project
        dragItem = item
        dragMode = mode
        dragPx = 0f
        if (item.id !in selectedItems) selectedItems = setOf(item.id)
        selectedTrackId = trackId
    }

    fun dragItemBy(dxPx: Float) {
        val base = dragItem ?: return
        val baseProject = gestureBase ?: return
        dragPx += dxPx
        val df = pxToFrames(dragPx)
        val srcLen = sources[base.sourceId]?.length ?: base.length
        val updated = when (dragMode) {
            DragMode.Move -> base.copy(position = snapped(base.position + df))
            DragMode.TrimLeft -> Edits.trimLeft(base, snapped(base.position + df))
            DragMode.TrimRight -> Edits.trimRight(base, snapped(base.end + df), srcLen)
            DragMode.FadeIn -> Edits.setFadeIn(base, base.fadeIn + df)
            DragMode.FadeOut -> Edits.setFadeOut(base, base.fadeOut - df)
        }
        val p = baseProject.updateItem(base.id) { updated }
        project = p
        sync(p)
    }

    fun endItemDrag() {
        dragItem = null
        endGesture()
    }

    // ------------------------------------------------------------ item edits

    fun splitAtPlayhead() {
        val (p, right) = Edits.splitAt(project, position, selectedItems)
        if (right.isEmpty()) {
            message = "Move the playhead over an item to split it"
            return
        }
        commit(p)
        selectedItems = right
    }

    fun deleteSelected() {
        if (selectedItems.isEmpty()) return
        commit(project.removeItems(selectedItems))
        selectedItems = emptySet()
    }

    fun duplicateSelected() {
        val copies = mutableSetOf<Long>()
        val p = project.copy(tracks = project.tracks.map { t ->
            val dups = t.items.filter { it.id in selectedItems }.map { Edits.duplicate(it).also { d -> copies += d.id } }
            if (dups.isEmpty()) t else t.copy(items = (t.items + dups).sortedBy { it.position })
        })
        commit(p)
        if (copies.isNotEmpty()) selectedItems = copies
    }

    fun copySelected() {
        clipboard = project.tracks.flatMap { it.items }.firstOrNull { it.id in selectedItems }
        if (clipboard != null) message = "Copied"
    }

    fun paste() {
        val clip = clipboard ?: return
        val trackId = selectedTrackId.takeIf { id -> project.tracks.any { it.id == id } }
            ?: project.tracks.firstOrNull()?.id ?: return
        val item = clip.copy(id = Ids.next(), position = position)
        commit(project.updateTrack(trackId) { it.copy(items = (it.items + item).sortedBy { i -> i.position }) })
        selectedItems = setOf(item.id)
    }

    fun toggleMuteSelected() {
        var p = project
        selectedItems.forEach { id -> p = p.updateItem(id) { it.copy(mute = !it.mute) } }
        commit(p)
    }

    fun normalizeSelected() {
        var p = project
        selectedItems.forEach { id ->
            p = p.updateItem(id) { item ->
                val peaks = sources[item.sourceId]?.peaks ?: return@updateItem item
                val from = (item.offset / PEAK_BUCKET).toInt().coerceIn(0, peaks.size)
                val to = ((item.offset + item.length) / PEAK_BUCKET + 1).toInt().coerceIn(from, peaks.size)
                var peak = 0f
                for (b in from until to) peak = max(peak, peaks[b])
                item.copy(gain = Edits.normalizeGain(peak))
            }
        }
        commit(p)
    }

    /** Moves the selected items to the track above (-1) or below (+1). */
    fun moveSelectedToTrack(direction: Int) {
        if (selectedItems.isEmpty()) return
        val tracks = project.tracks.toMutableList()
        val moves = mutableListOf<Pair<Int, Item>>()
        tracks.forEachIndexed { i, t ->
            t.items.filter { it.id in selectedItems }.forEach { moves += (i + direction) to it }
        }
        if (moves.any { it.first !in tracks.indices }) return
        for (i in tracks.indices) tracks[i] = tracks[i].copy(items = tracks[i].items.filterNot { it.id in selectedItems })
        moves.forEach { (i, item) -> tracks[i] = tracks[i].copy(items = (tracks[i].items + item).sortedBy { it.position }) }
        commit(project.copy(tracks = tracks))
        moves.firstOrNull()?.let { selectedTrackId = tracks[it.first].id }
    }

    fun onAppBackground() {
        if (recording) stopRecording()
        if (playing) {
            playing = false
            NativeEngine.setPlaying(false)
        }
        saveJob?.cancel()
        saveNow()
    }
}
