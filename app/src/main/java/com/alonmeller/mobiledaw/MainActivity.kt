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
import com.alonmeller.mobiledaw.ui.DawScreen
import com.alonmeller.mobiledaw.ui.DawTheme

class MainActivity : ComponentActivity() {
    private val vm: DawViewModel by viewModels()

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleRecord() else vm.message = "Microphone permission is needed to record"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            DawTheme {
                // A timeline always runs left to right, even on a Hebrew device.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    DawScreen(vm, onRecordPressed = ::onRecordPressed)
                }
            }
        }
    }

    private fun onRecordPressed() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted || vm.recording) vm.toggleRecord() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onStop() {
        super.onStop()
        vm.onAppBackground()
    }
}
