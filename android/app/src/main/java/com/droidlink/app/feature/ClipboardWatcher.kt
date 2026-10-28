package com.droidlink.app.feature

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.droidlink.app.link.Link
import com.droidlink.app.ui.ClipboardActivity

/**
 * Automatic phone→Mac clipboard (the same approach KDE Connect uses).
 *
 * Android 10+ won't let background apps read the clipboard, but it still *tries* to notify
 * our clip-changed listener and logs "Denying clipboard access to <our package>" when it
 * refuses. With READ_LOGS (granted once over adb from the Mac) we watch for that line and
 * then briefly bring up the invisible [ClipboardActivity], which may read the clip because
 * it has focus. "Display over other apps" is what allows that background activity start.
 */
object ClipboardWatcher {
    private const val TAG = "AyPhone"

    @Volatile private var process: Process? = null
    private var thread: Thread? = null
    private var lastLaunch = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Registering is what makes the system log the denial we watch for. */
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { }

    fun isAvailable(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED &&
            Settings.canDrawOverlays(ctx)

    @Synchronized
    fun start(ctx: Context) {
        if (thread != null || !isAvailable(ctx)) return
        val app = ctx.applicationContext
        app.getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(listener)
        val needle = "clipboard access to ${app.packageName}"
        thread = Thread({
            try {
                // -T 1: start at the newest line instead of replaying the whole buffer.
                val p = ProcessBuilder("logcat", "-T", "1", "*:E").redirectErrorStream(true).start()
                process = p
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (line.contains(needle, ignoreCase = true)) onCopyDetected(app)
                }
            } catch (e: Exception) {
                Log.w(TAG, "clipboard watcher stopped: $e")
            }
        }, "clipboard-watcher").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop(ctx: Context) {
        ctx.applicationContext.getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(listener)
        process?.destroy()
        process = null
        thread = null
    }

    /** Something was copied (seen in the log, or by the control service): read it shortly. */
    fun copyDetected(ctx: Context) {
        // Give the app a moment to finish writing the clip before reading it.
        handler.postDelayed({ onCopyDetected(ctx.applicationContext) }, 250)
    }

    private fun onCopyDetected(ctx: Context) {
        if (!Settings.canDrawOverlays(ctx)) return // needed to open the reader from the background
        val now = SystemClock.elapsedRealtime()
        // Skip duplicates, our own writes of text that came from the Mac, and when nobody is listening.
        if (now - lastLaunch < 800 || now - ClipboardBridge.lastSetFromMacAt < 1_500 || !Link.isConnected) return
        lastLaunch = now
        try {
            ctx.startActivity(
                Intent(ctx, ClipboardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(ClipboardActivity.EXTRA_AUTOMATIC, true)
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not open clipboard reader: $e")
        }
    }
}
