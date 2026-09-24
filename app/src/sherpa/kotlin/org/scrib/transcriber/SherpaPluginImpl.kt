package org.scrib.transcriber

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class SherpaPluginImpl : SherpaPlugin {

    override fun selectedId(context: Context): String? {
        val app = context.applicationContext
        val stored = ModelManager.storedActiveName(app) ?: return null
        return if (SherpaModel.byId(app, stored) != null) stored else null
    }

    override fun engine(context: Context): TranscriptionEngine =
        SherpaTranscriptionEngine.getInstance(context.applicationContext)

    override fun hasActiveModel(context: Context): Boolean {
        val app = context.applicationContext
        val id = selectedId(app) ?: return false
        val info = SherpaModel.byId(app, id) ?: return false
        return SherpaModel.isComplete(SherpaModel.dirFor(app, id), info)
    }

    override fun displayName(context: Context): String? {
        val app = context.applicationContext
        val id = selectedId(app) ?: return null
        return SherpaModel.byId(app, id)?.displayName
    }

    override fun displayNameFor(context: Context, id: String): String? =
        SherpaModel.byId(context.applicationContext, id)?.displayName

    override fun deleteModel(context: Context, id: String): Boolean {
        val app = context.applicationContext
        if (!SherpaModel.deleteModel(app, id)) {
            return false
        }
        ModelManager.clearActiveSelection(app, id)
        SherpaTranscriptionEngine.forgetInstance(id)
        return true
    }

    override fun importModel(
        context: Context,
        displayName: String,
        modelType: String,
        languages: List<String>?,
        parts: Map<String, Uri>
    ): String {
        val info = SherpaModel.importModel(context.applicationContext, displayName, modelType, languages, parts)
        return info.id
    }

    override fun modelRows(context: Context, activeId: String?): List<ModelRow> {
        val app = context.applicationContext
        return SherpaModel.list(app)
            .filter { SherpaModel.isComplete(SherpaModel.dirFor(app, it.id), it) }
            .map { info ->
                ModelRow(
                    id = info.id,
                    name = info.displayName,
                    badge = info.languages?.joinToString(" · ")
                        ?: app.getString(R.string.badge_custom),
                    multilingual = (info.languages?.size ?: 0) != 1,
                    sizeMb = (info.totalBytes / 1_000_000).toInt(),
                    tier = 3,
                    recommended = false,
                    custom = false,
                    state = if (info.id == activeId) RowState.Active else RowState.Installed,
                    progress = 0
                )
            }
    }

    override fun selfTestNotice(context: Context): String? {
        return if (selectedId(context.applicationContext) != null) {
            context.getString(R.string.status_selftest_whisper_only)
        } else {
            null
        }
    }

    @Composable
    override fun SettingsBlocks(
        rows: List<ModelRow>,
        busy: Boolean,
        onUse: (String) -> Unit,
        onDelete: (String) -> Unit,
        onRequestDelete: (String) -> Unit,
        onImportSherpa: (String, String, List<String>?, Map<String, Uri>) -> Unit
    ) {
        val cs = MaterialTheme.colorScheme
        var showAdd by remember { mutableStateOf(false) }
        var expanded by rememberSaveable { mutableStateOf(true) }
        SectionHeader(
            title = stringResource(R.string.other_engines),
            expanded = expanded,
            onToggle = { expanded = !expanded },
            top = 8.dp,
        )
        if (!expanded) return
        if (rows.isEmpty() && !busy) {
            Text(
                stringResource(R.string.sherpa_empty_hint),
                fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
        rows.forEach { row ->
            ModelRowCard(
                row,
                Actions(
                    onDownload = {}, onCancel = {}, onUse = onUse, onDelete = onDelete,
                    onAddUrl = {}, onImport = { _, _ -> }, onSelfTest = {}
                ),
                onRequestDelete = onRequestDelete
            )
        }
        if (busy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(3.dp)),
                color = cs.primary, trackColor = cs.outlineVariant
            )
        }
        SherpaAddButton("+", stringResource(R.string.add_sherpa)) { showAdd = true }
        if (showAdd) {
            AddSherpaModelDialog(
                onDismiss = { showAdd = false },
                onConfirm = { name, type, langs, parts ->
                    showAdd = false
                    onImportSherpa(name, type, langs, parts)
                }
            )
        }
    }
}

@Composable
private fun SherpaAddButton(glyph: String, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface, shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, cs.outline),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            .clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(glyph, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = cs.primary)
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
        }
    }
}

@Composable
private fun AddSherpaModelDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, List<String>?, Map<String, Uri>) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var modelType by rememberSaveable { mutableStateOf(SherpaModel.TYPE_DEFAULT) }
    var typeTouched by remember { mutableStateOf(false) }
    var langs by remember { mutableStateOf("") }
    var parts by remember { mutableStateOf(mapOf<String, Uri>()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun selectType(type: String) {
        modelType = type
        typeTouched = true
        val selected = mutableMapOf<String, Uri>()
        if (type == SherpaModel.TYPE_SENSE_VOICE) {
            (parts[SherpaModel.ROLE_MODEL] ?: parts[SherpaModel.ROLE_ENCODER])?.let {
                selected[SherpaModel.ROLE_MODEL] = it
            }
        } else {
            (parts[SherpaModel.ROLE_ENCODER] ?: parts[SherpaModel.ROLE_MODEL])?.let {
                selected[SherpaModel.ROLE_ENCODER] = it
            }
            parts[SherpaModel.ROLE_DECODER]?.let { selected[SherpaModel.ROLE_DECODER] = it }
            parts[SherpaModel.ROLE_JOINER]?.let { selected[SherpaModel.ROLE_JOINER] = it }
            parts[SherpaModel.ROLE_MERGED_DECODER]?.let { selected[SherpaModel.ROLE_MERGED_DECODER] = it }
        }
        parts[SherpaModel.ROLE_TOKENS]?.let { selected[SherpaModel.ROLE_TOKENS] = it }
        parts = selected
    }

    fun guessType(fileName: String?, role: String?) {
        if (!typeTouched) {
            val hint = fileName?.lowercase() ?: ""
            modelType = when {
                role == SherpaModel.ROLE_MODEL ||
                    hint.contains("sensevoice") || hint.contains("sense-voice") -> SherpaModel.TYPE_SENSE_VOICE
                hint.contains("zipformer2") -> SherpaModel.TYPE_STREAMING_ZIPFORMER2
                hint.contains("streaming") || hint.contains("chunk-16-left") -> SherpaModel.TYPE_STREAMING_TRANSDUCER
                hint.contains("moonshine") || role == SherpaModel.ROLE_MERGED_DECODER -> SherpaModel.TYPE_MOONSHINE
                hint.contains("canary") -> SherpaModel.TYPE_CANARY
                hint.contains("nemo") || hint.contains("gigaam") || hint.contains("parakeet") -> SherpaModel.TYPE_NEMO
                else -> SherpaModel.TYPE_DEFAULT
            }
        }
    }
    // One launcher per slot; each remembers its own role.
    @Composable
    fun pick(role: String) = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            parts = parts + (role to uri)
            guessType(queryDisplayName(context, uri), role)
        }
    }
    val pickEncoder = pick(SherpaModel.ROLE_ENCODER)
    val pickDecoder = pick(SherpaModel.ROLE_DECODER)
    val pickJoiner = pick(SherpaModel.ROLE_JOINER)
    val pickMergedDecoder = pick(SherpaModel.ROLE_MERGED_DECODER)
    val pickModel = pick(SherpaModel.ROLE_MODEL)
    val pickTokens = pick(SherpaModel.ROLE_TOKENS)

    fun scanFolder(treeUri: Uri) {
        try {
            val resolver = context.contentResolver
            val treeId = DocumentsContract.getTreeDocumentId(treeUri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
            val folderName = resolver.query(
                treeUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (nameIdx >= 0 && c.moveToFirst()) c.getString(nameIdx) else null
            }
            val found = mutableMapOf<String, Uri>()
            var primaryName: String? = null
            var primaryRole: String? = null
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { c ->
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    if (c.getString(mimeIdx) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        continue
                    }
                    val docName = c.getString(nameIdx) ?: continue
                    val docId = c.getString(idIdx) ?: continue
                    val lower = docName.lowercase()
                    val isModelFile = lower.endsWith(".onnx") || lower.endsWith(".ort")
                    val role = when {
                        isModelFile && ("merged_decoder" in lower || "merged-decoder" in lower || "decoder_model_merged" in lower) -> SherpaModel.ROLE_MERGED_DECODER
                        isModelFile && "encoder" in lower -> SherpaModel.ROLE_ENCODER
                        isModelFile && "decoder" in lower -> SherpaModel.ROLE_DECODER
                        isModelFile && ("joiner" in lower || "joint" in lower) -> SherpaModel.ROLE_JOINER
                        isModelFile && ("model" in lower || "sensevoice" in lower || "sense-voice" in lower) -> SherpaModel.ROLE_MODEL
                        lower.endsWith(".txt") && "tokens" in lower -> SherpaModel.ROLE_TOKENS
                        else -> null
                    }
                    if (role != null && role !in found) {
                        found[role] = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        if (role == SherpaModel.ROLE_ENCODER || role == SherpaModel.ROLE_MODEL) {
                            primaryName = docName
                            primaryRole = role
                        }
                    }
                }
            }
            if (found.isNotEmpty()) {
                parts = parts + found
                if (!typeTouched && found.containsKey(SherpaModel.ROLE_MERGED_DECODER)) {
                    modelType = SherpaModel.TYPE_MOONSHINE
                } else if (!typeTouched &&
                    found.containsKey(SherpaModel.ROLE_ENCODER) &&
                    found.containsKey(SherpaModel.ROLE_DECODER) &&
                    !found.containsKey(SherpaModel.ROLE_JOINER)
                ) {
                    modelType = SherpaModel.TYPE_CANARY
                } else {
                    val inferredRole = if (found.containsKey(SherpaModel.ROLE_MERGED_DECODER)) {
                        SherpaModel.ROLE_MERGED_DECODER
                    } else {
                        primaryRole
                    }
                    guessType(folderName ?: primaryName, inferredRole)
                }
            }
        } catch (_: Exception) {
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            scanFolder(uri)
        }
    }
    val pickers = when {
        modelType == SherpaModel.TYPE_SENSE_VOICE -> mapOf(
            SherpaModel.ROLE_MODEL to pickModel,
            SherpaModel.ROLE_TOKENS to pickTokens
        )
        modelType == SherpaModel.TYPE_MOONSHINE -> mapOf(
            SherpaModel.ROLE_ENCODER to pickEncoder,
            SherpaModel.ROLE_MERGED_DECODER to pickMergedDecoder,
            SherpaModel.ROLE_TOKENS to pickTokens
        )
        modelType == SherpaModel.TYPE_CANARY -> mapOf(
            SherpaModel.ROLE_ENCODER to pickEncoder,
            SherpaModel.ROLE_DECODER to pickDecoder,
            SherpaModel.ROLE_TOKENS to pickTokens
        )
        else -> mapOf(
            SherpaModel.ROLE_ENCODER to pickEncoder,
            SherpaModel.ROLE_DECODER to pickDecoder,
            SherpaModel.ROLE_JOINER to pickJoiner,
            SherpaModel.ROLE_TOKENS to pickTokens
        )
    }
    val slotLabels = when {
        modelType == SherpaModel.TYPE_SENSE_VOICE -> mapOf(
            SherpaModel.ROLE_MODEL to stringResource(R.string.sherpa_slot_model),
            SherpaModel.ROLE_TOKENS to stringResource(R.string.sherpa_slot_tokens)
        )
        modelType == SherpaModel.TYPE_MOONSHINE -> mapOf(
            SherpaModel.ROLE_ENCODER to stringResource(R.string.sherpa_slot_encoder),
            SherpaModel.ROLE_MERGED_DECODER to stringResource(R.string.sherpa_slot_merged_decoder),
            SherpaModel.ROLE_TOKENS to stringResource(R.string.sherpa_slot_tokens)
        )
        modelType == SherpaModel.TYPE_CANARY -> mapOf(
            SherpaModel.ROLE_ENCODER to stringResource(R.string.sherpa_slot_encoder),
            SherpaModel.ROLE_DECODER to stringResource(R.string.sherpa_slot_decoder),
            SherpaModel.ROLE_TOKENS to stringResource(R.string.sherpa_slot_tokens)
        )
        else -> mapOf(
            SherpaModel.ROLE_ENCODER to stringResource(R.string.sherpa_slot_encoder),
            SherpaModel.ROLE_DECODER to stringResource(R.string.sherpa_slot_decoder),
            SherpaModel.ROLE_JOINER to stringResource(R.string.sherpa_slot_joiner),
            SherpaModel.ROLE_TOKENS to stringResource(R.string.sherpa_slot_tokens)
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sherpa_add_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null }, singleLine = true,
                    label = { Text(stringResource(R.string.sherpa_name_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.sherpa_type_label),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TypeOption(
                    selected = modelType == SherpaModel.TYPE_DEFAULT,
                    label = stringResource(R.string.sherpa_type_default),
                    onClick = { selectType(SherpaModel.TYPE_DEFAULT) }
                )
                TypeOption(
                    selected = modelType == SherpaModel.TYPE_NEMO,
                    label = stringResource(R.string.sherpa_type_nemo),
                    onClick = { selectType(SherpaModel.TYPE_NEMO) }
                )
                TypeOption(
                    selected = modelType == SherpaModel.TYPE_STREAMING_TRANSDUCER,
                    label = stringResource(R.string.sherpa_type_streaming),
                    onClick = { selectType(SherpaModel.TYPE_STREAMING_TRANSDUCER) }
                )
                TypeOption(
                    selected = modelType == SherpaModel.TYPE_STREAMING_ZIPFORMER2,
                    label = stringResource(R.string.sherpa_type_streaming_zipformer2),
                    onClick = { selectType(SherpaModel.TYPE_STREAMING_ZIPFORMER2) }
                )
                TypeOption(
                    selected = modelType == SherpaModel.TYPE_SENSE_VOICE,
                    label = stringResource(R.string.sherpa_type_sense_voice),
                    onClick = { selectType(SherpaModel.TYPE_SENSE_VOICE) }
                )
                TypeOption(
                    selected = modelType == SherpaModel.TYPE_MOONSHINE,
                    label = stringResource(R.string.sherpa_type_moonshine),
                    onClick = { selectType(SherpaModel.TYPE_MOONSHINE) }
                )
                TypeOption(
                    selected = modelType == SherpaModel.TYPE_CANARY,
                    label = stringResource(R.string.sherpa_type_canary),
                    onClick = { selectType(SherpaModel.TYPE_CANARY) }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = langs, onValueChange = { langs = it }, singleLine = true,
                    label = { Text(stringResource(R.string.sherpa_langs_label)) },
                    placeholder = { Text(stringResource(R.string.sherpa_langs_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(onClick = { pickFolder.launch(null) })
                ) {
                    Text(
                        stringResource(R.string.sherpa_folder_button),
                        fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                    )
                }
                slotLabels.forEach { (role, label) ->
                    val fileName = parts[role]?.let { queryDisplayName(context, it) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(
                                fileName ?: "—",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { pickers.getValue(role).launch(arrayOf("*/*")) }) {
                            Text(stringResource(R.string.sherpa_choose))
                        }
                    }
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error ?: "", fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) {
                    error = context.getString(R.string.sherpa_need_name)
                    return@TextButton
                }
                val missing = SherpaModel.requiredRoles(modelType).filter { it !in parts }
                if (missing.isNotEmpty()) {
                    error = context.getString(R.string.sherpa_need_files)
                    return@TextButton
                }
                val parsed = langs.split(',', ' ')
                    .map { it.trim().lowercase() }
                    .filter { it.length in 2..3 }
                    .distinct()
                    .takeIf { it.isNotEmpty() }
                onConfirm(
                    name.trim(),
                    modelType,
                    parsed,
                    parts
                )
            }) { Text(stringResource(R.string.sherpa_add_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun TypeOption(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    }
}
