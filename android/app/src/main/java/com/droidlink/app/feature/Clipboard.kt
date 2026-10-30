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
        ActivityLog.add("Clipboard from Mac", text.take(60))
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

    /** The last image the Mac put on this clipboard, so it isn't sent straight back. */
    @Volatile private var lastImageFromMac: android.net.Uri? = null

    /** An image copied on the Mac: offered to other apps through a FileProvider URI. */
    fun setImageFromMac(ctx: Context, base64: String, mime: String) {
        val bytes = try { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) } catch (_: Exception) { return }
        val dir = java.io.File(ctx.cacheDir, "clipboard").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = java.io.File(dir, "mac-${System.currentTimeMillis()}.${if (mime == "image/jpeg") "jpg" else "png"}")
        file.writeBytes(bytes)
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        lastImageFromMac = uri
        lastSetFromMacAt = SystemClock.elapsedRealtime()
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(ctx.contentResolver, "Image from Mac", uri))
        ActivityLog.add("Image copied from Mac", file.name)
    }

    /** An image on this clipboard, made small enough to send (longest side 3000 px). */
    private fun imageFromClip(ctx: Context, uri: android.net.Uri): Pair<String, String>? = try {
        val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val mime = ctx.contentResolver.getType(uri) ?: "image/png"
        if (raw.size <= 6 * 1024 * 1024 && (mime == "image/png" || mime == "image/jpeg")) {
            android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP) to mime
        } else {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
            val scale = minOf(1f, 3000f / maxOf(bitmap.width, bitmap.height))
            val scaled = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP) to "image/jpeg"
        }
    } catch (e: Exception) {
        Log.w("AyPhone", "couldn't read the clipboard image: $e")
        null
    }

    /** Reading only works while one of our activities has focus (Android 10+). */
    fun readAndSend(ctx: Context, skipEcho: Boolean = false): Boolean {
        val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip ?: return false
        if (clip.itemCount == 0) return false
        // An image (a screenshot, a photo copied from the gallery): send the picture itself.
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null && clip.description.hasMimeType("image/*")) {
            if (skipEcho && uri == lastImageFromMac) return false
            val (data, mime) = imageFromClip(ctx, uri) ?: return false
            Link.send(msg("clipboardImage").put("data", data).put("mime", mime))
            ActivityLog.add("Image sent to Mac")
            return true
        }
        val text = clip.getItemAt(0).coerceToText(ctx)?.toString().orEmpty()
        if (text.isEmpty()) return false
        // Automatic mode: don't bounce the Mac's own text straight back to it.
        if (skipEcho && text == lastFromMac) return false
        Link.send(msg("clipboard").put("text", text))
        ActivityLog.add("Clipboard sent to Mac", text.take(60))
        return true
    }
}
