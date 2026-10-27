package com.alonmeller.mobiledaw

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.toArgb
import com.alonmeller.mobiledaw.audio.NativeEngine
import com.alonmeller.mobiledaw.data.AacEncoder
import com.alonmeller.mobiledaw.data.AudioExport
import com.alonmeller.mobiledaw.data.ImportedAudio
import com.alonmeller.mobiledaw.data.ProjectInfo
import com.alonmeller.mobiledaw.data.ProjectLibrary
import com.alonmeller.mobiledaw.data.ProjectMeta
import com.alonmeller.mobiledaw.data.ProjectSettings
import com.alonmeller.mobiledaw.data.ProjectStore
import com.alonmeller.mobiledaw.data.WavFile
import com.alonmeller.mobiledaw.model.DrumKit
import com.alonmeller.mobiledaw.model.Edits
import com.alonmeller.mobiledaw.model.Effect
import com.alonmeller.mobiledaw.model.EffectType
import com.alonmeller.mobiledaw.model.EnvKind
import com.alonmeller.mobiledaw.model.EnvPoint
import com.alonmeller.mobiledaw.model.Envelopes
import com.alonmeller.mobiledaw.model.History
import com.alonmeller.mobiledaw.model.Ids
import com.alonmeller.mobiledaw.model.Instrument
import com.alonmeller.mobiledaw.model.InstrumentType
import com.alonmeller.mobiledaw.model.Item
import com.alonmeller.mobiledaw.model.MASTER
import com.alonmeller.mobiledaw.model.FIRST_PAD_NOTE
import com.alonmeller.mobiledaw.model.Marker
import com.alonmeller.mobiledaw.model.Midi
import com.alonmeller.mobiledaw.model.MidiFile
import com.alonmeller.mobiledaw.model.Note
import com.alonmeller.mobiledaw.model.PAD_COUNT
import com.alonmeller.mobiledaw.model.Pad
import com.alonmeller.mobiledaw.model.Project
import com.alonmeller.mobiledaw.model.ReaperProject
import com.alonmeller.mobiledaw.model.Scales
import com.alonmeller.mobiledaw.model.Track
import com.alonmeller.mobiledaw.ui.DawColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/** Frames per waveform bucket; fine enough for the deepest zoom level. */
const val PEAK_BUCKET = 256

/** An audio buffer in the engine plus what the UI needs to draw it. */
class SourceInfo(val fileName: String, val length: Long, val peaks: FloatArray)

/** What a drag on an item changes, decided by where the finger lands. */
enum class DragMode { Move, TrimLeft, TrimRight, FadeIn, FadeOut }

/** Grid choices: notes per bar. */
val GRID_DIVISIONS = listOf(1, 2, 4, 8, 16)

/** What fills the screen under the transport bar. */
enum class MainView { Arrange, Mixer, Play }

/** Kinds of track that "+ Track" adds. */
enum class TrackKind { Audio, Synth, Drums }

/** How fast meters fall back after a peak. */
private const val METER_FALL_DB_PER_SECOND = 24f

/** What "Export" makes, as in REAPER's Render to File and GarageBand's Share. */
enum class ExportKind { Song, Stems, Midi, Reaper }

enum class ExportFormat(val label: String) { Wav24("WAV 24-bit"), Wav16("WAV 16-bit"), M4a("M4A (AAC)") }

data class ExportOptions(
    val kind: ExportKind = ExportKind.Song,
    val format: ExportFormat = ExportFormat.Wav24,
    /** Only the loop range instead of the whole song (REAPER's "time selection" bounds). */
    val loopOnly: Boolean = false,
    /** Brings the loudest peak to -1 dB. */
    val normalize: Boolean = true,
    /** Lets reverb, delay and notes ring out after the end (REAPER's render tail). */
    val tails: Boolean = true,
)

/** A finished export: the file to share and a line about it. */
class ExportResult(val file: File, val mime: String, val details: String)

/** Peak level that "Normalize" aims for: -1 dB, so encoders never clip. */
private const val NORMALIZE_PEAK = 0.891f

class DawViewModel(app: Application) : AndroidViewModel(app) {
    private val library = ProjectLibrary(app.filesDir)
    /** Folder name of the open song, and its store. */
    private var songId = library.current()
    private var store = ProjectStore(library.folder(songId))
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

    /** The timeline, the mixer, or the pads and keys of an instrument. */
    var view by mutableStateOf(MainView.Arrange)
        private set
    /** Instrument track that the play view plays. */
    var playTrackId by mutableLongStateOf(-1L)
        private set
    /** True while notes played on the pads or keys are recorded into the armed instrument track. */
    var recordingMidi by mutableStateOf(false)
        private set
    /** Track id and pad index being sampled from the microphone, Koala style. */
    var samplingPad by mutableStateOf<Pair<Long, Int>?>(null)
        private set
    /** MIDI item open in the piano roll. */
    var noteEditorItemId by mutableStateOf<Long?>(null)
        private set
    /** True while the piano roll loops its pattern. */
    var auditioning by mutableStateOf(false)
        private set
    /** Track id (or [MASTER]) whose effect chain is open, or null. */
    var fxTarget by mutableStateOf<Long?>(null)
        private set
    /** Tracks showing an automation envelope over their lane. */
    var envModes by mutableStateOf(mapOf<Long, EnvKind>())
        private set
    var message by mutableStateOf<String?>(if (engineStarted) null else "Audio engine failed to start")

    /** Name of the open song. */
    var songName by mutableStateOf(ProjectLibrary.DEFAULT_NAME)
        private set
    /** The song list, while it is showing. */
    var songs by mutableStateOf(listOf<ProjectInfo>())
        private set
    var showSongs by mutableStateOf(false)
        private set

    /** Timeline zoom in dp per second, and horizontal scroll in px. */
    var zoomDp by mutableFloatStateOf(60f)
        private set
    var scrollPx by mutableFloatStateOf(0f)
    var laneWidthPx by mutableFloatStateOf(0f)

    private val history = History<Project>()
    private var gestureBase: Project? = null
    private var saveJob: Job? = null
    /** True once [project] holds the open song, so a save can never write an empty project over it. */
    private var songLoaded = false
    private var nextTrackNumber = 1

    init {
        viewModelScope.launch { loadProject() }
    }

    // ------------------------------------------------------------ load / save / sync

    private suspend fun loadProject() {
        val target = store
        val loaded = withContext(Dispatchers.IO) {
            val newSources = mutableMapOf<Int, SourceInfo>()
            target.sourceLengthOf = { id -> NativeEngine.sourceLength(id) }
            val result = target.load(sampleRate) { file ->
                val audio = WavFile.read(target.audioFile(file)) ?: return@load null
                val samples = WavFile.resample(audio.samples, audio.sampleRate, sampleRate)
                val id = NativeEngine.createSource(samples)
                newSources[id] = SourceInfo(file, samples.size.toLong(), NativeEngine.sourcePeaks(id, PEAK_BUCKET))
                id
            }
            if (result != null) target.deleteUnused(newSources.values.map { it.fileName }.toSet())
            Triple(result, newSources, ProjectMeta.read(target.dir)?.name ?: ProjectLibrary.DEFAULT_NAME)
        }
        val (result, newSources, name) = loaded
        sources = newSources
        songName = name
        settings = result?.settings ?: ProjectSettings()
        NativeEngine.setMetronome(settings.metronome)
        applyProject(result?.project ?: Project())
        nextTrackNumber = (result?.project?.tracks?.size ?: 0) + 1
        songLoaded = true
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

    /** Instruments the engine already has, with the settings it was last given. */
    private val pushedInstruments = mutableMapOf<Long, Instrument>()

    private fun syncInstruments(p: Project) {
        for (inst in p.instruments) {
            val prev = pushedInstruments[inst.id]
            if (prev == null) {
                NativeEngine.createInstrument(inst.id, inst.type.code, inst.values.toFloatArray())
            } else if (prev.values != inst.values) {
                inst.values.forEachIndexed { i, v ->
                    if (prev.values.getOrNull(i) != v) NativeEngine.setInstrumentParam(inst.id, i, v)
                }
            }
            if (inst.pads.isNotEmpty() && prev?.pads != inst.pads) pushPads(inst)
            pushedInstruments[inst.id] = inst
        }
    }

    private fun pushPads(inst: Instrument) {
        val pads = inst.pads
        val params = FloatArray(pads.size * 5)
        val ranges = LongArray(pads.size * 2)
        pads.forEachIndexed { i, pad ->
            params[i * 5] = pad.gain
            params[i * 5 + 1] = pad.pitch
            params[i * 5 + 2] = if (pad.reverse) 1f else 0f
            params[i * 5 + 3] = if (pad.oneShot) 1f else 0f
            params[i * 5 + 4] = pad.chokeGroup.toFloat()
            ranges[i * 2] = pad.start
            ranges[i * 2 + 1] = pad.end
        }
        NativeEngine.setDrumPads(inst.id, IntArray(pads.size) { pads[it].sourceId }, params, ranges)
    }

    /** Timeline notes of each MIDI item; items never change in place, so they key the cache by identity. */
    private var noteCache = IdentityHashMap<Item, List<Note>>()

    fun timelineNotes(item: Item): List<Note> = noteCache.getOrPut(item) { Midi.timelineNotes(item) }

    /** Pushes the project to the audio engine. */
    private fun sync(p: Project) {
        syncEffects(p)
        syncInstruments(p)
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
        // Instrument tracks play the notes of their unmuted MIDI items.
        val cache = IdentityHashMap<Item, List<Note>>()
        for (t in p.tracks) for (item in t.items) if (item.isMidi) cache[item] = noteCache[item] ?: Midi.timelineNotes(item)
        noteCache = cache
        val trackNotes = p.tracks.map { t ->
            if (t.instrument == null) emptyList() else t.items.filter { it.isMidi && !it.mute }.flatMap { cache.getValue(it) }
        }
        val noteCounts = IntArray(p.tracks.size) { trackNotes[it].size }
        val noteTimes = LongArray(noteCounts.sum() * 2)
        val noteValues = FloatArray(noteTimes.size)
        var n = 0
        for (notes in trackNotes) for (note in notes) {
            noteTimes[n * 2] = note.start
            noteTimes[n * 2 + 1] = note.length
            noteValues[n * 2] = note.pitch.toFloat()
            noteValues[n * 2 + 1] = note.velocity
            n++
        }
        NativeEngine.setArrangement(
            trackParams, counts, itemParams, itemGains,
            fxCounts, fxIds,
            envCounts, points.map { it.frame }.toLongArray(), points.map { it.value }.toFloatArray(),
            LongArray(p.tracks.size) { p.tracks[it].instrument?.id ?: -1L }, noteCounts, noteTimes, noteValues,
            p.masterGain,
        )
        NativeEngine.setTempo(p.tempo)
        // While the piano roll auditions its pattern, the engine loops that instead.
        val audition = if (auditioning) noteEditorItemId?.let { p.findItem(it)?.second } else null
        if (audition != null) {
            NativeEngine.setLoop(audition.position, audition.position + minOf(audition.length, Midi.patternLength(audition)), true)
        } else {
            NativeEngine.setLoop(p.loopStart, p.loopEnd, p.loopOn)
        }
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
        if (noteEditorItemId != null && noteEditorItemId !in itemIds) {
            if (auditioning) toggleAudition()
            noteEditorItemId = null
        }
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
        if (!songLoaded) return
        val p = project
        val s = settings
        val files = sources.mapValues { it.value.fileName }
        val target = store
        viewModelScope.launch(Dispatchers.IO) { runCatching { target.save(p, s, sampleRate) { files[it] } } }
    }

    /** Saves the open song now and waits until it is on disk. */
    private suspend fun saveAndWait() {
        saveJob?.cancel()
        if (!songLoaded) return
        val p = project
        val s = settings
        val files = sources.mapValues { it.value.fileName }
        val target = store
        withContext(Dispatchers.IO) { runCatching { target.save(p, s, sampleRate) { files[it] } } }
    }

    /** Writes a new source's WAV file into the open song's folder, off the main thread. */
    private fun writeSource(fileName: String, samples: FloatArray) {
        val file = store.audioFile(fileName)
        viewModelScope.launch(Dispatchers.IO) { runCatching { WavFile.writeFloatMono(file, samples, sampleRate) } }
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
        if (exportProgress != null) exportProgress = NativeEngine.renderProgress()
        masterLevel = meter(masterLevel, NativeEngine.masterPeak())
        if (recording || samplingPad != null) {
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
        if (recordingMidi) {
            stopMidiRecording()
            return
        }
        if (auditioning) {
            toggleAudition()
            return
        }
        playing = !playing
        NativeEngine.setPlaying(playing)
    }

    /** Stop button: first press stops, a second press returns to the start. */
    fun stop() {
        if (recording) stopRecording()
        if (recordingMidi) stopMidiRecording()
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

    fun changeTempo(delta: Double) = setTempo(project.tempo + delta)

    /**
     * Sets the tempo. MIDI items and the loop keep their place in bars, as
     * with REAPER's beat timebase; audio stays where it was recorded.
     */
    fun setTempo(bpm: Double) {
        val p = project
        val tempo = bpm.coerceIn(30.0, 300.0)
        if (tempo == p.tempo) return
        val ratio = p.tempo / tempo
        commit(
            p.copy(
                tempo = tempo,
                loopStart = (p.loopStart * ratio).roundToLong(),
                loopEnd = (p.loopEnd * ratio).roundToLong(),
                tracks = p.tracks.map { t ->
                    if (t.items.none { it.isMidi }) t
                    else t.copy(items = t.items.map { if (it.isMidi) Midi.scaleTime(it, ratio) else it })
                },
            )
        )
    }

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

    /** True when the record button records notes (an instrument track is armed) rather than the microphone. */
    val recordsMidi: Boolean get() = project.tracks.firstOrNull { it.id == armedTrackId }?.instrument != null

    fun toggleRecord() {
        when {
            recording -> stopRecording()
            recordingMidi -> stopMidiRecording()
            samplingPad != null -> Unit
            recordsMidi -> startMidiRecording()
            else -> startRecording()
        }
    }

    private fun startRecording() {
        if (auditioning) toggleAudition()
        if (!NativeEngine.startRecording(true)) {
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
        writeSource(info.fileName, NativeEngine.sourceSamples(sourceId))

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
    fun isRecordingInto(track: Track) = (recording || recordingMidi) && track.id == armedTrackId

    // ------------------------------------------------------------ playing instruments and recording MIDI

    /** Notes held live on each instrument, so none is left hanging. */
    private val liveHeld = HashMap<Long, MutableSet<Int>>()
    /** Notes being recorded: pitch to the frame and velocity they started with. */
    private val heldNotes = HashMap<Int, Pair<Long, Float>>()
    /** Item that this recording made because no pattern was under its first note; it grows bar by bar. */
    private var takeItemId = -1L
    private var outputLatency = 0L

    /** Plays a note on [trackId]'s instrument now, and records it while that track records. */
    fun noteOn(trackId: Long, pitch: Int, velocity: Float) {
        val inst = project.tracks.firstOrNull { it.id == trackId }?.instrument ?: return
        NativeEngine.liveNote(inst.id, pitch, velocity)
        liveHeld.getOrPut(inst.id) { mutableSetOf() } += pitch
        if (recordingMidi && trackId == armedTrackId) heldNotes[pitch] = recordFrame() to velocity
    }

    fun noteOff(trackId: Long, pitch: Int) {
        val inst = project.tracks.firstOrNull { it.id == trackId }?.instrument ?: return
        NativeEngine.liveNote(inst.id, pitch, 0f)
        liveHeld[inst.id]?.remove(pitch)
        if (recordingMidi && trackId == armedTrackId) finishNote(pitch, recordFrame())
    }

    /** Plays a short note, as feedback while editing. */
    fun previewNote(trackId: Long, pitch: Int, velocity: Float = 0.8f) {
        val inst = project.tracks.firstOrNull { it.id == trackId }?.instrument ?: return
        NativeEngine.liveNote(inst.id, pitch, velocity)
        viewModelScope.launch {
            delay(250)
            NativeEngine.liveNote(inst.id, pitch, 0f)
        }
    }

    /** Releases every note held live, for when the pads or keys go away mid-touch. */
    fun releaseLiveNotes() {
        for ((id, pitches) in liveHeld) for (pitch in pitches) NativeEngine.liveNote(id, pitch, 0f)
        liveHeld.clear()
    }

    private fun startMidiRecording() {
        if (auditioning) toggleAudition()
        heldNotes.clear()
        takeItemId = -1L
        outputLatency = NativeEngine.outputLatency()
        recordStart = position
        recordingMidi = true
        if (view != MainView.Play) {
            project.tracks.firstOrNull { it.id == armedTrackId }?.let { message = "Recording notes into ${it.name}: play its pads or keys" }
        }
        if (!playing) {
            playing = true
            NativeEngine.setPlaying(true)
        }
    }

    private fun stopMidiRecording() {
        val now = recordFrame()
        heldNotes.keys.toList().forEach { finishNote(it, now) }
        recordingMidi = false
        // The whole take is one undo step.
        endGesture()
    }

    /** Timeline frame the performer hears now: the engine renders ahead of the speaker by the output latency. */
    private fun recordFrame(): Long {
        val p = project
        val rendered = NativeEngine.position()
        var frame = rendered - outputLatency
        // Just after a loop jump the speaker still plays the end of the loop.
        if (p.loopOn && p.loopEnd > p.loopStart && rendered >= p.loopStart && frame < p.loopStart) {
            frame += p.loopEnd - p.loopStart
        }
        return frame.coerceAtLeast(0)
    }

    private fun finishNote(pitch: Int, end: Long) {
        val (played, velocity) = heldNotes.remove(pitch) ?: return
        val p = project
        val track = p.tracks.firstOrNull { it.id == armedTrackId } ?: return
        val looping = p.loopOn && p.loopEnd > p.loopStart
        var start = played
        if (settings.quantizeRecording) {
            val step = framesPerBeat / 4
            start = ((played / step).roundToLong() * step).roundToLong()
            // A note pulled onto the loop end is heard at the loop start.
            if (looping && played < p.loopEnd && start >= p.loopEnd) start -= p.loopEnd - p.loopStart
        }
        val held = when {
            end >= played -> end - played
            looping -> p.loopEnd - played // the loop jumped while the note was held
            else -> 0L
        }
        val shortest = framesPerBeat / if (track.instrument?.type == InstrumentType.DRUMS) 4 else 8
        recordNote(track, Note(start, held.coerceAtLeast(shortest.roundToLong()), pitch, velocity))
    }

    /** Puts a recorded note into the pattern under it, or else into the item this take makes. */
    private fun recordNote(track: Track, note: Note) {
        val p = project
        val bar = framesPerBeat * 4
        val under = track.items.lastOrNull { it.isMidi && note.start >= it.position && note.start < it.end }
        val take = track.items.firstOrNull { it.id == takeItemId }
        val next = when {
            under != null -> p.updateItem(under.id) { Midi.mergeRecorded(it, listOf(note)) }
            take != null && note.start >= take.end -> {
                // The take grows to the end of the bar this note is in.
                val length = Midi.barAround(note.start, bar).second - take.position
                p.updateItem(take.id) { Midi.mergeRecorded(it.copy(length = length, loopLength = length), listOf(note)) }
            }
            else -> {
                val (start, end) = if (p.loopOn && note.start >= p.loopStart && note.start < p.loopEnd) {
                    p.loopStart to p.loopEnd
                } else {
                    Midi.barAround(note.start, bar)
                }
                val item = Midi.mergeRecorded(Midi.newItem(start, end - start), listOf(note))
                takeItemId = item.id
                p.updateTrack(track.id) { it.copy(items = (it.items + item).sortedBy { i -> i.position }) }
            }
        }
        // Each note is heard on the next pass through the loop.
        preview(next)
    }

    // ------------------------------------------------------------ tracks

    private fun newTrack(): Track {
        val number = nextTrackNumber++
        return Track(Ids.next(), "Audio $number", number - 1)
    }

    /**
     * Adds an empty track and arms it, so the next recording lands there.
     * A drum track comes with the built-in kit and an empty one-bar pattern
     * at the playhead, ready for the step sequencer.
     */
    fun addTrack(kind: TrackKind = TrackKind.Audio) {
        val t = when (kind) {
            TrackKind.Audio -> newTrack()
            TrackKind.Synth -> {
                val number = nextTrackNumber++
                Track(Ids.next(), "Synth $number", number - 1, instrument = Instrument(Ids.next(), InstrumentType.SYNTH))
            }
            TrackKind.Drums -> {
                val number = nextTrackNumber++
                val (start, end) = Midi.barAround(position, framesPerBeat * 4)
                Track(
                    Ids.next(), "Drums $number", number - 1,
                    items = listOf(Midi.newItem(start, end - start)),
                    instrument = Instrument(Ids.next(), InstrumentType.DRUMS, pads = List(PAD_COUNT) { kitPad(it) }),
                )
            }
        }
        commit(project.copy(tracks = project.tracks + t))
        armedTrackId = t.id
        selectedTrackId = t.id
        selectedItems = t.items.map { it.id }.toSet()
        playTrackId = if (t.instrument != null) t.id else playTrackId
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
            // Its own instrument, so the two tracks never share voices.
            instrument = t.instrument?.copy(id = Ids.next()),
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

    /** Shows [v], or goes back to the timeline when it is already showing. */
    fun showView(v: MainView) {
        if (view == v) {
            view = MainView.Arrange
            return
        }
        if (v == MainView.Play) playTrack?.let { openPlay(it.id) }
        view = v
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
            // MIDI items have no fades.
            !item.isMidi && y < handleZone && abs(x - (x0 + framesToPx(item.fadeIn))) < edge * 1.4f -> DragMode.FadeIn
            !item.isMidi && y < handleZone && abs(x - (x1 - framesToPx(item.fadeOut))) < edge * 1.4f -> DragMode.FadeOut
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
        val srcLen = if (base.isMidi) Midi.sourceLength(base) else sources[base.sourceId]?.length ?: base.length
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

    // ------------------------------------------------------------ instruments and drum pads

    /** The track the play view shows: the chosen one, else the selected or the first instrument track. */
    val playTrack: Track?
        get() = project.tracks.firstOrNull { it.id == playTrackId && it.instrument != null }
            ?: project.tracks.firstOrNull { it.id == selectedTrackId && it.instrument != null }
            ?: project.tracks.firstOrNull { it.instrument != null }

    /** Shows the pads or keys of an instrument track and arms it, so recording captures what is played. */
    fun openPlay(trackId: Long) {
        if (project.tracks.none { it.id == trackId && it.instrument != null }) return
        playTrackId = trackId
        selectedTrackId = trackId
        if (!recording && !recordingMidi) armedTrackId = trackId
        view = MainView.Play
    }

    fun toggleQuantizeRecording() {
        settings = settings.copy(quantizeRecording = !settings.quantizeRecording)
        scheduleSave()
    }

    /** Live synth or kit parameter change; [endGesture] makes it one undo step. */
    fun setInstrumentParam(trackId: Long, index: Int, value: Float) {
        if (gestureBase == null) gestureBase = project
        val p = project.updateTrack(trackId) { t ->
            val inst = t.instrument ?: return@updateTrack t
            t.copy(instrument = inst.copy(values = inst.values.toMutableList().also { it[index] = value }))
        }
        project = p
        syncInstruments(p)
    }

    fun resetInstrument(trackId: Long) = commit(
        project.updateTrack(trackId) { t -> t.copy(instrument = t.instrument?.let { it.copy(values = it.type.defaults()) }) }
    )

    /** Changes one pad; [live] changes (sliders) become one undo step at [endGesture]. */
    fun updatePad(trackId: Long, index: Int, live: Boolean = false, change: (Pad) -> Pad) {
        val p = project.updateTrack(trackId) { t ->
            val inst = t.instrument ?: return@updateTrack t
            t.copy(instrument = inst.copy(pads = inst.pads.mapIndexed { i, pad -> if (i == index) change(pad) else pad }))
        }
        if (live) preview(p) else commit(p)
    }

    /** Engine source of built-in kit sound [index], made and saved with the project on first use. */
    private fun kitSource(index: Int): Int {
        val sound = DrumKit.sounds[index]
        sources.entries.firstOrNull { it.value.fileName == sound.file }?.let { return it.key }
        val samples = DrumKit.render(index, sampleRate)
        val id = NativeEngine.createSource(samples)
        sources = sources + (id to SourceInfo(sound.file, samples.size.toLong(), NativeEngine.sourcePeaks(id, PEAK_BUCKET)))
        writeSource(sound.file, samples)
        return id
    }

    private fun kitPad(index: Int): Pad {
        val sound = DrumKit.sounds[index]
        return Pad(sourceId = kitSource(index), name = sound.name, chokeGroup = sound.chokeGroup)
    }

    fun setPadKitSound(trackId: Long, index: Int, sound: Int) {
        val pad = kitPad(sound)
        updatePad(trackId, index) { pad }
    }

    fun clearPad(trackId: Long, index: Int) = updatePad(trackId, index) { Pad() }

    /** The selected audio item, which can be loaded into a pad. */
    val selectedAudioItem: Item?
        get() = project.tracks.flatMap { it.items }.firstOrNull { it.id in selectedItems && !it.isMidi }

    /** Loads the selected audio item into a pad, like sending an item to REAPER's sampler. */
    fun setPadFromSelectedItem(trackId: Long, index: Int) {
        val item = selectedAudioItem ?: return
        updatePad(trackId, index) {
            Pad(sourceId = item.sourceId, name = "Clip", gain = item.gain, start = item.offset, end = item.offset + item.length)
        }
    }

    /** Records the microphone into a pad until [stopSampling]; the transport stays put. */
    fun startSampling(trackId: Long, index: Int) {
        if (recording || recordingMidi || samplingPad != null) return
        if (auditioning) toggleAudition()
        if (playing) {
            playing = false
            NativeEngine.setPlaying(false)
        }
        if (!NativeEngine.startRecording(false)) {
            message = "Could not open the microphone"
            return
        }
        recordedFrames = 0
        samplingPad = trackId to index
    }

    fun stopSampling() {
        val (trackId, index) = samplingPad ?: return
        samplingPad = null
        inputLevel = 0f
        val id = NativeEngine.stopRecording()
        if (id < 0) return
        val samples = NativeEngine.sourceSamples(id)
        val info = SourceInfo("sample_${UUID.randomUUID()}.wav", samples.size.toLong(), NativeEngine.sourcePeaks(id, PEAK_BUCKET))
        sources = sources + (id to info)
        writeSource(info.fileName, samples)
        // Skip the silence before and after the sound, as Koala trims a fresh sample.
        val (start, end) = Edits.soundRegion(samples, sampleRate)
        updatePad(trackId, index) { Pad(sourceId = id, name = "Sample", start = start, end = end) }
    }

    // ------------------------------------------------------------ importing audio files

    /** Turns decoded audio into an engine source saved with the project, off the main thread. */
    private suspend fun importSource(audio: ImportedAudio, prefix: String): Int? {
        val song = songId
        val target = store
        val (id, info) = withContext(Dispatchers.Default) {
            val samples = WavFile.resample(audio.samples, audio.sampleRate, sampleRate)
            if (samples.isEmpty()) return@withContext null
            val id = NativeEngine.createSource(samples)
            val info = SourceInfo("${prefix}_${UUID.randomUUID()}.wav", samples.size.toLong(), NativeEngine.sourcePeaks(id, PEAK_BUCKET))
            withContext(Dispatchers.IO) { runCatching { WavFile.writeFloatMono(target.audioFile(info.fileName), samples, sampleRate) } }
            id to info
        } ?: return null
        // Another song was opened meanwhile: this import belonged to the old one.
        if (song != songId) return null
        sources = sources + (id to info)
        return id
    }

    /** Puts an imported file on a new track at the playhead, like REAPER's Insert > Media file. */
    fun importAudio(audio: ImportedAudio) {
        message = "Importing ${audio.name}…"
        viewModelScope.launch {
            val id = importSource(audio, "import") ?: return@launch
            val t = newTrack().copy(name = audio.name.take(24).ifEmpty { "Import" })
            val item = Item(Ids.next(), id, position, 0, sources[id]?.length ?: 0L)
            commit(project.copy(tracks = project.tracks + t.copy(items = listOf(item))))
            selectedTrackId = t.id
            selectedItems = setOf(item.id)
            message = "Imported ${t.name}"
        }
    }

    /** Loads an imported file into a pad. */
    fun importToPad(trackId: Long, index: Int, audio: ImportedAudio) {
        viewModelScope.launch {
            val id = importSource(audio, "pad") ?: return@launch
            updatePad(trackId, index) { Pad(sourceId = id, name = audio.name.take(14).ifEmpty { "Sound" }) }
        }
    }

    // ------------------------------------------------------------ MIDI items and the piano roll

    val selectedMidiItem: Item?
        get() = project.tracks.flatMap { it.items }.firstOrNull { it.id in selectedItems && it.isMidi }

    /** Adds an empty pattern at the playhead's bar on the selected instrument track. */
    fun addPattern() {
        val track = project.tracks.firstOrNull { it.id == selectedTrackId && it.instrument != null }
        if (track == null) {
            message = "Select a synth or drum track first"
            return
        }
        val bar = framesPerBeat * 4
        val start = Midi.barAround(position, bar).first
        val bars = if (track.instrument?.type == InstrumentType.DRUMS) 1 else 4
        val item = Midi.newItem(start, (bar * bars).roundToLong())
        commit(project.updateTrack(track.id) { it.copy(items = (it.items + item).sortedBy { i -> i.position }) })
        selectedItems = setOf(item.id)
    }

    fun openNoteEditor(itemId: Long) {
        val (track, item) = project.findItem(itemId) ?: return
        if (!item.isMidi) return
        selectedItems = setOf(itemId)
        selectedTrackId = track.id
        noteEditorItemId = itemId
    }

    fun closeNoteEditor() {
        if (auditioning) toggleAudition()
        endGesture()
        noteEditorItemId = null
    }

    /** Replaces a MIDI item's notes; [live] changes (drags) become one undo step at [endGesture]. */
    fun setNotes(itemId: Long, notes: List<Note>, live: Boolean = false) {
        val p = project.updateItem(itemId) { it.copy(notes = Midi.sorted(notes)) }
        if (live) preview(p) else commit(p)
    }

    fun setPatternBars(itemId: Long, bars: Int) {
        val length = (framesPerBeat * 4 * bars).roundToLong()
        commit(project.updateItem(itemId) { Midi.setPatternLength(it, length) })
    }

    /** Pulls the pattern's notes onto a grid of [division] notes per bar. */
    fun quantizeNotes(itemId: Long, division: Int) {
        val grid = framesPerBeat * 4 / division
        commit(project.updateItem(itemId) { it.copy(notes = Midi.quantize(it.notes ?: emptyList(), grid, it.loopLength)) })
    }

    /** Loops the pattern open in the piano roll, so it plays while it is edited. */
    fun toggleAudition() {
        if (auditioning) {
            auditioning = false
            playing = false
            NativeEngine.setPlaying(false)
            NativeEngine.setLoop(project.loopStart, project.loopEnd, project.loopOn)
            return
        }
        if (recording || recordingMidi || samplingPad != null) return
        val item = noteEditorItemId?.let { project.findItem(it)?.second } ?: return
        auditioning = true
        sync(project)
        seekFrames(item.position)
        playing = true
        NativeEngine.setPlaying(true)
    }

    /** Where the playhead is inside the open pattern, or null when it is not playing it. */
    fun patternPosition(item: Item): Long? {
        if (!playing || position < item.position || position >= item.end) return null
        val local = position - item.position + item.offset
        val loop = Midi.patternLength(item)
        return if (item.loopLength > 0) Math.floorMod(local, loop) else local
    }

    /** Ends recording, sampling, auditioning and playback. */
    private fun stopAll() {
        if (recording) stopRecording()
        if (recordingMidi) stopMidiRecording()
        if (samplingPad != null) stopSampling()
        releaseLiveNotes()
        if (auditioning) toggleAudition()
        if (playing) {
            playing = false
            NativeEngine.setPlaying(false)
        }
    }

    fun onAppBackground() {
        stopAll()
        saveJob?.cancel()
        saveNow()
    }

    // ------------------------------------------------------------ songs (GarageBand's "My Songs")

    fun openSongs() {
        if (recording || recordingMidi || samplingPad != null) return
        showSongs = true
        refreshSongs()
    }

    fun closeSongs() {
        showSongs = false
    }

    private fun refreshSongs() {
        viewModelScope.launch {
            // The open song's length is saved with it, so save first for an up-to-date list.
            saveAndWait()
            songs = withContext(Dispatchers.IO) { library.list() }
        }
    }

    val currentSongId: String get() = songId

    fun openSong(id: String) {
        if (id == songId) {
            showSongs = false
            return
        }
        switchSong { id }
    }

    fun newSong() = switchSong { library.create(ProjectLibrary.DEFAULT_NAME) }

    fun renameSong(id: String, name: String) {
        val clean = name.trim().take(60)
        if (clean.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { library.rename(id, clean) }
            if (id == songId) songName = clean
            refreshSongs()
        }
    }

    fun duplicateSong(id: String) {
        viewModelScope.launch {
            if (id == songId) saveAndWait()
            val copy = withContext(Dispatchers.IO) { library.duplicate(id) }
            if (copy == null) message = "Could not copy the song (is the phone full?)"
            refreshSongs()
        }
    }

    /** Deletes a song; deleting the open one opens the newest other song, or a new one. */
    fun deleteSong(id: String) {
        if (id == songId) {
            switchSong {
                // Deleted first, so a new song made to replace the last one is plain "My Song".
                library.delete(id)
                library.list().firstOrNull()?.id ?: library.create(ProjectLibrary.DEFAULT_NAME)
            }
        } else {
            viewModelScope.launch {
                withContext(Dispatchers.IO) { library.delete(id) }
                refreshSongs()
            }
        }
    }

    /** Saves the open song, then opens the one [target] picks (on a background thread). */
    private fun switchSong(target: () -> String) {
        if (loading || exportProgress != null) return
        loading = true
        stopAll()
        viewModelScope.launch {
            saveAndWait()
            val id = withContext(Dispatchers.IO) { target().also { library.setCurrent(it) } }
            resetSession()
            songId = id
            store = ProjectStore(library.folder(id))
            loadProject()
            showSongs = false
            message = "Opened $songName"
        }
    }

    /** Forgets everything about the open song, in the engine and here. */
    private fun resetSession() {
        saveJob?.cancel()
        songLoaded = false
        NativeEngine.clearSession()
        pushedEffects.clear()
        pushedInstruments.clear()
        noteCache = IdentityHashMap()
        history.clear()
        gestureBase = null
        clipboard = null
        dragItem = null
        liveHeld.clear()
        heldNotes.clear()
        sources = emptyMap()
        project = Project()
        settings = ProjectSettings()
        selectedItems = emptySet()
        selectedTrackId = -1
        armedTrackId = -1
        playTrackId = -1
        noteEditorItemId = null
        fxTarget = null
        envModes = emptyMap()
        envDrag = null
        view = MainView.Arrange
        canUndo = false
        canRedo = false
        nextTrackNumber = 1
        trackLevels = emptyMap()
        scrollPx = 0f
        seekFrames(0)
    }

    // ------------------------------------------------------------ export (REAPER's Render to File)

    var exportOpen by mutableStateOf(false)
        private set
    var exportOptions by mutableStateOf(ExportOptions())
        private set
    /** 0..1 while an export renders, null otherwise. */
    var exportProgress by mutableStateOf<Float?>(null)
        private set
    var exportResult by mutableStateOf<ExportResult?>(null)
        private set
    private var exportJob: Job? = null

    private val exportDir: File get() = File(getApplication<Application>().cacheDir, "exports")

    fun openExport() {
        if (recording || recordingMidi || samplingPad != null) return
        exportResult = null
        exportOpen = true
    }

    fun closeExport() {
        cancelExport()
        exportOpen = false
    }

    fun changeExportOptions(change: (ExportOptions) -> ExportOptions) {
        exportOptions = change(exportOptions)
        exportResult = null
    }

    fun cancelExport() {
        if (exportJob?.isActive == true) NativeEngine.cancelRender()
    }

    /** True when the song has a loop range to export on its own. */
    val hasLoopRange: Boolean get() = project.loopEnd > project.loopStart

    /** Renders and writes the chosen export, like REAPER's Render button. */
    fun startExport() {
        if (exportJob?.isActive == true || loading) return
        val p = project
        val options = exportOptions
        val (start, end) = when {
            options.loopOnly && p.loopEnd > p.loopStart -> p.loopStart to p.loopEnd
            p.endFrame > 0 -> 0L to p.endFrame
            else -> {
                message = "Record or add something first: the song is empty"
                return
            }
        }
        stopAll()
        // Everything the export needs, read here on the main thread.
        val job = ExportJob(
            project = p,
            title = songName,
            start = start,
            end = end,
            options = options,
            files = sources.mapValues { it.value.fileName },
            songDir = store.dir,
            drumNotes = p.tracks.filter { it.instrument?.type == InstrumentType.DRUMS }.associate { t ->
                t.id to DrumKit.midiNotes(t.instrument!!.pads.map { pad ->
                    val file = sources[pad.sourceId]?.fileName
                    DrumKit.sounds.indexOfFirst { it.file == file }.takeIf { it >= 0 }
                })
            },
            colors = p.tracks.associate { it.id to (DawColors.track(it.colorIndex).toArgb() and 0xFFFFFF) },
        )
        exportResult = null
        exportProgress = 0f
        exportJob = viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val dir = exportDir.apply {
                        deleteRecursively()
                        mkdirs()
                    }
                    when (options.kind) {
                        ExportKind.Song -> exportSong(job, dir)
                        ExportKind.Stems -> exportStems(job, dir)
                        ExportKind.Midi -> exportMidi(job, dir)
                        ExportKind.Reaper -> exportReaper(job, dir)
                    }
                }
            }
            exportProgress = null
            result.onSuccess { r ->
                exportResult = r
                if (r == null) message = "Export cancelled"
            }.onFailure { message = "Export failed: ${it.message}" }
        }
    }

    private class ExportJob(
        val project: Project,
        val title: String,
        val start: Long,
        val end: Long,
        val options: ExportOptions,
        val files: Map<Int, String>,
        val songDir: File,
        val drumNotes: Map<Long, IntArray>,
        val colors: Map<Long, Int>,
    )

    private class RenderStats(val frames: Long, val peak: Float, val loudness: Double)

    /** Runs the engine's offline render into float WAV files; null when cancelled. */
    private fun render(
        start: Long,
        end: Long,
        tails: Boolean,
        mixPath: String = "",
        stemPaths: Array<String> = emptyArray(),
        dryPaths: Array<String> = emptyArray(),
    ): RenderStats? {
        val r = NativeEngine.render(start, end, if (tails) sampleRate * 10L else 0L, mixPath, stemPaths, dryPaths)
        if (r[1] > 0.5) return null
        check(r[0] > 0.5) { "could not write the audio (is the phone full?)" }
        return RenderStats(r[2].toLong(), r[3].toFloat(), r[4])
    }

    private fun duration(frames: Long): String {
        val seconds = frames / sampleRate
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun exportSong(job: ExportJob, dir: File): ExportResult? {
        val o = job.options
        val raw = File(dir, "mix.raw.wav")
        val stats = render(job.start, job.end, o.tails, mixPath = raw.path) ?: return null
        val gain = if (o.normalize && stats.peak > 1e-4f) NORMALIZE_PEAK / stats.peak else 1f
        val name = AudioExport.safeFileName(job.title, "Song")
        val out: File
        val mime: String
        if (o.format == ExportFormat.M4a) {
            out = File(dir, "$name.m4a")
            check(AacEncoder.encode(raw, out, gain)) { "this phone could not make an M4A file" }
            mime = "audio/mp4"
        } else {
            out = File(dir, "$name.wav")
            AudioExport.toPcm(raw, out, if (o.format == ExportFormat.Wav16) 16 else 24, gain)
            mime = "audio/wav"
        }
        raw.delete()
        val peak = stats.peak * gain
        val details = buildString {
            append("${duration(stats.frames)} · ${o.format.label}")
            if (peak > 0f) append(" · peak %+.1f dB".format(20 * log10(peak)))
            if (stats.loudness.isFinite()) append(" · %.1f LUFS".format(stats.loudness + 20 * log10(gain)))
            if (peak > 1f) append(" · clipped: turn on Normalize")
        }
        return ExportResult(out, mime, details)
    }

    private fun exportStems(job: ExportJob, dir: File): ExportResult? {
        val p = job.project
        val tracks = p.tracks.withIndex().filter { (_, t) -> !t.mute && t.items.any { !it.mute } }
        check(tracks.isNotEmpty()) { "no unmuted track has anything to play" }
        val raw = Array(p.tracks.size) { "" }
        tracks.forEach { (i, _) -> raw[i] = File(dir, "stem$i.raw.wav").path }
        val stats = render(job.start, job.end, job.options.tails, stemPaths = raw) ?: return null
        val bits = if (job.options.format == ExportFormat.Wav16) 16 else 24
        val folder = AudioExport.safeFileName("${job.title} stems", "Stems")
        val taken = mutableSetOf<String>()
        val entries = tracks.mapIndexed { n, (i, t) ->
            val base = "%02d %s".format(n + 1, AudioExport.safeFileName(t.name, "Track"))
            val wav = File(dir, AudioExport.unique(base, taken, ".wav"))
            AudioExport.toPcm(File(raw[i]), wav, bits)
            File(raw[i]).delete()
            "$folder/${wav.name}" to wav
        }
        val readme = File(dir, "README.txt")
        readme.writeText(
            """
            ${job.title}: one WAV file per track, from Mobile DAW.
            Tempo ${num(p.tempo)} BPM, 4/4, ${sampleRate} Hz. Every file starts at the same moment
            (${if (job.start == 0L) "the start of the song, bar 1" else "the start of the loop"}), so they line up when placed at the same point.

            GarageBand: set the song's tempo to ${num(p.tempo)}, then drag each file onto its own track at bar 1.
            REAPER: Insert > Media file, pick all the files and choose "one per track".

            כל הקבצים מתחילים באותו רגע. בגראג'בנד: קבעו טמפו ${num(p.tempo)} וגררו כל קובץ לערוץ משלו בתחילת השיר.
            """.trimIndent() + "\n"
        )
        val zip = File(dir, "$folder.zip")
        AudioExport.zip(zip, entries + ("$folder/README.txt" to readme))
        entries.forEach { it.second.delete() }
        return ExportResult(zip, "application/zip", "${entries.size} tracks · WAV $bits-bit · ${duration(stats.frames)}")
    }

    private fun num(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)

    /** Notes of the instrument tracks, drums mapped to General MIDI. */
    private fun midiParts(job: ExportJob): List<MidiFile.Part> = job.project.tracks.mapNotNull { t ->
        val inst = t.instrument ?: return@mapNotNull null
        val drums = inst.type == InstrumentType.DRUMS
        val map = job.drumNotes[t.id]
        val notes = t.items.filter { it.isMidi && !it.mute }.flatMap { Midi.timelineNotes(it) }.map { n ->
            if (map != null && n.pitch - FIRST_PAD_NOTE in map.indices) n.copy(pitch = map[n.pitch - FIRST_PAD_NOTE]) else n
        }
        if (notes.isEmpty()) null else MidiFile.Part(t.name, if (drums) MidiFile.DRUM_CHANNEL else 0, notes)
    }

    private fun exportMidi(job: ExportJob, dir: File): ExportResult {
        val parts = midiParts(job)
        check(parts.isNotEmpty()) { "there are no drum or synth notes yet" }
        val file = File(dir, AudioExport.safeFileName(job.title, "Song") + ".mid")
        file.writeBytes(MidiFile.write(job.title, job.project.tempo, sampleRate, job.project.markers, parts))
        return ExportResult(file, "audio/midi", "${parts.size} tracks · ${num(job.project.tempo)} BPM")
    }

    private fun exportReaper(job: ExportJob, dir: File): ExportResult? {
        val p = job.project
        val folder = AudioExport.asciiFileName(job.title, "Mobile DAW song")
        val entries = mutableListOf<Pair<String, File>>()
        val taken = mutableSetOf("$folder.rpp".lowercase())
        // The recordings, imports and samples the items play, as they are.
        val media = p.tracks.flatMap { it.items }.filter { !it.isMidi }.mapNotNull { job.files[it.sourceId] }.toSet()
            .filter { File(job.songDir, it).exists() }
        media.forEach {
            entries += "$folder/$it" to File(job.songDir, it)
            taken += it.lowercase()
        }
        // REAPER cannot play the app's instruments: render each one's sound to a file.
        val instrumentTracks = p.tracks.withIndex().filter { (_, t) -> t.instrument != null && t.items.any { it.isMidi } }
        val rendered = mutableMapOf<Long, ReaperProject.RenderedTrack>()
        val renders = mutableListOf<File>()
        if (instrumentTracks.isNotEmpty()) {
            val dry = Array(p.tracks.size) { "" }
            instrumentTracks.forEach { (i, _) -> dry[i] = File(dir, "dry$i.wav").path }
            val stats = render(0, p.endFrame, true, dryPaths = dry) ?: return null
            instrumentTracks.forEachIndexed { n, (i, t) ->
                val name = AudioExport.unique(AudioExport.asciiFileName(t.name, "Instrument ${n + 1}") + " (instrument)", taken, ".wav")
                val file = File(dir, name)
                File(dry[i]).renameTo(file)
                rendered[t.id] = ReaperProject.RenderedTrack(name, stats.frames)
                entries += "$folder/$name" to file
                renders += file
            }
        }
        val rpp = ReaperProject.write(
            project = p,
            sampleRate = sampleRate,
            mediaFile = { item -> job.files[item.sourceId]?.takeIf { it in media } },
            rendered = rendered,
            drumNotes = job.drumNotes,
            colors = job.colors,
            notes = reaperNotes(job),
            timestamp = System.currentTimeMillis() / 1000,
        )
        val rppFile = File(dir, "$folder.rpp").apply { writeText(rpp) }
        entries.add(0, "$folder/$folder.rpp" to rppFile)
        val zip = File(dir, "$folder (REAPER).zip")
        AudioExport.zip(zip, entries)
        rppFile.delete()
        renders.forEach { it.delete() }
        return ExportResult(zip, "application/zip", "${p.tracks.size} tracks · ${media.size + rendered.size} audio files")
    }

    /** REAPER's project notes: what could not come along, and how to add it back. */
    private fun reaperNotes(job: ExportJob): List<String> {
        val p = job.project
        fun describe(e: Effect) = e.type.title + (if (e.bypass) " (off)" else "") + ": " +
            e.type.params.mapIndexed { i, spec -> "${spec.name} ${spec.format(e.values.getOrElse(i) { spec.default })}" }.joinToString(", ")
        val lines = mutableListOf("${job.title} - from Mobile DAW", "")
        val withFx = p.tracks.filter { it.fx.isNotEmpty() }
        if (withFx.isNotEmpty() || p.masterFx.isNotEmpty()) {
            lines += "Effects are not included, because REAPER uses its own plug-ins (ReaEQ, ReaComp, ReaVerbate, ReaDelay...). They were:"
            withFx.forEach { t -> lines += "- ${t.name}: " + t.fx.joinToString("; ") { describe(it) } }
            if (p.masterFx.isNotEmpty()) lines += "- Master: " + p.masterFx.joinToString("; ") { describe(it) }
            lines += ""
        }
        if (p.tracks.any { it.instrument != null }) {
            lines += "Drum and synth tracks were rendered to audio. Their notes are on the \"MIDI\" track below each one:"
            lines += "add an instrument there (Insert virtual instrument on new track, or the track's FX button) to play them with another sound."
        }
        return lines
    }
}
