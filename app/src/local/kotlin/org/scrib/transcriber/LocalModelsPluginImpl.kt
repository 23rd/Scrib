package org.scrib.transcriber

import android.content.Context

class LocalModelsPluginImpl : LocalModelsPlugin {

    @Volatile
    private var engine: TranscriptionEngine? = null

    @Synchronized
    override fun engine(context: Context): TranscriptionEngine =
        engine ?: GgmlTranscriptionEngine(context.applicationContext).also { engine = it }

    override fun usesVadModel(modelPath: String): Boolean = TranscribeModel.usesVad(modelPath)

    override fun selfTest(context: Context): SelfTestResult {
        val active = ModelManager.activeModelFile(context)
            ?: return SelfTestResult(context.getString(R.string.status_selftest_needs_model), true)
        return try {
            val model = TranscribeModel.load(active.absolutePath)
            try {
                val session = model.openSession(CpuConfig.preferredThreadCount)
                try {
                    val samples = context.assets.open("jfk.wav").use { WavDecoder.decode(it) }
                    val pcm = pcmBuffer(samples)
                    session.run(pcm, samples.size, "en", null, false, null)
                    val text = session.fullText.orEmpty()
                    SelfTestResult(context.getString(R.string.status_selftest_ok, text.trim()), false)
                } finally {
                    session.release()
                }
            } finally {
                model.release()
            }
        } catch (e: Throwable) {
            SelfTestResult(context.getString(R.string.status_selftest_failed, e.message ?: ""), true)
        }
    }
}
