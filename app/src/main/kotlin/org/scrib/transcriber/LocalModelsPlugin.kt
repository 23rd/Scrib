package org.scrib.transcriber

import android.content.Context

// The local models: the engine, the catalog and the files they are downloaded to. All of it lives
// behind this interface, because Scrib Remote ships none of it — no inference library, no model
// list, no downloader — and still has to compile the same screens.
interface LocalModelsPlugin {

    fun engine(context: Context): TranscriptionEngine

    // Whether the silence-skipping model is worth handing to this file: a family that finds the
    // speech in the audio itself has no use for a second model doing the same job in front of it.
    fun usesVadModel(modelPath: String): Boolean

    // Blocking: the caller keeps it off the main thread.
    fun selfTest(context: Context): SelfTestResult
}

data class SelfTestResult(val message: String, val failed: Boolean)

object LocalModelsPlugins {

    val plugin: LocalModelsPlugin? by lazy {
        try {
            Class.forName("org.scrib.transcriber.LocalModelsPluginImpl")
                .getDeclaredConstructor().newInstance() as LocalModelsPlugin
        } catch (e: Throwable) {
            null
        }
    }
}
