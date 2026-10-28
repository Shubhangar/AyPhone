package com.droidlink.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.droidlink.app.feature.FileSender
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg

/** Share sheet target: links open on the Mac, text goes to its clipboard, files go to Downloads. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Link.isConnected) {
            toast("Not connected to your Mac")
            finish()
            return
        }
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            val files = FileSender.prepare(this, uris)
            FileSender.send(files)
            toast(if (files.size == 1) "Sending ${files[0].name} to Mac" else "Sending ${files.size} files to Mac")
        } else {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            when {
                text.isEmpty() -> toast("Nothing to send")
                text.startsWith("http") && Patterns.WEB_URL.matcher(text).matches() -> {
                    Link.send(msg("url").put("url", text))
                    toast("Opening on your Mac")
                }
                else -> {
                    Link.send(msg("clipboard").put("text", text))
                    toast("Copied to your Mac's clipboard")
                }
            }
        }
        finish()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
