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
import com.alonmeller.mobiledaw.data.ProjectStore
import com.alonmeller.mobiledaw.data.SavedProject
import com.alonmeller.mobiledaw.data.SavedTrack
import com.alonmeller.mobiledaw.data.WavFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Frames per waveform bucket; fine enough for the deepest zoom level. */
const val PEAK_BUCKET = 256

data class TrackState(
    val engineId: Int,
    val fileName: String,
    val name: String,
    val colorIndex: Int,
    val startFrame: Long,
    val lengthFrames: Long,
    val peaks: FloatArray,
    val gain: Float = 1f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
)

class DawViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProjectStore(app.filesDir)
    private val engineStarted = NativeEngine.start()
    val sampleRate: Int = NativeEngine.sampleRate()

    var tracks by mutableStateOf(listOf<TrackState>())
        private set
    var tempo by mutableStateOf(120.0)
        private set
    var metronome by mutableStateOf(false)
        private set
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
    var trackLevels by mutableStateOf(mapOf<Int, Float>())
        private set
    var loading by mutableStateOf(true)
        private set
    var message by mutableStateOf<String?>(if (engineStarted) null else "Audio engine failed to start")

    /** Timeline zoom in dp per second, and horizontal scroll in px. */
    var zoomDp by mutableFloatStateOf(60f)
        private set
    var scrollPx by mutableFloatStateOf(0f)
    var laneWidthPx by mutableFloatStateOf(0f)

    private var nextTrackNumber = 1

    init {
        viewModelScope.launch { loadProject() }
    }

    private suspend fun loadProject() {
        val project = withContext(Dispatchers.IO) { store.load() }
        if (project != null) {
            tempo = project.tempo
            metronome = project.metronome
            NativeEngine.setTempo(tempo)
            NativeEngine.setMetronome(metronome)
            val loaded = withContext(Dispatchers.IO) {
                project.tracks.mapNotNull { saved ->
                    val audio = WavFile.read(store.audioFile(saved.fileName)) ?: return@mapNotNull null
                    val samples = WavFile.resample(audio.samples, audio.sampleRate, sampleRate)
                    val start = saved.startFrame * sampleRate / project.sampleRate
                    val id = NativeEngine.addTrack(samples, start)
                    NativeEngine.setTrackGain(id, saved.gain)
                    NativeEngine.setTrackPan(id, saved.pan)
                    NativeEngine.setTrackMute(id, saved.mute)
                    NativeEngine.setTrackSolo(id, saved.solo)
                    TrackState(
                        engineId = id,
                        fileName = saved.fileName,
                        name = saved.name,
                        colorIndex = saved.colorIndex,
                        startFrame = start,
                        lengthFrames = samples.size.toLong(),
                        peaks = NativeEngine.trackPeaks(id, PEAK_BUCKET),
                        gain = saved.gain,
                        pan = saved.pan,
                        mute = saved.mute,
                        solo = saved.solo,
                    )
                }
            }
            tracks = loaded
            nextTrackNumber = loaded.size + 1
        }
        loading = false
    }

    private fun saveProject() {
        val snapshot = SavedProject(
            tempo = tempo,
            metronome = metronome,
            sampleRate = sampleRate,
            tracks = tracks.map {
                SavedTrack(it.fileName, it.name, it.colorIndex, it.startFrame, it.gain, it.pan, it.mute, it.solo)
            },
        )
        viewModelScope.launch(Dispatchers.IO) { store.save(snapshot) }
    }

    /** Called every UI frame to pull transport and meter state from the engine. */
    fun tick() {
        position = NativeEngine.position()
        masterLevel = NativeEngine.masterPeak()
        if (recording) {
            recordedFrames = NativeEngine.recordedFrames()
            inputLevel = NativeEngine.inputLevel()
        }
        if (playing) {
            trackLevels = tracks.associate { it.engineId to NativeEngine.trackPeak(it.engineId) }
            followPlayhead()
        } else if (trackLevels.isNotEmpty()) {
            trackLevels = emptyMap()
        }
    }

    private var density = 1f
    fun setDensity(value: Float) {
        density = value
    }

    fun framesToPx(frames: Long): Float = frames.toFloat() / sampleRate * zoomDp * density
    fun pxToFrames(px: Float): Long = (px / (zoomDp * density) * sampleRate).toLong()

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
        val anchorFrames = position
        val anchorScreen = framesToPx(anchorFrames) - scrollPx
        zoomDp = (zoomDp * factor).coerceIn(10f, 400f)
        scrollPx = (framesToPx(anchorFrames) - anchorScreen).coerceAtLeast(0f)
    }

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
        seekFrames(0)
        scrollPx = 0f
    }

    fun seekFrames(frame: Long) {
        NativeEngine.seek(frame.coerceAtLeast(0))
        position = NativeEngine.position()
    }

    fun seekToScreenX(x: Float) {
        seekFrames(pxToFrames(x + scrollPx))
    }

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
        val id = NativeEngine.stopRecording()
        recording = false
        inputLevel = 0f
        if (id < 0) return
        val number = nextTrackNumber++
        val track = TrackState(
            engineId = id,
            fileName = "take_${UUID.randomUUID()}.wav",
            name = "Audio $number",
            colorIndex = number - 1,
            startFrame = NativeEngine.trackStart(id),
            lengthFrames = NativeEngine.trackLength(id),
            peaks = NativeEngine.trackPeaks(id, PEAK_BUCKET),
        )
        tracks = tracks + track
        viewModelScope.launch(Dispatchers.IO) {
            WavFile.writeFloatMono(store.audioFile(track.fileName), NativeEngine.trackSamples(id), sampleRate)
            withContext(Dispatchers.Main) { saveProject() }
        }
    }

    fun toggleMetronome() {
        metronome = !metronome
        NativeEngine.setMetronome(metronome)
        saveProject()
    }

    fun changeTempo(delta: Double) {
        tempo = (tempo + delta).coerceIn(30.0, 300.0)
        NativeEngine.setTempo(tempo)
        saveProject()
    }

    private fun updateTrack(id: Int, save: Boolean = true, change: (TrackState) -> TrackState) {
        tracks = tracks.map { if (it.engineId == id) change(it) else it }
        if (save) saveProject()
    }

    fun setGain(id: Int, gain: Float, save: Boolean) {
        NativeEngine.setTrackGain(id, gain)
        updateTrack(id, save) { it.copy(gain = gain) }
    }

    fun setPan(id: Int, pan: Float, save: Boolean) {
        NativeEngine.setTrackPan(id, pan)
        updateTrack(id, save) { it.copy(pan = pan) }
    }

    fun toggleMute(id: Int) {
        val t = tracks.find { it.engineId == id } ?: return
        NativeEngine.setTrackMute(id, !t.mute)
        updateTrack(id) { it.copy(mute = !it.mute) }
    }

    fun toggleSolo(id: Int) {
        val t = tracks.find { it.engineId == id } ?: return
        NativeEngine.setTrackSolo(id, !t.solo)
        updateTrack(id) { it.copy(solo = !it.solo) }
    }

    fun moveTrack(id: Int, deltaPx: Float) {
        val t = tracks.find { it.engineId == id } ?: return
        val newStart = (t.startFrame + pxToFrames(deltaPx)).coerceAtLeast(0)
        NativeEngine.setTrackStart(id, newStart)
        updateTrack(id, save = false) { it.copy(startFrame = newStart) }
    }

    fun commitMove() = saveProject()

    fun deleteTrack(id: Int) {
        val t = tracks.find { it.engineId == id } ?: return
        NativeEngine.removeTrack(id)
        tracks = tracks.filterNot { it.engineId == id }
        viewModelScope.launch(Dispatchers.IO) { store.audioFile(t.fileName).delete() }
        saveProject()
    }

    fun onAppBackground() {
        if (recording) stopRecording()
        if (playing) {
            playing = false
            NativeEngine.setPlaying(false)
        }
        saveProject()
    }
}
