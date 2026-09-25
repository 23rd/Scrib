package org.scrib.transcriber

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
                val runs = remember { BenchmarkStore.load(activity) }
                BenchmarkHistoryScreen(runs, onDialog = {
                    BenchmarkStore.setFullscreen(activity, false)
                    activity.finish()
                })
            }
        }
    }
}

@Composable
private fun BenchmarkHistoryScreen(runs: List<BenchmarkRun>, onDialog: () -> Unit) {
    val cs = MaterialTheme.colorScheme
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
            IconButton(onClick = onDialog) {
                Icon(
                    painterResource(R.drawable.ic_fullscreen_exit),
                    contentDescription = stringResource(R.string.benchmark_dialog),
                    tint = cs.primary
                )
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
}
