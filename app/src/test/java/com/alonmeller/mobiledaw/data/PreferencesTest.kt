package com.alonmeller.mobiledaw.data

import com.alonmeller.mobiledaw.ExportFormat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PreferencesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Every preference moved off its default. */
    private val changed = Preferences(
        undoSteps = 50,
        startup = StartupProject.ShowSongs,
        pinchZoom = false,
        doubleTapReset = false,
        haptics = true,
        newSongTempo = 96,
        newSongMetronome = true,
        newTrackDb = -6.5f,
        armNewTracks = false,
        sendDb = -12f,
        sendPreFader = true,
        autoFadeMs = 20,
        closeInBackground = false,
        stopOnUnplug = false,
        pauseForCalls = false,
        audioApi = AudioApi.OpenSLES,
        outputDevice = 7,
        inputDevice = 9,
        sampleRate = 44100,
        bufferBursts = 0,
        performance = PerformanceMode.PowerSaving,
        exclusive = false,
        micPreset = MicPreset.Unprocessed,
        midiInput = false,
        midiOff = setOf("Keystation 49", "nanoKEY2"),
        sustainedPerformance = true,
        stopAtEnd = true,
        returnOnStop = true,
        autoScroll = false,
        fxTailMs = 8000,
        useReportedLatency = false,
        latencyOffsetMs = -35,
        recordFormat = RecordFormat.Pcm24,
        clipWarning = false,
        recordingPreview = false,
        askAfterRecording = true,
        discardPartialTakes = false,
        renderTailMs = 2000,
        exportFormat = ExportFormat.M4a,
        exportNormalize = false,
        uiScale = 1.15f,
        fullScreen = false,
        keepScreenOn = KeepScreenOn.WhilePlaying,
        maxFps = 30,
        meterDecayDb = 48f,
        showHints = false,
        showGrid = false,
        showItemNames = false,
        showTakeNumbers = false,
        sqrtPeaks = true,
        rectifiedPeaks = true,
        trackSize = TrackSize.Large,
        showTrackNumbers = true,
        headerMeters = false,
        playheadAfterPaste = false,
        zoomAnchor = ZoomAnchor.Center,
        knobSensitivity = 0.5f,
        noteVelocity = 64,
        previewNotes = false,
        patternBars = 8,
        normalizeImports = true,
    )

    @Test
    fun everyPreferenceSurvivesSavingAndLoading() {
        assertEquals(Preferences(), Preferences.fromJson(JSONObject(Preferences().toJson().toString())))
        // Every field is off its default, so one that toJson or fromJson leaves out shows up here.
        assertEquals(changed, Preferences.fromJson(JSONObject(changed.toJson().toString())))
    }

    @Test
    fun missingUnknownAndOutOfRangeValuesFallBackToTheDefaults() {
        assertEquals(Preferences(), Preferences.fromJson(JSONObject()))
        val odd = JSONObject()
            .put("undoSteps", 5)
            .put("sampleRate", 12345)
            .put("bufferBursts", 99)
            .put("uiScale", 9.0)
            .put("knobSensitivity", Double.NaN.toString())
            .put("noteVelocity", 0)
            .put("latencyOffsetMs", 5000)
            .put("audioApi", "Bogus")
            .put("trackSize", "")
            .put("keepScreenOn", 3)
            .put("startup", "NewProject")
        val p = Preferences.fromJson(odd)
        val d = Preferences()
        assertEquals(d.undoSteps, p.undoSteps)
        assertEquals(d.sampleRate, p.sampleRate)
        assertEquals(d.bufferBursts, p.bufferBursts)
        assertEquals(d.uiScale, p.uiScale)
        assertEquals(d.knobSensitivity, p.knobSensitivity)
        assertEquals(d.noteVelocity, p.noteVelocity)
        assertEquals(d.latencyOffsetMs, p.latencyOffsetMs)
        assertEquals(d.audioApi, p.audioApi)
        assertEquals(d.trackSize, p.trackSize)
        assertEquals(d.keepScreenOn, p.keepScreenOn)
        // What is fine is kept.
        assertEquals(StartupProject.NewProject, p.startup)
    }

    @Test
    fun theStoreKeepsPreferencesAndSurvivesABadFile() {
        val dir = tmp.newFolder("files")
        val store = PreferencesStore(dir)
        assertEquals(Preferences(), store.load())
        store.save(changed)
        assertEquals(changed, PreferencesStore(dir).load())
        File(dir, "preferences.json").writeText("{ not json")
        assertEquals(Preferences(), store.load())
    }

    @Test
    fun theAudioChoicesAreNumberedAsTheEngineReadsThem() {
        // AudioEngine.cpp turns these numbers into Oboe's settings.
        assertEquals(listOf(0, 1, 2), AudioApi.entries.map { it.ordinal })
        assertEquals(listOf("Auto", "AAudio", "OpenSLES"), AudioApi.entries.map { it.name })
        assertEquals(listOf("LowLatency", "None", "PowerSaving"), PerformanceMode.entries.map { it.name })
        assertEquals(
            listOf("VoiceRecognition", "Unprocessed", "Camcorder", "VoiceCommunication", "Generic", "VoicePerformance"),
            MicPreset.entries.map { it.name },
        )
        assertEquals(listOf(32, 24, 16), RecordFormat.entries.map { it.bits })
    }
}
