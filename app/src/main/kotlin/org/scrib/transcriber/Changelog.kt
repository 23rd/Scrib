package org.scrib.transcriber

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object Changelog {

    data class Release(val version: String, val lines: List<String>)

    private const val PREFS = "whatsnew"
    private const val KEY_SEEN = "seenVersion"

    fun load(context: Context): List<Release> {
        val releases = mutableListOf<Release>()
        var version: String? = null
        var lines = mutableListOf<String>()
        context.resources.openRawResource(R.raw.changelog).bufferedReader().useLines { all ->
            for (raw in all) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> {}
                    line.startsWith("#") -> {
                        version?.let { releases.add(Release(it, lines)) }
                        version = line.drop(1).trim()
                        lines = mutableListOf()
                    }
                    else -> lines.add(line)
                }
            }
        }
        version?.let { releases.add(Release(it, lines)) }
        return releases
    }

    fun shouldAnnounce(context: Context): Boolean {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        if (info.firstInstallTime == info.lastUpdateTime) {
            return false
        }
        return seen(context) != BuildConfig.VERSION_NAME
    }

    fun markAnnounced(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SEEN, BuildConfig.VERSION_NAME)
            .apply()
    }

    private fun seen(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SEEN, null)
}
