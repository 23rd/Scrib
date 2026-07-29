package org.scrib.transcriber

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    private val sharedVm: ScribViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on the first creation: a recreation (rotation) must not restart the work.
        if (savedInstanceState == null) {
            maybeTranscribeShared(intent)
        }
        setContent {
            ScribTheme {
                val vm: ScribViewModel = viewModel()
                val state by vm.state.collectAsState()
                val recording by vm.recording.collectAsState()
                val transcription by vm.transcription.collectAsState()
                var showAbout by rememberSaveable { mutableStateOf(false) }
                if (showAbout) {
                    BackHandler { showAbout = false }
                    AboutScreen(onBack = { showAbout = false })
                } else {
                    ScribScreen(
                        state = state,
                        onDownload = vm::download,
                        onCancel = vm::cancel,
                        onUse = vm::activate,
                        onDelete = vm::delete,
                        onAddUrl = vm::addCustom,
                        onImport = vm::importModel,
                        onSelfTest = vm::selfTest,
                        onPickLanguage = vm::setupForLanguage,
                        recording = recording,
                        onStartRecording = vm::startRecording,
                        onStopRecording = vm::stopRecording,
                        onCancelRecording = vm::cancelRecording,
                        transcription = transcription,
                        onTranscribeFile = vm::transcribeFile,
                        onCancelTranscription = vm::cancelTranscription,
                        onDismissTranscription = vm::dismissTranscription,
                        onSaveTranscript = vm::saveTranscript,
                        onTranscriptFormat = vm::setTranscriptFormat,
                        onAbout = { showAbout = true }
                    )
                }
            }
        }
    }

    // The platform hands a backgrounded app silence instead of the microphone, so a take that
    // would go on filling with nothing is finished here — a rotation is not leaving the app.
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            sharedVm.stopRecording()
        }
    }

    // A file arriving through the system share sheet goes straight into transcription. Without a
    // model there is nothing to run it with — the first-run screen then explains the download.
    private fun maybeTranscribeShared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) {
            return
        }
        val uri = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        } ?: return
        if (ModelManager.activeFileName(this) == null) {
            return
        }
        sharedVm.transcribeFile(uri, queryDisplayName(this, uri))
    }
}
