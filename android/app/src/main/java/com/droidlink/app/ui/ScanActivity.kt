package com.droidlink.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.droidlink.app.feature.FileSender
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scan to Mac: point the phone, and it lands on the Mac.
 * - Document: Google's scanner (edges, straightening, clean-up), as a PDF the Mac opens.
 * - Text: a photo read on the phone; after a look, the Mac pastes it where its cursor is.
 * - Photo: pasted into the app in front on the Mac (and left on its clipboard).
 * Opened with [EXTRA_MODE] (from the Mac's "Scan with phone"), it goes straight to that mode.
 */
class ScanActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MODE = "mode"

        fun open(ctx: Context, mode: String?) = ctx.startActivity(
            Intent(ctx, ScanActivity::class.java).putExtra(EXTRA_MODE, mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Text read from a photo, shown for trimming before it goes. */
    private var recognized by mutableStateOf<String?>(null)
    private var busy by mutableStateOf<String?>(null)
    private var photoMode = "photo"
    private var photoFile: File? = null

    private val scanner = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val pdf = scan?.pdf
        if (pdf == null) {
            finishIfDirect()
            return@registerForActivityResult
        }
        sendDocument(pdf.uri)
    }

    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = photoFile
        if (!taken || file == null) {
            finishIfDirect()
            return@registerForActivityResult
        }
        if (photoMode == "text") readText(file) else sendPhoto(file)
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePhoto(photoMode) else toast("AyPhone needs the camera for this")
    }

    private var direct = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AyPhoneTheme {
                Box(Modifier.fillMaxSize().background(BackdropBrush).safeDrawingPadding()) { Content() }
            }
        }
        if (savedInstanceState == null) {
            val mode = intent.getStringExtra(EXTRA_MODE)
            direct = mode != null
            mode?.let(::start)
        }
    }

    private fun finishIfDirect() {
        if (direct && recognized == null) finish()
    }

    private fun start(mode: String) {
        if (Link.state.value != Link.State.Connected) toast("Not connected to your Mac")
        when (mode) {
            "document" -> scanDocument()
            "text", "photo" -> takePhoto(mode)
        }
    }

    private fun scanDocument() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { scanner.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { toast("The document scanner isn't available on this phone") }
    }

    private fun takePhoto(mode: String) {
        photoMode = mode
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        val dir = File(cacheDir, "scans").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "scan-${System.currentTimeMillis()}.jpg")
        photoFile = file
        camera.launch(FileProvider.getUriForFile(this, "$packageName.files", file))
    }

    private fun sendDocument(uri: Uri) {
        val name = "Scan " + SimpleDateFormat("yyyy-MM-dd 'at' HH.mm.ss", Locale.getDefault()).format(Date()) + ".pdf"
        val size = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        val input = contentResolver.openInputStream(uri) ?: return
        FileSender.send(listOf(FileSender.Outgoing(name, size, "application/pdf", input)), glow = true, open = true)
        toast("Sending the scan to your Mac")
        finish()
    }

    private fun readText(file: File) {
        busy = "Reading the text…"
        lifecycleScope.launch {
            val text = try {
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(InputImage.fromFilePath(this@ScanActivity, Uri.fromFile(file))).await().text
            } catch (e: Exception) {
                ""
            }
            file.delete()
            busy = null
            if (text.isBlank()) {
                toast("No text found. Try again closer, in good light.")
                finishIfDirect()
            } else {
                recognized = text
            }
        }
    }

    private fun sendPhoto(file: File) {
        busy = "Sending to your Mac…"
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { shrink(file) }
            file.delete()
            Link.send(msg("insertImage").put("data", data).put("mime", "image/jpeg"))
            toast("Pasting on your Mac")
            finish()
        }
    }

    /** At most 2400 px on the longest side, as JPEG (base64). */
    private fun shrink(file: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2400) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        val rotated = rotateUpright(file, bitmap)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 88, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** Camera photos are stored sideways with an orientation tag; turn the pixels instead. */
    private fun rotateUpright(file: File, bitmap: Bitmap): Bitmap {
        val degrees = when (androidx.exifinterface.media.ExifInterface(file.path)
            .getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    @Composable
    private fun Content() {
        val text = recognized
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text(if (text != null) "Text from the photo" else "Scan to Mac", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                busy ?: if (text != null) "Trim it if you like. It's pasted where your Mac's cursor is." else "Point your phone; it lands on your Mac.",
                style = MaterialTheme.typography.bodyMedium, color = Secondary
            )
            Spacer(Modifier.height(20.dp))
            if (text != null) {
                OutlinedTextField(
                    value = text, onValueChange = { recognized = it },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Paste on Mac", Icons.AutoMirrored.Filled.Send) {
                    Link.send(msg("insertText").put("text", text))
                    toast("Pasting on your Mac")
                    finish()
                }
            } else if (busy == null) {
                Mode("Document", "Pages, receipts, whiteboards. Opens on your Mac as a PDF.", Icons.Filled.Description) { direct = false; start("document") }
                Spacer(Modifier.height(12.dp))
                Mode("Text", "Printed text, read on your phone and pasted where you're typing.", Icons.Filled.TextFields) { direct = false; start("text") }
                Spacer(Modifier.height(12.dp))
                Mode("Photo", "Pasted into the app in front, like Figma.", Icons.Filled.PhotoCamera) { direct = false; start("photo") }
            }
        }
    }

    @Composable
    private fun Mode(title: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.07f))
                .clickable(onClick = onClick).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(icon, null, tint = Glow, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Secondary)
            }
        }
    }
}
