package org.scrib.transcriber

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

class SherpaPluginImpl : SherpaPlugin {

    override val modelId: String = SherpaModel.ID

    override fun selectedId(context: Context): String? {
        val app = context.applicationContext
        migrateLegacy(app)
        val stored = ModelManager.storedActiveName(app)
        return if (stored == SherpaModel.ID) stored else null
    }

    override fun engine(context: Context): TranscriptionEngine =
        SherpaTranscriptionEngine.getInstance(context.applicationContext)

    override fun hasActiveModel(context: Context): Boolean =
        SherpaModel.isInstalled(context.applicationContext)

    override fun displayName(context: Context): String? =
        if (selectedId(context.applicationContext) != null) SherpaModel.DISPLAY_NAME else null

    override fun displayNameFor(context: Context, id: String): String? =
        if (id == SherpaModel.ID) SherpaModel.DISPLAY_NAME else null

    override fun deleteModel(context: Context, id: String): Boolean {
        if (id != SherpaModel.ID) {
            return false
        }
        val app = context.applicationContext
        SherpaModel.dir(app).deleteRecursively()
        ModelManager.clearActiveSelection(app, SherpaModel.ID)
        return true
    }

    @Synchronized
    override fun downloadModel(
        context: Context,
        id: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean
    ) {
        require(id == SherpaModel.ID)
        val app = context.applicationContext
        migrateLegacy(app)
        val dir = SherpaModel.dir(app)
        val total = SherpaModel.TOTAL_BYTES
        var done = SherpaModel.FILES.sumOf { file ->
            val f = File(dir, file.name)
            if (f.exists() && f.length() == file.sizeBytes && sha256(f) == file.sha256) file.sizeBytes else 0L
        }
        onProgress(done, total)
        for (file in SherpaModel.FILES) {
            val dest = File(dir, file.name)
            if (dest.exists() && dest.length() == file.sizeBytes && sha256(dest) == file.sha256) {
                continue
            }
            val base = done
            ModelManager.downloadUrl(SherpaModel.urlFor(file.name), dest, { downloaded, _ ->
                onProgress(base + downloaded, total)
            }, isCancelled, MIN_VALID_PART_SIZE)
            done = base + dest.length()
            if (dest.length() != file.sizeBytes || sha256(dest) != file.sha256) {
                dest.delete()
                throw RuntimeException("Downloaded ${file.name} failed verification")
            }
            onProgress(done, total)
        }
        if (ModelManager.activeFileName(app) == null) {
            ModelManager.setActive(app, SherpaModel.ID)
        }
    }

    override fun modelRow(
        context: Context,
        activeId: String?,
        progress: Int,
        downloading: Boolean,
        failed: Boolean
    ): ModelRow {
        val app = context.applicationContext
        migrateLegacy(app)
        val state = when {
            downloading -> RowState.Downloading
            failed -> RowState.Failed
            SherpaModel.isInstalled(app) ->
                if (activeId == SherpaModel.ID) RowState.Active else RowState.Installed
            else -> RowState.NotDownloaded
        }
        return ModelRow(
            id = SherpaModel.ID,
            name = SherpaModel.DISPLAY_NAME,
            badge = app.getString(R.string.badge_russian_only),
            multilingual = false,
            sizeMb = (SherpaModel.TOTAL_BYTES / 1_000_000).toInt(),
            tier = 5,
            recommended = false,
            custom = false,
            state = state,
            progress = progress
        )
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
        row: ModelRow,
        onDownload: (String) -> Unit,
        onCancel: (String) -> Unit,
        onUse: (String) -> Unit,
        onDelete: (String) -> Unit,
        onRequestDelete: (String) -> Unit
    ) {
        val cs = MaterialTheme.colorScheme
        Text(
            stringResource(R.string.other_engines), fontSize = 13.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp, color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 10.dp)
        )
        ModelRowCard(
            row,
            Actions(
                onDownload = onDownload, onCancel = onCancel, onUse = onUse, onDelete = onDelete,
                onAddUrl = {}, onImport = { _, _ -> }, onSelfTest = {}
            ),
            onRequestDelete = onRequestDelete
        )
    }

    private fun migrateLegacy(app: Context) {
        if (ModelManager.storedActiveName(app) == LEGACY_ID) {
            ModelManager.setActive(app, SherpaModel.ID)
        }
        val old = File(ModelManager.modelsDir(app), LEGACY_ID)
        val dir = SherpaModel.dir(app)
        if (old.exists() && !File(dir, SherpaModel.ENCODER).exists()) {
            if (!old.renameTo(dir)) {
                old.copyRecursively(dir, overwrite = true)
                old.deleteRecursively()
            }
        }
    }

    private fun sha256(file: File): String? = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val MIN_VALID_PART_SIZE = 1_000L
        const val LEGACY_ID = "gigaam-v3-e2e-rnnt"
    }
}
