package org.scrib.transcriber

import android.content.Context

internal enum class AidlThreads(val count: Int?) {
    AUTO(null), ONE(1), TWO(2), FOUR(4);

    companion object {
        private const val PREFS = "aidl"
        private const val KEY = "inference_threads"

        internal fun fromStoredCount(value: Int): AidlThreads =
            entries.firstOrNull { it.count == value } ?: AUTO

        fun read(context: Context): AidlThreads =
            fromStoredCount(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0))

        fun write(context: Context, value: AidlThreads) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY, value.count ?: 0)
                .apply()
        }
    }
}
