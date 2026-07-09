package org.scrib.transcriber

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ModelManager {

    private const val MODEL_NAME = "ggml-tiny-q5_1.bin"
    private const val MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin"
    private const val MIN_VALID_SIZE = 1_000_000L

    fun modelFile(context: Context): File = File(context.getExternalFilesDir(null), MODEL_NAME)

    fun isAvailable(context: Context): Boolean {
        val file = modelFile(context)
        return file.exists() && file.length() > MIN_VALID_SIZE
    }

    @Synchronized
    fun ensureModel(context: Context): File {
        val file = modelFile(context)
        if (file.exists() && file.length() > MIN_VALID_SIZE) {
            return file
        }
        download(MODEL_URL, file)
        return file
    }

    private fun download(url: String, dest: File) {
        dest.parentFile?.mkdirs()
        val temp = File(dest.absolutePath + ".part")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = true
        try {
            connection.inputStream.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        if (temp.length() <= MIN_VALID_SIZE) {
            temp.delete()
            throw RuntimeException("Downloaded model is too small")
        }
        if (dest.exists()) {
            dest.delete()
        }
        if (!temp.renameTo(dest)) {
            temp.copyTo(dest, overwrite = true)
            temp.delete()
        }
    }
}
