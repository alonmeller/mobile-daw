package com.alonmeller.mobiledaw

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.lifecycle.lifecycleScope
import com.alonmeller.mobiledaw.data.AudioDecoder
import com.alonmeller.mobiledaw.data.ImportedAudio
import com.alonmeller.mobiledaw.ui.DawScreen
import com.alonmeller.mobiledaw.ui.DawTheme
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            DawTheme {
                // A timeline always runs left to right, even on a Hebrew device.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    DawScreen(vm, withMic = ::withMic, pickAudio = ::pickAudio)
                }
            }
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

    override fun onStop() {
        super.onStop()
        vm.onAppBackground()
    }
}
