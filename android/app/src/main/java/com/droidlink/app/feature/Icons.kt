package com.droidlink.app.feature

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.Base64
import androidx.core.graphics.drawable.toBitmap
import java.io.ByteArrayOutputStream

object Icons {
    fun pngBase64(drawable: Drawable, size: Int): String = encode(drawable.toBitmap(size, size), Bitmap.CompressFormat.PNG, 100)

    /** Scales so the longer side is [maxSide] and encodes as JPEG (album art). */
    fun jpegBase64(bitmap: Bitmap, maxSide: Int): String {
        val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            bitmap
        }
        return encode(scaled, Bitmap.CompressFormat.JPEG, 82)
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(format, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
