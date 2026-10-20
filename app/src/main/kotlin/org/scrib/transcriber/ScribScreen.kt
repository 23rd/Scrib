package org.scrib.transcriber

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

internal data class Actions(
    val onDownload: (String) -> Unit,
    val onCancel: (String) -> Unit,
    val onUse: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onAddUrl: (String) -> Unit,
    val onImport: (Uri, String?) -> Unit,
    val onSelfTest: () -> Unit,
    val onTogglePinned: (String) -> Unit
)

private enum class ScribTab(val labelRes: Int, val icon: Int) {
    Home(R.string.nav_home, R.drawable.ic_tab_home),
    Record(R.string.nav_record, R.drawable.ic_tab_mic),
    File(R.string.nav_transcribe, R.drawable.ic_tab_graphic_eq),
    Translate(R.string.nav_translate, R.drawable.ic_tab_translate),
    Keyboard(R.string.nav_keyboard, R.drawable.ic_tab_keyboard),
    Settings(R.string.nav_settings, R.drawable.ic_tab_settings)
}

@Composable
fun ScribScreen(
    state: ScribUiState,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onUse: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAddUrl: (String) -> Unit,
    onImport: (Uri, String?) -> Unit,
    onImportExternal: (Uri, String?) -> Unit,
    onSelfTest: () -> Unit,
    onBenchmarkFile: (Uri, String?) -> Unit,
    onCancelBenchmark: () -> Unit,
    onPickLanguage: (LanguageOption?) -> Unit,
    onSkipSilence: (Boolean) -> Unit,
    onLiveStats: (Boolean) -> Unit,
    onKeyboardStats: (Boolean) -> Unit,
    onConfigureKeyboardLayouts: (Boolean, List<String>, List<String>) -> Unit,
    onImportSherpa: (String, String, List<String>?, Map<String, Uri>, (Throwable?) -> Unit) -> Unit,
    sherpaPlugin: SherpaPlugin?,
    endpointPlugin: EndpointPlugin?,
    onEndpointChanged: () -> Unit,
    recording: RecordingUi?,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    transcription: TranscribeUi?,
    onTranscribeFiles: (List<Pair<Uri, String?>>) -> Unit,
    onCancelTranscription: () -> Unit,
    onDismissTranscription: () -> Unit,
    onSaveTranscript: (Uri) -> Unit,
    onTranscriptFormat: (TranscriptFormat) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onSaveHistoryFile: (String, Uri) -> Unit,
    onTogglePinned: (String) -> Unit,
    benchmarkRuns: List<BenchmarkRun>,
    onAbout: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val actions = Actions(onDownload, onCancel, onUse, onDelete, onAddUrl, onImport, onSelfTest, onTogglePinned)
    var tab by rememberSaveable { mutableStateOf(ScribTab.Home) }
    var showAdd by remember { mutableStateOf(false) }
    var showLanguages by remember { mutableStateOf(false) }
    var showSwitcher by rememberSaveable { mutableStateOf(false) }
    val switchableModels = (state.standard + state.custom + state.sherpaRows)
        .filter { it.state == RowState.Installed || it.state == RowState.Active }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var showBenchmarks by rememberSaveable { mutableStateOf(false) }
    var openHistoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var showChangelog by rememberSaveable { mutableStateOf(false) }
    var showWhatsNew by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    var modelsExpanded by remember { mutableStateOf(SectionState.standardModels(context)) }
    var customExpanded by remember { mutableStateOf(SectionState.customModels(context)) }
    var translationExpanded by remember { mutableStateOf(SectionState.translationModels(context)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri, queryDisplayName(context, uri))
    }
    val externalPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportExternal(uri, queryDisplayName(context, uri))
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            onTranscribeFiles(uris.map { it to queryDisplayName(context, it) })
        }
    }
    val benchmarkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onBenchmarkFile(uri, queryDisplayName(context, uri))
        }
    }
    // Already granted, and the contract answers without showing anything; a refusal is reported by
    // the recording attempt itself.
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onStartRecording()
    }
    // Asked for at the first run, where the shade is about to become the only place the progress
    // shows. A refusal costs the notification, not the run.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val started = transcription != null && !state.benchmarkBatch.running
    LaunchedEffect(started) {
        if (started && android.os.Build.VERSION.SDK_INT >= 33) {
            notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        if (Changelog.shouldAnnounce(context)) {
            showWhatsNew = true
        }
    }

    LaunchedEffect(AppNav.pendingTab) {
        AppNav.takePendingTab()?.let { requested ->
            ScribTab.entries.firstOrNull { it.name.equals(requested, ignoreCase = true) }?.let { tab = it }
        }
    }

    BackHandler(tab != ScribTab.Home) { tab = ScribTab.Home }

    Column(Modifier.fillMaxSize().background(cs.background)) {
        Column(Modifier.weight(1f).statusBarsPadding()) {
            AppBar(onAbout, showAbout = tab == ScribTab.Home || tab == ScribTab.Settings)
            when (tab) {
                ScribTab.Home -> HomeTab(
                    state,
                    actions,
                    onOpenTab = { tab = it },
                    onRecord = { microphone.launch(android.Manifest.permission.RECORD_AUDIO) },
                    onPickFile = { audioPicker.launch(arrayOf("audio/*", "video/*")) },
                    onSwitchModel = if (switchableModels.size > 1) {
                        { showSwitcher = true }
                    } else {
                        null
                    }
                )
                ScribTab.Record -> RecordTab(
                    state,
                    actions,
                    onOpenTab = { tab = it },
                    onRecord = { microphone.launch(android.Manifest.permission.RECORD_AUDIO) },
                    onOpenEntry = { openHistoryId = it.id },
                    onDeleteEntry = onDeleteHistory,
                    onClearHistory = onClearHistory
                )
                ScribTab.File -> FileTab(state, actions, onOpenTab = { tab = it }) { audioPicker.launch(arrayOf("audio/*", "video/*")) }
                ScribTab.Translate -> TranslateTab(state.history, transcription, onOpenSettings = { tab = ScribTab.Settings })
                ScribTab.Keyboard -> KeyboardTab(state.keyboardLayouts, onConfigureKeyboardLayouts)
                ScribTab.Settings -> SettingsTab(
                    state = state,
                    actions = actions,
                    modelsExpanded = modelsExpanded,
                    customExpanded = customExpanded,
                    onToggleStandard = {
                        val next = !modelsExpanded
                        modelsExpanded = next
                        SectionState.setStandardModels(context, next)
                    },
                    onToggleCustom = {
                        val next = !customExpanded
                        customExpanded = next
                        SectionState.setCustomModels(context, next)
                    },
                    translationExpanded = translationExpanded,
                    onToggleTranslation = {
                        val next = !translationExpanded
                        translationExpanded = next
                        SectionState.setTranslationModels(context, next)
                    },
                    sherpaPlugin = sherpaPlugin,
                    endpointPlugin = endpointPlugin,
                    onEndpointChanged = onEndpointChanged,
                    onImportSherpa = onImportSherpa,
                    onPickLanguage = { showLanguages = true },
                    onAddCustom = { showAdd = true },
                    onImportBin = { picker.launch(arrayOf("*/*")) },
                    onImportExternal = { externalPicker.launch(arrayOf("*/*")) },
                    onRequestDelete = { deleteTarget = it },
                    onSkipSilence = onSkipSilence,
                    onLiveStats = onLiveStats,
                    onKeyboardStats = onKeyboardStats,
                    onOpenDictionary = { openDictionary(context) },
                    onOpenBenchmarks = {
                        if (BenchmarkStore.isFullscreen(context)) {
                            context.startActivity(Intent(context, BenchmarkHistoryActivity::class.java))
                        } else {
                            showBenchmarks = true
                        }
                    },
                    onOpenChangelog = { showChangelog = true },
                    onSelfTest = onSelfTest,
                    onBenchmarkFile = { benchmarkPicker.launch(arrayOf("audio/*", "video/*")) },
                    onCancelBenchmark = onCancelBenchmark,
                    transcription = transcription,
                    recording = recording
                )
            }
        }
        BottomBar(tab) { tab = it }
    }

    if (showAdd) {
        AddUrlDialog(onDismiss = { showAdd = false }, onConfirm = { showAdd = false; onAddUrl(it) })
    }
    if (showLanguages) {
        LanguageDialog(onDismiss = { showLanguages = false }, onPick = { showLanguages = false; onPickLanguage(it) })
    }
    if (showSwitcher) {
        ModelSwitchDialog(
            switchableModels,
            onDismiss = { showSwitcher = false },
            onUse = { showSwitcher = false; actions.onUse(it) }
        )
    }
    if (showBenchmarks) {
        BenchmarkHistoryDialog(
            benchmarkRuns,
            onDismiss = { showBenchmarks = false },
            onOpenFullscreen = {
                showBenchmarks = false
                BenchmarkStore.setFullscreen(context, true)
                context.startActivity(Intent(context, BenchmarkHistoryActivity::class.java))
            }
        )
    }
    recording?.let { r ->
        RecordingDialog(r, remoteHost = state.remoteHost, onStop = onStopRecording, onCancel = onCancelRecording)
    }
    if (transcription != null && !state.benchmarkBatch.running) {
        transcription?.let { t ->
        TranscriptionDialog(
            t, showStats = state.liveStats, onCancel = onCancelTranscription, onClose = onDismissTranscription,
            onSave = onSaveTranscript, onFormat = onTranscriptFormat
        )
        }
    }
    openHistoryId?.let { id ->
        state.history.firstOrNull { it.id == id }?.let { entry ->
            HistoryEntryDialog(
                entry,
                onClose = { openHistoryId = null },
                onSaveFile = { uri -> onSaveHistoryFile(id, uri) }
            )
        }
    }
    deleteTarget?.let { target ->
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_model_q)) },
            text = { Text(sherpaPlugin?.displayNameFor(context, target) ?: target.removePrefix(ModelManager.EXTERNAL_PREFIX)) },
            confirmButton = { TextButton(onClick = { onDelete(target); deleteTarget = null }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    if (showChangelog) {
        ChangelogDialog(remember { Changelog.load(context) }, onDismiss = { showChangelog = false })
    }
    if (showWhatsNew) {
        val release = remember { Changelog.load(context).firstOrNull() }
        if (release != null) {
            WhatsNewDialog(release, onClose = {
                showWhatsNew = false
                Changelog.markAnnounced(context)
            })
        }
    }
}

@Composable
private fun HomeTab(
    state: ScribUiState,
    actions: Actions,
    onOpenTab: (ScribTab) -> Unit,
    onRecord: () -> Unit,
    onPickFile: () -> Unit,
    onSwitchModel: (() -> Unit)?
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        item {
            if (state.firstRun) {
                NudgeCard(state, actions, onOpenSettings = { onOpenTab(ScribTab.Settings) })
            } else {
                StatusCard(
                    state,
                    onOpenSettings = { onOpenTab(ScribTab.Settings) },
                    onSwitchModel = onSwitchModel
                )
            }
        }
        if (!state.firstRun) {
            if (BuildConfig.LOCAL_MODELS) {
                item { TranslationStatusCard(onOpenSettings = { onOpenTab(ScribTab.Settings) }) }
            }
            item {
                ServiceCard(
                    glyph = "🎙",
                    title = stringResource(R.string.record_and_transcribe),
                    tag = "scrib/home/record"
                ) { onRecord() }
            }
            item {
                ServiceCard(
                    glyph = "📁",
                    title = stringResource(R.string.transcribe_audio_file),
                    tag = "scrib/home/pick_file"
                ) { onPickFile() }
            }
            val quick = (state.standard + state.custom + state.sherpaRows).filter { it.id in state.pinnedIds }
            if (quick.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.quick_access_title),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 8.dp)
                    )
                }
                items(quick, key = { it.id }) { row ->
                    QuickModelCard(row, actions)
                }
            }
        }
    }
}

@Composable
private fun QuickModelCard(row: ModelRow, actions: Actions) {
    val cs = MaterialTheme.colorScheme
    val active = row.state == RowState.Active
    Surface(
        color = if (active) cs.primaryContainer else cs.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (active) 1.5.dp else 1.dp, if (active) cs.primary else Color.Transparent),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .clickableRow(RoundedCornerShape(16.dp)) { actions.onUse(row.id) }
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    row.name,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = if (active) cs.onPrimaryContainer else cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    row.badge,
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = if (active) cs.onPrimaryContainer.copy(alpha = 0.75f) else cs.onSurfaceVariant
                )
            }
            IconButton(onClick = { actions.onTogglePinned(row.id) }) {
                Icon(
                    painterResource(R.drawable.ic_star_filled),
                    contentDescription = stringResource(R.string.quick_access_remove),
                    tint = if (active) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun RecordTab(
    state: ScribUiState,
    actions: Actions,
    onOpenTab: (ScribTab) -> Unit,
    onRecord: () -> Unit,
    onOpenEntry: (TranscriptHistory.Entry) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    if (state.firstRun) {
        SingleCardColumn { NudgeCard(state, actions, onOpenSettings = { onOpenTab(ScribTab.Settings) }) }
        return
    }
    val cs = MaterialTheme.colorScheme
    var showClearConfirmation by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        item { Spacer(Modifier.height(24.dp)) }
        item { RecordButton(onRecord) }
        item {
            Text(
                recordHint(state.remoteHost),
                fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                lineHeight = 17.sp
            )
        }
        if (state.history.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.history_section_title),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
                    )
                    TextButton(onClick = { showClearConfirmation = true }) {
                        Text(
                            stringResource(R.string.history_clear_all),
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.error
                        )
                    }
                }
            }
            items(state.history, key = { it.id }) { entry ->
                HistoryRowCard(entry, onOpen = { onOpenEntry(entry) }, onDelete = { onDeleteEntry(entry.id) })
            }
        }
    }
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_message)) },
            confirmButton = {
                TextButton(onClick = { showClearConfirmation = false; onClearHistory() }) {
                    Text(stringResource(R.string.history_clear_all), color = cs.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun HistoryRowCard(entry: TranscriptHistory.Entry, onOpen: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickableRow(RoundedCornerShape(16.dp), onOpen)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.fileName,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        historyTimestamp(entry.savedAtMs),
                        fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant
                    )
                }
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(R.string.action_delete),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant
                    )
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

private fun historyTimestamp(timestampMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestampMs))

@Composable
private fun FileTab(state: ScribUiState, actions: Actions, onOpenTab: (ScribTab) -> Unit, onPick: () -> Unit) {
    if (state.firstRun) {
        SingleCardColumn { NudgeCard(state, actions, onOpenSettings = { onOpenTab(ScribTab.Settings) }) }
        return
    }
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TranscribeFileButton(onPick)
        Text(
            stringResource(R.string.transcribe_background_hint),
            fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
            lineHeight = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyboardTab(
    settings: KeyboardLayoutSettings,
    onConfigureKeyboardLayouts: (Boolean, List<String>, List<String>) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var newKey by remember { mutableStateOf("") }
    var editingKey by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    val selectedKeys = settings.options.filter { it.selected }.map { it.key }
    val customKeys = settings.customKeys
    fun addNewKey() {
        val value = newKey.replace("\r", "").replace("\n", "")
        if (value.isBlank() || value in customKeys || customKeys.size >= KeyboardLayouts.MAX_CUSTOM_KEYS) {
            return
        }
        onConfigureKeyboardLayouts(
            settings.enabled,
            selectedKeys,
            listOf(value.take(KeyboardLayouts.MAX_CUSTOM_KEY_LENGTH)) + customKeys
        )
        newKey = ""
    }
    fun renameCustomKey(from: String, to: String) {
        val value = to.replace("\r", "").replace("\n", "").take(KeyboardLayouts.MAX_CUSTOM_KEY_LENGTH)
        if (value.isBlank() || (value in customKeys && value != from)) {
            return
        }
        onConfigureKeyboardLayouts(settings.enabled, selectedKeys, customKeys.map { if (it == from) value else it })
        editingKey = null
    }
    fun moveCustomKey(key: String, delta: Int) {
        val index = customKeys.indexOf(key)
        val to = index + delta
        if (index < 0 || to !in customKeys.indices) {
            return
        }
        onConfigureKeyboardLayouts(
            settings.enabled,
            selectedKeys,
            customKeys.toMutableList().also { Collections.swap(it, index, to) }
        )
    }
    val canAdd = newKey.isNotBlank() && newKey !in customKeys &&
        customKeys.size < KeyboardLayouts.MAX_CUSTOM_KEYS
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ime_layout_buttons_show),
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface
                )
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { onConfigureKeyboardLayouts(it, selectedKeys, customKeys) }
                )
            }
        }
        item {
            Column {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.ime_layout_buttons_hint),
                    fontSize = 12.5.sp,
                    color = cs.onSurfaceVariant,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(10.dp))
            }
        }
        if (settings.options.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.ime_layout_buttons_empty),
                    fontSize = 13.sp,
                    color = cs.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        } else {
            items(settings.options, key = { it.key }) { option ->
                val selected = selectedKeys.contains(option.key)
                val detail = buildString {
                    append(option.imeLabel)
                    if (option.languageTag.isNotBlank()) {
                        append(" · ")
                        append(option.languageTag)
                    }
                }
                Surface(
                    color = if (selected) cs.primaryContainer else cs.surface,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .selectable(
                            selected = selected,
                            role = Role.Checkbox,
                            onClick = {
                                onConfigureKeyboardLayouts(
                                    settings.enabled,
                                    if (selected) selectedKeys - option.key else selectedKeys + option.key,
                                    customKeys
                                )
                            }
                        )
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (selected) "✓" else "",
                            modifier = Modifier.width(22.dp),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = cs.primary
                        )
                        Text(
                            option.shortLabel,
                            modifier = Modifier.width(48.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (selected) cs.primary else cs.onSurfaceVariant
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                option.label,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = cs.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                detail,
                                fontSize = 11.5.sp,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (selected) {
                            Text(
                                (selectedKeys.indexOf(option.key) + 1).toString(),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = cs.primary
                            )
                        }
                    }
                }
            }
            item {
                Box(
                    Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    TextButton(
                        onClick = { onConfigureKeyboardLayouts(settings.enabled, emptyList(), customKeys) },
                        enabled = selectedKeys.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.ime_layout_buttons_clear), fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.ime_layout_buttons_open), fontSize = 12.sp)
            }
        }
        item {
            Column {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.ime_custom_keys_title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.ime_custom_keys_hint),
                    fontSize = 12.5.sp,
                    color = cs.onSurfaceVariant,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(10.dp))
            }
        }
        item {
            AddKeyRow(
                value = newKey,
                onValueChange = {
                    newKey = it.replace("\r", "").replace("\n", "")
                        .take(KeyboardLayouts.MAX_CUSTOM_KEY_LENGTH)
                },
                enabled = canAdd,
                onAdd = { addNewKey() }
            )
        }
        if (customKeys.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.ime_custom_keys_empty),
                    fontSize = 13.sp,
                    color = cs.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        } else {
            items(customKeys, key = { it }) { key ->
                val index = customKeys.indexOf(key)
                val editing = editingKey == key
                val cleaned = editText.replace("\r", "").replace("\n", "")
                val confirmEnabled = cleaned.isNotBlank() &&
                    (cleaned == key || cleaned !in customKeys)
                CustomKeyRow(
                    key = key,
                    editing = editing,
                    editText = editText,
                    onEditTextChange = {
                        editText = it.replace("\r", "").replace("\n", "")
                            .take(KeyboardLayouts.MAX_CUSTOM_KEY_LENGTH)
                    },
                    confirmEnabled = confirmEnabled,
                    onConfirmEdit = { renameCustomKey(key, editText) },
                    actionsEnabled = editingKey == null,
                    canMoveUp = index > 0,
                    canMoveDown = index >= 0 && index < customKeys.lastIndex,
                    onStartEdit = { editingKey = key; editText = key },
                    onCancelEdit = { editingKey = null },
                    onMoveUp = { moveCustomKey(key, -1) },
                    onMoveDown = { moveCustomKey(key, 1) },
                    onDelete = {
                        onConfigureKeyboardLayouts(settings.enabled, selectedKeys, customKeys - key)
                        if (editingKey == key) {
                            editingKey = null
                        }
                    },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

@Composable
private fun SettingsTab(
    state: ScribUiState,
    actions: Actions,
    modelsExpanded: Boolean,
    customExpanded: Boolean,
    onToggleStandard: () -> Unit,
    onToggleCustom: () -> Unit,
    translationExpanded: Boolean,
    onToggleTranslation: () -> Unit,
    sherpaPlugin: SherpaPlugin?,
    endpointPlugin: EndpointPlugin?,
    onEndpointChanged: () -> Unit,
    onImportSherpa: (String, String, List<String>?, Map<String, Uri>, (Throwable?) -> Unit) -> Unit,
    onPickLanguage: () -> Unit,
    onAddCustom: () -> Unit,
    onImportBin: () -> Unit,
    onImportExternal: () -> Unit,
    onRequestDelete: (String) -> Unit,
    onSkipSilence: (Boolean) -> Unit,
    onLiveStats: (Boolean) -> Unit,
    onKeyboardStats: (Boolean) -> Unit,
    onOpenDictionary: () -> Unit,
    onOpenBenchmarks: () -> Unit,
    onOpenChangelog: () -> Unit,
    onSelfTest: () -> Unit,
    onBenchmarkFile: () -> Unit,
    onCancelBenchmark: () -> Unit,
    transcription: TranscribeUi?,
    recording: RecordingUi?
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp)
    ) {
        if (state.firstRun) {
            item { NudgeCard(state, actions) }
        }
        if (BuildConfig.LOCAL_MODELS) {
            item {
                Text(
                    stringResource(R.string.models_explainer),
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                    lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp)
                )
            }
            item { LanguageEntry(onPickLanguage) }
            item { StandardHeader(expanded = modelsExpanded, onToggle = onToggleStandard) }
            if (modelsExpanded) {
                items(state.standard.size) { i -> ModelRowCard(state.standard[i], actions, onRequestDelete) }
            }
        }
        if (sherpaPlugin != null) {
            item {
                sherpaPlugin.SettingsBlocks(
                    rows = state.sherpaRows,
                    busy = state.sherpaBusy,
                    onUse = actions.onUse,
                    onDelete = actions.onDelete,
                    onRequestDelete = onRequestDelete,
                    onImportSherpa = onImportSherpa
                )
            }
        }
        if (endpointPlugin != null) {
            item { endpointPlugin.SettingsBlocks(onChanged = onEndpointChanged) }
        }
        if (BuildConfig.LOCAL_MODELS) {
            item { CustomHeader(expanded = customExpanded, onToggle = onToggleCustom) }
            if (customExpanded) {
                if (state.custom.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.custom_empty),
                            fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                            lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                } else {
                    items(state.custom.size) { i -> ModelRowCard(state.custom[i], actions, onRequestDelete) }
                }
            }
            item {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddButton("+", stringResource(R.string.add_from_hf), onAddCustom)
                    AddButton("↥", stringResource(R.string.import_bin), onImportBin)
                    AddButton("↧", stringResource(R.string.import_external), onImportExternal)
                }
            }
            item { TranslationModelsSection(expanded = translationExpanded, onToggle = onToggleTranslation) }
        }
        if (state.statusMsg.isNotEmpty()) {
            item { StatusBox(state.statusMsg, state.statusError) }
        }
        if (BuildConfig.LOCAL_MODELS) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    TextButton(onClick = onSelfTest) {
                        Text(stringResource(R.string.run_self_test), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
                    }
                }
            }
            item {
                BenchmarkBatchControl(
                    state = state.benchmarkBatch,
                    enabled = state.benchmarkModelCount > 0 &&
                        !state.benchmarkBatch.running &&
                        transcription == null && recording == null,
                    onClick = onBenchmarkFile,
                    onCancel = onCancelBenchmark
                )
            }
            val batch = state.benchmarkBatch
            if (batch.total > 0 && !batch.running &&
                (batch.completed > 0 || batch.failed > 0 || batch.cancelled)) {
                item {
                    val text = when {
                        batch.cancelled -> stringResource(R.string.benchmark_batch_cancelled, batch.completed, batch.total)
                        batch.failed > 0 -> stringResource(
                            R.string.benchmark_batch_partial,
                            batch.completed - batch.failed,
                            batch.total,
                            batch.failed
                        )
                        else -> stringResource(R.string.benchmark_batch_done, batch.completed, batch.total)
                    }
                    StatusBox(text, batch.cancelled || batch.failed > 0)
                }
            }
            item { SkipSilenceEntry(state, onSkipSilence) }
        }
        item { LiveStatsEntry(state.liveStats, onLiveStats) }
        item { KeyboardStatsEntry(state, onKeyboardStats) }
        item { DictionaryEntry(state.dictionaryEnabled, onOpenDictionary) }
        if (BuildConfig.LOCAL_MODELS) {
            item { BenchmarkEntry(state.benchmarkRuns, onOpenBenchmarks) }
        }
        item { ChangelogEntry(onOpenChangelog) }
    }
}

@Composable
private fun SingleCardColumn(content: @Composable () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        item { content() }
    }
}

@Composable
private fun ServiceCard(
    glyph: String,
    title: String,
    subtitle: String? = null,
    tag: String? = null,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Text(glyph, fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                if (subtitle != null) {
                    Text(subtitle, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp)
                }
            }
            Spacer(Modifier.width(16.dp))
            Text("›", fontSize = 22.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun BottomBar(selected: ScribTab, onSelect: (ScribTab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant))
        Surface(color = cs.surfaceContainer) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp)) {
                ScribTab.entries.forEach { t ->
                    val on = t == selected
                    val columnInteraction = remember { MutableInteractionSource() }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().testTag("scrib/tab/${t.name.lowercase()}")
                            .clickable(
                                interactionSource = columnInteraction,
                                indication = null
                            ) { onSelect(t) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier
                                .size(width = 48.dp, height = 32.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (on) cs.primaryContainer else Color.Transparent)
                                .clickable { onSelect(t) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painterResource(t.icon),
                                contentDescription = stringResource(t.labelRes),
                                tint = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            stringResource(t.labelRes),
                            fontSize = 10.sp, lineHeight = 11.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = if (on) cs.primary else cs.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun recordHint(remoteHost: String?): String =
    if (remoteHost != null) stringResource(R.string.record_hint_remote, remoteHost)
    else stringResource(R.string.record_hint)

@Composable
private fun AppBar(onAbout: () -> Unit, showAbout: Boolean) {
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
        Text(stringResource(R.string.app_name), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = cs.onBackground, letterSpacing = (-0.6).sp)
        Spacer(Modifier.weight(1f))
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) {
            if (showAbout) {
                TextButton(onClick = onAbout, modifier = Modifier.testTag("scrib/appbar/about")) {
                    Text(stringResource(R.string.about), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
                }
            }
        }
    }
}

@Composable
private fun Bar(h: androidx.compose.ui.unit.Dp, color: Color, alpha: Float) {
    Box(Modifier.width(3.dp).height(h).clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = alpha)))
}

@Composable
private fun StatusCard(state: ScribUiState, onOpenSettings: (() -> Unit)? = null, onSwitchModel: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            if (state.activeName != null) {
                val rowModifier = if (onSwitchModel != null) {
                    Modifier.fillMaxWidth().testTag("scrib/home/active_model").clickableRow(RoundedCornerShape(12.dp), onSwitchModel)
                } else {
                    Modifier.fillMaxWidth()
                }
                Row(
                    rowModifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(cs.primary))
                    Text(stringResource(if (BuildConfig.LOCAL_MODELS) R.string.active_model else R.string.active_endpoint), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp, color = cs.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(state.activeName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (onSwitchModel != null) {
                        Text("›", fontSize = 18.sp, color = cs.onSurfaceVariant)
                    }
                }
            } else {
                Text(stringResource(if (BuildConfig.LOCAL_MODELS) R.string.no_active_model else R.string.no_active_endpoint), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(stringResource(if (BuildConfig.LOCAL_MODELS) R.string.pick_model_hint else R.string.pick_endpoint_hint), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                if (onOpenSettings != null) {
                    TextButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 2.dp)) {
                        Text(stringResource(R.string.nav_settings), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                    }
                }
            }
            PrivacyLine(cs.onSurfaceVariant, topBorder = true, remoteHost = state.remoteHost)
        }
    }
}

@Composable
private fun NudgeCard(state: ScribUiState, actions: Actions, onOpenSettings: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val base = state.standard.firstOrNull { it.recommended }
    Surface(color = cs.primaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp)) {
            Text(stringResource(R.string.setup_transcription), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = cs.onPrimaryContainer)
            Text(
                stringResource(if (BuildConfig.LOCAL_MODELS) R.string.nudge_body else R.string.nudge_remote_body),
                fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (BuildConfig.LOCAL_MODELS) {
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
            } else if (onOpenSettings != null) {
                TextButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.nav_settings), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                }
            }
            PrivacyLine(cs.onPrimaryContainer.copy(alpha = 0.9f), topBorder = false, remoteHost = null)
        }
    }
}

@Composable
private fun PrivacyLine(textColor: Color, topBorder: Boolean, remoteHost: String?) {
    val text = when {
        remoteHost != null -> stringResource(R.string.privacy_remote, remoteHost)
        topBorder -> stringResource(R.string.privacy_full)
        else -> stringResource(R.string.privacy_short)
    }
    Row(
        Modifier.fillMaxWidth().padding(top = if (topBorder) 13.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(if (remoteHost != null) "☁️" else "🔒", fontSize = 14.sp)
        Text(
            text,
            fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = textColor, lineHeight = 17.sp
        )
    }
}

@Composable
internal fun SectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    top: Dp = 0.dp,
    trailing: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(top = top, bottom = 8.dp)
            .clip(RoundedCornerShape(18.dp)).clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chevron(expanded = expanded, color = cs.onSurfaceVariant)
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = cs.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            trailing()
        }
    }
}

@Composable
private fun StandardHeader(expanded: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    SectionHeader(
        title = stringResource(R.string.standard_models),
        expanded = expanded,
        onToggle = onToggle,
        trailing = {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(5, 8, 11, 14).forEach { Box(Modifier.width(3.dp).height(it.dp).clip(RoundedCornerShape(1.dp)).background(cs.outline)) }
            }
            Text(stringResource(R.string.size), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        }
    )
}

@Composable
private fun CustomHeader(expanded: Boolean, onToggle: () -> Unit) {
    SectionHeader(
        title = stringResource(R.string.custom_models),
        expanded = expanded,
        onToggle = onToggle,
        top = 8.dp,
    )
}

@Composable
internal fun Chevron(expanded: Boolean, color: Color) {
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
internal fun ModelRowCard(row: ModelRow, actions: Actions, onRequestDelete: (String) -> Unit, showPin: Boolean = true) {
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
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            row.name,
                            fontSize = 16.5.sp, fontWeight = FontWeight.Bold, color = onBg,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        if (active) Pill(stringResource(R.string.active_pill), cs.primary, cs.onPrimary, Modifier.align(Alignment.CenterVertically))
                        if (row.recommended && !active) Pill(stringResource(R.string.recommended).uppercase(), cs.primary, cs.onPrimary, Modifier.align(Alignment.CenterVertically))
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
            ActionZone(row, actions, onRequestDelete, showPin)
        }
    }
}

@Composable
private fun ActionZone(row: ModelRow, actions: Actions, onRequestDelete: (String) -> Unit, showPin: Boolean) {
    val cs = MaterialTheme.colorScheme
    when (row.state) {
        RowState.NotDownloaded -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Chip(stringResource(R.string.action_download), cs.primaryContainer, cs.onPrimaryContainer) { actions.onDownload(row.id) }
        }
        RowState.Failed -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Chip(stringResource(R.string.action_retry), cs.errorContainer, cs.onErrorContainer) { actions.onDownload(row.id) }
        }
        RowState.Downloading -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.downloading_pct, row.progress), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = cs.primary, modifier = Modifier.padding(bottom = 6.dp))
                LinearProgressIndicator(
                    progress = { (row.progress.coerceIn(0, 100)) / 100f },
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = cs.primary, trackColor = cs.outlineVariant
                )
            }
            TextButton(onClick = { actions.onCancel(row.id) }) { Text(stringResource(R.string.action_cancel), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant) }
        }
        RowState.Installed -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (showPin) PinToggle(row.pinned, row.id, actions)
            TextButton(onClick = { onRequestDelete(row.id) }) { Text(stringResource(R.string.action_delete), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant) }
            Spacer(Modifier.width(6.dp))
            Chip(stringResource(R.string.action_use), cs.primaryContainer, cs.onPrimaryContainer) { actions.onUse(row.id) }
        }
        RowState.Active -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (showPin) PinToggle(row.pinned, row.id, actions)
            TextButton(onClick = { onRequestDelete(row.id) }) { Text(stringResource(R.string.action_delete), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer.copy(alpha = 0.85f)) }
        }
    }
}

@Composable
private fun PinToggle(pinned: Boolean, id: String, actions: Actions) {
    val cs = MaterialTheme.colorScheme
    IconButton(onClick = { actions.onTogglePinned(id) }) {
        Icon(
            painterResource(if (pinned) R.drawable.ic_star_filled else R.drawable.ic_star_outline),
            contentDescription = stringResource(if (pinned) R.string.quick_access_remove else R.string.quick_access_add),
            tint = if (pinned) cs.primary else cs.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Surface(color = bg, shape = RoundedCornerShape(20.dp), modifier = modifier) {
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
internal fun AddButton(glyph: String, label: String, onClick: () -> Unit) {
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
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .testTag("scrib/record/button").clickableRow(RoundedCornerShape(16.dp), onClick)
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
private fun RecordingDialog(r: RecordingUi, remoteHost: String?, onStop: () -> Unit, onCancel: () -> Unit) {
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
                Text(
                    stringResource(if (remoteHost != null) R.string.record_hint_remote else R.string.record_hint),
                    fontSize = 12.5.sp, color = cs.onSurfaceVariant, lineHeight = 17.sp
                )
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
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
            .testTag("scrib/transcribe/pick_file").clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
        ) {
            Text(stringResource(R.string.transcribe_audio_file), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
        }
    }
}

@Composable
private fun BenchmarkBatchControl(
    state: BenchmarkBatchState,
    enabled: Boolean,
    onClick: () -> Unit,
    onCancel: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    if (state.running) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(
                stringResource(
                    R.string.benchmark_batch_running,
                    (state.completed + 1).coerceAtMost(state.total),
                    state.total,
                    state.currentModel.orEmpty()
                ),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = {
                    if (state.total > 0) state.completed.toFloat() / state.total else 0f
                },
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(3.dp)),
                color = cs.primary,
                trackColor = cs.outlineVariant
            )
            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.action_cancel), fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }
    } else {
        TextButton(
            enabled = enabled,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            Text(
                stringResource(R.string.benchmark_all_models),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = cs.onSurfaceVariant
            )
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

@Composable
private fun TranscriptionDialog(
    t: TranscribeUi,
    showStats: Boolean,
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
                if (t.running && t.batchTotal > 1) {
                    Text(
                        stringResource(R.string.transcribe_batch_position, t.batchIndex + 1, t.batchTotal),
                        fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = cs.primary
                    )
                    Spacer(Modifier.height(12.dp))
                }
                when {
                    t.error != null -> Text(t.error, fontSize = 13.sp, color = cs.error, lineHeight = 18.sp)
                    t.running && t.text.isEmpty() -> {
                        Text(stringResource(R.string.decoding_transcribing), fontSize = 13.sp, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        RunProgress(t, showStats)
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
                            RunProgress(t, showStats)
                        }
                    }
                }
                if (!t.running && t.error == null && showStats && t.metrics.hasData) {
                    Spacer(Modifier.height(8.dp))
                    StatsLine(t)
                }
                if (hasText) {
                    Spacer(Modifier.height(12.dp))
                    if (finished && t.segments.isNotEmpty()) {
                        FormatPicker(t.format, onFormat, t.segments.any { it.words.isNotEmpty() })
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
                    ) {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(t.formatted)) }) {
                            Icon(painterResource(R.drawable.ic_action_copy), stringResource(R.string.action_copy))
                        }
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, t.formatted)
                                .putExtra(Intent.EXTRA_SUBJECT, baseName)
                            context.startActivity(Intent.createChooser(send, null))
                        }) {
                            Icon(painterResource(R.drawable.ic_action_share), stringResource(R.string.action_share))
                        }
                        if (finished) {
                            IconButton(onClick = { saveLauncher.launch("$baseName.${t.format.extension}") }) {
                                Icon(
                                    painterResource(R.drawable.ic_action_save),
                                    stringResource(R.string.action_save_ext, t.format.extension)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (t.running) {
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_cancel))
                }
            } else {
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    )
}

@Composable
private fun HistoryEntryDialog(
    entry: TranscriptHistory.Entry,
    onClose: () -> Unit,
    onSaveFile: (Uri) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val baseName = entry.fileName.substringBeforeLast('.').ifBlank { "transcript" }
    val saveContract = remember { CreateTranscriptDocument(null, transcriptMimeType(TranscriptFormat.TXT)) }
    val saveLauncher = rememberLauncherForActivityResult(saveContract) { uri ->
        if (uri != null) onSaveFile(uri)
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(entry.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    Text(entry.text, fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurface)
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
                ) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(entry.text)) }) {
                        Icon(painterResource(R.drawable.ic_action_copy), stringResource(R.string.action_copy))
                    }
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, entry.text)
                            .putExtra(Intent.EXTRA_SUBJECT, baseName)
                        context.startActivity(Intent.createChooser(send, null))
                    }) {
                        Icon(painterResource(R.drawable.ic_action_share), stringResource(R.string.action_share))
                    }
                    IconButton(onClick = { saveLauncher.launch("$baseName.txt") }) {
                        Icon(
                            painterResource(R.drawable.ic_action_save),
                            stringResource(R.string.action_save_ext, TranscriptFormat.TXT.extension)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun StatsLine(t: TranscribeUi) {
    if (!t.metrics.hasData) {
        return
    }
    val metrics = t.metrics
    val rtf = rtfText(metrics)
    Column {
        if (!t.modelName.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.transcribe_stats_model, t.modelName),
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = stringResource(
                R.string.transcribe_stats_line,
                elapsed(metrics.totalMs),
                elapsedOrDash(metrics.decodeMs),
                elapsed(metrics.modelLoadMs),
                elapsedOrDash(metrics.inferenceMs),
                elapsedOrDash(metrics.audioDurationMs),
                rtf,
                memoryText(metrics.pssMb),
                memoryText(metrics.peakPssMb),
                memoryText(metrics.freeRamMb)
            ),
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 16.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (metrics.modelPssMb > 0) {
            Text(
                text = stringResource(
                    R.string.transcribe_stats_model_memory,
                    memoryText(metrics.modelPssMb),
                    memoryText(metrics.modelMemoryDeltaMb)
                ),
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun memoryText(megabytes: Int): String =
    if (megabytes >= 1024) {
        stringResource(R.string.transcribe_stats_gb, megabytes / 1024f)
    } else {
        stringResource(R.string.transcribe_stats_mb, megabytes)
    }

@Composable
private fun memoryOrDash(megabytes: Int): String = if (megabytes > 0) memoryText(megabytes) else "—"

private fun elapsedOrDash(ms: Long): String = if (ms > 0L) elapsed(ms) else "—"

private fun rtfText(metrics: TranscriptionMetrics): String =
    metrics.rtf?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—"

// The engine only starts reporting once decoding is done and the model is loaded, so the bar spins
// until then rather than sitting at a misleading zero. The line underneath doubles as the promise
// that walking away is safe — the run keeps going in the notification.
@Composable
private fun RunProgress(t: TranscribeUi, showStats: Boolean) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        if (t.percent < 0) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { t.percent.coerceIn(0, 100) / 100f },
                drawStopIndicator = {},
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
        if (showStats && t.metrics.hasData) {
            Spacer(Modifier.height(6.dp))
            StatsLine(t)
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
private fun FormatPicker(selected: TranscriptFormat, onSelect: (TranscriptFormat) -> Unit, hasWords: Boolean) {
    val cs = MaterialTheme.colorScheme
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        TranscriptFormat.entries.forEach { format ->
            if (format == TranscriptFormat.WORDS && !hasWords) {
                return@forEach
            }
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
                Text(stringResource(R.string.lang_entry_sub), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp)
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
    val usable = state.vadSupported
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .clickableRow(RoundedCornerShape(16.dp)) { if (!busy && usable) onToggle(!state.skipSilence) }
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Text("🤫", fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.vad_entry_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (usable) cs.onSurface else cs.onSurfaceVariant)
                Text(
                    when {
                        !usable -> stringResource(R.string.vad_entry_unsupported)
                        busy -> stringResource(R.string.vad_entry_downloading, state.vadProgress)
                        else -> stringResource(R.string.vad_entry_sub)
                    },
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = state.skipSilence && usable, enabled = !busy && usable, onCheckedChange = { onToggle(it) })
        }
    }
}

// Reading the process's own memory is a trip into the system server, twice a second, while the
// model is loaded and the phone is already the tightest it will be. It buys the live speed and
// memory figures, so it stays on by default — but it is the one moving part a user may want to
// weigh against the run, and a phone with little to spare may rather not pay for it at all.
@Composable
private fun LiveStatsEntry(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .clickableRow(RoundedCornerShape(16.dp)) { onToggle(!enabled) }
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Text("📈", fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.live_stats_entry_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(
                    if (enabled) stringResource(R.string.live_stats_entry_on)
                    else stringResource(R.string.live_stats_entry_sub),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = { onToggle(it) })
        }
    }
}
@Composable
private fun KeyboardStatsEntry(state: ScribUiState, onToggle: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .clickableRow(RoundedCornerShape(16.dp)) { onToggle(!state.keyboardStats) }
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Text("📊", fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ime_stats_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(
                    if (state.keyboardStats) stringResource(
                        R.string.ime_stats_line,
                        state.keyboardUsage.sessions,
                        state.keyboardUsage.words,
                        elapsed(state.keyboardUsage.timeMs)
                    ) else stringResource(R.string.ime_stats_sub),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = state.keyboardStats, onCheckedChange = { onToggle(it) })
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
private fun BenchmarkEntry(runs: List<BenchmarkRun>, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val latest = runs.firstOrNull()
    val subtitle = if (latest == null) {
        stringResource(R.string.benchmark_history_empty)
    } else {
        stringResource(
            R.string.benchmark_latest,
            latest.modelName ?: "—",
            rtfText(latest.metrics),
            memoryText(latest.metrics.peakPssMb)
        )
    }
    Surface(
        color = cs.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Text("↗", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = cs.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.benchmark_history), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(subtitle, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(16.dp))
            Text("›", fontSize = 22.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun BenchmarkHistoryDialog(
    runs: List<BenchmarkRun>,
    onDismiss: () -> Unit,
    onOpenFullscreen: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.background,
        tonalElevation = 0.dp,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.benchmark_history), modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenFullscreen) {
                    Icon(
                        painterResource(R.drawable.ic_fullscreen),
                        contentDescription = stringResource(R.string.benchmark_fullscreen),
                        tint = cs.primary
                    )
                }
            }
        },
        text = {
            if (runs.isEmpty()) {
                Text(stringResource(R.string.benchmark_history_empty), fontSize = 13.sp, color = cs.onSurfaceVariant)
            } else {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    runs.forEach { run ->
                        BenchmarkRunCard(run, Modifier.padding(bottom = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

@Composable
private fun ChangelogDialog(releases: List<Changelog.Release>, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.background,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.whats_new_entry_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                releases.forEachIndexed { i, release ->
                    Text(
                        release.version,
                        fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = cs.primary,
                        modifier = Modifier.padding(top = if (i == 0) 0.dp else 14.dp, bottom = 4.dp)
                    )
                    release.lines.forEach { line ->
                        Text(line, fontSize = 12.5.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

@Composable
private fun WhatsNewDialog(release: Changelog.Release, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = cs.background,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.whats_new_title, release.version)) },
        text = {
            Column {
                release.lines.forEach { line ->
                    Text(line, fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } }
    )
}

@Composable
private fun ChangelogEntry(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickableRow(RoundedCornerShape(16.dp), onClick)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Text("✨", fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.whats_new_entry_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(stringResource(R.string.whats_new_entry_sub), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(16.dp))
            Text("›", fontSize = 22.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
internal fun BenchmarkRunCard(run: BenchmarkRun, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        run.modelName ?: "—",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        run.fileName,
                        fontSize = 11.sp,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    benchmarkTimestamp(run.timestampMs),
                    fontSize = 10.sp,
                    color = cs.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BenchmarkMetric(
                    stringResource(R.string.benchmark_column_rtf),
                    rtfText(run.metrics),
                    Modifier.weight(1f)
                )
                BenchmarkMetric(
                    stringResource(R.string.benchmark_column_load),
                    elapsed(run.metrics.modelLoadMs),
                    Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BenchmarkMetric(
                    stringResource(R.string.benchmark_column_model_pss),
                    memoryOrDash(run.metrics.modelPssMb),
                    Modifier.weight(1f)
                )
                BenchmarkMetric(
                    stringResource(R.string.benchmark_column_peak),
                    memoryOrDash(run.metrics.peakPssMb),
                    Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BenchmarkMetric(
                    stringResource(R.string.benchmark_column_model_delta),
                    memoryOrDash(run.metrics.modelMemoryDeltaMb),
                    Modifier.weight(1f)
                )
                BenchmarkMetric(
                    stringResource(R.string.benchmark_column_free),
                    memoryOrDash(run.metrics.freeRamMb),
                    Modifier.weight(1f)
                )
            }
        }
    }
}

private fun benchmarkTimestamp(timestampMs: Long): String {
    val date = Date(timestampMs)
    return if (DateUtils.isToday(timestampMs)) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
    } else {
        DateFormat.getDateInstance(DateFormat.SHORT).format(date)
    }
}

@Composable
private fun BenchmarkMetric(label: String, value: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// Every custom key occupies this height whether it is being read or edited, so switching one to
// an edit field never resizes the row the list has already placed.
private val ROW_HEIGHT = 56.dp

// The same white row the keys live in, only empty and adding rather than holding one: a new key
// is typed and confirmed in one place, in the same shape as what is being edited.
@Composable
private fun AddKeyRow(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onAdd: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface,
        shape = RoundedCornerShape(14.dp),
        // The same bottom spacing every key row keeps below itself, so it sits in line with them.
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).height(ROW_HEIGHT)
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 4.dp).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyField(
                value = value,
                onValueChange = onValueChange,
                hint = stringResource(R.string.ime_custom_keys_add_hint),
                modifier = Modifier.weight(1f),
                onDone = onAdd
            )
            Spacer(Modifier.width(2.dp))
            KeyActionButton(
                icon = R.drawable.ic_add,
                description = stringResource(R.string.ime_custom_keys_add),
                tint = cs.primary,
                enabled = enabled,
                onClick = onAdd
            )
        }
    }
}

// The field of an edit or an add row: one height, one alignment, no decoration box of its own.
@Composable
private fun KeyField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceContainerHighest)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface
            ),
            cursorBrush = SolidColor(cs.primary),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() })
        )
        if (value.isEmpty()) {
            Text(
                hint,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// One key per row: the text on the left, order and edit actions on the right.

@Composable
private fun CustomKeyRow(
    key: String,
    editing: Boolean,
    editText: String,
    onEditTextChange: (String) -> Unit,
    confirmEnabled: Boolean,
    onConfirmEdit: () -> Unit,
    actionsEnabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface,
        shape = RoundedCornerShape(14.dp),
        // Both states measure the same: an edit field that grows on focus makes the row jump.
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp).height(ROW_HEIGHT)
    ) {
        if (editing) {
                // The same left inset as the text of a plain row, and the same right inset as its
                // last action, so swapping states moves nothing.
                Row(
                    Modifier.padding(start = 8.dp, end = 4.dp).fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    KeyField(
                        value = editText,
                        onValueChange = onEditTextChange,
                        hint = key,
                        modifier = Modifier.weight(1f),
                        onDone = onConfirmEdit
                    )
                    Spacer(Modifier.width(2.dp))
                KeyActionButton(
                    icon = R.drawable.ic_check,
                    description = stringResource(R.string.action_save),
                    tint = cs.primary,
                    enabled = confirmEnabled,
                    onClick = onConfirmEdit
                )
                KeyActionButton(
                    icon = R.drawable.ic_close,
                    description = stringResource(R.string.action_cancel),
                    enabled = true,
                    onClick = onCancelEdit
                )
            }
        } else {
            Row(
                Modifier.padding(start = 14.dp, end = 4.dp).fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    key,
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                KeyActionButton(
                    icon = R.drawable.ic_chevron_up,
                    description = stringResource(R.string.ime_custom_keys_move_up, key),
                    enabled = actionsEnabled && canMoveUp,
                    onClick = onMoveUp
                )
                KeyActionButton(
                    icon = R.drawable.ic_chevron_down,
                    description = stringResource(R.string.ime_custom_keys_move_down, key),
                    enabled = actionsEnabled && canMoveDown,
                    onClick = onMoveDown
                )
                KeyActionButton(
                    icon = R.drawable.ic_edit,
                    description = stringResource(R.string.ime_custom_keys_edit, key),
                    enabled = actionsEnabled,
                    onClick = onStartEdit
                )
                KeyActionButton(
                    icon = R.drawable.ic_close,
                    description = stringResource(R.string.ime_custom_keys_delete, key),
                    enabled = actionsEnabled,
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun KeyActionButton(
    icon: Int,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp)
    ) {
        Icon(
            painterResource(icon),
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun LanguageDialog(onDismiss: () -> Unit, onPick: (LanguageOption?) -> Unit) {
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
                    Column(
                        Modifier.fillMaxWidth().clickableRow(RectangleShape) { onPick(null) }.padding(vertical = 10.dp)
                    ) {
                        Text(stringResource(R.string.lang_auto), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                        Text(stringResource(R.string.lang_auto_sub), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
                    }
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
internal fun ModelSwitchDialog(rows: List<ModelRow>, onDismiss: () -> Unit, onUse: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.switch_model_title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                rows.forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().clickableRow(RectangleShape) { onUse(row.id) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.name,
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(row.badge, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
                        }
                        if (row.state == RowState.Active) {
                            Pill(stringResource(R.string.active_pill), cs.primary, cs.onPrimary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

@Composable
internal fun AddUrlDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit, bodyRes: Int = R.string.hf_dialog_body) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hf_dialog_title)) },
        text = {
            Column {
                Text(stringResource(bodyRes), fontSize = 13.sp)
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
