package org.scrib.transcriber

import android.content.Context
import android.content.SharedPreferences

data class EndpointSettings(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val name: String = ""
) {
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    val normalizedBaseUrl: String
        get() {
            var trimmed = baseUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) {
                return ""
            }
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                trimmed = "https://$trimmed"
            }
            trimmed = trimmed.substringBefore("/audio/transcriptions")
            if (!trimmed.endsWith("/v1")) {
                trimmed = "$trimmed/v1"
            }
            return trimmed
        }

    val host: String
        get() = normalizedBaseUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/v1")
            .trimEnd('/')

    val transcriptionsUrl: String get() = "$normalizedBaseUrl/audio/transcriptions"

    val display: String
        get() = name.ifBlank { host }

    companion object {
        const val DEFAULT_MODEL = "whisper-1"

        fun parse(baseUrl: String, apiKey: String, model: String, name: String = ""): EndpointSettings {
            val settings = EndpointSettings(
                baseUrl = baseUrl.trim(),
                name = name.trim(),
                apiKey = apiKey.trim(),
                model = model.trim().ifEmpty { DEFAULT_MODEL }
            )
            require(settings.normalizedBaseUrl.isNotEmpty()) { "Enter the server address" }
            require(settings.normalizedBaseUrl.startsWith("http")) { "The address must start with http" }
            return settings
        }
    }
}

object EndpointStore {

    private const val PREFS = "endpoint"

    private const val KEY_BASE_URL = "baseUrl"
    private const val KEY_API_KEY = "apiKey"
    private const val KEY_MODEL = "model"
    private const val KEY_NAME = "name"
    private const val KEY_ACTIVE = "active"

    fun settings(context: Context): EndpointSettings {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return EndpointSettings(
            baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            model = prefs.getString(KEY_MODEL, EndpointSettings.DEFAULT_MODEL).orEmpty(),
            name = prefs.getString(KEY_NAME, "").orEmpty()
        )
    }

    fun isConfigured(context: Context): Boolean = settings(context).isComplete

    fun isActive(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVE, false) && isConfigured(context)

    fun setActive(context: Context, active: Boolean) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ACTIVE, active).apply()
        if (active) {
            ModelManager.clearStoredActiveSelection(app)
        }
    }

    fun save(context: Context, settings: EndpointSettings) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_MODEL, settings.model)
            .putString(KEY_NAME, settings.name)
            .apply()
    }

    fun forget(context: Context): SharedPreferences.Editor =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_BASE_URL)
            .remove(KEY_API_KEY)
            .remove(KEY_MODEL)
            .remove(KEY_NAME)
            .putBoolean(KEY_ACTIVE, false)
}
