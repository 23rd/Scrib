package org.scrib.transcriber

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.whispercpp.whisper.WhisperContext
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var modelsContainer: LinearLayout
    private lateinit var status: TextView

    private val downloading = HashMap<String, Boolean>()
    private val progressViews = HashMap<String, TextView>()
    private val lastPercent = HashMap<String, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        root.addView(title(getString(R.string.app_name)))
        root.addView(
            hint(
                "Transcription runs fully offline on this device. Standard models are multilingual " +
                    "(they differ by size); \"English\" models are English-only but smaller and faster. " +
                    "For non-English speech (e.g. Russian) pick Base or larger — Tiny is weak."
            )
        )

        modelsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(modelsContainer)

        root.addView(wideButton("Add model from HuggingFace…") { promptCustomUrl() })
        root.addView(wideButton("Import .bin from device…") { pickLocalModel() })

        status = TextView(this).apply { setPadding(0, dp(16), 0, 0) }
        root.addView(status)

        root.addView(divider())
        root.addView(wideButton("Self-test (jfk.wav)") { runSelfTest() })

        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        modelsContainer.removeAllViews()
        progressViews.clear()
        val active = ModelManager.activeFileName(this)

        modelsContainer.addView(sectionHeader("Standard models"))
        for (model in ModelCatalog.MODELS) {
            modelsContainer.addView(modelRow(model, active))
        }

        val custom = ModelManager.installedCustomFileNames(this)
        if (custom.isNotEmpty()) {
            modelsContainer.addView(sectionHeader("Custom models"))
            for (fileName in custom) {
                modelsContainer.addView(customRow(fileName, active))
            }
        }
    }

    private fun modelRow(model: WhisperModel, active: String?): View {
        val installed = ModelManager.isInstalled(this, model)
        val isActive = installed && model.fileName == active
        val subtitle = buildString {
            append(if (model.multilingual) "multilingual" else "English only")
            if (model.approxBytes > 0) append(" · ≈${model.approxBytes / 1_000_000} MB")
            if (model.recommended) append(" · recommended")
        }
        return row(model.fileName, model.displayName, subtitle, installed, isActive, model)
    }

    private fun customRow(fileName: String, active: String?): View {
        val isActive = fileName == active
        val subtitle =
            (if (ModelCatalog.isEnglishOnly(fileName)) "English only" else "multilingual") + " · custom"
        val display = fileName.removePrefix("ggml-").removeSuffix(".bin")
        return row(fileName, display, subtitle, installed = true, isActive = isActive, model = null)
    }

    private fun row(
        fileName: String,
        displayName: String,
        subtitle: String,
        installed: Boolean,
        isActive: Boolean,
        model: WhisperModel?
    ): View {
        val rowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(TextView(this).apply {
            text = displayName
            textSize = 16f
            if (isActive) setTypeface(typeface, Typeface.BOLD)
        })
        val statusText = TextView(this).apply {
            text = if (isActive) "$subtitle · active" else subtitle
            textSize = 12f
            alpha = 0.7f
        }
        left.addView(statusText)
        rowView.addView(left)

        if (downloading.containsKey(fileName)) {
            progressViews[fileName] = statusText
            statusText.text = "Downloading… ${lastPercent[fileName] ?: 0}%"
            rowView.addView(smallButton("Cancel") { downloading[fileName] = true })
        } else if (!installed && model != null) {
            rowView.addView(smallButton("Download") { startDownload(model) })
        } else if (installed) {
            if (!isActive) {
                rowView.addView(smallButton("Use") {
                    ModelManager.setActive(this, fileName)
                    render()
                })
            }
            rowView.addView(smallButton("Delete") { confirmDelete(fileName) })
        }
        return rowView
    }

    private fun startDownload(model: WhisperModel) {
        if (downloading.containsKey(model.fileName)) {
            return
        }
        downloading[model.fileName] = false
        lastPercent[model.fileName] = 0
        status.text = ""
        render()
        thread {
            val error = try {
                ModelManager.download(
                    this,
                    model,
                    onProgress = { done, total ->
                        val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                        if (pct != lastPercent[model.fileName]) {
                            lastPercent[model.fileName] = pct
                            runOnUiThread {
                                progressViews[model.fileName]?.text =
                                    if (pct >= 0) "Downloading… $pct%" else "Downloading… ${done / 1_000_000} MB"
                            }
                        }
                    },
                    isCancelled = { downloading[model.fileName] == true }
                )
                null
            } catch (e: ModelManager.CancelledDownloadException) {
                CANCELLED
            } catch (e: Throwable) {
                e.message ?: "error"
            }
            runOnUiThread {
                downloading.remove(model.fileName)
                lastPercent.remove(model.fileName)
                status.text = if (error != null && error != CANCELLED) "Download failed: $error" else ""
                render()
            }
        }
    }

    private fun promptCustomUrl() {
        val input = EditText(this).apply { hint = "https://huggingface.co/…/ggml-….bin" }
        AlertDialog.Builder(this)
            .setTitle("Model from HuggingFace")
            .setMessage("Paste a direct link to a .bin ggml model (any whisper.cpp-compatible model).")
            .setView(input)
            .setPositiveButton("Download") { _, _ ->
                val model = try {
                    ModelManager.customModelFromUrl(input.text.toString())
                } catch (e: Exception) {
                    status.text = e.message ?: "Invalid link"
                    null
                }
                if (model != null) {
                    startDownload(model)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickLocalModel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK) {
            return
        }
        val uri = data?.data ?: return
        val name = queryDisplayName(uri)
        status.text = "Importing…"
        thread {
            val error = try {
                ModelManager.importFromUri(this, uri, name)
                null
            } catch (e: Throwable) {
                e.message ?: "error"
            }
            runOnUiThread {
                status.text = if (error == null) "" else "Import failed: $error"
                render()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun confirmDelete(fileName: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete model?")
            .setMessage(fileName)
            .setPositiveButton("Delete") { _, _ ->
                ModelManager.delete(this, fileName)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runSelfTest() {
        status.text = "Running…"
        thread {
            val result = try {
                val model = ModelManager.activeModelFile(this)
                if (model == null) {
                    "No active model — download one above."
                } else {
                    val context = WhisperContext.createContextFromFile(model.absolutePath)
                    val audio = assets.open("jfk.wav").use { WavDecoder.decode(it) }
                    val text = context.transcribeData(audio, "en")
                    context.release()
                    "OK · $text"
                }
            } catch (e: Throwable) {
                "ERROR: ${e.javaClass.simpleName}: ${e.message}"
            }
            runOnUiThread { status.text = result }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 22f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        alpha = 0.75f
        setPadding(0, 0, 0, dp(8))
    }

    private fun sectionHeader(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        alpha = 0.6f
        setPadding(0, dp(16), 0, dp(2))
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(20)
            bottomMargin = dp(8)
        }
        setBackgroundColor(Color.parseColor("#33808080"))
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        setPadding(dp(14), dp(6), dp(14), dp(6))
        setOnClickListener { onClick() }
    }

    private fun wideButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private companion object {
        const val REQUEST_IMPORT = 1001
        const val CANCELLED = "__cancelled__"
    }
}
