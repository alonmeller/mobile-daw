package com.alonmeller.mobiledaw

import android.app.Application
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.toArgb
import com.alonmeller.mobiledaw.audio.AudioStatus
import com.alonmeller.mobiledaw.audio.NativeEngine
import com.alonmeller.mobiledaw.data.AacEncoder
import com.alonmeller.mobiledaw.data.AudioExport
import com.alonmeller.mobiledaw.data.ImportedAudio
import com.alonmeller.mobiledaw.data.Preferences
import com.alonmeller.mobiledaw.data.PreferencesStore
import com.alonmeller.mobiledaw.data.ProjectInfo
import com.alonmeller.mobiledaw.data.ProjectLibrary
import com.alonmeller.mobiledaw.data.ProjectMeta
import com.alonmeller.mobiledaw.data.ProjectSettings
import com.alonmeller.mobiledaw.data.ProjectStore
import com.alonmeller.mobiledaw.data.StartupProject
import com.alonmeller.mobiledaw.data.ZoomAnchor
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
import com.alonmeller.mobiledaw.model.PeakTrail
import com.alonmeller.mobiledaw.model.Project
import com.alonmeller.mobiledaw.model.ReaperProject
import com.alonmeller.mobiledaw.model.Routing
import com.alonmeller.mobiledaw.model.Scales
import com.alonmeller.mobiledaw.model.Send
import com.alonmeller.mobiledaw.model.Take
import com.alonmeller.mobiledaw.model.Takes
import com.alonmeller.mobiledaw.model.Track
import com.alonmeller.mobiledaw.model.Zoom
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
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
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

/** Kinds of track that "+ Track" adds; a bus is an empty track with an effect that other tracks send to. */
enum class TrackKind { Audio, Synth, Drums, ReverbBus, DelayBus }

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

/** Input peak that counts as clipping while recording. */
private const val CLIP_LEVEL = 0.98f

/** Outputs that let the microphone be heard without feeding back: headphones, or an audio interface. */
private val HEADPHONE_TYPES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
)

/** Outputs only the listener hears, whose unplugging would send the song out of the speaker. */
private val PRIVATE_OUTPUTS = HEADPHONE_TYPES + setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET)

/** Outputs and inputs worth offering in the device settings: no earpiece, telephony or virtual devices. */
private val OUTPUT_CHOICES = PRIVATE_OUTPUTS + setOf(
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL,
    AudioDeviceInfo.TYPE_AUX_LINE,
)
private val INPUT_CHOICES = setOf(
    AudioDeviceInfo.TYPE_BUILTIN_MIC,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL,
)

/** An audio device the phone offers, for the device settings: Android's id for it and a name to show. */
data class AudioDeviceChoice(val id: Int, val name: String)

/** What a device is, in words, with its maker's name when it has one of its own. */
private fun deviceLabel(d: AudioDeviceInfo): String {
    val kind = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset (call quality)"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "Line"
    }
    val product = d.productName?.toString().orEmpty().trim()
    // The built-in devices carry the phone's model name, which says nothing.
    val builtIn = d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
    return if (product.isEmpty() || builtIn) kind else "$kind: $product"
}

/** Choices for [devices], numbered where two would read the same (a phone's several microphones). */
private fun deviceChoices(devices: List<AudioDeviceInfo>): List<AudioDeviceChoice> {
    val labels = devices.map(::deviceLabel)
    val seen = HashMap<String, Int>()
    return devices.mapIndexed { i, d ->
        val label = labels[i]
        val n = (seen[label] ?: 0) + 1
        seen[label] = n
        AudioDeviceChoice(d.id, if (labels.count { it == label } > 1) "$label $n" else label)
    }
}

/** Linear gain of [db] decibels. */
private fun dbToGain(db: Float): Float = 10f.pow(db / 20f)

class DawViewModel(app: Application) : AndroidViewModel(app) {
    private val library = ProjectLibrary(app.filesDir)
    /** Folder name of the open song, and its store. */
    private var songId = library.current()
    private var store = ProjectStore(library.folder(songId))
    private val prefsStore = PreferencesStore(app.filesDir)
    /** The app's preferences, REAPER's Preferences window: one set for every song. */
    var prefs by mutableStateOf(prefsStore.load())
        private set
    private val engineStarted = configureEngine(prefs) && NativeEngine.start()
    /** The rate the song plays at; only the audio device settings change it. */
    var sampleRate by mutableIntStateOf(NativeEngine.sampleRate())
        private set

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
    /** Track whose routing window (its folder and sends) is open, or null. */
    var routingTrackId by mutableStateOf<Long?>(null)
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
    var zoomDp by mutableFloatStateOf(Zoom.DEFAULT_DP_PER_SECOND)
        private set
    var scrollPx by mutableFloatStateOf(0f)
    var laneWidthPx by mutableFloatStateOf(0f)

    private val history = History<Project>(prefs.undoSteps)
    private var gestureBase: Project? = null
    private var saveJob: Job? = null
    /** True once [project] holds the open song, so a save can never write an empty project over it. */
    private var songLoaded = false
    private var nextTrackNumber = 1

    init {
        applyEnginePrefs()
        viewModelScope.launch {
            // REAPER's "open on startup": the last song, a new one (unless the last is still empty), or the song list.
            if (prefs.startup == StartupProject.NewProject) {
                val fresh = withContext(Dispatchers.IO) {
                    if (store.isEmpty()) null else library.create(ProjectLibrary.DEFAULT_NAME).also { library.setCurrent(it) }
                }
                if (fresh != null) {
                    songId = fresh
                    store = ProjectStore(library.folder(fresh))
                }
            }
            loadProject()
            if (prefs.startup == StartupProject.ShowSongs) openSongs()
        }
    }

    // ------------------------------------------------------------ load / save / sync

    private suspend fun loadProject() {
        val target = store
        val sampleRate = sampleRate
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
        // A new song starts at the tempo and with the metronome the preferences give.
        settings = result?.settings ?: ProjectSettings(metronome = prefs.newSongMetronome)
        NativeEngine.setMetronome(settings.metronome)
        pushMetronomeSound()
        applyProject(result?.project ?: Project(tempo = prefs.newSongTempo.toDouble()))
        nextTrackNumber = (result?.project?.tracks?.size ?: 0) + 1
        songLoaded = true
        loading = false
        if (rateCheckPending) {
            rateCheckPending = false
            checkSampleRate()
        }
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
        // Routing: the folder each track plays into, and its sends by the receiving track's index.
        val indexOf = p.tracks.withIndex().associate { it.value.id to it.index }
        val sends = p.tracks.map { t -> t.sends.filter { it.target in indexOf } }
        val sendTargets = sends.flatten().map { indexOf.getValue(it.target) }.toIntArray()
        val sendParams = FloatArray(sendTargets.size * 3)
        sends.flatten().forEachIndexed { k, send ->
            sendParams[k * 3] = send.gain
            sendParams[k * 3 + 1] = if (send.preFader) 1f else 0f
            sendParams[k * 3 + 2] = if (send.mute) 1f else 0f
        }
        NativeEngine.setArrangement(
            trackParams, counts, itemParams, itemGains,
            fxCounts, fxIds,
            envCounts, points.map { it.frame }.toLongArray(), points.map { it.value }.toFloatArray(),
            LongArray(p.tracks.size) { p.tracks[it].instrument?.id ?: -1L }, noteCounts, noteTimes, noteValues,
            Routing.parents(p.tracks), IntArray(p.tracks.size) { sends[it].size }, sendTargets, sendParams,
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
        if (p.tracks.none { it.id == routingTrackId }) routingTrackId = null
        if (noteEditorItemId != null && noteEditorItemId !in itemIds) {
            if (auditioning) toggleAudition()
            noteEditorItemId = null
        }
        // The armed track may have moved, or gone.
        updateMonitoring()
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

    /**
     * Writes a new source's WAV file into the open song's folder, off the
     * main thread: [bits] 32 is float, else 24- or 16-bit PCM.
     */
    private fun writeSource(fileName: String, samples: FloatArray, bits: Int = 32) {
        val file = store.audioFile(fileName)
        val rate = sampleRate
        viewModelScope.launch(Dispatchers.IO) { runCatching { WavFile.writeMono(file, samples, rate, bits) } }
    }

    fun undo() {
        if (busyRecording()) return
        history.undo(project)?.let { applyProject(it); scheduleSave() }
    }

    fun redo() {
        if (busyRecording()) return
        history.redo(project)?.let { applyProject(it); scheduleSave() }
    }

    /** True (with a word to the user) while a recording is running, which undo, the tempo and the loop must not change under it. */
    private fun busyRecording(): Boolean {
        if (recording || recordingMidi) message = "Stop recording first"
        return recording || recordingMidi
    }

    // ------------------------------------------------------------ per-frame polling

    private var lastTickNanos = 0L

    /** Called every UI frame to pull transport and meter state from the engine. */
    fun tick() {
        val now = System.nanoTime()
        val seconds = if (lastTickNanos == 0L) 0f else ((now - lastTickNanos) / 1e9f).coerceAtMost(0.25f)
        lastTickNanos = now
        // Meters jump up to each new peak and fall back at a steady rate, like a mixing desk.
        val fall = 10f.pow(-prefs.meterDecayDb * seconds / 20f)
        fun meter(shown: Float, peak: Float) = max(peak, shown * fall).let { if (it < 1e-4f) 0f else it }

        position = NativeEngine.position()
        if (exportProgress != null) exportProgress = NativeEngine.renderProgress()
        masterLevel = meter(masterLevel, NativeEngine.masterPeak())
        val capturing = recording || samplingPad != null
        // Monitoring shows the input too, so the level can be set before recording.
        if (capturing || monitoringLive) {
            val peak = NativeEngine.inputLevel()
            inputLevel = meter(inputLevel, peak)
            if (capturing) recordedFrames = NativeEngine.recordedFrames()
            if (capturing && peak >= CLIP_LEVEL && !clipWarned && prefs.clipWarning) {
                clipWarned = true
                message = "Clipping! Sing softer or move the phone away"
            }
            if (recording && countInBeats == 0 && prefs.recordingPreview) previewPeak(position, peak)
        }
        if (recording || recordingMidi) {
            seePosition(position)
            val countIn = NativeEngine.countInRemaining()
            countInBeats = if (countIn > 0) ceil(countIn / framesPerBeat).toInt() else 0
        }
        // REAPER's "stop playback at end of project", unless something records or the loop goes round.
        if (playing && prefs.stopAtEnd && !recording && !recordingMidi && !auditioning && !project.loopOn &&
            project.endFrame > 0 && position >= project.endFrame
        ) {
            stop()
        }
        val levels = HashMap<Long, Float>()
        project.tracks.forEachIndexed { i, t ->
            val v = meter(trackLevels[t.id] ?: 0f, NativeEngine.trackPeak(i))
            if (v > 0f) levels[t.id] = v
        }
        if (levels.isNotEmpty() || trackLevels.isNotEmpty()) trackLevels = levels
        if (playing && prefs.autoScroll) followPlayhead()
    }

    /**
     * True while the screen has something moving to show: the transport,
     * meters, a recording or an export. Otherwise the screen polls the
     * engine only now and then, which saves the battery.
     */
    val animating: Boolean
        get() = playing || recording || recordingMidi || samplingPad != null || monitoringLive || auditioning ||
            exportProgress != null || masterLevel > 0f || inputLevel > 0f || trackLevels.isNotEmpty()

    /**
     * The take being recorded as it comes in, REAPER's recording preview:
     * the timeline frame each screen update reached, and the loudest input
     * since the one before.
     */
    val recordPreview = PeakTrail()
    private var previewPass = 0

    private fun previewPeak(frame: Long, peak: Float) {
        // A new pass round the loop starts a new take.
        if (recordPasses != previewPass) {
            previewPass = recordPasses
            recordPreview.clear()
        }
        recordPreview.add(frame, peak)
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

    /**
     * Zooms the timeline. What stays put on screen is REAPER's "horizontal
     * zoom center": the playhead while it is in view, or the middle of the view.
     */
    fun zoom(factor: Float) {
        val head = screenX(position)
        val anchor = if (prefs.zoomAnchor == ZoomAnchor.Playhead && head in 0f..laneWidthPx) head else laneWidthPx / 2
        zoomAround(factor, anchor)
    }

    /** Zooms keeping the time at screen [x] where it is, as a pinch does around the fingers. */
    fun zoomAround(factor: Float, x: Float) {
        val frame = pxToFrames(x + scrollPx)
        zoomDp = (zoomDp * factor).coerceIn(Zoom.MIN_DP_PER_SECOND, Zoom.MAX_DP_PER_SECOND)
        scrollPx = (framesToPx(frame) - x).coerceAtLeast(0f)
    }

    /** Shows the whole song across the screen, REAPER's "zoom out project"; a short song shows its first four bars. */
    fun zoomToFit() {
        if (laneWidthPx <= 0f) return
        val end = max(project.endFrame, (framesPerBeat * 16).toLong())
        zoomDp = Zoom.fit(end, sampleRate, laneWidthPx / density)
        scrollPx = 0f
    }

    /** The tracks' height while a pinch changes it; it is saved in the preferences when the fingers lift. */
    private var pinchTrackHeight by mutableStateOf<Float?>(null)

    /** How tall each track is on the timeline, in dp. */
    val trackHeight: Float get() = pinchTrackHeight ?: prefs.trackHeight.toFloat()

    /** A pinch up and down makes every track taller or shorter by [factor]. */
    fun scaleTrackHeight(factor: Float) {
        pinchTrackHeight = (trackHeight * factor).coerceIn(Zoom.MIN_TRACK_HEIGHT.toFloat(), Zoom.MAX_TRACK_HEIGHT.toFloat())
    }

    fun endTrackHeightPinch() {
        val height = pinchTrackHeight ?: return
        updatePrefs { it.copy(trackHeight = height.roundToInt()) }
        pinchTrackHeight = null
    }

    /** The zoom buttons: taller or shorter tracks, one step at a time. */
    fun stepTrackHeight(up: Boolean) = updatePrefs { it.copy(trackHeight = Zoom.stepHeight(it.trackHeight, up)) }

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
        if (playing) {
            playStart = position
            // REAPER's count-in before playback: as long as the recording one, or a bar when that is off.
            val countIn = if (settings.countInPlayback) (framesPerBeat * 4 * maxOf(1, settings.countInBars)).roundToLong() else 0L
            NativeEngine.startPlaying(countIn)
        } else {
            NativeEngine.setPlaying(false)
        }
    }

    /** Where playback last started, for REAPER's return to start on stop. */
    private var playStart = 0L

    /**
     * Stop button: first press stops (and, with the preference, goes back to
     * where playback started, as REAPER does), a second press returns to the start.
     */
    fun stop() {
        // Stopping a recording during its count-in stops the transport too: still a first press.
        val wasPlaying = playing
        val wasRecording = recording || recordingMidi
        if (recording) stopRecording()
        if (recordingMidi) stopMidiRecording()
        playing = false
        NativeEngine.setPlaying(false)
        when {
            !wasPlaying -> {
                seekFrames(0)
                scrollPx = 0f
            }
            prefs.returnOnStop && !wasRecording -> {
                seekFrames(playStart)
                if (prefs.autoScroll) followPlayhead()
            }
        }
    }

    fun rewind() {
        seekFrames(if (project.loopOn) project.loopStart else 0)
        scrollPx = (framesToPx(position) - laneWidthPx * 0.15f).coerceAtLeast(0f)
    }

    fun seekFrames(frame: Long) {
        // A take lines up with the timeline as it played: no jumping while recording.
        if (recording) return
        NativeEngine.seek(frame.coerceAtLeast(0))
        position = NativeEngine.position()
    }

    fun seekToScreenX(x: Float) = seekFrames(snapped(frameAtScreen(x)))

    fun toggleMetronome() {
        settings = settings.copy(metronome = !settings.metronome)
        NativeEngine.setMetronome(settings.metronome)
        scheduleSave()
    }

    /** REAPER's metronome volume, in dB. */
    fun setMetronomeDb(db: Float) {
        settings = settings.copy(metronomeDb = db.coerceIn(-40f, 12f))
        pushMetronomeSound()
        scheduleSave()
    }

    /** A higher click on each bar's first beat, or every beat the same. */
    fun setMetronomeAccent(on: Boolean) {
        settings = settings.copy(metronomeAccent = on)
        pushMetronomeSound()
        scheduleSave()
    }

    /** REAPER's "count-in before playback". */
    fun setCountInPlayback(on: Boolean) {
        settings = settings.copy(countInPlayback = on)
        scheduleSave()
    }

    private fun pushMetronomeSound() = NativeEngine.setMetronomeSound(dbToGain(settings.metronomeDb), settings.metronomeAccent)

    /**
     * Sets the tempo. MIDI items and the loop keep their place in bars, as
     * with REAPER's beat timebase; audio stays where it was recorded.
     */
    fun setTempo(bpm: Double) {
        val tempo = bpm.coerceIn(30.0, 300.0)
        if (tempo == project.tempo || busyRecording()) return
        commit(retimed(project, tempo))
    }

    /** The song a tempo drag started from, and the last step it showed. */
    private var tempoDrag: Pair<Project, Project>? = null

    /**
     * Sets the tempo while a finger drags the tempo display, as in
     * GarageBand; [endGesture] makes the drag one undo step. Each step is
     * worked out from the song as the drag found it, so rounding never adds
     * up, unless something else changed the song in between.
     */
    fun dragTempo(bpm: Double) {
        if (busyRecording()) return
        val tempo = bpm.coerceIn(30.0, 300.0)
        if (tempo == project.tempo) return
        val base = tempoDrag?.takeIf { it.second === project }?.first ?: project
        val next = if (tempo == base.tempo) base else retimed(base, tempo)
        preview(next)
        tempoDrag = base to next
    }

    private fun retimed(p: Project, tempo: Double): Project {
        val ratio = p.tempo / tempo
        return p.copy(
            tempo = tempo,
            loopStart = (p.loopStart * ratio).roundToLong(),
            loopEnd = (p.loopEnd * ratio).roundToLong(),
            tracks = p.tracks.map { t ->
                if (t.items.none { it.isMidi }) t
                else t.copy(items = t.items.map { if (it.isMidi) Midi.scaleTime(it, ratio) else it })
            },
        )
    }

    fun toggleSnap() {
        settings = settings.copy(snap = !settings.snap)
        scheduleSave()
    }

    fun cycleGrid() {
        val i = GRID_DIVISIONS.indexOf(settings.gridDivision)
        setGridDivision(GRID_DIVISIONS[(i + 1) % GRID_DIVISIONS.size])
    }

    fun setGridDivision(division: Int) {
        if (division !in GRID_DIVISIONS) return
        settings = settings.copy(gridDivision = division)
        scheduleSave()
    }

    // ------------------------------------------------------------ loop and markers

    fun toggleLoop() {
        if (busyRecording()) return
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
        if (busyRecording()) return
        gestureBase = project
        loopDragStart = snapped(frameAtScreen(x))
    }

    fun dragLoop(x: Float) {
        if (recording || recordingMidi) return
        val a = loopDragStart
        val b = snapped(frameAtScreen(x))
        val p = project.copy(loopStart = minOf(a, b), loopEnd = maxOf(a, b), loopOn = abs(b - a) > 0)
        project = p
        sync(p)
    }

    fun endGesture() {
        tempoDrag = null
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

    /** Beats of count-in still to come, while the metronome counts a recording in. */
    var countInBeats by mutableIntStateOf(0)
        private set
    /** Passes round the loop so far in this recording; each one becomes a take. */
    var recordPasses by mutableIntStateOf(1)
        private set
    /** The count-in this recording started with, and the loop it goes round, as they were when it started. */
    private var recordCountIn = 0L
    private var recordLoop: Pair<Long, Long>? = null
    private var clipWarned = false
    /** The engine position last seen while recording, to tell a jump back round the loop. */
    private var lastSeenPosition = 0L
    private var wrapped = false

    private fun seePosition(pos: Long) {
        if (pos < lastSeenPosition) {
            wrapped = true
            recordPasses++
        }
        lastSeenPosition = pos
    }

    private fun startTakeTracking(start: Long, countIn: Long) {
        recordStart = start
        recordCountIn = countIn
        lastSeenPosition = start
        wrapped = false
        recordPasses = 1
        countInBeats = 0
        recordPreview.clear()
        previewPass = 1
    }

    fun toggleRecord() {
        when {
            recording -> stopRecording()
            recordingMidi -> stopMidiRecording()
            samplingPad != null -> Unit
            recordsMidi -> startMidiRecording()
            else -> startRecording()
        }
    }

    /** Frames the metronome counts in before a recording that starts from a stop. */
    private fun countInFrames(): Long =
        if (playing || settings.countInBars <= 0) 0L else (framesPerBeat * 4 * settings.countInBars).roundToLong()

    private fun startRecording() {
        if (auditioning) toggleAudition()
        val countIn = countInFrames()
        if (!NativeEngine.startRecording(true, countIn)) {
            message = "Could not open the microphone"
            return
        }
        val p = project
        startTakeTracking(NativeEngine.recordStartFrame(), countIn)
        // With the loop on, the transport goes round it until recording stops.
        recordLoop = if (p.loopOn && p.loopEnd > p.loopStart) p.loopStart to p.loopEnd else null
        recordedFrames = 0
        clipWarned = false
        recording = true
        playing = true
        updateMonitoring()
    }

    private fun stopRecording() {
        // Stopped before the count-in was over: nothing was played yet, so the transport stops too.
        val countingIn = NativeEngine.countInRemaining() > 0
        val captured = NativeEngine.stopRecording()
        recording = false
        inputLevel = 0f
        countInBeats = 0
        if (countingIn) {
            playing = false
            NativeEngine.setPlaying(false)
        }
        updateMonitoring()
        if (captured < 0) return
        // What was played while hearing the timeline start comes this far into the capture: the
        // count-in, the latency the audio system reports (REAPER's "use audio driver reported
        // latency") and the manual offset, which moves takes earlier.
        val reported = if (prefs.useReportedLatency) NativeEngine.recordLatency() else 0L
        val lead = (recordCountIn + reported + prefs.latencyOffsetMs * sampleRate / 1000L).coerceAtLeast(0L)
        val layout = Takes.layout(NativeEngine.sourceLength(captured), recordStart, lead, recordLoop, prefs.discardPartialTakes)
        if (layout == null) {
            NativeEngine.releaseSource(captured)
            if (!countingIn) message = "The take was too short to keep"
            return
        }
        var sourceId = captured
        var samples = NativeEngine.sourceSamples(captured)
        if (layout.padBefore > 0 || layout.padAfter > 0) {
            // Passes cut short are silent where nothing was recorded.
            val padded = FloatArray((layout.padBefore + samples.size + layout.padAfter).toInt())
            samples.copyInto(padded, layout.padBefore.toInt())
            samples = padded
            sourceId = NativeEngine.createSource(padded)
            NativeEngine.releaseSource(captured)
        }
        val info = SourceInfo("take_${UUID.randomUUID()}.wav", samples.size.toLong(), NativeEngine.sourcePeaks(sourceId, PEAK_BUCKET))
        sources = sources + (sourceId to info)
        writeSource(info.fileName, samples, prefs.recordFormat.bits)

        // One take per pass round the loop, the newest playing.
        val takes = layout.offsets.map { Take(sourceId, it) }
        val fade = autoFade(layout.length)
        val item = Takes.withTakes(
            Item(Ids.next(), sourceId, layout.position, 0, layout.length, fadeIn = fade, fadeOut = fade),
            takes, takes.lastIndex,
        )
        var p = project
        var trackId = armedTrackId
        if (p.tracks.none { it.id == trackId }) {
            val t = newTrack()
            p = p.copy(tracks = p.tracks + t)
            trackId = t.id
        }
        val (items, holding) = Takes.place(p.tracks.first { it.id == trackId }.items, item, settings.keepTakes)
        commit(p.updateTrack(trackId) { it.copy(items = items) })
        armedTrackId = trackId
        selectedTrackId = trackId
        selectedItems = holding
        updateMonitoring()
        val most = items.filter { it.id in holding }.maxOfOrNull { Takes.count(it) } ?: 1
        if (most > 1) tip("Take $most of $most: ◀ ▶ above picks another")
        // REAPER's prompt after recording: keep the new take, or throw it away.
        if (prefs.askAfterRecording) takePrompt = true
    }

    /** True while asking whether to keep the take just recorded. */
    var takePrompt by mutableStateOf(false)
        private set

    fun keepTake() {
        takePrompt = false
    }

    /** Throws the take just recorded away, as undo would. */
    fun discardTake() {
        takePrompt = false
        undo()
    }

    /** REAPER's automatic fades on new items, at most a quarter of the item. */
    private fun autoFade(length: Long): Long = minOf(prefs.autoFadeMs * sampleRate / 1000L, length / 4)

    /** A word of help on the screen, unless the preferences turn tips off. */
    private fun tip(text: String) {
        if (prefs.showHints) message = text
    }

    /** True while a take is being recorded into this track. */
    fun isRecordingInto(track: Track) = (recording || recordingMidi) && track.id == armedTrackId

    /** Count-in before recording from a stop: off, then 1 bar, then 2 bars. */
    fun setCountIn(bars: Int) {
        settings = settings.copy(countInBars = bars.coerceIn(0, 2))
        scheduleSave()
    }

    /** Recording over an item: add a take to it (REAPER's way), or replace it like tape. */
    fun setKeepTakes(keep: Boolean) {
        settings = settings.copy(keepTakes = keep)
        scheduleSave()
    }

    // ------------------------------------------------------------ input monitoring (REAPER's record monitoring)

    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshHeadphones()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshHeadphones()
    }

    /** True while wired or USB headphones are plugged in, so the microphone can be heard without feeding back. */
    var headphones by mutableStateOf(false)
        private set
    /** True when the only headphones are Bluetooth ones, which are too slow to sing along with. */
    private var bluetoothOnly = false
    /** True while the microphone is heard live. */
    var monitoringLive by mutableStateOf(false)
        private set
    /** The arrangement track the engine monitors through (-1: the master), or null when it is not monitoring. */
    private var monitorIndex: Int? = null
    private var foreground = true

    /** The phone's outputs and inputs, for the device settings. */
    var outputDevices by mutableStateOf(listOf<AudioDeviceChoice>())
        private set
    var inputDevices by mutableStateOf(listOf<AudioDeviceChoice>())
        private set
    /** True while headphones of any kind (wired, USB or Bluetooth) are connected. */
    private var privateOutput = false

    // After the state it fills, which Kotlin sets up in the order it is written.
    init {
        audioManager?.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        refreshHeadphones()
    }

    override fun onCleared() {
        audioManager?.unregisterAudioDeviceCallback(deviceCallback)
    }

    private fun refreshHeadphones() {
        val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: emptyArray()
        val inputs = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS) ?: emptyArray()
        outputDevices = deviceChoices(outputs.filter { it.type in OUTPUT_CHOICES })
        inputDevices = deviceChoices(inputs.filter { it.type in INPUT_CHOICES })
        val private = outputs.any { it.type in PRIVATE_OUTPUTS }
        if (privateOutput && !private && prefs.stopOnUnplug && (playing || recording || recordingMidi)) {
            // Unplugged mid-song: the phone's speaker would play it out loud.
            stopAll()
            message = "Stopped: the headphones were unplugged"
        }
        privateOutput = private
        val wired = outputs.any { it.type in HEADPHONE_TYPES }
        bluetoothOnly = !wired && outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        if (wired != headphones) {
            headphones = wired
            // Unplugged: the phone's speaker would feed the microphone back into itself.
            updateMonitoring()
        }
    }

    /** The 🎧 button: hear yourself through the armed track while recording, with headphones. */
    fun toggleMonitoring() {
        settings = settings.copy(monitoring = !settings.monitoring)
        scheduleSave()
        updateMonitoring()
        if (settings.monitoring && !headphones) {
            message = if (bluetoothOnly) "Bluetooth is too slow to hear yourself: use wired headphones"
            else "Plug in headphones to hear yourself"
        }
    }

    /**
     * Turns monitoring on or off as the setting, the headphones and the armed
     * audio track allow. Recording with no track armed is heard straight
     * through the master, until it lands on its new track.
     */
    private fun updateMonitoring() {
        val index = project.tracks.indexOfFirst { it.id == armedTrackId && it.instrument == null }
        val wanted = settings.monitoring && headphones && foreground && samplingPad == null && (index >= 0 || recording)
        val target = if (wanted) index else null
        if (target == monitorIndex) return
        if (target != null && !NativeEngine.setMonitor(true, target)) {
            // Most likely the microphone permission was taken back: stay off until asked again.
            settings = settings.copy(monitoring = false)
            message = "Could not open the microphone"
            if (monitorIndex != null) NativeEngine.setMonitor(false, -1)
            monitorIndex = null
            monitoringLive = false
            return
        }
        if (target == null) {
            NativeEngine.setMonitor(false, -1)
            if (!recording && samplingPad == null) inputLevel = 0f
        }
        monitorIndex = target
        monitoringLive = target != null
    }

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
        midiHeld.clear()
        sustained.clear()
    }

    // ------------------------------------------------------------ MIDI keyboards (REAPER's MIDI inputs)

    /** Names of the MIDI keyboards and controllers connected, for the MIDI Devices page. */
    var midiDevices by mutableStateOf(listOf<String>())

    /** Each note a MIDI keyboard holds, and the track it plays. */
    private val midiHeld = HashMap<Int, Long>()
    /** Notes let go while the sustain pedal is down, which sound on until it comes up. */
    private val sustained = HashMap<Int, Long>()
    private var sustainDown = false

    /**
     * The track a MIDI keyboard plays, as REAPER sends MIDI to the armed
     * track: the armed instrument track, else the one the pads and keys
     * show, else the selected one, else the first.
     */
    private val midiTrack: Track?
        get() {
            val instruments = project.tracks.filter { it.instrument != null }
            return instruments.firstOrNull { it.id == armedTrackId }
                ?: instruments.firstOrNull { it.id == playTrackId }
                ?: instruments.firstOrNull { it.id == selectedTrackId }
                ?: instruments.firstOrNull()
        }

    /** A note from a MIDI keyboard: [velocity] 1..127, or 0 when it is let go. */
    fun midiNote(pitch: Int, velocity: Int) {
        if (velocity > 0) {
            val track = midiTrack ?: return
            // Struck again: the old note gives way to the new one.
            sustained.remove(pitch)?.let { noteOff(it, pitch) }
            midiHeld.remove(pitch)?.let { noteOff(it, pitch) }
            midiHeld[pitch] = track.id
            noteOn(track.id, pitch, velocity / 127f)
        } else {
            val trackId = midiHeld.remove(pitch) ?: return
            if (sustainDown) sustained[pitch] = trackId else noteOff(trackId, pitch)
        }
    }

    /** The sustain pedal (controller 64). */
    fun midiSustain(down: Boolean) {
        sustainDown = down
        if (down) return
        for ((pitch, trackId) in sustained) noteOff(trackId, pitch)
        sustained.clear()
    }

    private fun startMidiRecording() {
        if (auditioning) toggleAudition()
        heldNotes.clear()
        takeItemId = -1L
        outputLatency = if (prefs.useReportedLatency) NativeEngine.outputLatency() else 0L
        val countIn = countInFrames()
        startTakeTracking(position, countIn)
        recordingMidi = true
        if (view != MainView.Play) {
            project.tracks.firstOrNull { it.id == armedTrackId }?.let { tip("Recording notes into ${it.name}: play its pads or keys") }
        }
        if (!playing) {
            playing = true
            NativeEngine.startPlaying(countIn)
        }
    }

    private fun stopMidiRecording() {
        val countingIn = NativeEngine.countInRemaining() > 0
        val now = recordFrame()
        heldNotes.keys.toList().forEach { finishNote(it, now) }
        recordingMidi = false
        countInBeats = 0
        if (countingIn) {
            playing = false
            NativeEngine.setPlaying(false)
        }
        // The whole take is one undo step.
        endGesture()
    }

    /**
     * Timeline frame the performer hears now: the engine renders ahead of the
     * speaker by the output latency, and during a count-in the timeline waits,
     * so what is heard then comes before the start.
     */
    private fun recordFrame(): Long {
        val p = project
        val pos = NativeEngine.position()
        seePosition(pos)
        val rendered = pos - NativeEngine.countInRemaining()
        var frame = rendered - outputLatency
        // Just after a loop jump the speaker still plays the end of the loop.
        if (wrapped && p.loopOn && p.loopEnd > p.loopStart && rendered >= p.loopStart && frame < p.loopStart) {
            frame += p.loopEnd - p.loopStart
        }
        return frame
    }

    private fun finishNote(pitch: Int, end: Long) {
        val (pressed, velocity) = heldNotes.remove(pitch) ?: return
        var played = pressed
        var stop = end
        // Played in the count-in: a note just before the start (a pickup played
        // a little early) starts with the take, and earlier ones are dropped.
        if (recordCountIn > 0 && played < recordStart) {
            if (recordStart - played > framesPerBeat / 4) return
            played = recordStart
            stop = maxOf(stop, recordStart)
        }
        played = played.coerceAtLeast(0)
        stop = stop.coerceAtLeast(0)
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
            stop >= played -> stop - played
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

    /** A new audio track: blue, as GarageBand colors recordings. */
    private fun newTrack(): Track {
        val number = nextTrackNumber++
        return Track(Ids.next(), "Audio $number", DawColors.AUDIO_COLOR, gain = newTrackGain)
    }

    /** REAPER's default volume for new tracks. */
    private val newTrackGain: Float get() = dbToGain(prefs.newTrackDb)

    /**
     * Adds an empty track and arms it, so the next recording lands there.
     * A drum track comes with the built-in kit and an empty one-bar pattern
     * at the playhead, ready for the step sequencer.
     */
    fun addTrack(kind: TrackKind = TrackKind.Audio) {
        if (kind == TrackKind.ReverbBus || kind == TrackKind.DelayBus) {
            val bus = newBus(kind)
            commit(project.copy(tracks = project.tracks + bus))
            selectedTrackId = bus.id
            tip("Send tracks to ${bus.name} from their mixer strip: tap ROUTE")
            return
        }
        val t = when (kind) {
            TrackKind.Audio -> newTrack()
            TrackKind.Synth -> {
                val number = nextTrackNumber++
                Track(
                    Ids.next(), "Synth $number", DawColors.INSTRUMENT_COLOR, gain = newTrackGain,
                    instrument = Instrument(Ids.next(), InstrumentType.SYNTH),
                )
            }
            TrackKind.Drums -> {
                val number = nextTrackNumber++
                val bar = framesPerBeat * 4
                val start = Midi.barAround(position, bar).first
                Track(
                    Ids.next(), "Drums $number", DawColors.DRUMS_COLOR, gain = newTrackGain,
                    items = listOf(Midi.newItem(start, (bar * patternBarsFor(InstrumentType.DRUMS)).roundToLong())),
                    instrument = Instrument(Ids.next(), InstrumentType.DRUMS, pads = List(PAD_COUNT) { kitPad(it) }),
                )
            }
            TrackKind.ReverbBus, TrackKind.DelayBus -> error("buses are added above")
        }
        commit(project.copy(tracks = project.tracks + t))
        // REAPER's "record arm new tracks".
        if (prefs.armNewTracks) armedTrackId = t.id
        selectedTrackId = t.id
        selectedItems = t.items.map { it.id }.toSet()
        playTrackId = if (t.instrument != null) t.id else playTrackId
        updateMonitoring()
    }

    /**
     * An effects bus: an empty track whose effect gives back only the effect
     * (no dry sound), so tracks can send it as much or as little as they like.
     */
    private fun newBus(kind: TrackKind): Track {
        // Counted like any track, so the next "Audio N" still numbers the tracks.
        nextTrackNumber++
        val reverb = kind == TrackKind.ReverbBus
        val type = if (reverb) EffectType.REVERB else EffectType.DELAY
        val values = type.defaults().toMutableList()
        if (reverb) {
            values[type.paramIndex("Wet")] = 1f
            values[type.paramIndex("Dry")] = 0f
        } else {
            values[type.paramIndex("Mix")] = 1f
            values[type.paramIndex("Dry")] = 0f
        }
        val name = if (reverb) "Reverb bus" else "Delay bus"
        val taken = project.tracks.map { it.name }.toSet()
        val unique = if (name !in taken) name else (2..99).map { "$name $it" }.firstOrNull { it !in taken } ?: name
        return Track(Ids.next(), unique, DawColors.BUS_COLOR, gain = newTrackGain, fx = listOf(Effect(Ids.next(), type, values)))
    }

    /** Deletes a track; the tracks in its folder stay, one level up. */
    fun deleteTrack(id: Long) = commit(Routing.removeTrack(project, id))

    fun renameTrack(id: Long, name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) commit(project.updateTrack(id) { it.copy(name = clean) })
    }

    /** Copies a track with its items, effects, automation and sends, right below it (and its folder). */
    fun duplicateTrack(id: Long) {
        val t = project.tracks.firstOrNull { it.id == id } ?: return
        val copy = t.copy(
            id = Ids.next(),
            name = "${t.name} copy",
            solo = false,
            collapsed = false,
            items = t.items.map { it.copy(id = Ids.next()) },
            fx = t.fx.map { it.copy(id = Ids.next()) },
            // Its own instrument, so the two tracks never share voices.
            instrument = t.instrument?.copy(id = Ids.next()),
            sends = t.sends.map { it.copy(id = Ids.next()) },
        )
        commit(Routing.insertAfter(project, id, copy))
        selectedTrackId = copy.id
    }

    /** Moves a track, with the tracks in its folder, past the next track up or down in the same folder. */
    fun moveTrack(id: Long, direction: Int) {
        val moved = Routing.move(project, id, direction)
        if (moved == null) {
            val t = project.tracks.firstOrNull { it.id == id } ?: return
            message = if (t.indent > 0) "It is at the edge of its folder: use \"Move out of folder\"" else "It is already at the ${if (direction < 0) "top" else "bottom"}"
            return
        }
        commit(moved)
    }

    // ------------------------------------------------------------ folders (REAPER's track folders)

    /** Puts a track in a new folder track, which takes its place and mixes it. */
    fun newFolder(id: Long) {
        if (project.tracks.none { it.id == id }) return
        val number = nextTrackNumber++
        val folder = Track(Ids.next(), "Folder $number", DawColors.FOLDER_COLOR)
        Routing.wrapInFolder(project, id, folder)?.let {
            commit(it)
            selectedTrackId = folder.id
        }
    }

    /** True when the track above can take this one into its folder. */
    fun canIndent(id: Long): Boolean {
        val i = project.tracks.indexOfFirst { it.id == id }
        return i > 0 && project.tracks[i - 1].indent >= project.tracks[i].indent
    }

    /** Moves a track into the folder of the track above it (that track becomes a folder if it was not one). */
    fun indentTrack(id: Long) {
        if (!canIndent(id)) return
        val next = Routing.indent(project, id)
        if (next == null) {
            message = "That would send the track's sound back into itself"
            return
        }
        // The new folder shows what went into it.
        val parent = Routing.parents(next.tracks)[next.tracks.indexOfFirst { it.id == id }]
        commit(if (parent >= 0) next.updateTrack(next.tracks[parent].id) { it.copy(collapsed = false) } else next)
    }

    /** Moves a track out of its folder, to just below the folder. */
    fun outdentTrack(id: Long) {
        val next = Routing.outdent(project, id)
        if (next == null) message = "That would send the track's sound back into itself" else commit(next)
    }

    /** Shows or hides the tracks in a folder (REAPER's folder collapse). */
    fun toggleCollapsed(id: Long) {
        // Saved with the song, and undone like any other change.
        commit(project.updateTrack(id) { it.copy(collapsed = !it.collapsed) })
        val hidden = Routing.hidden(project.tracks)
        if (selectedTrackId in hidden) selectedTrackId = id
        if (selectedItems.isNotEmpty() && project.tracks.any { it.id in hidden && it.items.any { item -> item.id in selectedItems } }) {
            selectedItems = emptySet()
        }
    }

    /** Tracks the timeline hides inside collapsed folders. */
    val hiddenTracks: Set<Long> get() = Routing.hidden(project.tracks)

    // ------------------------------------------------------------ sends (REAPER's routing window)

    fun openRouting(trackId: Long) {
        routingTrackId = trackId
    }

    fun closeRouting() {
        endGesture()
        routingTrackId = null
    }

    /** Tracks that [trackId] can send to without its sound coming back to it. */
    fun sendTargets(trackId: Long): List<Track> = Routing.sendTargets(project, trackId)

    /** REAPER's default send level and mode. */
    private val newSendGain: Float get() = dbToGain(prefs.sendDb)

    fun addSend(from: Long, to: Long) {
        val next = Routing.addSend(project, from, to, newSendGain, prefs.sendPreFader)
        if (next == null) message = "That would send the track's sound back into itself" else commit(next)
    }

    /** Makes a new reverb or delay bus and sends [from] to it. */
    fun sendToNewBus(from: Long, kind: TrackKind) {
        val bus = newBus(kind)
        Routing.addSend(project.copy(tracks = project.tracks + bus), from, bus.id, newSendGain, prefs.sendPreFader)?.let { commit(it) }
    }

    /** Live send level change; [endGesture] makes it one undo step. */
    fun setSendGain(trackId: Long, sendId: Long, gain: Float) =
        preview(Routing.updateSend(project, trackId, sendId) { it.copy(gain = gain) })

    fun resetSendGain(trackId: Long, sendId: Long) =
        commit(Routing.updateSend(project, trackId, sendId) { it.copy(gain = newSendGain) })

    fun toggleSendPreFader(trackId: Long, sendId: Long) =
        commit(Routing.updateSend(project, trackId, sendId) { it.copy(preFader = !it.preFader) })

    fun toggleSendMute(trackId: Long, sendId: Long) =
        commit(Routing.updateSend(project, trackId, sendId) { it.copy(mute = !it.mute) })

    fun removeSend(trackId: Long, sendId: Long) = commit(Routing.removeSend(project, trackId, sendId))

    fun toggleArm(id: Long) {
        if (recording) return
        armedTrackId = if (armedTrackId == id) -1 else id
        updateMonitoring()
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

    /**
     * Finds the item and drag mode for a touch at screen x, y within a lane of
     * height [laneH]. A selected item comes first: its edges trim it, from a
     * little outside it too, so even a narrow one can be made longer, and the
     * handles at its top corners set its fades. Any other item just moves.
     */
    fun hitTest(track: Track, x: Float, y: Float, laneH: Float): Pair<Item, DragMode>? {
        val edge = 18 * density
        val outside = 14 * density
        val selected = track.items.lastOrNull {
            it.id in selectedItems && x >= screenX(it.position) - outside && x <= screenX(it.end) + outside
        }
        if (selected != null) {
            val x0 = screenX(selected.position)
            val x1 = screenX(selected.end)
            // A narrow item keeps its middle third for moving it.
            val reach = minOf(edge, (x1 - x0) / 3)
            // MIDI items have no fades.
            val fades = !selected.isMidi && y < laneH * 0.35f
            val mode = when {
                fades && abs(x - (x0 + framesToPx(selected.fadeIn))) < edge * 1.4f -> DragMode.FadeIn
                fades && abs(x - (x1 - framesToPx(selected.fadeOut))) < edge * 1.4f -> DragMode.FadeOut
                x < x0 + reach -> DragMode.TrimLeft
                x > x1 - reach -> DragMode.TrimRight
                else -> DragMode.Move
            }
            return selected to mode
        }
        val item = track.items.lastOrNull { x >= screenX(it.position) && x <= screenX(it.end) } ?: return null
        return item to DragMode.Move
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
        // REAPER moves the edit cursor past what was pasted, so pasting again carries on from there.
        if (prefs.playheadAfterPaste) seekFrames(item.end)
    }

    fun toggleMuteSelected() {
        var p = project
        selectedItems.forEach { id -> p = p.updateItem(id) { it.copy(mute = !it.mute) } }
        commit(p)
    }

    fun normalizeSelected() {
        var p = project
        selectedItems.forEach { id -> p = p.updateItem(id, ::normalized) }
        commit(p)
    }

    /** [item] with its gain bringing its loudest peak to -1 dB. */
    private fun normalized(item: Item): Item {
        val peaks = sources[item.sourceId]?.peaks ?: return item
        val from = (item.offset / PEAK_BUCKET).toInt().coerceIn(0, peaks.size)
        val to = ((item.offset + item.length) / PEAK_BUCKET + 1).toInt().coerceIn(from, peaks.size)
        var peak = 0f
        for (b in from until to) peak = max(peak, peaks[b])
        return Takes.withGain(item, Edits.normalizeGain(peak))
    }

    // ------------------------------------------------------------ takes (REAPER's takes)

    /** The selected item, when it holds more than one take. */
    val selectedTakesItem: Item?
        get() = project.tracks.flatMap { it.items }.firstOrNull { it.id in selectedItems && Takes.count(it) > 1 }

    /** Plays the next (+1) or previous (-1) take of the selected items, like REAPER's T and Shift+T. */
    fun stepTake(direction: Int) = changeTakes { Takes.step(it, direction) }

    /** Plays take [index] of an item: a tap on its lane. */
    fun selectTake(itemId: Long, index: Int) = commit(project.updateItem(itemId) { Takes.select(it, index) })

    /** Removes the take that plays, keeping the others. */
    fun deleteTake() = changeTakes { Takes.deleteActive(it) ?: it }

    /** Keeps only the take that plays (REAPER's "crop to active take"). */
    fun cropTake() = changeTakes(Takes::crop)

    private fun changeTakes(change: (Item) -> Item) {
        var p = project
        for (id in selectedItems) p = p.updateItem(id) { if (Takes.count(it) > 1) change(it) else it }
        commit(p)
    }

    /** Moves the selected items to the track above (-1) or below (+1). */
    fun moveSelectedToTrack(direction: Int) {
        if (selectedItems.isEmpty()) return
        val tracks = project.tracks.toMutableList()
        val hidden = hiddenTracks
        // The next track shown, skipping those hidden in collapsed folders.
        fun next(i: Int): Int {
            var j = i + direction
            while (j in tracks.indices && tracks[j].id in hidden) j += direction
            return j
        }
        val moves = mutableListOf<Pair<Int, Item>>()
        tracks.forEachIndexed { i, t ->
            t.items.filter { it.id in selectedItems }.forEach { moves += next(i) to it }
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
        updateMonitoring()
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
        if (!NativeEngine.startRecording(false, 0)) {
            message = "Could not open the microphone"
            return
        }
        recordedFrames = 0
        clipWarned = false
        samplingPad = trackId to index
        updateMonitoring()
    }

    fun stopSampling() {
        val (trackId, index) = samplingPad ?: return
        samplingPad = null
        inputLevel = 0f
        val id = NativeEngine.stopRecording()
        updateMonitoring()
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
            val t = newTrack().copy(name = audio.name.take(24).ifEmpty { "Import" }, colorIndex = DawColors.IMPORT_COLOR)
            val length = sources[id]?.length ?: 0L
            val fade = autoFade(length)
            val plain = Item(Ids.next(), id, position, 0, length, fadeIn = fade, fadeOut = fade)
            val item = if (prefs.normalizeImports) normalized(plain) else plain
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
        val item = Midi.newItem(start, (bar * patternBarsFor(track.instrument?.type)).roundToLong())
        commit(project.updateTrack(track.id) { it.copy(items = (it.items + item).sortedBy { i -> i.position }) })
        selectedItems = setOf(item.id)
    }

    /** Bars in a new pattern: the preference, or else one for drums and four for a synth. */
    private fun patternBarsFor(type: InstrumentType?): Int = when {
        prefs.patternBars > 0 -> prefs.patternBars
        type == InstrumentType.DRUMS -> 1
        else -> 4
    }

    /** Velocity of notes drawn in the piano roll (REAPER's default new note velocity), 0..1. */
    val newNoteVelocity: Float get() = prefs.noteVelocity / 127f

    /** Plays a note just added or moved in the piano roll, unless the preferences say not to. */
    fun previewEditedNote(trackId: Long, pitch: Int, velocity: Float = newNoteVelocity) {
        if (prefs.previewNotes) previewNote(trackId, pitch, velocity)
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

    /** True while the audio device is closed because the app is in the background. */
    private var engineClosed = false

    fun onAppBackground() {
        stopAll()
        // The microphone is never left open behind the user's back.
        foreground = false
        updateMonitoring()
        saveJob?.cancel()
        saveNow()
        flushPrefs()
        // REAPER's "close audio device when inactive": it frees the audio hardware for other
        // apps, and a stream left open runs its callback even in silence, using the battery.
        if (prefs.closeInBackground) {
            NativeEngine.shutdown()
            engineClosed = true
        }
    }

    fun onAppForeground() {
        foreground = true
        if (engineClosed) {
            engineClosed = false
            if (!NativeEngine.start()) message = "Audio engine failed to start"
            checkSampleRate()
        }
        updateMonitoring()
    }

    /** Another app took the sound, or a call came in: stop, as REAPER stops when its device goes away. */
    fun onAudioInterrupted() {
        if (!playing && !recording && !recordingMidi && samplingPad == null) return
        stopAll()
        message = "Stopped for another app's sound or a call"
    }

    // ------------------------------------------------------------ preferences (REAPER's Preferences window)

    var showPrefs by mutableStateOf(false)
        private set
    /** The page the Preferences window shows, kept while the app runs, as REAPER reopens the last one. */
    var prefsPage by mutableStateOf("General")
    /** Set by the activity: whether the phone can hold its CPU at a steady speed. */
    var sustainedSupported by mutableStateOf(false)

    /** Where the songs are kept and how much room they take, for the Paths page. */
    class StorageInfo(val folder: String, val songs: Int, val usedBytes: Long, val freeBytes: Long)

    var storage by mutableStateOf<StorageInfo?>(null)
        private set

    fun refreshStorage() {
        viewModelScope.launch {
            storage = withContext(Dispatchers.IO) {
                runCatching { StorageInfo(library.dir.path, library.list().size, library.usedBytes(), library.freeBytes()) }.getOrNull()
            }
        }
    }

    fun openPrefs() {
        if (busyRecording() || samplingPad != null) return
        showPrefs = true
        refreshAudioStatus()
    }

    fun closePrefs() {
        showPrefs = false
        flushPrefs()
    }

    private var prefsSaveJob: Job? = null

    /** Changes the preferences, applies what changed and saves them. */
    fun updatePrefs(change: (Preferences) -> Preferences) {
        val old = prefs
        val new = change(old)
        if (new == old) return
        prefs = new
        history.limit = new.undoSteps
        canUndo = history.canUndo
        applyEnginePrefs()
        if (new.exportFormat != old.exportFormat || new.exportNormalize != old.exportNormalize) {
            exportOptions = exportOptions.copy(format = new.exportFormat, normalize = new.exportNormalize)
        }
        if (deviceChanged(old, new)) restartAudio()
        prefsSaveJob?.cancel()
        prefsSaveJob = viewModelScope.launch {
            delay(300)
            withContext(Dispatchers.IO) { runCatching { prefsStore.save(new) } }
        }
    }

    /** Puts every preference back as it came, REAPER's "reset to defaults". */
    fun resetPrefs() = updatePrefs { Preferences() }

    private fun flushPrefs() {
        if (prefsSaveJob?.isActive != true) return
        prefsSaveJob?.cancel()
        val p = prefs
        viewModelScope.launch(Dispatchers.IO) { runCatching { prefsStore.save(p) } }
    }

    /** Saves the preferences at once, for the activity to read them as it starts again; false when that failed. */
    fun savePrefsNow(): Boolean {
        prefsSaveJob?.cancel()
        return runCatching { prefsStore.save(prefs) }.isSuccess
    }

    private fun deviceChanged(a: Preferences, b: Preferences) =
        a.audioApi != b.audioApi || a.outputDevice != b.outputDevice || a.inputDevice != b.inputDevice ||
            a.sampleRate != b.sampleRate || a.bufferBursts != b.bufferBursts || a.performance != b.performance ||
            a.exclusive != b.exclusive || a.micPreset != b.micPreset

    /** Hands the device settings to the engine, which reopens the streams when they are open. */
    private fun configureEngine(p: Preferences): Boolean = NativeEngine.configure(
        api = p.audioApi.ordinal,
        performanceMode = p.performance.ordinal,
        exclusive = p.exclusive,
        sampleRate = p.sampleRate,
        bufferBursts = p.bufferBursts,
        outputDevice = p.outputDevice,
        inputDevice = p.inputDevice,
        inputPreset = p.micPreset.ordinal,
    )

    /** What the engine takes at once, without reopening the device. */
    private fun applyEnginePrefs() {
        NativeEngine.setFxTail(prefs.fxTailMs * sampleRate / 1000L)
    }

    /** What the audio streams really got, shown in the device settings. */
    var audioStatus by mutableStateOf(AudioStatus())
        private set
    /** True while the audio device reopens with new settings. */
    var audioRestarting by mutableStateOf(false)
        private set

    fun refreshAudioStatus() {
        audioStatus = AudioStatus.from(NativeEngine.audioStatus())
    }

    /** Reopens the audio device with the device settings, off the main thread. */
    private fun restartAudio() {
        stopAll()
        val p = prefs
        audioRestarting = true
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { configureEngine(p) }
            audioRestarting = false
            if (!ok) message = "Could not open the audio device with these settings"
            checkSampleRate()
            refreshAudioStatus()
        }
    }

    private var rateCheckPending = false

    /**
     * Reloads the song when the engine now runs at another rate, as after
     * choosing another sample rate: its audio is resampled to the new rate
     * as it loads, as REAPER resamples a project's media.
     */
    private fun checkSampleRate() {
        val rate = NativeEngine.sampleRate()
        if (rate == sampleRate || rate <= 0) return
        if (loading || exportProgress != null) {
            rateCheckPending = true
            return
        }
        loading = true
        stopAll()
        viewModelScope.launch {
            // Saved at the old rate, which the song file records with its frames.
            saveAndWait()
            val pos = position * rate / sampleRate
            resetSession()
            sampleRate = rate
            applyEnginePrefs()
            loadProject()
            seekFrames(pos)
            message = "The song now plays at $rate Hz"
        }
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
    var exportOptions by mutableStateOf(ExportOptions(format = prefs.exportFormat, normalize = prefs.exportNormalize))
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
            tail = prefs.renderTailMs * sampleRate / 1000L,
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
        /** Longest tail to render after the end, REAPER's render tail. */
        val tail: Long,
    )

    private class RenderStats(val frames: Long, val peak: Float, val loudness: Double)

    /**
     * Runs the engine's offline render into float WAV files, with up to
     * [tail] frames after the end for effects and notes to ring out; null
     * when cancelled. With [ignoreSolo] it renders as if nothing were
     * soloed, so every stem is whole.
     */
    private fun render(
        start: Long,
        end: Long,
        tail: Long,
        mixPath: String = "",
        stemPaths: Array<String> = emptyArray(),
        dryPaths: Array<String> = emptyArray(),
        ignoreSolo: Boolean = false,
    ): RenderStats? {
        val r = NativeEngine.render(start, end, tail, mixPath, stemPaths, dryPaths, ignoreSolo)
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
        val stats = render(job.start, job.end, if (o.tails) job.tail else 0L, mixPath = raw.path) ?: return null
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

    /**
     * One file per top-level track: a folder's file holds the tracks in it,
     * and a bus's file its effect, so the files add up to the song.
     */
    private fun exportStems(job: ExportJob, dir: File): ExportResult? {
        val p = job.project
        val audible = Routing.audible(p)
        val tracks = p.tracks.withIndex().filter { (i, t) -> t.indent == 0 && audible[i] }
        check(tracks.isNotEmpty()) { "no unmuted track has anything to play" }
        val raw = Array(p.tracks.size) { "" }
        tracks.forEach { (i, _) -> raw[i] = File(dir, "stem$i.raw.wav").path }
        val stats = render(job.start, job.end, if (job.options.tails) job.tail else 0L, stemPaths = raw, ignoreSolo = true) ?: return null
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
            ${job.title}: one WAV file per track, from Playdaw Studio. A folder's file holds the tracks
            in it and a bus's file its effect, so together the files make the whole song.
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
        val folder = AudioExport.asciiFileName(job.title, "Playdaw Studio song")
        val entries = mutableListOf<Pair<String, File>>()
        val taken = mutableSetOf("$folder.rpp".lowercase())
        // The recordings (every take), imports and samples the items play, as they are.
        val media = p.tracks.flatMap { it.items }.filter { !it.isMidi }.flatMap { Takes.all(it) }
            .mapNotNull { job.files[it.sourceId] }.toSet()
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
            val stats = render(0, p.endFrame, job.tail, dryPaths = dry, ignoreSolo = true) ?: return null
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
            mediaFile = { sourceId -> job.files[sourceId]?.takeIf { it in media } },
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
        val lines = mutableListOf("${job.title} - from Playdaw Studio", "")
        val withFx = p.tracks.filter { it.fx.isNotEmpty() }
        if (withFx.isNotEmpty() || p.masterFx.isNotEmpty()) {
            lines += "Effects are not included, because REAPER uses its own plug-ins (ReaEQ, ReaComp, ReaVerbate, ReaDelay...). They were:"
            withFx.forEach { t -> lines += "- ${t.name}: " + t.fx.joinToString("; ") { describe(it) } }
            if (p.masterFx.isNotEmpty()) lines += "- Master: " + p.masterFx.joinToString("; ") { describe(it) }
            lines += ""
        }
        // ReaperProject mutes these sends, or they would add a second copy of the dry sound.
        val buses = p.tracks.filter { t -> t.fx.isNotEmpty() && p.tracks.any { s -> s.sends.any { it.target == t.id && !it.mute } } }
        if (buses.isNotEmpty()) {
            lines += "The sends into ${buses.joinToString { "\"${it.name}\"" }} arrive muted, because the effects there are not included."
            lines += "Add a REAPER effect on that track (ReaVerbate for a reverb, ReaDelay for a delay) set to all wet and no dry,"
            lines += "then unmute its receives: the track's routing button (IO), or the mixer's sends."
            lines += ""
        }
        if (p.tracks.any { it.instrument != null }) {
            lines += "Drum and synth tracks were rendered to audio. Their notes are on the \"MIDI\" track below each one:"
            lines += "add an instrument there (Insert virtual instrument on new track, or the track's FX button) to play them with another sound."
        }
        return lines
    }
}
