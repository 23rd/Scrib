package org.scrib.transcriber

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.ITranscriptionService
import org.opentranscribe.api.ITranscriptionSession
import org.opentranscribe.api.ITranscriptionStream
import org.opentranscribe.api.StreamRequest
import org.opentranscribe.api.TranscriberCapabilities
import org.opentranscribe.api.TranscriptionRequest
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TranscriptionService : Service() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // Streams run on their own threads, so they need cancelling by hand when the service goes away.
    private val streams = Collections.synchronizedSet(mutableSetOf<AudioStream>())

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

        override fun openStream(
            request: StreamRequest?,
            callback: ITranscriptionCallback?
        ): ITranscriptionStream? {
            if (callback == null) {
                return null
            }
            val stream = TranscriptionEngine.get(applicationContext).openStream(request, callback)
            streams.add(stream)
            stream.start { streams.remove(stream) }
            return object : ITranscriptionStream.Stub() {
                override fun write(pcm: ByteArray?, length: Int) {
                    if (pcm != null) {
                        stream.write(pcm, length)
                    }
                }

                override fun endOfStream() {
                    stream.endOfStream()
                }

                override fun cancel() {
                    stream.cancel()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        synchronized(streams) {
            streams.forEach { it.cancel() }
            streams.clear()
        }
        executor.shutdownNow()
        super.onDestroy()
    }
}
