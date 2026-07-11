package org.scrib.transcriber

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Actions(
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
    transcription: TranscribeUi?,
    onTranscribeFile: (Uri, String?) -> Unit,
    onCancelTranscription: () -> Unit,
    onDismissTranscription: () -> Unit,
    onAbout: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val actions = Actions(onDownload, onCancel, onUse, onDelete, onAddUrl, onImport, onSelfTest)
    var showAdd by remember { mutableStateOf(false) }
    var showLanguages by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri, queryDisplayName(context, uri))
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onTranscribeFile(uri, queryDisplayName(context, uri))
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
                item { TranscribeFileButton { audioPicker.launch(arrayOf("audio/*", "video/*")) } }
            }
            item {
                Text(
                    "Bigger models are more accurate but slower and larger to download. English-only models are smaller and faster; for other languages choose Base or larger — Tiny is weak.",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                    lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp)
                )
            }
            item { LanguageEntry { showLanguages = true } }
            item { StandardHeader() }
            items(state.standard.size) { i -> ModelRowCard(state.standard[i], actions) { deleteTarget = it } }
            item {
                Text(
                    "CUSTOM MODELS", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 10.dp)
                )
            }
            if (state.custom.isEmpty()) {
                item {
                    Text(
                        "Add your own GGML model by direct link or import a .bin from this device.",
                        fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                        lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            } else {
                items(state.custom.size) { i -> ModelRowCard(state.custom[i], actions) { deleteTarget = it } }
            }
            item {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddButton("+", "Add model from HuggingFace…") { showAdd = true }
                    AddButton("↥", "Import .bin from device…") { picker.launch(arrayOf("*/*")) }
                }
            }
            if (state.statusMsg.isNotEmpty()) {
                item { StatusBox(state.statusMsg, state.statusError) }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(top = 22.dp), contentAlignment = Alignment.Center) {
                    TextButton(onClick = onSelfTest) {
                        Text("Run self-test on sample clip", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
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
    transcription?.let { t ->
        TranscriptionDialog(t, onCancel = onCancelTranscription, onClose = onDismissTranscription)
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete model?") },
            text = { Text(target) },
            confirmButton = { TextButton(onClick = { onDelete(target); deleteTarget = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
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
            Text("About", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
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
                Text("ACTIVE MODEL", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            if (state.activeName != null) {
                Text(state.activeName, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = cs.onSurface, letterSpacing = (-0.5).sp)
                Text("Ready to transcribe", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.primary, modifier = Modifier.padding(top = 6.dp))
            } else {
                Text("No active model", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text("Pick a model below and tap Use.", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
            }
            PrivacyLine(cs.onSurfaceVariant, cs.outlineVariant, topBorder = true)
        }
    }
}

@Composable
private fun NudgeCard(state: ScribUiState, actions: Actions) {
    val cs = MaterialTheme.colorScheme
    val baseFile = state.standard.firstOrNull { it.note == "recommended" }?.id
    Surface(color = cs.primaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp)) {
            Text("Set up transcription", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = cs.onPrimaryContainer)
            Text(
                "No speech model yet. Download one to start — Base is a balanced choice: small download, solid accuracy, works in any language.",
                fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            Surface(
                color = cs.primary, shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clickableRow { baseFile?.let(actions.onDownload) }
            ) {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text("Download Base · ≈57 MB", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = cs.onPrimary)
                }
            }
            PrivacyLine(cs.onPrimaryContainer.copy(alpha = 0.9f), cs.outlineVariant, topBorder = false)
        }
    }
}

@Composable
private fun PrivacyLine(textColor: Color, border: Color, topBorder: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(top = if (topBorder) 13.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("🔒", fontSize = 14.sp)
        Text(
            if (topBorder) "Audio is transcribed entirely on your phone. Nothing is uploaded — the app only goes online to download a model."
            else "Runs 100% on-device. Your voice messages never leave the phone.",
            fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = textColor, lineHeight = 17.sp
        )
    }
}

@Composable
private fun StandardHeader() {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("STANDARD MODELS", fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = cs.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(5, 8, 11, 14).forEach { Box(Modifier.width(3.dp).height(it.dp).clip(RoundedCornerShape(1.dp)).background(cs.outline)) }
            Text("size", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        }
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
private fun ModelRowCard(row: ModelRow, actions: Actions, onRequestDelete: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val active = row.state == RowState.Active
    val bg = if (active) cs.primaryContainer else cs.surface
    val onBg = if (active) cs.onPrimaryContainer else cs.onSurface
    val onBgVar = if (active) cs.onPrimaryContainer.copy(alpha = 0.75f) else cs.onSurfaceVariant
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
                        if (active) Pill("✓ ACTIVE", cs.primary, cs.onPrimary)
                        if (row.note != null && !active) Pill(row.note.uppercase(), cs.primary, cs.onPrimary)
                    }
                    Text(row.subtitle, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = onBgVar, modifier = Modifier.padding(top = 4.dp))
                    if (row.state == RowState.Failed) {
                        Text("Download failed — check your connection.", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = cs.error, modifier = Modifier.padding(top = 5.dp))
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
            Chip("↓ Download", cs.primaryContainer, cs.onPrimaryContainer) { actions.onDownload(row.id) }
        }
        RowState.Failed -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Chip("Retry", cs.errorContainer, cs.onErrorContainer) { actions.onDownload(row.id) }
        }
        RowState.Downloading -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Downloading ${row.progress}%", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = cs.primary, modifier = Modifier.padding(bottom = 6.dp))
                LinearProgressIndicator(
                    progress = { (row.progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = cs.primary, trackColor = cs.outlineVariant
                )
            }
            TextButton(onClick = { actions.onCancel(row.id) }) { Text("Cancel", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant) }
        }
        RowState.Installed -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onRequestDelete(row.id) }) { Text("Delete", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant) }
            Spacer(Modifier.width(6.dp))
            Chip("Use", cs.primaryContainer, cs.onPrimaryContainer) { actions.onUse(row.id) }
        }
        RowState.Active -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onRequestDelete(row.id) }) { Text("Delete", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer.copy(alpha = 0.85f)) }
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
    Surface(color = bg, shape = RoundedCornerShape(20.dp), modifier = Modifier.clickableRow(onClick)) {
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = fg, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

@Composable
private fun AddButton(glyph: String, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, cs.outline),
        modifier = Modifier.fillMaxWidth().clickableRow(onClick)
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
private fun TranscribeFileButton(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.primaryContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).clickableRow(onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
        ) {
            Text("Transcribe an audio file", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
        }
    }
}

@Composable
private fun TranscriptionDialog(t: TranscribeUi, onCancel: () -> Unit, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = { if (!t.running) onClose() },
        title = { Text(t.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                when {
                    t.error != null -> Text(t.error, fontSize = 13.sp, color = cs.error, lineHeight = 18.sp)
                    t.running && t.text.isEmpty() -> {
                        Text("Decoding & transcribing on-device…", fontSize = 13.sp, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    else -> {
                        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                            Text(t.text, fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurface)
                        }
                        if (t.running) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (t.running) TextButton(onClick = onCancel) { Text("Cancel") }
            else TextButton(onClick = onClose) { Text("Close") }
        },
        dismissButton = {
            if (!t.running && t.error == null && t.text.isNotBlank()) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(t.text)) }) { Text("Copy") }
            }
        }
    )
}

@Composable
private fun LanguageEntry(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).clickableRow(onClick)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🌐", fontSize = 18.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Find a model for your language", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text("Pick a language — Scrib downloads the right model", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
            }
            Text("›", fontSize = 22.sp, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun LanguageDialog(onDismiss: () -> Unit, onPick: (LanguageOption) -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your language") },
        text = {
            Column {
                Text(
                    "One multilingual model covers ~99 languages — this picks a good size and downloads it.",
                    fontSize = 12.5.sp, color = cs.onSurfaceVariant, lineHeight = 17.sp
                )
                Spacer(Modifier.height(6.dp))
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    LanguageCatalog.LANGUAGES.forEach { lang ->
                        Column(
                            Modifier.fillMaxWidth().clickableRow { onPick(lang) }.padding(vertical = 10.dp)
                        ) {
                            Text(lang.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                            Text(lang.note, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun AddUrlDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model from HuggingFace") },
        text = {
            Column {
                Text("Paste a direct link to a .bin ggml model (any whisper.cpp-compatible model).", fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = false, placeholder = { Text("https://huggingface.co/…/ggml-….bin") })
            }
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
    }
}.getOrNull()
