package org.scrib.transcriber

import android.util.Log

object StateLog {

    private const val TAG = "scrib.state"

    fun log(message: String) {
        if (BuildConfig.DEBUG) {
            Log.i(TAG, message)
        }
    }
}
