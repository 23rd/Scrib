package org.scrib.transcriber

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.sqrt

internal data class Actions(
    val onDownload: (String) -> Unit,
    val onCancel: (String) -> Unit,
    val onUse: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onAddUrl: (String) -> Unit,
    val onImport: (Uri, String?) -> Unit,
    val onSelfTest: () -> Unit
)

@Composable
fun ScribScreen(
    state: ScribUiState,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onUse: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAddUrl: (String) -> Unit,
    onImport: (Uri, String?) -> Unit,
    onSelfTest: () -> Unit,
    onPickLanguage: (LanguageOption) -> Unit,
    onSkipSilence: (Boolean) -> Unit,
    onImportSherpa: (String, String, List<String>?, Map<String, Uri>) -> Unit,
    sherpaPlugin: SherpaPlugin?,
    recording: RecordingUi?,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    transcription: TranscribeUi?,
    onTranscribeFile: (Uri, String?) -> Unit,
    onCancelTranscription: () -> Unit,
    onDismissTranscription: () -> Unit,
    onSaveTranscript: (Uri) -> Unit,
    onTranscriptFormat: (TranscriptFormat) -> Unit,
    onAbout: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val actions = Actions(onDownload, onCancel, onUse, onDelete, onAddUrl, onImport, onSelfTest)
    var showAdd by remember { mutableStateOf(false) }
    var showLanguages by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var modelsExpanded by rememberSaveable { mutableStateOf(true) }

    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri, queryDisplayName(context, uri))
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onTranscribeFile(uri, queryDisplayName(context, uri))
    }
    // Already granted, and the contract answers without showing anything; a refusal is reported by
    // the recording attempt itself.
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onStartRecording()
    }
    // Asked for at the first run, where the shade is about to become the only place the progress
    // shows. A refusal costs the notification, not the run.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val started = transcription != null
    LaunchedEffect(started) {
        if (started && android.os.Build.VERSION.SDK_INT >= 33) {
            notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(Modifier.fillMaxSize().background(cs.background).systemBarsPadding()) {
        AppBar(onAbout)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp)
        ) {
            item {
                if (state.firstRun) NudgeCard(state, actions) else StatusCard(state)
            }
            if (!state.firstRun) {
                item { RecordButton { microphone.launch(android.Manifest.permission.RECORD_AUDIO) } }
                item { TranscribeFileButton { audioPicker.launch(arrayOf("audio/*", "video/*")) } }
                item { KeyboardEntry() }
                item { SkipSilenceEntry(state, onSkipSilence) }
                item { DictionaryEntry(state.dictionaryEnabled) { openDictionary(context) } }
            }
            item {
                Text(
                    stringResource(R.string.models_explainer),
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                    lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp)
                )
            }
            item { LanguageEntry { showLanguages = true } }
            item { StandardHeader(expanded = modelsExpanded, onToggle = { modelsExpanded = !modelsExpanded }) }
            if (modelsExpanded) {
                items(state.standard.size) { i -> ModelRowCard(state.standard[i], actions) { deleteTarget = it } }
            }
            if (sherpaPlugin != null) {
                item {
                    sherpaPlugin.SettingsBlocks(
                        rows = state.sherpaRows,
                        busy = state.sherpaBusy,
                        onUse = actions.onUse,
                        onDelete = actions.onDelete,
                        onRequestDelete = { deleteTarget = it },
                        onImportSherpa = onImportSherpa
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.custom_models), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 10.dp)
                )
            }
            if (state.custom.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.custom_empty),
                        fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                        lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            } else {
                items(state.custom.size) { i -> ModelRowCard(state.custom[i], actions) { deleteTarget = it } }
            }
            item {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddButton("+", stringResource(R.string.add_from_hf)) { showAdd = true }
                    AddButton("↥", stringResource(R.string.import_bin)) { picker.launch(arrayOf("*/*")) }
                }
            }
            if (state.statusMsg.isNotEmpty()) {
                item { StatusBox(state.statusMsg, state.statusError) }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(top = 22.dp), contentAlignment = Alignment.Center) {
                    TextButton(onClick = onSelfTest) {
                        Text(stringResource(R.string.run_self_test), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddUrlDialog(onDismiss = { showAdd = false }, onConfirm = { showAdd = false; onAddUrl(it) })
    }
    if (showLanguages) {
        LanguageDialog(onDismiss = { showLanguages = false }, onPick = { showLanguages = false; onPickLanguage(it) })
    }
    recording?.let { r ->
        RecordingDialog(r, onStop = onStopRecording, onCancel = onCancelRecording)
    }
    transcription?.let { t ->
        TranscriptionDialog(
            t, onCancel = onCancelTranscription, onClose = onDismissTranscription,
            onSave = onSaveTranscript, onFormat = onTranscriptFormat
        )
    }
    deleteTarget?.let { target ->
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_model_q)) },
            text = { Text(sherpaPlugin?.displayNameFor(context, target) ?: target) },
            confirmButton = { TextButton(onClick = { onDelete(target); deleteTarget = null }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun AppBar(onAbout: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, top = 10.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.5.dp)) {
            Bar(9.dp, cs.primary, 1f)
            Bar(18.dp, cs.primary, 1f)
            Bar(13.dp, cs.primary, 0.6f)
        }
        Spacer(Modifier.width(9.dp))
        Text("Scrib", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = cs.onBackground, letterSpacing = (-0.6).sp)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onAbout) {
            Text(stringResource(R.string.about), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
        }
    }
}

@Composable
private fun Bar(h: androidx.compose.ui.unit.Dp, color: Color, alpha: Float) {
    Box(Modifier.width(3.dp).height(h).clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = alpha)))
}

@Composable
private fun StatusCard(state: ScribUiState) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(cs.primary))
                Text(stringResource(R.string.active_model), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            if (state.activeName != null) {
                Text(state.activeName, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = cs.onSurface, letterSpacing = (-0.5).sp)
                Text(stringResource(R.string.ready_to_transcribe), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.primary, modifier = Modifier.padding(top = 6.dp))
            } else {
                Text(stringResource(R.string.no_active_model), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(stringResource(R.string.pick_model_hint), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
            }
            PrivacyLine(cs.onSurfaceVariant, topBorder = true)
        }
    }
}

@Composable
private fun NudgeCard(state: ScribUiState, actions: Actions) {
    val cs = MaterialTheme.colorScheme
    val base = state.standard.firstOrNull { it.recommended }
    Surface(color = cs.primaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp)) {
            Text(stringResource(R.string.setup_transcription), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = cs.onPrimaryContainer)
            Text(
                stringResource(R.string.nudge_body),
                fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            Surface(
                color = cs.primary, shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clickableRow(RoundedCornerShape(22.dp)) { base?.let { actions.onDownload(it.id) } }
            ) {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (base != null) stringResource(R.string.download_named_size, base.name, base.sizeMb) else stringResource(R.string.action_download),
                        fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = cs.onPrimary
                    )
                }
            }
            PrivacyLine(cs.onPrimaryContainer.copy(alpha = 0.9f), topBorder = false)
        }
    }
}

@Composable
private fun PrivacyLine(textColor: Color, topBorder: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(top = if (topBorder) 13.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("🔒", fontSize = 14.sp)
        Text(
            stringResource(if (topBorder) R.string.privacy_full else R.string.privacy_short),
            fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = textColor, lineHeight = 17.sp
        )
    }
}

@Composable
private fun StandardHeader(expanded: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chevron(expanded = expanded, color = cs.onSurfaceVariant)
            Text(stringResource(R.string.standard_models), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = cs.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(5, 8, 11, 14).forEach { Box(Modifier.width(3.dp).height(it.dp).clip(RoundedCornerShape(1.dp)).background(cs.outline)) }
            }
            Text(stringResource(R.string.size), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun Chevron(expanded: Boolean, color: Color) {
    // Next to all-caps text the ink sits above the line-box center, so the mark is nudged up
    // to meet the caps rather than the box.
    Canvas(Modifier.size(12.dp).offset(y = -1.dp)) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            if (expanded) {
                moveTo(size.width * 0.15f, size.height * 0.35f)
                lineTo(size.width * 0.5f, size.height * 0.7f)
                lineTo(size.width * 0.85f, size.height * 0.35f)
            } else {
                moveTo(size.width * 0.35f, size.height * 0.15f)
                lineTo(size.width * 0.7f, size.height * 0.5f)
                lineTo(size.width * 0.35f, size.height * 0.85f)
            }
        }
        drawPath(path, color, style = stroke)
    }
}

@Composable
private fun QualityBars(tier: Int, active: Boolean) {
    val cs = MaterialTheme.colorScheme
    val on = cs.primary
    val off = if (active) cs.onPrimaryContainer.copy(alpha = 0.25f) else cs.outlineVariant
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until 5) {
            val h = (5 + i * 3.2f).dp
            Box(Modifier.width(3.dp).height(h).clip(RoundedCornerShape(1.5.dp)).background(if (i < tier) on else off))
        }
    }
}

@Composable
internal fun ModelRowCard(row: ModelRow, actions: Actions, onRequestDelete: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val active = row.state == RowState.Active
    val bg = if (active) cs.primaryContainer else cs.surface
    val onBg = if (active) cs.onPrimaryContainer else cs.onSurface
    val onBgVar = if (active) cs.onPrimaryContainer.copy(alpha = 0.75f) else cs.onSurfaceVariant
    val sizePart = if (row.custom) stringResource(R.string.badge_custom) else stringResource(R.string.size_mb, row.sizeMb)
    Surface(
        color = bg,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(if (active) 1.5.dp else 1.dp, if (active) cs.primary else cs.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.name, fontSize = 16.5.sp, fontWeight = FontWeight.Bold, color = onBg, overflow = TextOverflow.Ellipsis)
                        if (active) Pill(stringResource(R.string.active_pill), cs.primary, cs.onPrimary)
                        if (row.recommended && !active) Pill(stringResource(R.string.recommended).uppercase(), cs.primary, cs.onPrimary)
                    }
                    Text("${row.badge} · $sizePart", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = onBgVar, modifier = Modifier.padding(top = 4.dp))
                    if (row.state == RowState.Failed) {
                        Text(stringResource(R.string.row_download_failed), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = cs.error, modifier = Modifier.padding(top = 5.dp))
                    }
                }
                if (row.state != RowState.Downloading) {
                    Spacer(Modifier.width(12.dp))
                    QualityBars(row.tier, active)
                }
            }
            Spacer(Modifier.height(12.dp))
            ActionZone(row, actions, onRequestDelete)
        }
    }
}

@Composable
private fun ActionZone(row: ModelRow, actions: Actions, onRequestDelete: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    when (row.state) {
        RowState.NotDownloaded -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Chip("↓ " + stringResource(R.string.action_download), cs.primaryContainer, cs.onPrimaryContainer) { actions.onDownload(row.id) }
        }
        RowState.Failed -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Chip(stringResource(R.string.action_retry), cs.errorContainer, cs.onErrorContainer) { actions.onDownload(row.id) }
        }
        RowState.Downloading -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.downloading_pct, row.progress), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = cs.primary, modifier = Modifier.padding(bottom = 6.dp))
                LinearProgressIndicator(
                    progress = { (row.progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = cs.primary, trackColor = cs.outlineVariant
                )
            }
            TextButton(onClick = { actions.onCancel(row.id) }) { Text(stringResource(R.string.action_cancel), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant) }
        }
        RowState.Installed -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onRequestDelete(row.id) }) { Text(stringResource(R.string.action_delete), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant) }
            Spacer(Modifier.width(6.dp))
            Chip(stringResource(R.string.action_use), cs.primaryContainer, cs.onPrimaryContainer) { actions.onUse(row.id) }
        }
        RowState.Active -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onRequestDelete(row.id) }) { Text(stringResource(R.string.action_delete), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer.copy(alpha = 0.85f)) }
        }
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color) {
    Surface(color = bg, shape = RoundedCornerShape(20.dp)) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp, color = fg, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun Chip(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Surface(color = bg, shape = RoundedCornerShape(20.dp), modifier = Modifier.clickableRow(RoundedCornerShape(20.dp), onClick)) {
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = fg, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

@Composable
private fun AddButton(glyph: String, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, cs.outline),
        modifier = Modifier.fillMaxWidth().clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(glyph, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = cs.primary)
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
        }
    }
}

@Composable
private fun StatusBox(msg: String, error: Boolean) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = if (error) cs.errorContainer else cs.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp)
    ) {
        Text(
            msg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp,
            color = if (error) cs.onErrorContainer else cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun RecordButton(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.primary, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(cs.onPrimary))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.record_and_transcribe), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onPrimary)
        }
    }
}

@Composable
private fun RecordingDialog(r: RecordingUi, onStop: () -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        // Neither a tap outside nor Back may drop a take by accident: the two buttons are the way out.
        onDismissRequest = {},
        title = { Text(stringResource(R.string.recording)) },
        text = {
            Column {
                Text(
                    elapsed(r.elapsedMs), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace, color = cs.onSurface
                )
                Spacer(Modifier.height(14.dp))
                LevelMeter(r.levels)
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.record_hint), fontSize = 12.5.sp, color = cs.onSurfaceVariant, lineHeight = 17.sp)
            }
        },
        confirmButton = { TextButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } }
    )
}

// The app's own bars, driven by the microphone: one per sampled level, newest on the right, and a
// square root so ordinary speech still moves them.
@Composable
private fun LevelMeter(levels: List<Float>) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(34.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        for (i in 0 until METER_BARS) {
            val level = levels.getOrElse(levels.size - METER_BARS + i) { 0f }
            val height = 3.dp + 29.dp * sqrt(level.coerceIn(0f, 1f))
            Box(
                Modifier.weight(1f).height(height).clip(RoundedCornerShape(2.dp))
                    .background(cs.primary.copy(alpha = if (level > 0f) 1f else 0.35f))
            )
        }
    }
}

private const val METER_BARS = 28

private fun elapsed(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

@Composable
private fun TranscribeFileButton(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.primaryContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
        ) {
            Text(stringResource(R.string.transcribe_audio_file), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
        }
    }
}

// Pre-fills the save dialog with the transcript name and, when the provider supports it, opens
// the picker at the source recording's location — SAF cannot write there without the user's pick.
private class CreateTranscriptDocument(private val initialUri: Uri?, mimeType: String) : ActivityResultContracts.CreateDocument(mimeType) {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            if (initialUri != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
}

// Document providers rewrite a file name whose extension disagrees with the type they were asked
// for — .srt would come back as .srt.txt. Asking for the type the platform itself maps the
// extension to keeps the name, and an unmapped one passes as an opaque file rather than as text.
private fun transcriptMimeType(format: TranscriptFormat): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(format.extension) ?: "application/octet-stream"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptionDialog(
    t: TranscribeUi,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onSave: (Uri) -> Unit,
    onFormat: (TranscriptFormat) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val baseName = t.fileName.substringBeforeLast('.').ifBlank { "transcript" }
    val saveContract = remember(t.sourceUri, t.format) {
        CreateTranscriptDocument(t.sourceUri, transcriptMimeType(t.format))
    }
    val saveLauncher = rememberLauncherForActivityResult(saveContract) { uri ->
        if (uri != null) onSave(uri)
    }
    // Copy and Share are offered the moment there is text, not only at the end: a long run's words
    // should never be trapped behind its remaining minutes. Save waits — a partial file on disk
    // looks finished later on.
    val finished = !t.running && t.error == null && t.text.isNotBlank()
    val hasText = t.error == null && t.text.isNotBlank()
    val timed = t.format != TranscriptFormat.TXT && t.segments.isNotEmpty()
    AlertDialog(
        onDismissRequest = { if (!t.running) onClose() },
        title = { Text(t.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                when {
                    t.error != null -> Text(t.error, fontSize = 13.sp, color = cs.error, lineHeight = 18.sp)
                    t.running && t.text.isEmpty() -> {
                        Text(stringResource(R.string.decoding_transcribing), fontSize = 13.sp, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        RunProgress(t)
                    }
                    else -> {
                        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                            Text(
                                t.formatted,
                                fontSize = if (timed) 12.sp else 14.sp,
                                lineHeight = if (timed) 17.sp else 20.sp,
                                fontFamily = if (timed) FontFamily.Monospace else null,
                                color = cs.onSurface
                            )
                        }
                        if (t.running) {
                            Spacer(Modifier.height(12.dp))
                            RunProgress(t)
                        }
                    }
                }
                if (hasText) {
                    Spacer(Modifier.height(12.dp))
                    if (finished && t.segments.isNotEmpty()) {
                        FormatPicker(t.format, onFormat)
                        Spacer(Modifier.height(2.dp))
                    }
                    FlowRow {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(t.formatted)) }) { Text(stringResource(R.string.action_copy)) }
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, t.formatted)
                                .putExtra(Intent.EXTRA_SUBJECT, baseName)
                            context.startActivity(Intent.createChooser(send, null))
                        }) { Text(stringResource(R.string.action_share)) }
                        if (finished) {
                            TextButton(onClick = { saveLauncher.launch("$baseName.${t.format.extension}") }) {
                                Text(stringResource(R.string.action_save_ext, t.format.extension))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (t.running) TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            else TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
        }
    )
}

// Whisper only starts reporting once decoding is done and the model is loaded, so the bar spins
// until then rather than sitting at a misleading zero. The line underneath doubles as the promise
// that walking away is safe — the run keeps going in the notification.
@Composable
private fun RunProgress(t: TranscribeUi) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        if (t.percent < 0) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { t.percent.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (t.etaMs != null) {
                    stringResource(R.string.transcribing_pct_eta, t.percent, remaining(context, t.etaMs))
                } else {
                    stringResource(R.string.transcribing_pct, t.percent)
                },
                fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = cs.primary
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.transcribe_background_hint),
            fontSize = 11.5.sp, color = cs.onSurfaceVariant, lineHeight = 16.sp
        )
    }
}

// Plain text, or one of the timestamped formats. The pick drives the view as well as the export,
// so what is copied or saved is what was on screen.
@Composable
private fun FormatPicker(selected: TranscriptFormat, onSelect: (TranscriptFormat) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TranscriptFormat.entries.forEach { format ->
            val on = format == selected
            Surface(
                color = if (on) cs.primary else cs.surfaceContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.clickableRow(RoundedCornerShape(20.dp)) { onSelect(format) }
            ) {
                Text(
                    format.name, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp,
                    color = if (on) cs.onPrimary else cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun LanguageEntry(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Text("🌐", fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.lang_entry_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(stringResource(R.string.lang_entry_sub), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.width(16.dp))
            Text("›", fontSize = 22.sp, color = cs.onSurfaceVariant)
        }
    }
}

// The voice keyboard is useless until it is switched on in the system's own keyboard list, and
// nothing in the app can do that on the user's behalf.
@Composable
private fun KeyboardEntry() {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickableRow(RoundedCornerShape(16.dp)) {
            runCatching {
                context.startActivity(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        }
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Text("⌨", fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ime_entry_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(stringResource(R.string.ime_entry_sub), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(16.dp))
            Text("›", fontSize = 22.sp, color = cs.onSurfaceVariant)
        }
    }
}

// Finding the speech first needs a detector of its own, so the switch downloads it once and only
// then takes effect. Off by default: it is a second pass over the audio and a second copy of the
// speech in memory, which a short voice message has nothing to gain from.
@Composable
private fun SkipSilenceEntry(state: ScribUiState, onToggle: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val busy = state.vadProgress >= 0
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .clickableRow(RoundedCornerShape(16.dp)) { if (!busy) onToggle(!state.skipSilence) }
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Text("🤫", fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.vad_entry_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(
                    if (busy) stringResource(R.string.vad_entry_downloading, state.vadProgress)
                    else stringResource(R.string.vad_entry_sub),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = state.skipSilence, enabled = !busy, onCheckedChange = { onToggle(it) })
        }
    }
}
@Composable
private fun DictionaryEntry(enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Text("\uD83D\uDCDD", fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dict_entry_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(
                    stringResource(if (enabled) R.string.dict_entry_on else R.string.dict_entry_sub),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp
                )
            }
            Spacer(Modifier.width(16.dp))
            Text("›", fontSize = 22.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun LanguageDialog(onDismiss: () -> Unit, onPick: (LanguageOption) -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.your_language)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.lang_dialog_hint),
                    fontSize = 12.5.sp, color = cs.onSurfaceVariant, lineHeight = 17.sp
                )
                Spacer(Modifier.height(6.dp))
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    LanguageCatalog.LANGUAGES.forEach { lang ->
                        Column(
                            Modifier.fillMaxWidth().clickableRow(RectangleShape) { onPick(lang) }.padding(vertical = 10.dp)
                        ) {
                            Text(stringResource(lang.nameRes), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                            Text(stringResource(lang.noteRes), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

@Composable
private fun AddUrlDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hf_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.hf_dialog_body), fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = false, placeholder = { Text(stringResource(R.string.hf_placeholder)) })
            }
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(stringResource(R.string.action_download)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

private fun Modifier.clickableRow(shape: Shape, onClick: () -> Unit): Modifier =
    this.clip(shape).clickable(onClick = onClick)

private fun openDictionary(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent().setClassName(
                context.packageName,
                "org.scrib.transcriber.DictionaryActivity"
            )
        )
    }
}

internal fun queryDisplayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
    }
}.getOrNull()
