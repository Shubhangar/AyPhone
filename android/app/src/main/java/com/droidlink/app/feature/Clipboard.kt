package com.droidlink.app.feature

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.droidlink.app.ui.ClipboardActivity
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg

object ClipboardBridge {
    @Volatile var lastSetFromMacAt = 0L
        private set
    @Volatile private var lastFromMac: String? = null

    /** Writing the clipboard is allowed from the background. */
    fun setFromMac(ctx: Context, text: String) {
        if (text.isEmpty()) return
        lastFromMac = text
        lastSetFromMacAt = SystemClock.elapsedRealtime()
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("From Mac", text))
    }

    /**
     * The Mac asked for the clipboard (⌘V on the Mac, or ⌃⌥V). Briefly opens the invisible
     * [ClipboardActivity], which may read it because it has focus; "Display over other apps"
     * is what allows starting it from the background. No accessibility service or adb needed.
     */
    fun sendOnRequest(ctx: Context) {
        val reason = when {
            PhoneScreen.current(ctx) != "unlocked" -> "locked"
            !Settings.canDrawOverlays(ctx) -> "overlay"
            else -> null
        }
        if (reason != null) {
            Link.send(msg("clipboardUnavailable").put("action", reason))
            return
        }
        try {
            ctx.startActivity(
                Intent(ctx, ClipboardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(ClipboardActivity.EXTRA_REQUESTED, true)
            )
        } catch (e: Exception) {
            Log.w("AyPhone", "could not open clipboard reader: $e")
            Link.send(msg("clipboardUnavailable").put("action", "failed"))
        }
    }

    /** Reading only works while one of our activities has focus (Android 10+). */
    fun readAndSend(ctx: Context, skipEcho: Boolean = false): Boolean {
        val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip ?: return false
        if (clip.itemCount == 0) return false
        val text = clip.getItemAt(0).coerceToText(ctx)?.toString().orEmpty()
        if (text.isEmpty()) return false
        // Automatic mode: don't bounce the Mac's own text straight back to it.
        if (skipEcho && text == lastFromMac) return false
        Link.send(msg("clipboard").put("text", text))
        return true
    }
}
