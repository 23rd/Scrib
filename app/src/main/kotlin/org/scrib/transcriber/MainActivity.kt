package org.scrib.transcriber

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScribTheme {
                val vm: ScribViewModel = viewModel()
                val state by vm.state.collectAsState()
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
                        transcription = transcription,
                        onTranscribeFile = vm::transcribeFile,
                        onCancelTranscription = vm::cancelTranscription,
                        onDismissTranscription = vm::dismissTranscription,
                        onAbout = { showAbout = true }
                    )
                }
            }
        }
    }
}
