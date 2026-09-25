package org.scrib.transcriber

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class BenchmarkHistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val activity = this
        setContent {
            ScribTheme {
                val revision by BenchmarkStore.revisions.collectAsState()
                val runs = remember(revision) { BenchmarkStore.load(activity) }
                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/json")
                ) { uri ->
                    if (uri != null) {
                        try {
                            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                                it.write(BenchmarkStore.exportJson(activity))
                            } ?: throw RuntimeException("cannot write")
                            toast(getString(R.string.benchmark_exported))
                        } catch (e: Throwable) {
                            toast(getString(R.string.benchmark_export_failed))
                        }
                    }
                }
                BenchmarkHistoryScreen(
                    runs = runs,
                    onExport = { exportLauncher.launch(HISTORY_FILE_NAME) },
                    onClear = {
                        BenchmarkStore.clear(activity).also { cleared ->
                            if (!cleared) {
                                toast(getString(R.string.benchmark_clear_failed))
                            }
                        }
                    },
                    onDialog = {
                        BenchmarkStore.setFullscreen(activity, false)
                        activity.finish()
                    }
                )
            }
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val HISTORY_FILE_NAME = "scrib-benchmark-history.json"
    }
}

@Composable
private fun BenchmarkHistoryScreen(
    runs: List<BenchmarkRun>,
    onExport: () -> Unit,
    onClear: () -> Boolean,
    onDialog: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showClearConfirmation by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize()
            .background(cs.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.benchmark_history),
                modifier = Modifier.weight(1f),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = cs.onBackground
            )
            IconButton(onClick = { showInfo = true }) {
                Icon(
                    painterResource(R.drawable.ic_info),
                    contentDescription = stringResource(R.string.benchmark_info),
                    tint = cs.primary
                )
            }
            IconButton(onClick = onDialog) {
                Icon(
                    painterResource(R.drawable.ic_fullscreen_exit),
                    contentDescription = stringResource(R.string.benchmark_dialog),
                    tint = cs.primary
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)
        ) {
            TextButton(onClick = onExport, enabled = runs.isNotEmpty()) {
                Text(stringResource(R.string.benchmark_export_json))
            }
            TextButton(
                onClick = { showClearConfirmation = true },
                enabled = runs.isNotEmpty()
            ) {
                Text(stringResource(R.string.benchmark_clear_history), color = cs.error)
            }
        }
        if (runs.isEmpty()) {
            Text(
                stringResource(R.string.benchmark_history_empty),
                fontSize = 14.sp,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp)
            )
        } else {
            runs.forEach { run ->
                BenchmarkRunCard(run, Modifier.padding(bottom = 10.dp))
            }
        }
    }
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.benchmark_clear_title)) },
            text = { Text(stringResource(R.string.benchmark_clear_message)) },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (onClear()) {
                        showClearConfirmation = false
                    }
                }) {
                    Text(stringResource(R.string.benchmark_clear_history), color = cs.error)
                }
            }
        )
    }
    if (showInfo) {
        BenchmarkMetricsInfoDialog(onDismiss = { showInfo = false })
    }
}

@Composable
private fun BenchmarkMetricsInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.benchmark_info_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                BenchmarkMetricInfoRow(
                    stringResource(R.string.benchmark_column_rtf),
                    stringResource(R.string.benchmark_info_rtf)
                )
                BenchmarkMetricInfoRow(
                    stringResource(R.string.benchmark_column_load),
                    stringResource(R.string.benchmark_info_load)
                )
                BenchmarkMetricInfoRow(
                    stringResource(R.string.benchmark_column_model_pss),
                    stringResource(R.string.benchmark_info_model_pss)
                )
                BenchmarkMetricInfoRow(
                    stringResource(R.string.benchmark_column_model_delta),
                    stringResource(R.string.benchmark_info_model_delta)
                )
                BenchmarkMetricInfoRow(
                    stringResource(R.string.benchmark_column_peak),
                    stringResource(R.string.benchmark_info_peak)
                )
                BenchmarkMetricInfoRow(
                    stringResource(R.string.benchmark_column_free),
                    stringResource(R.string.benchmark_info_free)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun BenchmarkMetricInfoRow(label: String, description: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            modifier = Modifier.width(108.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = cs.primary
        )
        Text(
            description,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = cs.onSurfaceVariant
        )
    }
}
