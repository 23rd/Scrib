package org.scrib.transcriber

import android.content.Context
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodSubtype
import android.view.inputmethod.InputMethodManager
import java.util.Locale

private fun shortLayoutLabel(languageTag: String, imeLabel: String): String {
    val parts = languageTag.replace('_', '-').split('-').filter { it.isNotBlank() }
    if (parts.isEmpty()) {
        return imeLabel.take(2).uppercase(Locale.ROOT).ifBlank { "KB" }
    }
    val language = parts[0].uppercase(Locale.ROOT)
    val variant = parts.drop(1).firstOrNull {
        val value = it.uppercase(Locale.ROOT)
        (value.length == 2 && value != language) || value.length == 4
    }?.uppercase(Locale.ROOT)
    return listOfNotNull(language, variant).joinToString("-")
}

data class KeyboardLayoutTarget(
    val imeId: String,
    val subtype: InputMethodSubtype,
    val key: String,
    val languageTag: String,
    val label: String,
    val imeLabel: String
) {
    val shortLabel: String = shortLayoutLabel(languageTag, label)
}

data class KeyboardLayoutOption(
    val key: String,
    val shortLabel: String,
    val label: String,
    val imeLabel: String,
    val languageTag: String,
    val selected: Boolean
)

data class KeyboardLayoutSettings(
    val enabled: Boolean,
    val options: List<KeyboardLayoutOption>
)

object KeyboardLayouts {
    private const val PREFS = "keyboard"
    private const val KEY_ENABLED = "layoutButtonsEnabled"
    private const val KEY_TARGETS = "layoutTargets"
    private const val TARGET_SEPARATOR = "\n"
    private const val KEYBOARD_MODE = "keyboard"
    private const val VOICE_MODE = "voice"

    fun settings(context: Context): KeyboardLayoutSettings {
        val selectedKeys = savedKeys(context)
        val selected = selectedKeys.toSet()
        val available = availableTargets(context)
        val byKey = available.associateBy { it.key }
        val selectedTargets = selectedKeys.mapNotNull { byKey[it] }
        val ordered = selectedTargets + available.filter { it.key !in selected }
        return KeyboardLayoutSettings(
            enabled = savedEnabled(context),
            options = ordered.map { target ->
                KeyboardLayoutOption(
                    key = target.key,
                    shortLabel = target.shortLabel,
                    label = target.label,
                    imeLabel = target.imeLabel,
                    languageTag = target.languageTag,
                    selected = target.key in selected
                )
            }
        )
    }

    fun targets(context: Context): List<KeyboardLayoutTarget> {
        if (!savedEnabled(context)) {
            return emptyList()
        }
        val available = availableTargets(context).associateBy { it.key }
        return savedKeys(context).mapNotNull { available[it] }
    }

    fun save(context: Context, enabled: Boolean, keys: List<String>) {
        val normalized = keys.filter { it.isNotBlank() }.distinct()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_TARGETS, normalized.joinToString(TARGET_SEPARATOR))
            .apply()
    }

    private fun savedEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    private fun savedKeys(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TARGETS, "")
            .orEmpty()
            .split(TARGET_SEPARATOR)
            .filter { it.isNotBlank() }
            .distinct()

    private fun availableTargets(context: Context): List<KeyboardLayoutTarget> {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return emptyList()
        val targets = LinkedHashMap<String, KeyboardLayoutTarget>()
        val inputMethods = runCatching { manager.enabledInputMethodList }.getOrDefault(emptyList())
        for (info in inputMethods) {
            if (info.packageName == context.packageName) {
                continue
            }
            val subtypes = runCatching {
                manager.getEnabledInputMethodSubtypeList(info, true)
            }.getOrDefault(emptyList())
            for (subtype in subtypes) {
                if (!isSelectable(subtype)) {
                    continue
                }
                val target = target(context, info, subtype)
                targets.putIfAbsent(target.key, target)
            }
        }
        return targets.values.sortedWith(
            compareBy<KeyboardLayoutTarget> { it.imeLabel.lowercase(Locale.getDefault()) }
                .thenBy { it.label.lowercase(Locale.getDefault()) }
                .thenBy { it.key }
        )
    }

    private fun isSelectable(subtype: InputMethodSubtype): Boolean {
        if (subtype.isAuxiliary || subtype.getMode() == VOICE_MODE) {
            return false
        }
        val mode = subtype.getMode()
        return mode == KEYBOARD_MODE || mode.isBlank()
    }

    private fun target(context: Context, info: InputMethodInfo, subtype: InputMethodSubtype): KeyboardLayoutTarget {
        val languageTag = languageTag(subtype)
        val imeLabel = runCatching { info.loadLabel(context.packageManager).toString() }
            .getOrDefault(context.packageName)
        val subtypeLabel = runCatching {
            subtype.getDisplayName(context, info.packageName, info.serviceInfo.applicationInfo).toString()
        }.getOrNull()
        val label = if (languageTag.isBlank()) {
            subtypeLabel?.takeIf { it.isNotBlank() } ?: imeLabel
        } else {
            layoutLabel(context, languageTag, imeLabel)
        }
        val key = "${info.id}|${subtype.hashCode()}"
        return KeyboardLayoutTarget(
            imeId = info.id,
            subtype = subtype,
            key = key,
            languageTag = languageTag,
            label = label,
            imeLabel = imeLabel
        )
    }

    @Suppress("DEPRECATION")
    private fun languageTag(subtype: InputMethodSubtype): String {
        val language = subtype.getLanguageTag().orEmpty()
        val locale = subtype.getLocale().orEmpty()
        return (if (language.isBlank() || language.equals("und", ignoreCase = true)) locale else language)
            .replace('_', '-')
    }

    private fun layoutLabel(
        context: Context,
        languageTag: String,
        fallback: String
    ): String {
        if (languageTag.isBlank()) {
            return fallback
        }
        val display = Locale.forLanguageTag(languageTag).getDisplayName(context.resources.configuration.locales[0])
        return display.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) } ?: languageTag
    }

}
