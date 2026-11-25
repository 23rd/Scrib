package org.scrib.transcriber

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay

internal enum class RunStage(@StringRes val label: Int) {
    Preparing(R.string.run_stats_preparing),
    Transcribing(R.string.run_stats_transcribing),
    Processing(R.string.run_stats_processing),
    Complete(R.string.run_stats_complete)
}

internal fun TranscribeUi.runStage(): RunStage = when {
    !running -> RunStage.Complete
    usesEndpoint -> RunStage.Processing
    audioPrepared || metrics.audioDurationMs > 0 || percent >= 0 || text.isNotBlank() -> RunStage.Transcribing
    else -> RunStage.Preparing
}

internal sealed class RunStat(@StringRes val label: Int) {
    class Name(label: Int, val name: String) : RunStat(label)
    class Duration(label: Int, val ms: Long) : RunStat(label)
    class Memory(label: Int, val mb: Int) : RunStat(label)
    class Ratio(label: Int, val ratio: Double) : RunStat(label)
}

internal fun TranscriptionMetrics.runStats(modelName: String?, usesEndpoint: Boolean): List<RunStat> = buildList {
    fun duration(@StringRes label: Int, ms: Long) {
        if (ms > 0) add(RunStat.Duration(label, ms))
    }
    fun memory(@StringRes label: Int, mb: Int) {
        if (mb > 0) add(RunStat.Memory(label, mb))
    }
    modelName?.takeUnless(String::isBlank)?.let { add(RunStat.Name(R.string.run_stats_model, it)) }
    duration(R.string.run_stats_decode, decodeMs)
    if (!usesEndpoint) {
        duration(R.string.run_stats_load, modelLoadMs)
        duration(R.string.run_stats_inference, inferenceMs)
    }
    duration(R.string.run_stats_audio, audioDurationMs)
    if (!usesEndpoint) {
        rtf?.takeIf { it.isFinite() && it > 0 }?.let { add(RunStat.Ratio(R.string.run_stats_ratio, it)) }
    }
    memory(R.string.run_stats_memory, pssMb)
    memory(R.string.run_stats_peak, peakPssMb)
    memory(R.string.run_stats_free, freeRamMb)
    if (!usesEndpoint) {
        memory(R.string.run_stats_model_memory, modelPssMb)
        memory(R.string.run_stats_model_delta, modelMemoryDeltaMb)
    }
}

internal fun formatRunClock(ms: Long, locale: Locale): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    return if (seconds < 3_600) {
        String.format(locale, "%02d:%02d", seconds / 60, seconds % 60)
    } else {
        String.format(locale, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    }
}

@Composable
internal fun TranscriptionStats(t: TranscribeUi, showStats: Boolean) {
    val cs = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val start = t.startedAtElapsedMs
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    var now by remember(start) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(start, t.running) {
        if (start != null && t.running) {
            while (true) {
                // Only the UI clock updates when live performance sampling is disabled.
                now = SystemClock.elapsedRealtime()
                delay(1_000L - (now - start).coerceAtLeast(0L) % 1_000L)
            }
        }
    }
    val total = if (t.running && start != null) (now - start).coerceAtLeast(0L) else t.metrics.totalMs
    val stage = stringResource(t.runStage().label)
    val summary = if (start != null || total > 0) {
        stringResource(R.string.run_stats_elapsed, stage, formatRunClock(total, locale))
    } else stage
    if (!showStats) {
        Text(summary, style = typography.bodySmall, color = cs.onSurfaceVariant)
        return
    }

    val stats = remember(t.metrics, t.modelName, t.usesEndpoint) { t.metrics.runStats(t.modelName, t.usesEndpoint) }
    var expanded by rememberSaveable(start, t.fileName, t.batchIndex) { mutableStateOf(false) }
    if (stats.isEmpty()) {
        Text(summary, style = typography.bodySmall, color = cs.onSurfaceVariant)
        return
    }

    val shape = RoundedCornerShape(12.dp)
    val action = stringResource(if (expanded) R.string.run_stats_collapse else R.string.run_stats_expand)
    Surface(color = cs.surfaceContainer, shape = shape, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().clip(shape)
                    .clickable(role = Role.Button, onClickLabel = action) { expanded = !expanded }
                    .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("scrib/transcription/stats")
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.run_stats_title), style = typography.labelLarge)
                    Text(summary, style = typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Icon(
                    painterResource(if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down),
                    contentDescription = null, modifier = Modifier.size(16.dp)
                )
            }
            if (expanded) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                        .testTag("scrib/transcription/stats/details"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    stats.forEach { stat ->
                        val value = when (stat) {
                            is RunStat.Name -> stat.name
                            is RunStat.Duration -> if (stat.ms < 1_000) {
                                stringResource(R.string.run_stats_millis, stat.ms)
                            } else {
                                formatRunClock(stat.ms, locale)
                            }
                            is RunStat.Memory -> memoryText(stat.mb)
                            is RunStat.Ratio -> stringResource(R.string.run_stats_multiplier, stat.ratio)
                        }
                        Row(
                            Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                stringResource(stat.label), modifier = Modifier.weight(1f),
                                style = typography.bodySmall, color = cs.onSurfaceVariant
                            )
                            Text(
                                value, modifier = Modifier.weight(1f), textAlign = TextAlign.End,
                                style = typography.bodySmall, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
