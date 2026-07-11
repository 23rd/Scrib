package org.scrib.transcriber

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScribTheme {
                val vm: ScribViewModel = viewModel()
                val state by vm.state.collectAsState()
                val transcription by vm.transcription.collectAsState()
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
                    onDismissTranscription = vm::dismissTranscription
                )
            }
        }
    }
}
