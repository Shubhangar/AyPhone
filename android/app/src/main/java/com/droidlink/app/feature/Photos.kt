package com.droidlink.app.feature

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** The phone's latest photos for the Mac's Photos page, and new screenshots sent automatically. */
object Photos {
    private const val PREFS = "settings"

    private fun allowed(ctx: Context) = ContextCompat.checkSelfPermission(
        ctx, if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

    fun screenshotSync(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("screenshotSync", true)

    fun setScreenshotSync(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("screenshotSync", on).apply()

    /** The newest [limit] photos with small thumbnails (240 px). */
    fun listMessage(ctx: Context, limit: Int = 60): JSONObject {
        val m = msg("photos").put("on", screenshotSync(ctx))
        if (!allowed(ctx)) return m.put("action", "permission").put("photos", JSONArray())
        val list = JSONArray()
        val resolver = ctx.contentResolver
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.RELATIVE_PATH
        )
        try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext() && list.length() < limit) {
                    val id = c.getLong(0)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val thumb = try {
                        val bitmap = resolver.loadThumbnail(uri, Size(240, 240), null)
                        val out = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 72, out)
                        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    } catch (_: Exception) {
                        continue
                    }
                    list.put(
                        JSONObject()
                            .put("id", id.toString())
                            .put("name", c.getString(1) ?: "photo")
                            .put("date", c.getLong(2) * 1000)
                            .put("thumb", thumb)
                            .put("screenshot", (c.getString(3) ?: "").contains("Screenshot", ignoreCase = true))
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("AyPhone", "listing photos failed: $e")
        }
        return m.put("photos", list)
    }

    /** Sends the full photo, like any file. */
    fun send(ctx: Context, id: String) {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toLongOrNull() ?: return)
        FileSender.send(FileSender.prepare(ctx, listOf(uri)))
    }

    // MARK: New screenshots

    private var observer: ContentObserver? = null
    private var lastSent = 0L

    /** Watches for new screenshots and sends each one to the Mac (while connected and turned on). */
    fun watch(ctx: Context) {
        if (observer != null || !allowed(ctx)) return
        val app = ctx.applicationContext
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (!Link.isConnected || !screenshotSync(app)) return
                newestScreenshot(app)?.let { id ->
                    if (id == lastSent) return
                    lastSent = id
                    ActivityLog.add("Screenshot sent to Mac")
                    send(app, id.toString())
                }
            }
        }
        app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o)
        observer = o
    }

    fun stop(ctx: Context) {
        observer?.let { ctx.applicationContext.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    /** A screenshot taken in the last 10 seconds, once Android has finished writing it. */
    private fun newestScreenshot(ctx: Context): Long? = try {
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DATE_ADDED),
            "${MediaStore.Images.Media.IS_PENDING} = 0 AND ${MediaStore.Images.Media.DATE_ADDED} >= ?",
            arrayOf((System.currentTimeMillis() / 1000 - 10).toString()),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            if (c.moveToFirst() && (c.getString(1) ?: "").contains("Screenshot", ignoreCase = true)) c.getLong(0) else null
        }
    } catch (_: Exception) {
        null
    }
}
