package org.scrib.transcriber

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val typeOptions = listOf(
    SherpaModel.TYPE_DEFAULT to R.string.sherpa_type_default,
    SherpaModel.TYPE_NEMO to R.string.sherpa_type_nemo,
    SherpaModel.TYPE_STREAMING_TRANSDUCER to R.string.sherpa_type_streaming,
    SherpaModel.TYPE_STREAMING_ZIPFORMER2 to R.string.sherpa_type_streaming_zipformer2,
    SherpaModel.TYPE_SENSE_VOICE to R.string.sherpa_type_sense_voice,
    SherpaModel.TYPE_MOONSHINE to R.string.sherpa_type_moonshine,
    SherpaModel.TYPE_CANARY to R.string.sherpa_type_canary,
    SherpaModel.TYPE_DOLPHIN to R.string.sherpa_type_dolphin,
    SherpaModel.TYPE_OMNILINGUAL to R.string.sherpa_type_omnilingual,
    SherpaModel.TYPE_NEMO_CTC to R.string.sherpa_type_nemo_ctc,
    SherpaModel.TYPE_ZIPFORMER_CTC to R.string.sherpa_type_zipformer_ctc,
    SherpaModel.TYPE_FIRE_RED_AED to R.string.sherpa_type_fire_red_aed,
    SherpaModel.TYPE_FIRE_RED_CTC to R.string.sherpa_type_fire_red_ctc
)

private data class FolderFile(val name: String, val uri: Uri)

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

    override suspend fun importModel(
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
        onImportSherpa: (String, String, List<String>?, Map<String, Uri>, (Throwable?) -> Unit) -> Unit
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
                busy = busy,
                onDismiss = { showAdd = false },
                onConfirm = { name, type, langs, parts, completed ->
                    onImportSherpa(name, type, langs, parts, completed)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSherpaModelDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, List<String>?, Map<String, Uri>, (Throwable?) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var modelType by rememberSaveable { mutableStateOf(SherpaModel.TYPE_DEFAULT) }
    var typeTouched by remember { mutableStateOf(false) }
    var langs by remember { mutableStateOf("") }
    var parts by remember { mutableStateOf(mapOf<String, Uri>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var detectedType by remember { mutableStateOf<String?>(null) }
    var ambiguousTypes by remember { mutableStateOf<List<String>?>(null) }
    var showFamilyPicker by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var typeMenuExpanded by remember { mutableStateOf(false) }

    fun mapDetectedParts(type: String, files: List<Pair<String, Uri>>): Map<String, Uri> {
        val byName = files.associateBy { it.first }
        val mapped = SherpaModel.planFiles(type, files.map { it.first }).orEmpty()
            .mapNotNull { (role, fileName) -> byName[fileName]?.let { role to it.second } }
            .toMap()
        val auxiliary = files
            .filter { SherpaModel.isAuxiliaryFile(it.first) }
            .associate { SherpaModel.auxiliaryRole(it.first) to it.second }
        return mapped + auxiliary
    }

    fun selectType(type: String) {
        modelType = type
        typeMenuExpanded = false
        typeTouched = true
        detectedType = null
        ambiguousTypes = null
        showFamilyPicker = false
        error = null
        val selected = mutableMapOf<String, Uri>()
        SherpaModel.requiredRoles(type).forEach { role ->
            val uri = when (role) {
                SherpaModel.ROLE_MODEL -> parts[SherpaModel.ROLE_MODEL] ?: parts[SherpaModel.ROLE_ENCODER]
                SherpaModel.ROLE_ENCODER -> parts[SherpaModel.ROLE_ENCODER] ?: parts[SherpaModel.ROLE_MODEL]
                else -> parts[role]
            }
            if (uri != null) selected[role] = uri
        }
        parts.keys.filter { SherpaModel.isAuxiliaryRole(it) }.forEach { role ->
            parts[role]?.let { selected[role] = it }
        }
        parts = selected
    }

    fun detectSelectedParts() {
        if (typeTouched) return
        detectedType = null
        ambiguousTypes = null
        showFamilyPicker = false
        val files = parts.mapNotNull { (role, uri) ->
            if (SherpaModel.isAuxiliaryRole(role)) null else queryDisplayName(context, uri)?.let { it to uri }
        }
        val hint = name.takeIf { it.isNotBlank() }
            ?: files.firstOrNull { SherpaModel.isAuxiliaryFile(it.first).not() }?.first
        when (val detection = SherpaModel.detect(files.map { it.first }, hint)) {
            is SherpaModel.Detection.Detected -> {
                modelType = detection.modelType
                parts = mapDetectedParts(detection.modelType, files)
                detectedType = detection.modelType
                ambiguousTypes = null
                error = null
            }
            is SherpaModel.Detection.Ambiguous -> {
                modelType = detection.candidates.first()
                parts = mapDetectedParts(modelType, files)
                detectedType = null
                ambiguousTypes = detection.candidates
                showFamilyPicker = true
                error = null
            }
            SherpaModel.Detection.Unknown -> detectedType = null
        }
    }

    @Composable
    fun pick(role: String) = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            parts = parts + (role to uri)
            detectSelectedParts()
        }
    }
    val pickEncoder = pick(SherpaModel.ROLE_ENCODER)
    val pickDecoder = pick(SherpaModel.ROLE_DECODER)
    val pickJoiner = pick(SherpaModel.ROLE_JOINER)
    val pickMergedDecoder = pick(SherpaModel.ROLE_MERGED_DECODER)
    val pickModel = pick(SherpaModel.ROLE_MODEL)
    val pickTokens = pick(SherpaModel.ROLE_TOKENS)

    fun scanFolder(treeUri: Uri) {
        if (scanning) return
        scanning = true
        error = null
        scope.launch {
            try {
                val folder = withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val treeId = DocumentsContract.getTreeDocumentId(treeUri)
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
                    val folderDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
                    val folderName = resolver.query(
                        folderDocumentUri,
                        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        null, null, null
                    )?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
                    }
                    val files = mutableListOf<FolderFile>()
                    resolver.query(
                        children,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID
                        ),
                        null, null, null
                    )?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        if (nameIndex >= 0 && idIndex >= 0) {
                            while (cursor.moveToNext()) {
                                val fileName = cursor.getString(nameIndex) ?: continue
                                val documentId = cursor.getString(idIndex) ?: continue
                                val lowerName = fileName.lowercase()
                                if (lowerName.endsWith(".onnx") || lowerName.endsWith(".ort") ||
                                    lowerName.endsWith(".txt") || SherpaModel.isAuxiliaryFile(lowerName)
                                ) {
                                    files += FolderFile(
                                        fileName,
                                        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                                    )
                                }
                            }
                        }
                    }
                    folderName to files
                }
                val folderName = folder.first?.takeIf { it.isNotBlank() }
                val files = folder.second.map { it.name to it.uri }
                if (folderName != null) name = folderName
                if (typeTouched) {
                    parts = mapDetectedParts(modelType, files)
                    error = if (SherpaModel.planFiles(modelType, files.map { it.first }) == null) {
                        context.getString(R.string.sherpa_folder_unknown)
                    } else {
                        null
                    }
                } else {
                    when (val detection = SherpaModel.detect(files.map { it.first }, folderName)) {
                        is SherpaModel.Detection.Detected -> {
                            modelType = detection.modelType
                            parts = mapDetectedParts(detection.modelType, files)
                            detectedType = detection.modelType
                            ambiguousTypes = null
                            showFamilyPicker = false
                        }
                        is SherpaModel.Detection.Ambiguous -> {
                            modelType = detection.candidates.first()
                            parts = mapDetectedParts(modelType, files)
                            detectedType = null
                            ambiguousTypes = detection.candidates
                            showFamilyPicker = true
                        }
                        SherpaModel.Detection.Unknown -> {
                            modelType = SherpaModel.TYPE_DEFAULT
                            parts = emptyMap()
                            detectedType = null
                            ambiguousTypes = null
                            error = context.getString(R.string.sherpa_folder_unknown)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("SherpaImport", "Folder scan failed", e)
                error = context.getString(R.string.sherpa_folder_failed)
            } finally {
                scanning = false
            }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) scanFolder(uri)
    }
    val roles = SherpaModel.requiredRoles(modelType)
    val pickers = roles.associateWith { role ->
        when (role) {
            SherpaModel.ROLE_MODEL -> pickModel
            SherpaModel.ROLE_DECODER -> pickDecoder
            SherpaModel.ROLE_JOINER -> pickJoiner
            SherpaModel.ROLE_MERGED_DECODER -> pickMergedDecoder
            SherpaModel.ROLE_TOKENS -> pickTokens
            else -> pickEncoder
        }
    }
    val slotLabels = roles.associateWith { role ->
        stringResource(
            when (role) {
                SherpaModel.ROLE_MODEL -> R.string.sherpa_slot_model
                SherpaModel.ROLE_ENCODER -> R.string.sherpa_slot_encoder
                SherpaModel.ROLE_DECODER -> R.string.sherpa_slot_decoder
                SherpaModel.ROLE_JOINER -> R.string.sherpa_slot_joiner
                SherpaModel.ROLE_MERGED_DECODER -> R.string.sherpa_slot_merged_decoder
                else -> R.string.sherpa_slot_tokens
            }
        )
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.sherpa_add_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = {
                        name = it
                        error = null
                        detectSelectedParts()
                    }, singleLine = true,
                    label = { Text(stringResource(R.string.sherpa_name_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                ExposedDropdownMenuBox(
                    expanded = typeMenuExpanded,
                    onExpandedChange = { typeMenuExpanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = typeLabel(modelType),
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.sherpa_type_label)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(typeMenuExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false }
                    ) {
                        typeOptions.forEach { (type, labelRes) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(labelRes)) },
                                onClick = { selectType(type) }
                            )
                        }
                    }
                }
                detectedType?.let { detected ->
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.sherpa_detected, typeLabel(detected)),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { detectedType = null }) {
                                Text(stringResource(R.string.sherpa_change))
                            }
                        }
                    }
                }
                if (ambiguousTypes != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.sherpa_detection_ambiguous),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showFamilyPicker = true }) {
                            Text(stringResource(R.string.sherpa_choose_type))
                        }
                    }
                }
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
                        .clickable(enabled = !scanning && !busy) { pickFolder.launch(null) }
                ) {
                    Text(
                        stringResource(if (scanning) R.string.sherpa_scanning else R.string.sherpa_folder_button),
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
                        TextButton(
                            enabled = !scanning && !busy,
                            onClick = { pickers.getValue(role).launch(arrayOf("*/*")) }
                        ) {
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
            TextButton(
                enabled = !busy && !scanning,
                onClick = {
                    if (ambiguousTypes != null) {
                        error = context.getString(R.string.sherpa_need_type)
                        return@TextButton
                    }
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
                    onConfirm(name.trim(), modelType, parsed, parts) { failure ->
                        if (failure == null) {
                            onDismiss()
                        } else {
                            error = context.getString(
                                R.string.sherpa_import_failed,
                                failure.message ?: failure.javaClass.simpleName
                            )
                        }
                    }
                }
            ) { Text(stringResource(R.string.sherpa_add_confirm)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
    if (showFamilyPicker && ambiguousTypes != null) {
        AlertDialog(
            onDismissRequest = { showFamilyPicker = false },
            title = { Text(stringResource(R.string.sherpa_choose_detected)) },
            text = { Text(stringResource(R.string.sherpa_detection_ambiguous)) },
            confirmButton = {
                Column {
                    ambiguousTypes?.forEach { type ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { selectType(type) }
                        ) {
                            Text(typeLabel(type), modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showFamilyPicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun typeLabel(type: String): String = stringResource(
    typeOptions.firstOrNull { it.first == type }?.second ?: R.string.sherpa_type_default
)
