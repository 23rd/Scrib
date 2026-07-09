package org.scrib.transcriber

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TranscriptionService : Service() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val binder = object : ITranscriptionService.Stub() {
        override fun transcribe(
            audio: ParcelFileDescriptor?,
            fileName: String?,
            languageHint: String?,
            callback: ITranscriptionCallback?
        ) {
            if (callback == null || audio == null) {
                try {
                    audio?.close()
                } catch (ignore: Exception) {
                }
                return
            }
            executor.execute {
                val engine = TranscriptionEngine.get(applicationContext)
                engine.transcribe(audio, fileName, languageHint, callback)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
