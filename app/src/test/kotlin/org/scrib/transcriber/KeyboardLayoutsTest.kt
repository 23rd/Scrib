package org.scrib.transcriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutsTest {

    @Test
    fun testEnterKeyIsTwoCharacterToken() {
        assertEquals(2, KeyboardLayouts.ENTER_KEY.length)
        assertFalse(KeyboardLayouts.ENTER_KEY.contains('\n'))
        assertEquals('\\', KeyboardLayouts.ENTER_KEY[0])
        assertEquals('n', KeyboardLayouts.ENTER_KEY[1])
    }

    @Test
    fun testInsertTextMapsEnterKeyToRealNewline() {
        val text = KeyboardLayouts.insertText(KeyboardLayouts.ENTER_KEY)
        assertEquals("\n", text)
        assertEquals(1, text.length)
    }

    @Test
    fun testInsertTextPassesOtherKeysVerbatim() {
        assertEquals(" ", KeyboardLayouts.insertText(KeyboardLayouts.SPACE_KEY))
        assertEquals("hello", KeyboardLayouts.insertText("hello"))
        assertEquals("?!", KeyboardLayouts.insertText("?!"))
        assertEquals("", KeyboardLayouts.insertText(""))
    }

    @Test
    fun testNormalizeCustomKeysKeepsWhitespaceOnlyKey() {
        assertEquals(listOf("   "), KeyboardLayouts.normalizeCustomKeys(listOf("   ")))
        assertEquals(listOf(" "), KeyboardLayouts.normalizeCustomKeys(listOf(" ", " ")))
    }

    @Test
    fun testNormalizeCustomKeysStripsRealLineBreaksFromEntries() {
        // Newlines cannot survive the record separator, so they are stripped out of an entry.
        assertEquals(listOf("ab"), KeyboardLayouts.normalizeCustomKeys(listOf("a\r\nb")))
        // An entry made of nothing else is dropped.
        assertEquals(listOf("ok"), KeyboardLayouts.normalizeCustomKeys(listOf("\r\n", "ok")))
    }

    @Test
    fun testNormalizeCustomKeysDedupesAndCaps() {
        assertEquals(listOf(".", "?"), KeyboardLayouts.normalizeCustomKeys(listOf(".", ".", "?", ".")))
        assertEquals(64, KeyboardLayouts.normalizeCustomKeys(listOf("x".repeat(70))).first().length)
        assertEquals(
            KeyboardLayouts.MAX_CUSTOM_KEYS,
            KeyboardLayouts.normalizeCustomKeys((0 until 60).map { "key$it" }).size
        )
    }

    @Test
    fun testSavedCustomKeysRoundTrip() {
        val keys = listOf(" ", KeyboardLayouts.ENTER_KEY, ".")
        val saved = KeyboardLayouts.normalizeCustomKeys(keys).joinToString("\n")
        assertTrue(saved.contains(KeyboardLayouts.ENTER_KEY))
        assertEquals(keys, KeyboardLayouts.parseCustomKeys(saved))
    }
}
