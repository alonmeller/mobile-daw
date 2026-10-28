package com.alonmeller.mobiledaw.data

import com.alonmeller.mobiledaw.ExportFormat
import com.alonmeller.mobiledaw.model.Zoom
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What opens when the app starts, as REAPER's "Open project(s) on startup". */
enum class StartupProject(val label: String) {
    LastProject("Last active song"),
    NewProject("A new song"),
    ShowSongs("My Songs, to pick one"),
}

/** The phone's audio system, as REAPER's "Audio system": automatic is AAudio on Android 8.1 and later, else OpenSL ES. */
enum class AudioApi(val label: String) { Auto("Automatic"), AAudio("AAudio"), OpenSLES("OpenSL ES") }

/** How the audio system trades latency for battery, as Android's performance modes. */
enum class PerformanceMode(val label: String) { LowLatency("Low latency"), None("Normal"), PowerSaving("Power saving") }

/** How Android treats the microphone's sound before the app gets it (its input preset). */
enum class MicPreset(val label: String) {
    VoiceRecognition("Voice recognition"),
    Unprocessed("Unprocessed"),
    Camcorder("Camcorder"),
    VoiceCommunication("Voice call"),
    Generic("Generic"),
    VoicePerformance("Live performance"),
}

enum class KeepScreenOn(val label: String) { Always("Always"), WhilePlaying("While playing"), Never("Never") }

/** The track heights the app named before tracks could be any height; their size classes still shape the track headers. */
enum class TrackSize(val label: String, val heightDp: Int) { Small("Small", 72), Normal("Normal", 92), Large("Large", 120) }

/** The sample format new recordings are saved in, as REAPER's recording format. */
enum class RecordFormat(val label: String, val bits: Int) { Float32("32-bit float", 32), Pcm24("24-bit", 24), Pcm16("16-bit", 16) }

/** What stays put when the timeline zooms, as REAPER's "Horizontal zoom center". */
enum class ZoomAnchor(val label: String) { Playhead("Playhead"), Center("Center of view") }

/**
 * The app's preferences, like REAPER's Preferences window, which REAPER
 * keeps in reaper.ini: one set for every song, saved in preferences.json.
 * Settings that belong to one song stay in [ProjectSettings].
 */
data class Preferences(
    // General
    val undoSteps: Int = 200,
    val startup: StartupProject = StartupProject.LastProject,
    // General > Keyboard/Multitouch
    val pinchZoom: Boolean = true,
    val doubleTapReset: Boolean = true,
    val haptics: Boolean = false,
    // Project
    val newSongTempo: Int = 120,
    val newSongMetronome: Boolean = false,
    // Project > Track/Send Defaults
    val newTrackDb: Float = 0f,
    val armNewTracks: Boolean = true,
    val sendDb: Float = -6f,
    val sendPreFader: Boolean = false,
    // Project > Media Item Defaults
    val autoFadeMs: Int = 10,
    // Audio
    val closeInBackground: Boolean = true,
    val stopOnUnplug: Boolean = true,
    val pauseForCalls: Boolean = true,
    // Audio > Device
    val audioApi: AudioApi = AudioApi.Auto,
    /** Android's id of the output and input devices to use, or 0 for the system's choice. */
    val outputDevice: Int = 0,
    val inputDevice: Int = 0,
    /** 0 is the device's own rate. */
    val sampleRate: Int = 0,
    /** Buffer size in bursts (the device's block); 0 starts at one and grows when the sound glitches. */
    val bufferBursts: Int = 2,
    val performance: PerformanceMode = PerformanceMode.LowLatency,
    val exclusive: Boolean = true,
    val micPreset: MicPreset = MicPreset.VoiceRecognition,
    // Audio > MIDI Devices
    val midiInput: Boolean = true,
    /** Names of the MIDI devices turned off. */
    val midiOff: Set<String> = emptySet(),
    // Audio > Buffering
    val sustainedPerformance: Boolean = false,
    // Audio > Playback
    val stopAtEnd: Boolean = false,
    val returnOnStop: Boolean = false,
    val autoScroll: Boolean = true,
    val fxTailMs: Int = 4000,
    // Audio > Recording
    val useReportedLatency: Boolean = true,
    val latencyOffsetMs: Int = 0,
    val recordFormat: RecordFormat = RecordFormat.Float32,
    val clipWarning: Boolean = true,
    val recordingPreview: Boolean = true,
    val askAfterRecording: Boolean = false,
    // Audio > Loop Recording
    val discardPartialTakes: Boolean = true,
    // Audio > Rendering
    val renderTailMs: Int = 10_000,
    val exportFormat: ExportFormat = ExportFormat.Wav24,
    val exportNormalize: Boolean = true,
    // Appearance
    val uiScale: Float = 1f,
    val fullScreen: Boolean = true,
    val keepScreenOn: KeepScreenOn = KeepScreenOn.Always,
    /** Screen updates per second while something moves; 0 is every frame the screen shows. */
    val maxFps: Int = 0,
    val meterDecayDb: Float = 24f,
    val showHints: Boolean = true,
    val showGrid: Boolean = true,
    // Appearance > Media
    val showItemNames: Boolean = true,
    val showTakeNumbers: Boolean = true,
    // Appearance > Peaks/Waveforms
    val sqrtPeaks: Boolean = false,
    val rectifiedPeaks: Boolean = false,
    // Appearance > Track Control Panels
    /** Height of every track on the timeline in dp, which a pinch up and down also changes. */
    val trackHeight: Int = TrackSize.Normal.heightDp,
    val showTrackNumbers: Boolean = false,
    val headerMeters: Boolean = true,
    // Editing Behavior
    val playheadAfterPaste: Boolean = true,
    val zoomAnchor: ZoomAnchor = ZoomAnchor.Playhead,
    // Editing Behavior > Mouse
    val knobSensitivity: Float = 1f,
    // Editing Behavior > MIDI Editor
    /** Velocity of notes drawn in the piano roll, 1..127. */
    val noteVelocity: Int = 102,
    val previewNotes: Boolean = true,
    /** Bars in a new pattern; 0 is one for drums and four for a synth. */
    val patternBars: Int = 0,
    // Media
    val normalizeImports: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("undoSteps", undoSteps)
        .put("startup", startup.name)
        .put("pinchZoom", pinchZoom)
        .put("doubleTapReset", doubleTapReset)
        .put("haptics", haptics)
        .put("newSongTempo", newSongTempo)
        .put("newSongMetronome", newSongMetronome)
        .put("newTrackDb", newTrackDb.toDouble())
        .put("armNewTracks", armNewTracks)
        .put("sendDb", sendDb.toDouble())
        .put("sendPreFader", sendPreFader)
        .put("autoFadeMs", autoFadeMs)
        .put("closeInBackground", closeInBackground)
        .put("stopOnUnplug", stopOnUnplug)
        .put("pauseForCalls", pauseForCalls)
        .put("audioApi", audioApi.name)
        .put("outputDevice", outputDevice)
        .put("inputDevice", inputDevice)
        .put("sampleRate", sampleRate)
        .put("bufferBursts", bufferBursts)
        .put("performance", performance.name)
        .put("exclusive", exclusive)
        .put("micPreset", micPreset.name)
        .put("midiInput", midiInput)
        .put("midiOff", JSONArray(midiOff.sorted()))
        .put("sustainedPerformance", sustainedPerformance)
        .put("stopAtEnd", stopAtEnd)
        .put("returnOnStop", returnOnStop)
        .put("autoScroll", autoScroll)
        .put("fxTailMs", fxTailMs)
        .put("useReportedLatency", useReportedLatency)
        .put("latencyOffsetMs", latencyOffsetMs)
        .put("recordFormat", recordFormat.name)
        .put("clipWarning", clipWarning)
        .put("recordingPreview", recordingPreview)
        .put("askAfterRecording", askAfterRecording)
        .put("discardPartialTakes", discardPartialTakes)
        .put("renderTailMs", renderTailMs)
        .put("exportFormat", exportFormat.name)
        .put("exportNormalize", exportNormalize)
        .put("uiScale", uiScale.toDouble())
        .put("fullScreen", fullScreen)
        .put("keepScreenOn", keepScreenOn.name)
        .put("maxFps", maxFps)
        .put("meterDecayDb", meterDecayDb.toDouble())
        .put("showHints", showHints)
        .put("showGrid", showGrid)
        .put("showItemNames", showItemNames)
        .put("showTakeNumbers", showTakeNumbers)
        .put("sqrtPeaks", sqrtPeaks)
        .put("rectifiedPeaks", rectifiedPeaks)
        .put("trackHeight", trackHeight)
        .put("showTrackNumbers", showTrackNumbers)
        .put("headerMeters", headerMeters)
        .put("playheadAfterPaste", playheadAfterPaste)
        .put("zoomAnchor", zoomAnchor.name)
        .put("knobSensitivity", knobSensitivity.toDouble())
        .put("noteVelocity", noteVelocity)
        .put("previewNotes", previewNotes)
        .put("patternBars", patternBars)
        .put("normalizeImports", normalizeImports)

    companion object {
        /** Sample rates the app offers; 0 is the device's own. */
        val SAMPLE_RATES = listOf(0, 44100, 48000)

        /** Reads preferences, keeping the default for anything missing, unknown or out of range. */
        fun fromJson(json: JSONObject): Preferences {
            val d = Preferences()
            fun int(key: String, default: Int, range: IntRange) = json.optInt(key, default).let { if (it in range) it else default }
            fun float(key: String, default: Float, range: ClosedFloatingPointRange<Float>) =
                json.optDouble(key, default.toDouble()).toFloat().let { if (!it.isNaN() && it in range) it else default }
            fun bool(key: String, default: Boolean) = json.optBoolean(key, default)
            val midiOff = json.optJSONArray("midiOff")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSet() }
            return Preferences(
                undoSteps = int("undoSteps", d.undoSteps, 10..1000),
                startup = json.enum("startup", d.startup),
                pinchZoom = bool("pinchZoom", d.pinchZoom),
                doubleTapReset = bool("doubleTapReset", d.doubleTapReset),
                haptics = bool("haptics", d.haptics),
                newSongTempo = int("newSongTempo", d.newSongTempo, 30..300),
                newSongMetronome = bool("newSongMetronome", d.newSongMetronome),
                newTrackDb = float("newTrackDb", d.newTrackDb, -60f..6f),
                armNewTracks = bool("armNewTracks", d.armNewTracks),
                sendDb = float("sendDb", d.sendDb, -60f..6f),
                sendPreFader = bool("sendPreFader", d.sendPreFader),
                autoFadeMs = int("autoFadeMs", d.autoFadeMs, 0..1000),
                closeInBackground = bool("closeInBackground", d.closeInBackground),
                stopOnUnplug = bool("stopOnUnplug", d.stopOnUnplug),
                pauseForCalls = bool("pauseForCalls", d.pauseForCalls),
                audioApi = json.enum("audioApi", d.audioApi),
                outputDevice = int("outputDevice", d.outputDevice, 0..Int.MAX_VALUE),
                inputDevice = int("inputDevice", d.inputDevice, 0..Int.MAX_VALUE),
                sampleRate = json.optInt("sampleRate", d.sampleRate).let { if (it in SAMPLE_RATES) it else d.sampleRate },
                bufferBursts = int("bufferBursts", d.bufferBursts, 0..16),
                performance = json.enum("performance", d.performance),
                exclusive = bool("exclusive", d.exclusive),
                micPreset = json.enum("micPreset", d.micPreset),
                midiInput = bool("midiInput", d.midiInput),
                midiOff = midiOff ?: d.midiOff,
                sustainedPerformance = bool("sustainedPerformance", d.sustainedPerformance),
                stopAtEnd = bool("stopAtEnd", d.stopAtEnd),
                returnOnStop = bool("returnOnStop", d.returnOnStop),
                autoScroll = bool("autoScroll", d.autoScroll),
                fxTailMs = int("fxTailMs", d.fxTailMs, 0..30_000),
                useReportedLatency = bool("useReportedLatency", d.useReportedLatency),
                latencyOffsetMs = int("latencyOffsetMs", d.latencyOffsetMs, -500..500),
                recordFormat = json.enum("recordFormat", d.recordFormat),
                clipWarning = bool("clipWarning", d.clipWarning),
                recordingPreview = bool("recordingPreview", d.recordingPreview),
                askAfterRecording = bool("askAfterRecording", d.askAfterRecording),
                discardPartialTakes = bool("discardPartialTakes", d.discardPartialTakes),
                renderTailMs = int("renderTailMs", d.renderTailMs, 0..30_000),
                exportFormat = json.enum("exportFormat", d.exportFormat),
                exportNormalize = bool("exportNormalize", d.exportNormalize),
                uiScale = float("uiScale", d.uiScale, 0.7f..1.5f),
                fullScreen = bool("fullScreen", d.fullScreen),
                keepScreenOn = json.enum("keepScreenOn", d.keepScreenOn),
                maxFps = int("maxFps", d.maxFps, 0..240),
                meterDecayDb = float("meterDecayDb", d.meterDecayDb, 3f..200f),
                showHints = bool("showHints", d.showHints),
                showGrid = bool("showGrid", d.showGrid),
                showItemNames = bool("showItemNames", d.showItemNames),
                showTakeNumbers = bool("showTakeNumbers", d.showTakeNumbers),
                sqrtPeaks = bool("sqrtPeaks", d.sqrtPeaks),
                rectifiedPeaks = bool("rectifiedPeaks", d.rectifiedPeaks),
                // Before tracks could be any height, the preferences kept one of three sizes.
                trackHeight = if (json.has("trackHeight")) int("trackHeight", d.trackHeight, Zoom.MIN_TRACK_HEIGHT..Zoom.MAX_TRACK_HEIGHT)
                else json.enum("trackSize", TrackSize.Normal).heightDp,
                showTrackNumbers = bool("showTrackNumbers", d.showTrackNumbers),
                headerMeters = bool("headerMeters", d.headerMeters),
                playheadAfterPaste = bool("playheadAfterPaste", d.playheadAfterPaste),
                zoomAnchor = json.enum("zoomAnchor", d.zoomAnchor),
                knobSensitivity = float("knobSensitivity", d.knobSensitivity, 0.25f..4f),
                noteVelocity = int("noteVelocity", d.noteVelocity, 1..127),
                previewNotes = bool("previewNotes", d.previewNotes),
                patternBars = int("patternBars", d.patternBars, 0..16),
                normalizeImports = bool("normalizeImports", d.normalizeImports),
            )
        }

        private inline fun <reified E : Enum<E>> JSONObject.enum(key: String, default: E): E {
            val name = optString(key, "")
            return enumValues<E>().firstOrNull { it.name == name } ?: default
        }
    }
}

/** Keeps the [Preferences] in preferences.json in [dir], the app's files. */
class PreferencesStore(dir: File) {
    private val file = File(dir, "preferences.json")

    /** The saved preferences, or the defaults when there are none yet or the file is unreadable. */
    fun load(): Preferences = runCatching {
        if (file.exists()) Preferences.fromJson(JSONObject(file.readText())) else Preferences()
    }.getOrDefault(Preferences())

    @Synchronized
    fun save(prefs: Preferences) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(prefs.toJson().toString(2))
        check(tmp.renameTo(file)) { "Could not save ${file.path}" }
    }
}
