package org.scrib.transcriber

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable

interface SherpaPlugin {

    fun selectedId(context: Context): String?

    fun engine(context: Context): TranscriptionEngine

    fun hasActiveModel(context: Context): Boolean

    fun displayName(context: Context): String?

    fun displayNameFor(context: Context, id: String): String?

    fun deleteModel(context: Context, id: String): Boolean

    fun importModel(
        context: Context,
        displayName: String,
        modelType: String,
        languages: List<String>?,
        parts: Map<String, Uri>
    ): String

    fun modelRows(context: Context, activeId: String?): List<ModelRow>

    fun selfTestNotice(context: Context): String?

    @Composable
    fun SettingsBlocks(
        rows: List<ModelRow>,
        busy: Boolean,
        onUse: (String) -> Unit,
        onDelete: (String) -> Unit,
        onRequestDelete: (String) -> Unit,
        onImportSherpa: (String, String, List<String>?, Map<String, Uri>) -> Unit
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
