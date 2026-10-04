package org.scrib.transcriber

import android.content.Context

// The Whisper models: the engine, the catalog and the files they are downloaded to. All of it
// lives behind this interface, because Scrib Remote ships none of it — no libwhisper.so, no model
// list, no downloader — and still has to compile the same screens.
interface LocalModelsPlugin {

    fun engine(context: Context): TranscriptionEngine

    // A parakeet file segments itself, so it does not want the VAD model in front of it.
    fun isParakeetModel(modelPath: String): Boolean

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
