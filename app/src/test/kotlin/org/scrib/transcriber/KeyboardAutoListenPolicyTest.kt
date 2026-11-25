package org.scrib.transcriber

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardAutoListenPolicyTest {
    private val text = InputType.TYPE_CLASS_TEXT

    @Test fun regularTextAndMessagesAreAllowed() {
        assertTrue(KeyboardAutoListenPolicy.allowsEditor(text, 0, true))
        assertTrue(KeyboardAutoListenPolicy.allowsEditor(
            text or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            EditorInfo.IME_FLAG_NO_EXTRACT_UI, true
        ))
    }

    @Test fun noneOfThePasswordVariationsCanAutoStart() {
        for (variation in listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )) {
            assertFalse(KeyboardAutoListenPolicy.allowsEditor(text or variation, 0, true))
        }
        assertFalse(KeyboardAutoListenPolicy.allowsEditor(
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0, true
        ))
    }

    @Test fun absentPrivacySensitiveAndUnknownEditorsStayManual() {
        assertFalse(KeyboardAutoListenPolicy.allowsEditor(null, 0, true))
        assertFalse(KeyboardAutoListenPolicy.allowsEditor(InputType.TYPE_NULL, 0, true))
        assertFalse(KeyboardAutoListenPolicy.allowsEditor(text, 0, false))
        assertFalse(KeyboardAutoListenPolicy.allowsEditor(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, true))
        assertFalse(KeyboardAutoListenPolicy.allowsEditor(text or 0x00000ff0, 0, true))
        assertFalse(KeyboardAutoListenPolicy.allowsEditor(InputType.TYPE_CLASS_PHONE, 0, true))
        assertFalse(KeyboardAutoListenPolicy.allowsEditor(InputType.TYPE_CLASS_DATETIME, 0, true))
    }

    @Test fun activeEndpointAndEndpointOnlyBuildNeverResolveAnEngine() {
        assertNull(KeyboardAutoListenPolicy.localEngine(true, { true }) { error("no route") })
        assertNull(KeyboardAutoListenPolicy.localEngine(false, { false }) { error("no route") })
    }

    @Test fun endpointActivationDuringResolutionDiscardsTheLocalRoute() {
        var active = false
        assertNull(KeyboardAutoListenPolicy.localEngine(true, { active }) {
            active = true
            Any()
        })
    }

    @Test fun onlyTheSelectedReadyLocalEngineCanBeReturned() {
        val local = Any()
        assertSame(local, KeyboardAutoListenPolicy.localEngine(true, { false }) { local })
        assertNull(KeyboardAutoListenPolicy.localEngine(true, { false }) { null })
    }
}
