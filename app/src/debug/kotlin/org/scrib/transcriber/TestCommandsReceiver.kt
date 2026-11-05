package org.scrib.transcriber

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.ITranscriptionService
import org.opentranscribe.api.TranscriptionRequest

class TestCommandsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val uid = sentFromUid
            if (uid != Process.SHELL_UID && uid != Process.ROOT_UID && uid != Process.myUid()) {
                log("dropped: sender uid $uid is not shell")
                return
            }
        }
        val app = context.applicationContext
        when (intent.action?.removePrefix(ACTION_PREFIX) ?: return) {
            "STATE" -> reportState(app)
            "OPEN_TAB" -> openTab(app, intent)
            "TRANSCRIBE" -> startTranscription(app, intent)
            "IMPORT_MODEL" -> importModel(app, intent)
            "API_TRANSCRIBE" -> apiTranscribe(app, intent)
            else -> log("unknown command ${intent.action}")
        }
    }

    private fun reportState(context: Context) {
        log("pkg=${BuildConfig.APPLICATION_ID}")
        log("active_model=${ModelManager.activeDisplayName(context) ?: "none"}")
        log("language=${ModelManager.language(context) ?: "auto"}")
        log("installed_models=${ModelManager.installedFileNames(context).joinToString(",")}")
        val run = TranscriptionRun.state.value
        log(
            if (run == null) "transcription=idle"
            else "transcription=file=${run.fileName} running=${run.running} percent=${run.percent}" +
                " error=${run.error} text=${run.text.take(120)}"
        )
        log("history_entries=${TranscriptHistory.load(context).size}")
    }

    private fun openTab(context: Context, intent: Intent) {
        val tab = intent.getStringExtra("tab") ?: run { log("open_tab: no tab extra"); return }
        val activity = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_OPEN_TAB, tab)
        try {
            context.startActivity(activity)
            log("open_tab: $tab")
        } catch (e: Exception) {
            log("open_tab refused (app in background?): ${e.message}")
        }
    }

    private fun startTranscription(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path") ?: run { log("transcribe: no path extra"); return }
        if (!ModelManager.hasActiveModel(context)) {
            log("transcribe refused: no active model")
            return
        }
        val (uri, name) = try {
            resolveAudio(context, path)
        } catch (e: Throwable) {
            log("transcribe refused: ${e.message}")
            return
        }
        val ephemeral = intent.getBooleanExtra("ephemeral", true)
        // Absent or negative extras mean the whole file; a span with nothing in it is refused.
        val range = run {
            val startMs = intent.getLongExtra("start_ms", -1L)
            val endMs = intent.getLongExtra("end_ms", -1L)
            if (startMs >= 0 && endMs > startMs) {
                AudioRange(startMs, endMs)
            } else {
                if (startMs >= 0 || endMs >= 0) {
                    log("transcribe: ignoring invalid range start=$startMs end=$endMs")
                }
                null
            }
        }
        val started = if (ephemeral) {
            TranscriptionRun.startEphemeral(context, uri, name, uri, range = range)
        } else {
            TranscriptionRun.start(context, uri, name, uri, range = range)
        }
        log(
            if (started) "transcribe started: $name ($uri)${if (ephemeral) " [ephemeral]" else ""}" +
                (range?.let { " range=${it.startMs}-${it.endMs}ms" } ?: "")
            else "transcribe refused: busy"
        )
        if (started) {
            try {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun resolveAudio(context: Context, raw: String): Pair<Uri, String> {
        if (!raw.startsWith("/android_asset/")) {
            val uri = toUri(raw)
            return uri to (uri.lastPathSegment ?: "test-audio")
        }
        val assetPath = raw.removePrefix("/android_asset/")
        val out = File(context.cacheDir, assetPath.substringAfterLast('/'))
        context.assets.open(assetPath).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(out) to out.name
    }

    private fun importModel(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path") ?: run { log("import: no path extra"); return }
        val pending = goAsync()
        executor.execute {
            try {
                val name = ModelManager.importFromUri(context, toUri(path), intent.getStringExtra("name"))
                log("import done: $name")
            } catch (e: Throwable) {
                log("import failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun toUri(raw: String): Uri = when {
        raw.startsWith("content://") || raw.startsWith("file://") -> Uri.parse(raw)
        raw.startsWith("/android_asset/") -> Uri.parse("file:///android_asset/${raw.removePrefix("/android_asset/")}")
        else -> Uri.fromFile(File(raw))
    }

    // Binds to another app's service as a genuine cross-process client and hands it a range
    // request, so the AIDL surface is exercised through real Binder marshalling. Extras: target
    // (package), path (a file inside THIS app's own dirs), start_ms, end_ms. Every callback lands
    // on the scrib.state tag; the terminal one unbinds and closes the descriptor.
    private fun apiTranscribe(context: Context, intent: Intent) {
        val target = intent.getStringExtra("target") ?: run { log("api: no target extra"); return }
        val path = intent.getStringExtra("path") ?: run { log("api: no path extra"); return }
        val startMs = intent.getLongExtra("start_ms", 0L)
        val endMs = intent.getLongExtra("end_ms", 0L)
        val file = File(path)
        if (!file.exists()) {
            log("api: no such file: $path")
            return
        }
        var session: ParcelFileDescriptor? = null
        var connection: ServiceConnection? = null
        fun settle() {
            runCatching { session?.close() }
            connection?.let { runCatching { context.unbindService(it) } }
        }
        val callback = object : ITranscriptionCallback.Stub() {
            override fun onTranscriptionProgress(text: String?) {
                log("api: progress: ${text?.take(60)}")
            }

            override fun onTranscriptionResult(text: String?) {
                log("api: result: ${text?.take(160)}")
                settle()
            }

            override fun onTranscriptionSegment(startMs: Long, endMs: Long, text: String?) {
                log("api: segment $startMs-$endMs: $text")
            }

            override fun onTranscriptionError(error: org.opentranscribe.api.TranscriptionError?) {
                log("api: error type=${error?.type} message=${error?.message}")
                settle()
            }
        }
        val serviceIntent = Intent("org.opentranscribe.api.ITranscriptionService").setPackage(target)
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try {
                    val service = ITranscriptionService.Stub.asInterface(binder)
                    log("api: contractVersion=${service.capabilities.contractVersion}")
                    val request = TranscriptionRequest()
                    request.fileName = file.name
                    request.startMs = startMs
                    request.endMs = endMs
                    session = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    service.transcribe(session, request, callback)
                } catch (e: Exception) {
                    log("api: failed: $e")
                    settle()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
            }
        }
        if (!context.bindService(serviceIntent, connection!!, Context.BIND_AUTO_CREATE)) {
            log("api: cannot bind to $target")
        }
    }

    companion object {
        const val ACTION_PREFIX = "org.scrib.transcriber.test."
        const val TAG = "scrib.state"

        private val executor = Executors.newSingleThreadExecutor()

        fun log(message: String) = Log.i(TAG, message)
    }
}
