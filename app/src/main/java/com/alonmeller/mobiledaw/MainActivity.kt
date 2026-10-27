package com.alonmeller.mobiledaw

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.alonmeller.mobiledaw.data.AudioDecoder
import com.alonmeller.mobiledaw.data.ImportedAudio
import com.alonmeller.mobiledaw.ui.DawScreen
import com.alonmeller.mobiledaw.ui.DawTheme
import com.alonmeller.mobiledaw.ui.ExportActions
import java.io.File
import kotlinx.coroutines.Dispatchers
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSave = savedInstanceState?.getString(PENDING_SAVE)?.let(::File)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    override fun onStart() {
        super.onStart()
        vm.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        vm.onAppBackground()
    }

    private companion object {
        const val PENDING_SAVE = "pendingSave"

        /** The types of file an export makes. */
        val EXPORT_TYPES = listOf("audio/wav", "audio/mp4", "audio/midi", "application/zip", "application/octet-stream")
    }
}
