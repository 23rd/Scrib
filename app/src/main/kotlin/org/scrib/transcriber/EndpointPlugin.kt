package org.scrib.transcriber

import android.content.Context
import androidx.compose.runtime.Composable

interface EndpointPlugin {

    fun isActive(context: Context): Boolean

    fun engine(context: Context): TranscriptionEngine

    fun isConfigured(context: Context): Boolean

    fun host(context: Context): String

    fun modelName(context: Context): String

    fun setActive(context: Context, active: Boolean)

    suspend fun fetchModels(context: Context): Result<List<String>>

    suspend fun testConnection(context: Context): Result<String>

    @Composable
    fun SettingsBlocks(onChanged: () -> Unit)
}

object EndpointPlugins {

    @Volatile
    private var resolved: Boolean = false

    private var cached: EndpointPlugin? = null

    val plugin: EndpointPlugin?
        get() {
            if (!resolved) {
                synchronized(this) {
                    if (!resolved) {
                        cached = try {
                            Class.forName("org.scrib.transcriber.EndpointPluginImpl")
                                .getDeclaredConstructor().newInstance() as EndpointPlugin
                        } catch (e: Throwable) {
                            null
                        }
                        resolved = true
                    }
                }
            }
            return cached
        }
}
