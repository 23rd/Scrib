package org.scrib.transcriber

import android.os.ParcelFileDescriptor

class StubTranscriptionEngine : TranscriptionEngine {

    override fun transcribe(
        audio: ParcelFileDescriptor,
        fileName: String?,
        languageHint: String?,
        callback: ITranscriptionCallback
    ) {
        val size = try {
            audio.statSize
        } catch (e: Exception) {
            -1L
        } finally {
            try {
                audio.close()
            } catch (ignore: Exception) {
            }
        }
        val hint = if (languageHint.isNullOrEmpty()) "auto" else languageHint
        val name = if (fileName.isNullOrEmpty()) "audio" else fileName
        callback.onTranscriptionResult("[offline stub] $name · $size bytes · lang=$hint")
    }
}
