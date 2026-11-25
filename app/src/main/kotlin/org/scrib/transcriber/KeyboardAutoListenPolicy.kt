package org.scrib.transcriber

import android.text.InputType
import android.view.inputmethod.EditorInfo

internal object KeyboardAutoListenPolicy {
    // Don't infer safety from a missing or unknown editor descriptor. Only these known text
    // variations can auto-start; number/PIN, phone, datetime and every password type are manual.
    fun allowsEditor(inputType: Int?, imeOptions: Int, hasConnection: Boolean): Boolean {
        if (!hasConnection || inputType == null ||
            imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0 ||
            inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT
        ) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_NORMAL,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> true
            else -> false
        }
    }
    // Callers can only supply a local factory. Recheck after resolving it in case the user
    // switched to an endpoint while the selected local engine was being resolved.
    fun <Engine> localEngine(
        localModels: Boolean,
        endpointActive: () -> Boolean,
        selectedLocal: () -> Engine?
    ): Engine? {
        if (!localModels || endpointActive()) return null
        val engine = selectedLocal() ?: return null
        return engine.takeUnless { endpointActive() }
    }
}
