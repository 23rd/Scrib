package org.scrib.transcriber

import android.content.Context

object SectionState {

    private const val PREFS = "sections"

    private const val KEY_STANDARD = "standardModels"
    private const val KEY_SHERPA = "sherpaModels"
    private const val KEY_CUSTOM = "customModels"
    private const val KEY_ENDPOINT = "remoteEndpoint"

    fun standardModels(context: Context): Boolean = read(context, KEY_STANDARD, true)

    fun setStandardModels(context: Context, expanded: Boolean) = write(context, KEY_STANDARD, expanded)

    fun sherpaModels(context: Context): Boolean = read(context, KEY_SHERPA, true)

    fun setSherpaModels(context: Context, expanded: Boolean) = write(context, KEY_SHERPA, expanded)

    fun customModels(context: Context): Boolean = read(context, KEY_CUSTOM, true)

    fun setCustomModels(context: Context, expanded: Boolean) = write(context, KEY_CUSTOM, expanded)

    fun remoteEndpoint(context: Context): Boolean = read(context, KEY_ENDPOINT, true)

    fun setRemoteEndpoint(context: Context, expanded: Boolean) = write(context, KEY_ENDPOINT, expanded)

    private fun read(context: Context, key: String, fallback: Boolean): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key, fallback)

    private fun write(context: Context, key: String, expanded: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, expanded)
            .apply()
    }
}
