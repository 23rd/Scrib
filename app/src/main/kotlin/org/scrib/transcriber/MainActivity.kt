package org.scrib.transcriber

import android.app.Activity
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.whispercpp.whisper.WhisperContext
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val info = TextView(this).apply {
            text = getString(R.string.main_status)
        }
        val downloadButton = Button(this).apply {
            text = "Download model"
            setOnClickListener { downloadModel() }
        }
        val wavButton = Button(this).apply {
            text = "Self-test: jfk.wav (WAV)"
            setOnClickListener { runSelfTest(opus = false) }
        }
        val opusButton = Button(this).apply {
            text = "Self-test: jfk.ogg (Opus decode)"
            setOnClickListener { runSelfTest(opus = true) }
        }
        status = TextView(this).apply {
            setPadding(0, 48, 0, 0)
            text = "Model exists=${modelFile().exists()}"
        }
        root.addView(info)
        root.addView(downloadButton)
        root.addView(wavButton)
        root.addView(opusButton)
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun downloadModel() {
        status.text = "Downloading model…"
        thread {
            val message = try {
                val file = ModelManager.ensureModel(this)
                "Model ready · ${file.length() / 1_000_000} MB"
            } catch (e: Throwable) {
                "Download failed: ${e.message}"
            }
            runOnUiThread { status.text = message }
        }
    }

    private fun modelFile(): File = File(getExternalFilesDir(null), "ggml-tiny-q5_1.bin")

    private fun opusFile(): File = File(getExternalFilesDir(null), "jfk.ogg")

    private fun runSelfTest(opus: Boolean) {
        status.text = "Running…"
        thread {
            val result = try {
                val model = modelFile()
                if (!model.exists()) {
                    "Model not found:\n${model.absolutePath}"
                } else {
                    val context = WhisperContext.createContextFromFile(model.absolutePath)
                    val audio = if (opus) {
                        val file = opusFile()
                        if (!file.exists()) throw RuntimeException("jfk.ogg not found: ${file.absolutePath}")
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                            .use { AudioDecoder.decodeToPcm16kMono(it) }
                    } else {
                        assets.open("jfk.wav").use { WavDecoder.decode(it) }
                    }
                    val started = System.currentTimeMillis()
                    val text = context.transcribeData(audio, "en")
                    val elapsed = System.currentTimeMillis() - started
                    context.release()
                    "OK · ${if (opus) "Opus" else "WAV"} · ${audio.size} samples · ${elapsed}ms\n\n$text"
                }
            } catch (e: Throwable) {
                "ERROR: ${e.javaClass.simpleName}: ${e.message}"
            }
            runOnUiThread { status.text = result }
        }
    }
}
