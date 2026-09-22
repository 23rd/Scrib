package org.scrib.transcriber

import android.content.Context
import androidx.compose.runtime.Composable
import java.io.File

interface SherpaPlugin {

    val modelId: String

    fun selectedId(context: Context): String?

    fun engine(context: Context): TranscriptionEngine

    fun hasActiveModel(context: Context): Boolean

    fun displayName(context: Context): String?

    fun displayNameFor(context: Context, id: String): String?

    fun deleteModel(context: Context, id: String): Boolean

    fun downloadModel(
        context: Context,
        id: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean
    )

    fun modelRow(
        context: Context,
        activeId: String?,
        progress: Int,
        downloading: Boolean,
        failed: Boolean
    ): ModelRow?

    fun selfTestNotice(context: Context): String?

    @Composable
    fun SettingsBlocks(
        row: ModelRow,
        onDownload: (String) -> Unit,
        onCancel: (String) -> Unit,
        onUse: (String) -> Unit,
        onDelete: (String) -> Unit,
        onRequestDelete: (String) -> Unit
    )
}

object SherpaPlugins {

    val plugin: SherpaPlugin? by lazy {
        try {
            Class.forName("org.scrib.transcriber.SherpaPluginImpl")
                .getDeclaredConstructor().newInstance() as SherpaPlugin
        } catch (e: Exception) {
            null
        }
    }
}
