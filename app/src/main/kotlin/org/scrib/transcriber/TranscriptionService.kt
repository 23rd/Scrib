package org.scrib.transcriber

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.ITranscriptionService
import org.opentranscribe.api.ITranscriptionSession
import org.opentranscribe.api.TranscriberCapabilities
import org.opentranscribe.api.TranscriptionRequest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TranscriptionService : Service() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val binder = object : ITranscriptionService.Stub() {

        override fun getCapabilities(): TranscriberCapabilities =
            TranscriptionEngine.get(applicationContext).capabilities()

        override fun transcribe(
            audio: ParcelFileDescriptor?,
            request: TranscriptionRequest?,
            callback: ITranscriptionCallback?
        ): ITranscriptionSession? {
            if (callback == null || audio == null) {
                try {
                    audio?.close()
                } catch (ignore: Exception) {
                }
                return null
            }
            val cancellation = CancellationToken()
            executor.execute {
                TranscriptionEngine.get(applicationContext).transcribe(audio, request, callback, cancellation)
            }
            return object : ITranscriptionSession.Stub() {
                override fun cancel() {
                    cancellation.cancel()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
