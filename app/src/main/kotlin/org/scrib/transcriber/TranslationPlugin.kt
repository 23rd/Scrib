package org.scrib.transcriber

import android.content.Context

data class TranslationResult(val text: String, val tokens: Int)

interface TranslationEngine {

    // blocking, streams partials, throws CancelledException
    fun translate(
        modelPath: String,
        text: String,
        sourceLanguage: String?,
        targetLanguage: String,
        cancellation: CancellationToken,
        onPartial: (String) -> Unit
    ): TranslationResult

    // asks the model which langauge it is, null if unknown
    fun detectLanguage(modelPath: String, text: String, cancellation: CancellationToken): String?

    // frees the model, blocks while translating
    fun releaseModel()
}

// behind an interface so Scrib Remote still compiles without it
interface TranslationPlugin {

    fun engine(context: Context): TranslationEngine
}

object TranslationPlugins {

    val plugin: TranslationPlugin? by lazy {
        try {
            Class.forName("org.scrib.transcriber.TranslationPluginImpl")
                .getDeclaredConstructor().newInstance() as TranslationPlugin
        } catch (e: Throwable) {
            null
        }
    }
}
