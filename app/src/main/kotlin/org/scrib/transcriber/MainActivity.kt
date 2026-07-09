package org.scrib.transcriber

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val textView = TextView(this).apply {
            text = getString(R.string.main_status)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(64, 128, 64, 64)
        }
        setContentView(textView)
    }
}
