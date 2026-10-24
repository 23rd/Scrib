package org.scrib.transcriber

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object AppNav {

    var pendingTab by mutableStateOf<String?>(null)
        private set

    fun openTab(name: String) {
        pendingTab = name
    }

    fun takePendingTab(): String? {
        val value = pendingTab ?: return null
        pendingTab = null
        return value
    }
}
