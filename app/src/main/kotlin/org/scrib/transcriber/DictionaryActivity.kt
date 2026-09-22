package org.scrib.transcriber

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class DictionaryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScribTheme {
                var enabled by remember { mutableStateOf(Dictionary.isEnabled(this)) }
                var text by remember { mutableStateOf(Dictionary.getRules(this)) }
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        try {
                            text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                                ?: throw RuntimeException("empty file")
                        } catch (e: Throwable) {
                            toast(getString(R.string.dict_import_failed))
                        }
                    }
                }
                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("text/plain")
                ) { uri ->
                    if (uri != null) {
                        try {
                            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                                ?: throw RuntimeException("cannot write")
                        } catch (e: Throwable) {
                            toast(getString(R.string.dict_import_failed))
                        }
                    }
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.dict_dialog_title),
                                fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(checked = enabled, onCheckedChange = { enabled = it })
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.dict_dialog_body),
                            fontSize = 13.sp, lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = text, onValueChange = { text = it }, singleLine = false,
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { importLauncher.launch(arrayOf("text/plain")) }) {
                                Text(stringResource(R.string.dict_import))
                            }
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = { exportLauncher.launch(DICT_FILE_NAME) }) {
                                Text(stringResource(R.string.dict_export))
                            }
                            Spacer(Modifier.weight(1f))
                            Button(onClick = {
                                Dictionary.setEnabled(this@DictionaryActivity, enabled)
                                Dictionary.setRules(this@DictionaryActivity, text)
                                toast(getString(R.string.dict_saved))
                            }) {
                                Text(stringResource(R.string.action_save))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val DICT_FILE_NAME = "scrib-dictionary.txt"
    }
}
