package com.droidlink.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.droidlink.app.Notifications
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg
import com.droidlink.app.service.MirrorService

/** Invisible screen that shows Android's "Start sharing?" prompt, then hands off to [MirrorService]. */
class MirrorActivity : Activity() {
    companion object {
        private const val REQUEST = 1
        private const val EXTRA_MAX_SIZE = "maxSize"

        /** Called when the Mac asks to mirror. */
        fun request(ctx: Context, maxSize: Int) {
            if (MirrorService.instance != null) {
                // Already running (e.g. the Mac window was reopened): just resend a full picture.
                Link.send(msg("mirrorState").put("state", "running"))
                MirrorService.instance?.requestKeyFrame()
                return
            }
            val intent = Intent(ctx, MirrorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_MAX_SIZE, maxSize)
            Link.send(msg("mirrorState").put("state", "requesting"))
            // Showing the prompt from the background needs "Display over other apps";
            // otherwise a notification asks the user to tap.
            if (Settings.canDrawOverlays(ctx)) {
                try {
                    ctx.startActivity(intent)
                    return
                } catch (_: Exception) {}
            }
            Notifications.showMirrorPrompt(ctx, intent)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), REQUEST)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, MirrorService::class.java)
                        .putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(MirrorService.EXTRA_DATA, data)
                        .putExtra(MirrorService.EXTRA_MAX_SIZE, intent.getIntExtra(EXTRA_MAX_SIZE, 1280))
                )
            } else {
                Link.send(msg("mirrorState").put("state", "denied"))
            }
        }
        finish()
    }
}
