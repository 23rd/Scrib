package org.scrib.transcriber

import android.content.Context

internal enum class AppThemeMode(val storageValue: String, val title: Int) {
    SYSTEM("system", R.string.theme_system),
    LIGHT("light", R.string.theme_light),
    DARK("dark", R.string.theme_dark),
    BLACK("black", R.string.theme_black);

    fun usesDarkColors(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK, BLACK -> true
    }

    companion object {
        fun fromStorage(value: String?): AppThemeMode = entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

internal object AppThemePreferences {
    const val KEY = "theme"

    fun get(context: Context) = context.getSharedPreferences("app_appearance", Context.MODE_PRIVATE)

    fun set(context: Context, mode: AppThemeMode) {
        val editor = get(context).edit()
        if (mode == AppThemeMode.SYSTEM) editor.remove(KEY) else editor.putString(KEY, mode.storageValue)
        editor.apply()
    }
}
