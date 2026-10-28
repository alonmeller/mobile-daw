package com.alonmeller.mobiledaw

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.alonmeller.mobiledaw.audio.MidiInput
import com.alonmeller.mobiledaw.data.AudioDecoder
import com.alonmeller.mobiledaw.data.ImportedAudio
import com.alonmeller.mobiledaw.data.KeepScreenOn
import com.alonmeller.mobiledaw.data.PreferencesStore
import com.alonmeller.mobiledaw.ui.DawScreen
import com.alonmeller.mobiledaw.ui.DawTheme
import com.alonmeller.mobiledaw.ui.ExportActions
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val vm: DawViewModel by viewModels()

    /** What to do once the microphone permission is granted. */
    private var afterMicGranted: (() -> Unit)? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = afterMicGranted
        afterMicGranted = null
        if (granted) action?.invoke() else vm.message = "Microphone permission is needed to record"
    }

    /** What to do with the audio file the user picks. */
    private var afterAudioPicked: ((ImportedAudio) -> Unit)? = null

    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val action = afterAudioPicked
        afterAudioPicked = null
        if (uri == null || action == null) return@registerForActivityResult
        vm.message = "Reading the file…"
        lifecycleScope.launch {
            val audio = withContext(Dispatchers.Default) { AudioDecoder.decode(applicationContext, uri) }
            if (audio == null) vm.message = "Could not read that audio file" else action(audio)
        }
    }

    /** The exported file waiting for the user to pick where to save it. */
    private var pendingSave: File? = null

    /** "Save to phone": the system's save screen, one per kind of file so it offers the right type. */
    private val savePickers = EXPORT_TYPES.associateWith { mime ->
        registerForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri -> saveExport(uri) }
    }

    /** The interface size this activity started with (Preferences › Appearance). */
    private var uiScale = 1f

    /**
     * Applies the interface size the way Android's own display size does: by
     * scaling the screen's density, so every button, text, menu and dialog
     * grows or shrinks together.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        uiScale = PreferencesStore(newBase.filesDir).load().uiScale
        if (uiScale != 1f) {
            val dpi = newBase.resources.configuration.densityDpi
            applyOverrideConfiguration(Configuration().apply { densityDpi = (dpi * uiScale).roundToInt() })
        }
    }

    /** MIDI keyboards plugged into the phone. */
    private var midi: MidiInput? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Drawn behind the phone's bars, as Android 15 requires; the screen keeps clear of them itself.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        pendingSave = savedInstanceState?.getString(PENDING_SAVE)?.let(::File)
        vm.sustainedSupported = getSystemService(PowerManager::class.java)?.isSustainedPerformanceModeSupported == true
        midi = MidiInput(
            this,
            // Not while the app is away: the notes would wait and all sound at once on return.
            listening = { name ->
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && vm.prefs.midiInput && name !in vm.prefs.midiOff
            },
            onDevices = { vm.midiDevices = it },
            onNote = vm::midiNote,
            onSustain = vm::midiSustain,
        ).also { it.start() }
        followPreferences()
        val exportActions = ExportActions(share = ::share, save = ::save)
        setContent {
            DawTheme {
                // A timeline always runs left to right, even on a Hebrew device.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    DawScreen(vm, withMic = ::withMic, pickAudio = ::pickAudio, exportActions = exportActions)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingSave?.let { outState.putString(PENDING_SAVE, it.path) }
    }

    /** Hands an exported file to another app: WhatsApp, Gmail, Drive, a file manager... */
    private fun share(file: File, mime: String) {
        val uri = runCatching { FileProvider.getUriForFile(this, "$packageName.files", file) }.getOrNull()
        if (uri == null) {
            vm.message = "Could not share ${file.name}"
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, file.name)) }
            .onFailure { vm.message = "No app on this phone can take that file" }
    }

    /** Lets the user pick a folder and a name (Downloads, Drive...), then copies the file there. */
    private fun save(file: File, mime: String) {
        val picker = savePickers[mime] ?: savePickers.getValue("application/octet-stream")
        pendingSave = file
        picker.launch(file.name)
    }

    private fun saveExport(uri: Uri?) {
        val file = pendingSave
        pendingSave = null
        if (uri == null || file == null) return
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val out = contentResolver.openOutputStream(uri) ?: return@runCatching false
                    out.use { file.inputStream().use { input -> input.copyTo(it) } }
                    true
                }.getOrDefault(false)
            }
            vm.message = if (saved) "Saved ${file.name}" else "Could not save ${file.name}"
        }
    }

    /** Lets the user pick an audio file (MP3, WAV, M4A, ...) and hands it to [action] decoded. */
    private fun pickAudio(action: (ImportedAudio) -> Unit) {
        afterAudioPicked = action
        audioPicker.launch(arrayOf("audio/*"))
    }

    /** Runs [action] now if the app may use the microphone, or after the user allows it. */
    private fun withMic(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            afterMicGranted = action
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Applies the preferences that are about the phone rather than the song, each time they change. */
    private fun followPreferences() {
        lifecycleScope.launch { snapshotFlow { vm.prefs.fullScreen }.collect { applyFullScreen() } }
        lifecycleScope.launch {
            snapshotFlow {
                // A recording would stop if the screen went dark, so for one it stays on in any case.
                val busy = vm.recording || vm.recordingMidi || vm.samplingPad != null
                when (vm.prefs.keepScreenOn) {
                    KeepScreenOn.Always -> true
                    KeepScreenOn.WhilePlaying -> busy || vm.playing || vm.exportProgress != null
                    KeepScreenOn.Never -> busy
                }
            }.collect { on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        lifecycleScope.launch {
            snapshotFlow { vm.prefs.sustainedPerformance && vm.sustainedSupported }.collect { window.setSustainedPerformanceMode(it) }
        }
        lifecycleScope.launch {
            // Holding the sound while the song runs pauses other players, and lets a call stop the song.
            snapshotFlow { vm.prefs.pauseForCalls && (vm.playing || vm.recording || vm.recordingMidi) }.collect { holdAudioFocus(it) }
        }
        lifecycleScope.launch {
            snapshotFlow { vm.prefs.uiScale }.drop(1).collectLatest { scale ->
                // After the last of a few quick taps, the screen starts again at the new size.
                delay(700)
                if (scale != uiScale && vm.savePrefsNow()) recreate()
            }
        }
    }

    /** Full screen hides the status and navigation bars; a swipe from the edge shows them for a moment. */
    private fun applyFullScreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (vm.prefs.fullScreen) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Some phones bring the bars back for a dialog.
        if (hasFocus) applyFullScreen()
    }

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener({ change ->
                // A call or another player takes the sound; a notification that only lowers it is let be.
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    vm.onAudioInterrupted()
                }
            }, Handler(Looper.getMainLooper()))
            .build()
    }
    private var holdingFocus = false

    private fun holdAudioFocus(hold: Boolean) {
        val manager = audioManager ?: return
        if (hold == holdingFocus) return
        holdingFocus = hold
        if (hold) manager.requestAudioFocus(focusRequest) else manager.abandonAudioFocusRequest(focusRequest)
    }

    override fun onStart() {
        super.onStart()
        vm.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        // Starting again at a new interface size is not leaving the app.
        if (!isChangingConfigurations) vm.onAppBackground()
    }

    override fun onDestroy() {
        midi?.stop()
        midi = null
        if (holdingFocus) audioManager?.abandonAudioFocusRequest(focusRequest)
        super.onDestroy()
    }

    private companion object {
        const val PENDING_SAVE = "pendingSave"

        /** The types of file an export makes. */
        val EXPORT_TYPES = listOf("audio/wav", "audio/mp4", "audio/midi", "application/zip", "application/octet-stream")
    }
}
