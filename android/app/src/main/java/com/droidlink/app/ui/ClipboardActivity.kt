package com.droidlink.app.ui

import android.app.Activity
import android.widget.Toast
import com.droidlink.app.feature.ClipboardBridge
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg

/** Invisible activity: Android only lets a focused app read the clipboard. */
class ClipboardActivity : Activity() {
    companion object {
        /** Opened by [com.droidlink.app.feature.ClipboardWatcher] rather than by a tap. */
        const val EXTRA_AUTOMATIC = "automatic"
        /** Opened because the Mac asked for the clipboard. */
        const val EXTRA_REQUESTED = "requested"
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val automatic = intent.getBooleanExtra(EXTRA_AUTOMATIC, false)
        if (intent.getBooleanExtra(EXTRA_REQUESTED, false)) {
            if (!ClipboardBridge.readAndSend(this)) Link.send(msg("clipboardUnavailable").put("action", "empty"))
        } else if (automatic) {
            if (Link.isConnected) ClipboardBridge.readAndSend(this, skipEcho = true)
        } else {
            val message = when {
                !Link.isConnected -> "Not connected to your Mac"
                ClipboardBridge.readAndSend(this) -> "Clipboard sent to Mac"
                else -> "Clipboard is empty"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
