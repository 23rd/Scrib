package org.scrib.transcriber

import android.app.Activity
import android.os.Bundle
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
        val button = Button(this).apply {
            text = "Run whisper self-test (jfk.wav)"
            setOnClickListener { runSelfTest() }
        }
        status = TextView(this).apply {
            setPadding(0, 48, 0, 0)
            text = "Model: ${modelFile().absolutePath}\nexists=${modelFile().exists()}"
        }
        root.addView(info)
        root.addView(button)
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun modelFile(): File = File(getExternalFilesDir(null), "ggml-tiny-q5_1.bin")

    private fun runSelfTest() {
        status.text = "Running…"
        thread {
            val result = try {
                val model = modelFile()
                if (!model.exists()) {
                    "Model not found:\n${model.absolutePath}"
                } else {
                    val context = WhisperContext.createContextFromFile(model.absolutePath)
                    val audio = assets.open("jfk.wav").use { WavDecoder.decode(it) }
                    val started = System.currentTimeMillis()
                    val text = context.transcribeData(audio, "en")
                    val elapsed = System.currentTimeMillis() - started
                    context.release()
                    "OK · ${audio.size} samples · ${elapsed}ms\n\n$text"
                }
            } catch (e: Throwable) {
                "ERROR: ${e.javaClass.simpleName}: ${e.message}"
            }
            runOnUiThread { status.text = result }
        }
    }
}
