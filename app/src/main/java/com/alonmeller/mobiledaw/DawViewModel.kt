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
import com.alonmeller.mobiledaw.model.Effect
import com.alonmeller.mobiledaw.model.EffectType
import com.alonmeller.mobiledaw.model.EnvKind
import com.alonmeller.mobiledaw.model.EnvPoint
import com.alonmeller.mobiledaw.model.Envelopes
import com.alonmeller.mobiledaw.model.History
import com.alonmeller.mobiledaw.model.Ids
import com.alonmeller.mobiledaw.model.Item
import com.alonmeller.mobiledaw.model.MASTER
import com.alonmeller.mobiledaw.model.Marker
import com.alonmeller.mobiledaw.model.Project
import com.alonmeller.mobiledaw.model.Scales
import com.alonmeller.mobiledaw.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/** Frames per waveform bucket; fine enough for the deepest zoom level. */
const val PEAK_BUCKET = 256

/** An audio buffer in the engine plus what the UI needs to draw it. */
class SourceInfo(val fileName: String, val length: Long, val peaks: FloatArray)

/** What a drag on an item changes, decided by where the finger lands. */
enum class DragMode { Move, TrimLeft, TrimRight, FadeIn, FadeOut }

/** Grid choices: notes per bar. */
val GRID_DIVISIONS = listOf(1, 2, 4, 8, 16)

/** How fast meters fall back after a peak. */
private const val METER_FALL_DB_PER_SECOND = 24f

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

    /** Mixer instead of the arrange view. */
    var showMixer by mutableStateOf(false)
        private set
    /** Track id (or [MASTER]) whose effect chain is open, or null. */
    var fxTarget by mutableStateOf<Long?>(null)
        private set
    /** Tracks showing an automation envelope over their lane. */
    var envModes by mutableStateOf(mapOf<Long, EnvKind>())
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
            applyProject(result.project)
            nextTrackNumber = result.project.tracks.size + 1
        }
        loading = false
    }

    /** Effects the engine already has, with the settings it was last given. */
    private val pushedEffects = mutableMapOf<Long, Effect>()

    private fun syncEffects(p: Project) {
        for (e in p.allEffects) {
            val prev = pushedEffects[e.id]
            if (prev == null) {
                NativeEngine.createEffect(e.id, e.type.code, e.values.toFloatArray())
                if (e.bypass) NativeEngine.setEffectBypass(e.id, true)
            } else if (prev != e) {
                e.values.forEachIndexed { i, v ->
                    if (prev.values.getOrNull(i) != v) NativeEngine.setEffectParam(e.id, i, v)
                }
                if (prev.bypass != e.bypass) NativeEngine.setEffectBypass(e.id, e.bypass)
            }
            pushedEffects[e.id] = e
        }
    }

    /** Pushes the project to the audio engine. */
    private fun sync(p: Project) {
        syncEffects(p)
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
        val fxCounts = IntArray(p.tracks.size + 1)
        p.tracks.forEachIndexed { i, t -> fxCounts[i] = t.fx.size }
        fxCounts[p.tracks.size] = p.masterFx.size
        val fxIds = (p.tracks.flatMap { it.fx } + p.masterFx).map { it.id }.toLongArray()
        val envCounts = IntArray(p.tracks.size * 2)
        val points = mutableListOf<EnvPoint>()
        p.tracks.forEachIndexed { i, t ->
            envCounts[i * 2] = t.volumeEnv.size
            envCounts[i * 2 + 1] = t.panEnv.size
            // Volume goes as fader positions so the engine interpolates on the
            // fader scale, exactly the straight lines drawn on screen.
            points += t.volumeEnv.map { EnvPoint(it.frame, Scales.gainToPosition(it.value)) }
            points += t.panEnv
        }
        NativeEngine.setArrangement(
            trackParams, counts, itemParams, itemGains,
            fxCounts, fxIds,
            envCounts, points.map { it.frame }.toLongArray(), points.map { it.value }.toFloatArray(),
            p.masterGain,
        )
        NativeEngine.setTempo(p.tempo)
        NativeEngine.setLoop(p.loopStart, p.loopEnd, p.loopOn)
    }

    private fun applyProject(p: Project) {
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
        applyProject(newProject)
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
        history.undo(project)?.let { applyProject(it); scheduleSave() }
    }

    fun redo() {
        history.redo(project)?.let { applyProject(it); scheduleSave() }
    }

    // ------------------------------------------------------------ per-frame polling

    private var lastTickNanos = 0L

    /** Called every UI frame to pull transport and meter state from the engine. */
    fun tick() {
        val now = System.nanoTime()
        val seconds = if (lastTickNanos == 0L) 0f else ((now - lastTickNanos) / 1e9f).coerceAtMost(0.25f)
        lastTickNanos = now
        // Meters jump up to each new peak and fall back at a steady rate, like a mixing desk.
        val fall = 10f.pow(-METER_FALL_DB_PER_SECOND * seconds / 20f)
        fun meter(shown: Float, peak: Float) = max(peak, shown * fall).let { if (it < 1e-4f) 0f else it }

        position = NativeEngine.position()
        masterLevel = meter(masterLevel, NativeEngine.masterPeak())
        if (recording) {
            recordedFrames = NativeEngine.recordedFrames()
            inputLevel = meter(inputLevel, NativeEngine.inputLevel())
        }
        val levels = HashMap<Long, Float>()
        project.tracks.forEachIndexed { i, t ->
            val v = meter(trackLevels[t.id] ?: 0f, NativeEngine.trackPeak(i))
            if (v > 0f) levels[t.id] = v
        }
        if (levels.isNotEmpty() || trackLevels.isNotEmpty()) trackLevels = levels
        if (playing) followPlayhead()
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

    fun renameTrack(id: Long, name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) commit(project.updateTrack(id) { it.copy(name = clean) })
    }

    /** Copies a track with its items, effects and automation right below it. */
    fun duplicateTrack(id: Long) {
        val index = project.tracks.indexOfFirst { it.id == id }
        if (index < 0) return
        val t = project.tracks[index]
        val copy = t.copy(
            id = Ids.next(),
            name = "${t.name} copy",
            solo = false,
            items = t.items.map { it.copy(id = Ids.next()) },
            fx = t.fx.map { it.copy(id = Ids.next()) },
        )
        commit(project.copy(tracks = project.tracks.toMutableList().apply { add(index + 1, copy) }))
        selectedTrackId = copy.id
    }

    fun moveTrack(id: Long, direction: Int) {
        val tracks = project.tracks.toMutableList()
        val i = tracks.indexOfFirst { it.id == id }
        val j = i + direction
        if (i < 0 || j !in tracks.indices) return
        tracks[i] = tracks[j].also { tracks[j] = tracks[i] }
        commit(project.copy(tracks = tracks))
    }

    fun toggleArm(id: Long) {
        if (recording) return
        armedTrackId = if (armedTrackId == id) -1 else id
    }

    fun toggleMute(id: Long) = commit(project.updateTrack(id) { it.copy(mute = !it.mute) })
    fun toggleSolo(id: Long) = commit(project.updateTrack(id) { it.copy(solo = !it.solo) })

    /** Live slider change; [endGesture] makes it one undo step. */
    fun setTrackGain(id: Long, gain: Float) = preview(project.updateTrack(id) { it.copy(gain = gain) })

    fun setTrackPan(id: Long, pan: Float) = preview(project.updateTrack(id) { it.copy(pan = pan.coerceIn(-1f, 1f)) })

    fun centerPan(id: Long) = commit(project.updateTrack(id) { it.copy(pan = 0f) })

    fun setMasterGain(gain: Float) = preview(project.copy(masterGain = gain))

    /** Applies a live change during a gesture; [endGesture] makes the whole gesture one undo step. */
    private fun preview(p: Project) {
        if (gestureBase == null) gestureBase = project
        project = p
        sync(p)
    }

    fun toggleMixer() {
        showMixer = !showMixer
    }

    // ------------------------------------------------------------ effects

    fun openFx(target: Long) {
        fxTarget = target
    }

    fun closeFx() {
        endGesture()
        fxTarget = null
    }

    fun fxTitle(target: Long): String =
        if (target == MASTER) "Master" else project.tracks.firstOrNull { it.id == target }?.name ?: ""

    fun addEffect(target: Long, type: EffectType) =
        commit(project.withFxChain(target, project.fxChain(target) + Effect(Ids.next(), type)))

    fun removeEffect(target: Long, id: Long) =
        commit(project.withFxChain(target, project.fxChain(target).filterNot { it.id == id }))

    fun moveEffect(target: Long, id: Long, direction: Int) {
        val chain = project.fxChain(target).toMutableList()
        val i = chain.indexOfFirst { it.id == id }
        val j = i + direction
        if (i < 0 || j !in chain.indices) return
        chain[i] = chain[j].also { chain[j] = chain[i] }
        commit(project.withFxChain(target, chain))
    }

    fun toggleBypass(target: Long, id: Long) = commit(
        project.withFxChain(target, project.fxChain(target).map { if (it.id == id) it.copy(bypass = !it.bypass) else it })
    )

    /** Live parameter change; only the effect is updated, the arrangement stays as it is. */
    fun setEffectParam(target: Long, id: Long, index: Int, value: Float) {
        if (gestureBase == null) gestureBase = project
        val chain = project.fxChain(target).map { e ->
            if (e.id != id) e else e.copy(values = e.values.toMutableList().also { it[index] = value })
        }
        val p = project.withFxChain(target, chain)
        project = p
        syncEffects(p)
    }

    fun resetEffect(target: Long, id: Long) = commit(
        project.withFxChain(target, project.fxChain(target).map { if (it.id == id) it.copy(values = it.type.defaults()) else it })
    )

    // ------------------------------------------------------------ automation envelopes

    /** Track header "A" button: no envelope, then volume, then pan. */
    fun cycleEnvelope(trackId: Long) {
        envModes = when (envModes[trackId]) {
            null -> envModes + (trackId to EnvKind.Volume)
            EnvKind.Volume -> envModes + (trackId to EnvKind.Pan)
            EnvKind.Pan -> envModes - trackId
        }
    }

    fun envValueToPosition(kind: EnvKind, value: Float) =
        if (kind == EnvKind.Volume) Scales.gainToPosition(value) else Scales.panToPosition(value)

    fun envPositionToValue(kind: EnvKind, position: Float) =
        if (kind == EnvKind.Volume) Scales.positionToGain(position) else Scales.positionToPan(position)

    /** Envelopes keep clear of the lane's edges so their extreme points stay visible. */
    private val envInset get() = 8 * density

    /** Screen y of an envelope [value] in a lane of height [laneH]. */
    fun envY(kind: EnvKind, value: Float, laneH: Float): Float =
        envInset + (1 - envValueToPosition(kind, value)) * (laneH - 2 * envInset)

    private fun envValueAtY(kind: EnvKind, y: Float, laneH: Float): Float =
        envPositionToValue(kind, 1 - ((y - envInset) / (laneH - 2 * envInset)).coerceIn(0f, 1f))

    /** Envelope point near screen (x, y) in a lane of height [laneH], if any. */
    fun envPointAt(track: Track, kind: EnvKind, x: Float, y: Float, laneH: Float): Int? {
        val radius = 22 * density
        var best: Int? = null
        var bestDist = Float.MAX_VALUE
        track.envelope(kind).forEachIndexed { i, pt ->
            val px = screenX(pt.frame)
            val py = envY(kind, pt.value, laneH)
            val d = kotlin.math.hypot(px - x, py - y)
            if (d < radius && d < bestDist) {
                best = i
                bestDist = d
            }
        }
        return best
    }

    fun addEnvPoint(trackId: Long, kind: EnvKind, x: Float, y: Float, laneH: Float) {
        val frame = snapped(frameAtScreen(x))
        val value = envValueAtY(kind, y, laneH)
        commit(project.updateTrack(trackId) { it.withEnvelope(kind, Envelopes.add(it.envelope(kind), EnvPoint(frame, value))) })
    }

    fun deleteEnvPoint(trackId: Long, kind: EnvKind, index: Int) =
        commit(project.updateTrack(trackId) { it.withEnvelope(kind, Envelopes.remove(it.envelope(kind), index)) })

    fun clearEnvelope(trackId: Long, kind: EnvKind) =
        commit(project.updateTrack(trackId) { it.withEnvelope(kind, emptyList()) })

    /** Track id and index of the envelope point being dragged, so the lane can show its value. */
    var envDrag by mutableStateOf<Pair<Long, Int>?>(null)
        private set

    fun beginEnvDrag(trackId: Long, index: Int) {
        gestureBase = project
        envDrag = trackId to index
    }

    fun dragEnvPoint(trackId: Long, kind: EnvKind, x: Float, y: Float, laneH: Float) {
        val base = gestureBase ?: return
        val index = envDrag?.second ?: return
        val frame = snapped(frameAtScreen(x))
        val value = envValueAtY(kind, y, laneH)
        val p = base.updateTrack(trackId) { it.withEnvelope(kind, Envelopes.move(it.envelope(kind), index, frame, value)) }
        project = p
        sync(p)
    }

    fun endEnvDrag() {
        envDrag = null
        endGesture()
    }

    // ------------------------------------------------------------ item selection and dragging

    fun selectItem(id: Long?, trackId: Long) {
        selectedItems = if (id == null) emptySet() else setOf(id)
        selectedTrackId = trackId
    }

    /** Finds the item and drag mode for a touch at screen x, y within a lane of height [laneH]. */
    fun hitTest(track: Track, x: Float, y: Float, laneH: Float): Pair<Item, DragMode>? {
        val edge = 16 * density
        // A selected item's edges stay grabbable a little outside its bounds.
        val tolerance = edge / 2
        val item = track.items.lastOrNull { x >= screenX(it.position) && x <= screenX(it.end) }
            ?: track.items.lastOrNull {
                it.id in selectedItems && x >= screenX(it.position) - tolerance && x <= screenX(it.end) + tolerance
            }
            ?: return null
        val x0 = screenX(item.position)
        val x1 = screenX(item.end)
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
