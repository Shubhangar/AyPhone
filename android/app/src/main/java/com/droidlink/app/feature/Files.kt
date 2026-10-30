package com.droidlink.app.feature

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import com.droidlink.app.Notifications
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Files from the Mac land in Downloads/AyPhone. Called only from the socket reader thread. */
object FileReceiver {
    /** [glow]: the Mac sent it from its Files option, so the transfer glow plays here. */
    private class Incoming(val uri: Uri, val out: OutputStream, val name: String, val mime: String, val glow: Boolean)

    private val incoming = HashMap<String, Incoming>()

    fun handle(ctx: Context, m: JSONObject) {
        val id = m.optString("id")
        val resolver = ctx.contentResolver
        when (m.optString("type")) {
            "fileOffer" -> {
                val name = m.optString("name", "file").substringAfterLast('/').ifEmpty { "file" }
                val mime = m.optString("mime", "application/octet-stream")
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AyPhone")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                val out = resolver.openOutputStream(uri)?.buffered(256 * 1024) ?: return
                incoming[id] = Incoming(uri, out, name, mime, m.optBoolean("glow"))
            }
            "fileChunk" -> incoming[id]?.out?.write(Base64.decode(m.optString("data"), Base64.DEFAULT))
            "fileEnd" -> {
                val f = incoming.remove(id) ?: return
                f.out.close()
                resolver.update(f.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                Notifications.showFile(ctx, f.uri, f.name, f.mime)
                ActivityLog.add("Received from Mac", f.name)
                if (f.glow) com.droidlink.app.ui.TransferGlow.show(ctx, receiving = true)
            }
        }
    }

    /** Deletes half-received files after a disconnect. */
    fun abortAll(ctx: Context) {
        for (f in incoming.values) {
            try { f.out.close() } catch (_: Exception) {}
            try { ctx.contentResolver.delete(f.uri, null, null) } catch (_: Exception) {}
        }
        incoming.clear()
    }
}

object FileSender {
    private const val CHUNK = 192 * 1024

    class Outgoing(val name: String, val size: Long, val mime: String, val input: InputStream)

    /**
     * Opens the streams right away, while we still hold the URI grant from the share
     * sheet or picker; the transfer itself then runs in the background.
     */
    fun prepare(ctx: Context, uris: List<Uri>): List<Outgoing> = uris.mapNotNull { uri ->
        try {
            val resolver = ctx.contentResolver
            var name = uri.lastPathSegment ?: "file"
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            val input = resolver.openInputStream(uri) ?: return@mapNotNull null
            Outgoing(name, size, resolver.getType(uri) ?: "application/octet-stream", input)
        } catch (e: Exception) {
            Log.w("AyPhone", "cannot open $uri: $e")
            null
        }
    }

    /** [glow] for sends from the Files option (the Files tile or widget), which play the transfer
     *  glow here and on the Mac; photos and screenshots go quietly. */
    fun send(files: List<Outgoing>, glow: Boolean = false) {
        if (files.isEmpty()) return
        if (glow) com.droidlink.app.ui.TransferGlow.show(Link.context, receiving = false)
        Link.scope.launch {
            for (f in files) {
                try {
                    f.input.use { sendOne(f, it, glow) }
                } catch (e: Exception) {
                    Log.w("AyPhone", "sending ${f.name} failed: $e")
                }
            }
        }
    }

    private fun sendOne(f: Outgoing, input: InputStream, glow: Boolean) {
        val id = UUID.randomUUID().toString()
        Link.sendNow(msg("fileOffer").put("id", id).put("name", f.name).put("size", f.size).put("mime", f.mime).put("glow", glow))
        val buf = ByteArray(CHUNK)
        var seq = 0
        while (true) {
            // Fill the buffer so chunks stay a predictable size.
            var n = 0
            while (n < CHUNK) {
                val r = input.read(buf, n, CHUNK - n)
                if (r < 0) break
                n += r
            }
            if (n == 0) break
            Link.sendNow(
                msg("fileChunk").put("id", id).put("seq", seq++)
                    .put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP))
            )
            if (n < CHUNK) break
        }
        Link.sendNow(msg("fileEnd").put("id", id))
        ActivityLog.add("Sent to Mac", f.name)
    }
}
