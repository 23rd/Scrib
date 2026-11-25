package org.scrib.transcriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeModeTest {
    @Test fun missingOrUnknownPreferenceUsesSystem() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStorage(null))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStorage("future"))
    }

    @Test fun storedModesRoundTrip() {
        AppThemeMode.entries.forEach { assertEquals(it, AppThemeMode.fromStorage(it.storageValue)) }
    }

    @Test fun onlySystemFollowsSystemContrast() {
        assertFalse(AppThemeMode.SYSTEM.usesDarkColors(false))
        assertTrue(AppThemeMode.SYSTEM.usesDarkColors(true))
        assertFalse(AppThemeMode.LIGHT.usesDarkColors(true))
        assertTrue(AppThemeMode.DARK.usesDarkColors(false))
        assertTrue(AppThemeMode.BLACK.usesDarkColors(false))
    }
}
