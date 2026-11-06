package com.droidlink.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.os.Bundle
import android.util.Base64
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The Pad: one screen, three uses.
 * - Draw: the phone as a drawing tablet for the Mac's screen (pen pressure passed on).
 * - Sign: sign here; Place on Mac pastes it as a clean, transparent image where you're working.
 * - Mark up: ink drawn here floats over the Mac's screen (for presenting or screen sharing).
 */
class PadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AyPhoneTheme {
                Box(Modifier.fillMaxSize().background(BackdropBrush).safeDrawingPadding()) { PadScreen() }
            }
        }
    }

    override fun onDestroy() {
        // Leaving Mark up takes the ink off the Mac's screen.
        Link.send(msg("ink").put("state", "done"))
        super.onDestroy()
    }
}

private class Line(
    val points: MutableList<Offset> = mutableListOf(), val widths: MutableList<Float> = mutableListOf(),
    val color: Color = Color.White, val highlighter: Boolean = false,
    /** Mark up: the same stroke on the Mac (for undo and the eraser). */
    val id: String = java.util.UUID.randomUUID().toString(),
)

@Composable
private fun PadScreen() {
    var mode by rememberSaveable { mutableStateOf("draw") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        val activity = LocalContext.current as android.app.Activity
        // The same header as the Trackpad: back, title, and a switch to the Trackpad.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { activity.finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Pad", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                activity.startActivity(android.content.Intent(activity, TrackpadActivity::class.java))
                activity.finish()
            }) { Icon(Icons.Filled.Mouse, "Trackpad", tint = Color.White) }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(GlassFill)
                .border(1.dp, GlassEdge, RoundedCornerShape(16.dp)).padding(4.dp)
        ) {
            listOf("draw" to "Draw", "sign" to "Sign", "markup" to "Mark up").forEach { (key, label) ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (mode == key) Indigo.copy(alpha = 0.55f) else Color.Transparent)
                        .clickable {
                            if (mode == "markup" && key != "markup") Link.send(msg("ink").put("state", "done"))
                            mode = key
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text(label, fontWeight = FontWeight.SemiBold) }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (mode) {
            "draw" -> DrawPad()
            "sign" -> SignPad()
            else -> MarkUpPad()
        }
    }
}

/** The drawing area takes the shape of the Mac's screen. */
@Composable
private fun ScreenShaped(content: @Composable () -> Unit) {
    val screen by Link.macScreen.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val ratio = screen.first.toFloat() / screen.second.coerceAtLeast(1)
        // Portrait phone, landscape screen: as wide as the phone allows.
        Box(Modifier.fillMaxWidth().aspectRatio(ratio)) { content() }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun DrawPad() {
    val trail = remember { mutableStateListOf<Offset>() }
    var area by remember { mutableStateOf(IntSize(1, 1)) }
    ScreenShaped {
        if (trail.isEmpty()) PadHint(Icons.Filled.Draw, "Draw here, it draws on your Mac", "The same spot as on its screen · S Pen pressure carries over")
        Canvas(
            Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(GlassFill)
                .background(Indigo.copy(alpha = 0.06f)).border(1.dp, GlassEdge, RoundedCornerShape(28.dp))
                .onSizeChanged { area = it }
                .pointerInteropFilter { e ->
                    val w = area.width.toFloat()
                    val h = area.height.toFloat()
                    val points = JSONArray()
                    fun add(x: Float, y: Float, p: Float) {
                        points.put((x / w).coerceIn(0f, 1f).toDouble()).put((y / h).coerceIn(0f, 1f).toDouble()).put(p.toDouble())
                    }
                    val stylus = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                    fun pressure(p: Float) = if (stylus) p.coerceIn(0.05f, 1f) else 1f
                    val state = when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { trail.clear(); "down" }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> "up"
                        else -> "move"
                    }
                    for (i in 0 until e.historySize) add(e.getHistoricalX(i), e.getHistoricalY(i), pressure(e.getHistoricalPressure(i)))
                    add(e.x, e.y, pressure(e.pressure))
                    trail.add(Offset(e.x, e.y))
                    if (trail.size > 60) trail.removeAt(0)
                    Link.send(msg("padPointer").put("state", state).put("points", points))
                    true
                }
        ) {
            if (trail.size > 1) {
                val path = Path().apply { moveTo(trail[0].x, trail[0].y); trail.drop(1).forEach { lineTo(it.x, it.y) } }
                drawPath(path, Cyan.copy(alpha = 0.7f), style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    // The drawing lives in the Mac app, so these press its own Undo and Redo.
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PadButton("Undo", Icons.AutoMirrored.Filled.Undo, Modifier.weight(1f)) { Link.send(msg("padKeys").put("action", "cmd+z")); trail.clear() }
        PadButton("Redo", Icons.AutoMirrored.Filled.Redo, Modifier.weight(1f)) { Link.send(msg("padKeys").put("action", "cmd+shift+z")) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SignPad() {
    val ctx = LocalContext.current
    val lines = remember { mutableStateListOf<Line>() }
    var version by remember { mutableIntStateOf(0) }
    var saved by remember { mutableStateOf(savedSignatures(ctx)) }
    var canvasSize by remember { mutableStateOf(1f to 1f) }
    Canvas(
        Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFFF4F1FA))
            .pointerInteropFilter { e ->
                val stylus = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                val width = 5f * (if (stylus) (0.4f + e.pressure * 1.2f) else 1f)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> lines.add(Line())
                }
                lines.lastOrNull()?.let { line ->
                    for (i in 0 until e.historySize) { line.points.add(Offset(e.getHistoricalX(i), e.getHistoricalY(i))); line.widths.add(width) }
                    line.points.add(Offset(e.x, e.y)); line.widths.add(width)
                }
                version++
                true
            }
    ) {
        canvasSize = size.width to size.height
        @Suppress("UNUSED_EXPRESSION") version
        lines.forEach { line -> drawLine(line, Color(0xFF1B1530)) }
        if (lines.isEmpty()) drawLine(Line(mutableListOf(Offset(48f, size.height - 56f), Offset(size.width - 48f, size.height - 56f)), mutableListOf(2f, 2f)), Color(0x33000000))
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PadButton("Undo", Icons.AutoMirrored.Filled.Undo, Modifier.weight(1f)) { lines.removeLastOrNull(); version++ }
        PadButton("Clear", Icons.Filled.Delete, Modifier.weight(1f)) { lines.clear(); version++ }
        PadButton("Place", Icons.AutoMirrored.Filled.Send, Modifier.weight(1.4f), primary = true) {
            if (lines.isEmpty()) return@PadButton
            val png = signaturePng(lines, canvasSize)
            Link.send(msg("insertImage").put("data", Base64.encodeToString(png, Base64.NO_WRAP)).put("mime", "image/png"))
            saveSignature(ctx, png)
            saved = savedSignatures(ctx)
            Toast.makeText(ctx, "Placing your signature on the Mac", Toast.LENGTH_SHORT).show()
        }
    }
    if (saved.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text("Recent signatures", style = MaterialTheme.typography.labelMedium, color = Secondary)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            saved.forEach { file ->
                val bitmap = remember(file) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
                if (bitmap != null) {
                    Box(Modifier.size(128.dp, 72.dp)) {
                        Box(
                            Modifier.size(120.dp, 64.dp).align(Alignment.BottomStart).clip(RoundedCornerShape(14.dp)).background(Color(0xFFF4F1FA))
                                .clickable {
                                    Link.send(msg("insertImage").put("data", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)).put("mime", "image/png"))
                                    Toast.makeText(ctx, "Placing your signature on the Mac", Toast.LENGTH_SHORT).show()
                                }.padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) { Image(bitmap, "Signature") }
                        // Delete this saved signature.
                        Box(
                            Modifier.align(Alignment.TopEnd).size(24.dp).clip(CircleShape).background(Color(0xFF3A3556))
                                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                                .clickable { file.delete(); saved = savedSignatures(ctx) },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.Close, "Delete signature", Modifier.size(14.dp), tint = Color.White) }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLine(line: Line, color: Color) {
    val pts = line.points
    if (pts.size == 1) drawCircle(color, line.widths[0] / 2, pts[0])
    for (i in 1 until pts.size) {
        drawLine(color, pts[i - 1], pts[i], strokeWidth = line.widths[i], cap = StrokeCap.Round)
    }
}

/** The strokes as a transparent PNG, cropped to the ink, twice the size for sharpness. */
private fun signaturePng(lines: List<Line>, size: Pair<Float, Float>): ByteArray {
    val all = lines.flatMap { it.points }
    val pad = 16f
    val left = (all.minOf { it.x } - pad).coerceAtLeast(0f)
    val top = (all.minOf { it.y } - pad).coerceAtLeast(0f)
    val right = (all.maxOf { it.x } + pad).coerceAtMost(size.first)
    val bottom = (all.maxOf { it.y } + pad).coerceAtMost(size.second)
    val scale = 2f
    val bitmap = Bitmap.createBitmap(((right - left) * scale).toInt().coerceAtLeast(1), ((bottom - top) * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color(0xFF14102A).toArgb(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    for (line in lines) {
        val p = line.points
        if (p.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle((p[0].x - left) * scale, (p[0].y - top) * scale, line.widths[0] * scale / 2, paint)
            paint.style = Paint.Style.STROKE
        }
        for (i in 1 until p.size) {
            paint.strokeWidth = line.widths[i] * scale
            canvas.drawLine((p[i - 1].x - left) * scale, (p[i - 1].y - top) * scale, (p[i].x - left) * scale, (p[i].y - top) * scale, paint)
        }
    }
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}

private fun signatureDir(ctx: android.content.Context) = File(ctx.filesDir, "signatures").apply { mkdirs() }

private fun savedSignatures(ctx: android.content.Context): List<File> =
    signatureDir(ctx).listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

/** Keeps the last three. */
private fun saveSignature(ctx: android.content.Context, png: ByteArray) {
    File(signatureDir(ctx), "signature-${System.currentTimeMillis()}.png").writeBytes(png)
    savedSignatures(ctx).drop(3).forEach { it.delete() }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MarkUpPad() {
    val lines = remember { mutableStateListOf<Line>() }
    var version by remember { mutableIntStateOf(0) }
    var color by remember { mutableStateOf(Color(0xFFFF4FD8)) }
    var highlighter by remember { mutableStateOf(false) }
    var eraser by remember { mutableStateOf(false) }
    val colors = listOf(
        Color(0xFFFF4FD8), Color(0xFFFF4D4F), Color(0xFFFF9F1A), Color(0xFFFFD43B), Color(0xFF3DDC84),
        Color(0xFF2DD4BF), Color(0xFF4FC3FF), Color(0xFF8B7CFF), Color.White, Color(0xFF111111),
    )
    var area by remember { mutableStateOf(IntSize(1, 1)) }
    // One tool at a time, like the tabs above.
    val tool = when { eraser -> "eraser"; highlighter -> "highlighter"; else -> "pen" }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(GlassFill)
            .border(1.dp, GlassEdge, RoundedCornerShape(16.dp)).padding(4.dp)
    ) {
        listOf(Triple("pen", "Pen", Icons.Filled.Edit), Triple("highlighter", "Highlighter", Icons.Filled.BorderColor), Triple("eraser", "Eraser", EraserIcon))
            .forEach { (key, label, icon) ->
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (tool == key) Indigo.copy(alpha = 0.55f) else Color.Transparent)
                        .clickable { eraser = key == "eraser"; if (key != "eraser") highlighter = key == "highlighter" }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, null, Modifier.size(18.dp), tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
    }
    Spacer(Modifier.height(10.dp))
    // Colours for the pen and highlighter (picking one leaves the eraser).
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.alpha(if (eraser) 0.35f else 1f).horizontalScroll(rememberScrollState()).padding(vertical = 2.dp)
    ) {
        colors.forEach { c ->
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(c)
                    .border(if (c == color && !eraser) 3.dp else 1.dp, if (c == color && !eraser) Color.White else Color.White.copy(alpha = 0.25f), CircleShape)
                    .clickable { color = c; eraser = false }
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    ScreenShaped {
        if (lines.isEmpty()) PadHint(Icons.Filled.Draw, "Draw here, it shows on your Mac", "Ink floats over its screen, for everyone watching")
        Canvas(
            Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(GlassFill)
                .background(Indigo.copy(alpha = 0.06f)).border(1.dp, GlassEdge, RoundedCornerShape(28.dp))
                .onSizeChanged { area = it }
                .pointerInteropFilter { e ->
                    val w = area.width.toFloat()
                    val h = area.height.toFloat()
                    val points = JSONArray()
                    fun add(x: Float, y: Float) { points.put((x / w).toDouble()).put((y / h).toDouble()) }
                    if (eraser) {
                        // Rub over a stroke to take it away, here and on the Mac.
                        val touch = Offset(e.x, e.y)
                        val hit = lines.filter { line -> line.points.any { (it - touch).getDistance() < 28f } }
                        if (hit.isNotEmpty()) {
                            lines.removeAll(hit)
                            Link.send(msg("ink").put("state", "remove").put("text", hit.joinToString(",") { it.id }))
                            version++
                        }
                        return@pointerInteropFilter true
                    }
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                        val line = Line(color = color, highlighter = highlighter)
                        lines.add(line)
                        Link.send(
                            msg("ink").put("state", "start").put("id", line.id).put("index", color.toArgb() and 0xFFFFFF)
                                .put("width", 5).put("on", highlighter).put("points", JSONArray().put((e.x / w).toDouble()).put((e.y / h).toDouble()))
                        )
                    } else {
                        for (i in 0 until e.historySize) add(e.getHistoricalX(i), e.getHistoricalY(i))
                        add(e.x, e.y)
                        Link.send(msg("ink").put("state", "add").put("points", points))
                    }
                    lines.lastOrNull()?.let { line ->
                        for (i in 0 until e.historySize) { line.points.add(Offset(e.getHistoricalX(i), e.getHistoricalY(i))); line.widths.add(4f) }
                        line.points.add(Offset(e.x, e.y)); line.widths.add(4f)
                    }
                    version++
                    true
                }
        ) {
            @Suppress("UNUSED_EXPRESSION") version
            lines.forEach { line -> drawLine(line, if (line.highlighter) line.color.copy(alpha = 0.4f) else line.color) }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PadButton("Undo", Icons.AutoMirrored.Filled.Undo, Modifier.weight(1f)) {
            val last = lines.removeLastOrNull() ?: return@PadButton
            version++
            Link.send(msg("ink").put("state", "remove").put("text", last.id))
        }
        PadButton("Clear", Icons.Filled.Delete, Modifier.weight(1f)) {
            lines.clear(); version++
            Link.send(msg("ink").put("state", "clear"))
        }
        PadButton("Save", Icons.Filled.Save, Modifier.weight(1f)) { Link.send(msg("ink").put("state", "save")) }
        PadButton("Done", Icons.Filled.Check, Modifier.weight(1f), primary = true) {
            lines.clear(); version++
            Link.send(msg("ink").put("state", "done"))
        }
    }
}

/** What to do, faint in the middle of an empty surface, as on the Trackpad. */
@Composable
private fun PadHint(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    // Above the surface (drawn over its glass), and it doesn't take touches, so drawing goes through.
    Column(Modifier.fillMaxSize().zIndex(1f).alpha(0.5f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(detail, style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun PadButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier.height(52.dp).clip(RoundedCornerShape(16.dp))
            .background(if (primary) Indigo.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.09f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

/** An eraser (Material's icon set has none). */
private val EraserIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("Eraser", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                "M16.24,3.56L21.19,8.5C21.97,9.29 21.97,10.55 21.19,11.34L12,20.53C10.44,22.09 7.91,22.09 6.34,20.53" +
                    "L2.81,17C2.03,16.21 2.03,14.95 2.81,14.16L13.41,3.56C14.2,2.78 15.46,2.78 16.24,3.56M4.22,15.58" +
                    "L7.76,19.11C8.54,19.9 9.8,19.9 10.59,19.11L14.12,15.58L9.17,10.63L4.22,15.58Z"
            ).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.White)
        )
    }.build()
}
