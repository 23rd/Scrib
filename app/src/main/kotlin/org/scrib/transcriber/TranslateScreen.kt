package org.scrib.transcriber

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// the Translate tab
@Composable
internal fun TranslateTab(history: List<TranscriptHistory.Entry>, transcription: TranscribeUi?, onOpenSettings: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (!BuildConfig.LOCAL_MODELS) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            InfoCard(stringResource(R.string.translate_remote_unavailable))
        }
        return
    }
    val vm: TranslationViewModel = viewModel()
    val ui by vm.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var source by rememberSaveable { mutableStateOf("") }
    var showPick by remember { mutableStateOf(false) }
    var pickSource by remember { mutableStateOf(false) }
    var pickTarget by remember { mutableStateOf(false) }
    var openEntry by remember { mutableStateOf<TranslationHistory.Entry?>(null) }
    var showClearHistory by rememberSaveable { mutableStateOf(false) }
    val hasModel = ui.activeName != null
    LaunchedEffect(Unit) { vm.refresh() }

    val latest = transcription?.takeIf { !it.running && it.error == null && it.text.isNotBlank() }?.text
        ?: history.firstOrNull()?.text
    val run = ui.run
    val running = run?.running == true

    LazyColumn(
        Modifier.fillMaxSize().testTag("scrib/translate"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        item {
            if (!hasModel) {
                NudgeCard(
                    title = stringResource(R.string.translate_nudge_title),
                    body = stringResource(R.string.translate_nudge_body),
                    action = stringResource(R.string.translate_nudge_action),
                    onAction = onOpenSettings
                )
            }
        }
        item {
            SourceField(
                value = source,
                onValueChange = { source = it },
                hint = stringResource(R.string.translate_source_hint),
                enabled = !running,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("scrib/translate/source")
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (latest != null) {
                    TextButton(onClick = { source = latest }, enabled = !running) {
                        Text(stringResource(R.string.translate_use_latest), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (history.isNotEmpty()) {
                    TextButton(onClick = { showPick = true }, enabled = !running) {
                        Text(stringResource(R.string.translate_pick_transcript), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LanguageButton(
                    label = stringResource(R.string.translate_from),
                    value = if (ui.sourceLanguage.isEmpty()) stringResource(R.string.lang_auto) else TranslationLanguages.displayName(ui.sourceLanguage),
                    enabled = !running,
                    modifier = Modifier.weight(1f)
                ) { pickSource = true }
                IconButton(onClick = vm::swapLanguages, enabled = !running && ui.sourceLanguage.isNotEmpty()) {
                    Text("⇄", fontSize = 20.sp, color = if (ui.sourceLanguage.isEmpty()) cs.outline else cs.primary)
                }
                LanguageButton(
                    label = stringResource(R.string.translate_to),
                    value = TranslationLanguages.displayName(ui.targetLanguage),
                    enabled = !running,
                    modifier = Modifier.weight(1f)
                ) { pickTarget = true }
            }
            Spacer(Modifier.height(12.dp))
            if (running) {
                OutlinedButton(onClick = vm::cancelTranslate, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = { vm.translate(source) },
                    enabled = hasModel && source.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("scrib/translate/go")
                ) {
                    Text(stringResource(R.string.translate_action), fontWeight = FontWeight.Bold)
                }
            }
            if (ui.statusMsg.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(ui.statusMsg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = if (ui.statusError) cs.error else cs.onSurfaceVariant)
            }
        }
        if (run != null) {
            item {
                ResultCard(run, onCopy = { clipboard.setText(AnnotatedString(run.text)) }, onShare = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, run.text)
                    context.startActivity(Intent.createChooser(send, null))
                }, onDismiss = vm::dismissRun)
            }
        }
        if (ui.history.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.translate_history_section),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
                    )
                    TextButton(onClick = { showClearHistory = true }) {
                        Text(stringResource(R.string.history_clear_all), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.error)
                    }
                }
            }
            items(ui.history, key = { "h-" + it.id }) { entry ->
                TranslationRowCard(entry, onOpen = { openEntry = entry }, onDelete = { vm.deleteHistoryEntry(entry.id) })
            }
        }
    }

    openEntry?.let { entry ->
        TranslationEntryDialog(entry, onClose = { openEntry = null }, onReuse = { source = entry.source; openEntry = null })
    }
    if (showClearHistory) {
        AlertDialog(
            onDismissRequest = { showClearHistory = false },
            title = { Text(stringResource(R.string.translate_history_clear_title)) },
            text = { Text(stringResource(R.string.translate_history_clear_message)) },
            confirmButton = {
                TextButton(onClick = { showClearHistory = false; vm.clearHistory() }) {
                    Text(stringResource(R.string.history_clear_all), color = cs.error)
                }
            },
            dismissButton = { TextButton(onClick = { showClearHistory = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    if (showPick) {
        TranscriptPickDialog(history, onDismiss = { showPick = false }) { showPick = false; source = it.text }
    }
    if (pickSource) {
        LanguagePickDialog(
            title = stringResource(R.string.translate_from),
            codes = listOf("") + TranslationLanguages.CODES,
            selected = ui.sourceLanguage,
            onDismiss = { pickSource = false }
        ) { pickSource = false; vm.setSourceLanguage(it) }
    }
    if (pickTarget) {
        LanguagePickDialog(
            title = stringResource(R.string.translate_to),
            codes = TranslationLanguages.CODES,
            selected = ui.targetLanguage,
            onDismiss = { pickTarget = false }
        ) { pickTarget = false; vm.setTargetLanguage(it) }
    }
}

// source text box in the apps own card shape
@Composable
private fun SourceField(value: String, onValueChange: (String) -> Unit, hint: String, enabled: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = modifier
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 112.dp, max = 320.dp).padding(horizontal = 16.dp, vertical = 14.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            )
            if (value.isEmpty()) {
                Text(hint, fontSize = 15.sp, lineHeight = 21.sp, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, fontSize = 13.5.sp, lineHeight = 19.sp, color = cs.onSurface, modifier = Modifier.padding(18.dp))
    }
}

@Composable
private fun NudgeCard(title: String, body: String, action: String, onAction: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.primaryContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = cs.onPrimaryContainer)
            Spacer(Modifier.height(6.dp))
            Text(body, fontSize = 13.5.sp, lineHeight = 19.sp, color = cs.onPrimaryContainer.copy(alpha = 0.9f))
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAction, modifier = Modifier.testTag("scrib/translate/open_settings")) {
                Text(action, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// translation models section on the Settings tab
@Composable
internal fun TranslationModelsSection(expanded: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val vm: TranslationViewModel = viewModel()
    val ui by vm.state.collectAsState()
    val context = LocalContext.current
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri, queryDisplayName(context, uri))
    }
    val actions = Actions(
        onDownload = vm::download, onCancel = vm::cancel, onUse = vm::activate, onDelete = vm::delete,
        onAddUrl = vm::addCustom, onImport = vm::importModel, onSelfTest = {}, onTogglePinned = {}
    )
    LaunchedEffect(Unit) { vm.refresh() }
    Column(Modifier.fillMaxWidth().testTag("scrib/settings/translation")) {
        SectionHeader(title = stringResource(R.string.translate_models), expanded = expanded, onToggle = onToggle, top = 8.dp)
        if (expanded) {
            Text(
                stringResource(R.string.translate_models_hint),
                fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 17.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
            )
            ui.rows.forEach { row ->
                ModelRowCard(row, actions, onRequestDelete = { deleteTarget = it }, showPin = false)
            }
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AddButton("+", stringResource(R.string.add_from_hf)) { showAdd = true }
                AddButton("↥", stringResource(R.string.import_bin)) { importPicker.launch(arrayOf("*/*")) }
            }
            if (ui.statusMsg.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(ui.statusMsg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = if (ui.statusError) cs.error else cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_model_q)) },
            text = { Text(target.removeSuffix(".gguf")) },
            confirmButton = { TextButton(onClick = { vm.delete(target); deleteTarget = null }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    if (showAdd) {
        AddUrlDialog(onDismiss = { showAdd = false }, onConfirm = { showAdd = false; vm.addCustom(it) }, bodyRes = R.string.translate_hf_body)
    }
}

// translation model card on Home, mirrors the speech one
@Composable
internal fun TranslationStatusCard(onOpenSettings: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val vm: TranslationViewModel = viewModel()
    val ui by vm.state.collectAsState()
    var showSwitcher by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refresh() }
    val installed = ui.rows.filter { it.state == RowState.Installed || it.state == RowState.Active }
    val switchable = installed.size > 1
    Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            val activeName = ui.activeName
            if (activeName != null) {
                val rowModifier = if (switchable) {
                    Modifier.fillMaxWidth().testTag("scrib/home/translation_model").clip(RoundedCornerShape(12.dp)).clickable { showSwitcher = true }
                } else {
                    Modifier.fillMaxWidth()
                }
                Row(rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(cs.primary))
                    Text(stringResource(R.string.translate_active_model), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp, color = cs.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(activeName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (switchable) {
                        Text("›", fontSize = 18.sp, color = cs.onSurfaceVariant)
                    }
                }
            } else {
                Text(stringResource(R.string.translate_no_model), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(stringResource(R.string.translate_no_model_hint), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                TextButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 2.dp)) {
                    Text(stringResource(R.string.nav_settings), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 13.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🔒", fontSize = 14.sp)
                Text(stringResource(R.string.translate_privacy), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 17.sp)
            }
        }
    }
    if (showSwitcher) {
        ModelSwitchDialog(installed, onDismiss = { showSwitcher = false }, onUse = { showSwitcher = false; vm.activate(it) })
    }
}

@Composable
private fun LanguageButton(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, cs.outline),
        modifier = modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = cs.onSurfaceVariant)
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ResultCard(run: TranslateRun, onCopy: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp).testTag("scrib/translate/result")
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.translate_result), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (run.running) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (run.loading) R.string.translate_loading_model else R.string.translate_running), fontSize = 12.sp, color = cs.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                run.error != null -> Text(run.error, fontSize = 13.sp, color = cs.error, lineHeight = 18.sp)
                run.text.isEmpty() && !run.running -> Text("—", color = cs.onSurfaceVariant)
                else -> Text(run.text, fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurface)
            }
            if (!run.running) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val seconds = run.elapsedMs / 1000.0
                    val rate = if (seconds > 0) run.tokens / seconds else 0.0
                    Text(
                        stringResource(
                            R.string.translate_stats,
                            elapsedOf(run.elapsedMs),
                            run.tokens,
                            String.format(Locale.ROOT, "%.1f", rate)
                        ) + (run.modelName?.let { " · $it" } ?: ""),
                        fontSize = 11.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f)
                    )
                    if (run.text.isNotEmpty()) {
                        IconButton(onClick = onCopy) { Icon(painterResource(R.drawable.ic_action_copy), stringResource(R.string.action_copy), modifier = Modifier.size(20.dp)) }
                        IconButton(onClick = onShare) { Icon(painterResource(R.drawable.ic_action_share), stringResource(R.string.action_share), modifier = Modifier.size(20.dp)) }
                    }
                    IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_close), modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TranscriptPickDialog(history: List<TranscriptHistory.Entry>, onDismiss: () -> Unit, onPick: (TranscriptHistory.Entry) -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translate_pick_transcript)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(history, key = { it.id }) { entry ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(entry) }
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Text(entry.fileName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.text.trim(), fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun LanguagePickDialog(title: String, codes: List<String>, selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(codes, key = { it }) { code ->
                    val on = code == selected
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(code) }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (code.isEmpty()) stringResource(R.string.lang_auto) else TranslationLanguages.displayName(code),
                            fontSize = 15.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (on) cs.primary else cs.onSurface, modifier = Modifier.weight(1f)
                        )
                        if (on) Icon(painterResource(R.drawable.ic_check), null, tint = cs.primary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun languagePair(entry: TranslationHistory.Entry): String {
    val from = if (entry.sourceLanguage.isEmpty()) stringResource(R.string.lang_auto) else TranslationLanguages.displayName(entry.sourceLanguage)
    val to = if (entry.targetLanguage.isEmpty()) "?" else TranslationLanguages.displayName(entry.targetLanguage)
    return "$from → $to"
}

private fun historyStamp(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

@Composable
private fun TranslationRowCard(entry: TranslationHistory.Entry, onOpen: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpen)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        languagePair(entry),
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(historyStamp(entry.savedAtMs), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.action_delete), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
                }
            }
            Text(
                entry.text,
                fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun TranslationEntryDialog(entry: TranslationHistory.Entry, onClose: () -> Unit, onReuse: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(languagePair(entry), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.translate_result), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(entry.text, fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurface)
                    if (entry.source.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Text(stringResource(R.string.translate_history_original), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(entry.source, fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant)
                    }
                }
                entry.modelName?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("${historyStamp(entry.savedAtMs)} · $it", fontSize = 11.5.sp, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(entry.text)) }) {
                        Icon(painterResource(R.drawable.ic_action_copy), stringResource(R.string.action_copy))
                    }
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, entry.text)
                        context.startActivity(Intent.createChooser(send, null))
                    }) {
                        Icon(painterResource(R.drawable.ic_action_share), stringResource(R.string.action_share))
                    }
                    if (entry.source.isNotBlank()) {
                        IconButton(onClick = onReuse) {
                            Icon(painterResource(R.drawable.ic_tab_translate), stringResource(R.string.action_translate))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_close)) }
        }
    )
}
