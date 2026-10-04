package org.scrib.transcriber

import android.content.Context
import com.whispercpp.whisper.WhisperContext

class LocalModelsPluginImpl : LocalModelsPlugin {

    @Volatile
    private var whisper: TranscriptionEngine? = null

    @Synchronized
    override fun engine(context: Context): TranscriptionEngine =
        whisper ?: WhisperTranscriptionEngine(context.applicationContext).also { whisper = it }

    override fun isParakeetModel(modelPath: String): Boolean = WhisperContext.isParakeetModel(modelPath)

    override fun selfTest(context: Context): SelfTestResult {
        val active = ModelManager.activeModelFile(context)
            ?: return SelfTestResult(context.getString(R.string.status_selftest_needs_model), true)
        return try {
            val whisper = WhisperContext.createContextFromFile(active.absolutePath)
            val audio = context.assets.open("jfk.wav").use { WavDecoder.decode(it) }
            val text = whisper.transcribeData(audio, "en")
            whisper.release()
            SelfTestResult(context.getString(R.string.status_selftest_ok, text.trim()), false)
        } catch (e: Throwable) {
            SelfTestResult(context.getString(R.string.status_selftest_failed, e.message ?: ""), true)
        }
    }
}
