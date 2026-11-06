package com.droidlink.app.ui

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.droidlink.app.feature.Updates
import kotlinx.coroutines.launch

/**
 * "What's new" after an update: a few cards, each a small looping animation of the feature with
 * a line or two about it. Next, then Get started. Once per version (onboarding covers new
 * installs); Settings → About opens it again.
 */
object WhatsNew {
    private const val KEY = "whatsNewSeen"

    /** Shown when this version's tour hasn't been seen and AyPhone was updated, not freshly installed. */
    fun due(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val version = Updates.currentVersion(ctx)
        if (prefs.getString(KEY, null) == version) return false
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return info.firstInstallTime != info.lastUpdateTime
    }

    fun markSeen(ctx: Context) =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(KEY, Updates.currentVersion(ctx)).apply()
}

private class TourPage(val title: String, val body: String, val art: @Composable (Float) -> Unit)

@Composable
internal fun WhatsNewTour(done: () -> Unit) {
    val pages = whatsNewPages()
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val clock = rememberInfiniteTransition(label = "art")
    val t by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "t")
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("What's new", style = MaterialTheme.typography.titleMedium, color = Secondary, modifier = Modifier.weight(1f))
            if (pager.currentPage < pages.lastIndex) TextButton(onClick = done) { Text("Skip", color = Secondary) }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { i ->
            val page = pages[i]
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(28.dp)).background(GlassFill)
                        .border(1.dp, GlassEdge, RoundedCornerShape(28.dp)),
                    contentAlignment = Alignment.Center
                ) { page.art(t) }
                Spacer(Modifier.height(28.dp))
                Text(page.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(page.body, style = MaterialTheme.typography.bodyLarge, color = Secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i ->
                Box(
                    Modifier.padding(3.dp).height(7.dp).width(if (i == pager.currentPage) 20.dp else 7.dp).clip(CircleShape)
                        .background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.3f))
                )
            }
        }
        val last = pager.currentPage == pages.lastIndex
        PrimaryButton(if (last) "Get started" else "Next", if (last) Icons.Filled.Check else Icons.AutoMirrored.Filled.ArrowForward, Modifier.fillMaxWidth()) {
            if (last) done() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
        }
    }
}

/** This version's new features. */
@Composable
private fun whatsNewPages(): List<TourPage> = androidx.compose.runtime.remember {
    listOf(
        TourPage("Everything in four tabs", "Home shows your Mac and what you'll want next. Control, Send and Settings hold the rest.") { TabsArt(it) },
        TourPage("Deck", "Big buttons for the app in front on your Mac, like Figma, Zoom or Spotify. They change as you switch apps. In Control, or from the card on Home.") { DeckArt(it) },
        TourPage("Scan to Mac", "Scan a document into a PDF, read printed text into wherever you're typing, or snap a photo into the app in front. In Send, or from your Mac's menu bar.") { ScanArt(it) },
        TourPage("Pad", "Draw on your Mac with your finger or S Pen, place your signature on a PDF, or mark up its screen while you present. In Control.") { PadArt(it) },
        TourPage("Typing on the trackpad", "Your keyboard's suggestions and autocorrect now work, and the word you're typing shows above the keys.") { TypingArt(it) },
        TourPage("While you were away", "On Macs with Apple Intelligence: unlock and get a short summary of what came in on your phone. Made on the Mac, off until you switch it on in Settings.") { AwayArt(it) },
    )
}

// MARK: Animations (each loops over t, 0…1)

private val Ink = Color.White
private val Faint = Color.White.copy(alpha = 0.12f)

@Composable
private fun TabsArt(t: Float) {
    val active = (t * 4).toInt().coerceIn(0, 3)
    Canvas(Modifier.size(240.dp, 200.dp)) {
        roundRect(Offset(40f, 10f), Size(size.width - 80f, size.height - 20f), 36f, Color(0xFF221D3D))
        val bar = Offset(52f, size.height - 74f)
        roundRect(bar, Size(size.width - 104f, 52f), 20f, Color(0xFF1C1834))
        val slot = (size.width - 104f) / 4
        for (i in 0..3) {
            val cx = bar.x + slot * i + slot / 2
            if (i == active) roundRect(Offset(cx - 26f, bar.y + 8f), Size(52f, 22f), 11f, Indigo)
            drawCircle(if (i == active) Ink else Ink.copy(alpha = 0.4f), 7f, Offset(cx, bar.y + 19f))
            roundRect(Offset(cx - 14f, bar.y + 36f), Size(28f, 5f), 2f, if (i == active) Ink else Ink.copy(alpha = 0.3f))
        }
        // The page above changes with the tab.
        val top = 34f
        for (row in 0 until 3) roundRect(Offset(64f, top + row * 34f), Size((size.width - 128f) * (0.5f + 0.15f * ((row + active) % 3)), 22f), 10f, Faint)
    }
}

@Composable
private fun DeckArt(t: Float) {
    val lit = (t * 9).toInt().coerceIn(0, 8)
    Canvas(Modifier.size(220.dp, 220.dp)) {
        roundRect(Offset(70f, 6f), Size(40f, 40f), 12f, Color(0xFFA259FF))
        roundRect(Offset(124f, 16f), Size(120f, 18f), 9f, Ink.copy(alpha = 0.7f))
        val cell = (size.width - 60f) / 3
        for (i in 0 until 9) {
            val x = 30f + (i % 3) * cell
            val y = 66f + (i / 3) * cell * 0.92f
            val on = i == lit
            roundRect(Offset(x + 6f, y), Size(cell - 12f, cell * 0.92f - 12f), 26f, if (on) Indigo else Faint)
            drawCircle(if (on) Ink else Ink.copy(alpha = 0.5f), 11f, Offset(x + cell / 2, y + cell * 0.36f))
        }
    }
}

@Composable
private fun ScanArt(t: Float) {
    Canvas(Modifier.size(260.dp, 200.dp)) {
        // A page under the phone's camera, a scan line passing over it, then off to the Mac.
        val page = Offset(30f, 30f)
        roundRect(page, Size(130f, 170f), 10f, Color(0xFFF4F1FA))
        for (l in 0 until 6) roundRect(Offset(page.x + 16f, page.y + 24f + l * 22f), Size(98f - (l % 2) * 30f, 8f), 4f, Color(0xFFB9B2CF))
        val scan = page.y + (t.coerceAtMost(0.6f) / 0.6f) * 170f
        if (t < 0.6f) drawLine(Cyan, Offset(page.x - 8f, scan), Offset(page.x + 138f, scan), 5f, StrokeCap.Round)
        val fly = ((t - 0.6f) / 0.4f).coerceIn(0f, 1f)
        val mac = Offset(size.width - 150f, 60f)
        roundRect(mac, Size(130f, 86f), 10f, Color(0xFF2A2547))
        roundRect(Offset(mac.x - 10f, mac.y + 86f), Size(150f, 12f), 6f, Color(0xFF3A3360))
        if (fly > 0f) {
            val p = Offset(page.x + 65f + (mac.x + 40f - page.x - 65f) * fly, page.y + 85f - 40f * kotlin.math.sin(fly * Math.PI.toFloat()))
            roundRect(Offset(p.x - 18f, p.y - 24f), Size(36f, 48f), 5f, Color(0xFFF4F1FA).copy(alpha = 1f - fly * 0.3f))
        }
    }
}

@Composable
private fun PadArt(t: Float) {
    Canvas(Modifier.size(260.dp, 180.dp)) {
        roundRect(Offset(10f, 10f), Size(size.width - 20f, size.height - 20f), 28f, Color(0xFFF4F1FA))
        drawLine(Color(0x33000000), Offset(40f, size.height - 50f), Offset(size.width - 40f, size.height - 50f), 3f)
        // A signature written out over the loop.
        val path = Path().apply {
            moveTo(50f, 110f)
            cubicTo(70f, 40f, 95f, 40f, 90f, 110f)
            cubicTo(88f, 130f, 110f, 70f, 130f, 95f)
            cubicTo(145f, 115f, 160f, 60f, 180f, 90f)
            cubicTo(195f, 115f, 215f, 80f, 260f, 70f)
            cubicTo(300f, 62f, 330f, 90f, 360f, 80f)
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val part = Path()
        measure.getSegment(0f, measure.length * (t / 0.8f).coerceAtMost(1f), part, true)
        drawPath(part, Color(0xFF1B1530), style = Stroke(7f, cap = StrokeCap.Round))
    }
}

@Composable
private fun TypingArt(t: Float) {
    val word = "Shipping"
    val shown = word.take(((t / 0.7f).coerceAtMost(1f) * word.length).toInt())
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Faint).padding(14.dp)) {
            Text(if (shown.isEmpty()) " " else "$shown▏", style = MaterialTheme.typography.headlineSmall, color = Ink)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(shown.ifEmpty { "Ship" }, "Shipping", "Shipped").forEachIndexed { i, s ->
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (i == 1 && t > 0.75f) Indigo else Faint).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(s, color = Ink, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun AwayArt(t: Float) {
    val slide = (t / 0.3f).coerceIn(0f, 1f)
    Canvas(Modifier.size(260.dp, 200.dp)) {
        roundRect(Offset(10f, 20f), Size(size.width - 20f, 150f), 14f, Color(0xFF2A2547))
        roundRect(Offset(-10f + 20f, 170f), Size(size.width - 20f, 14f), 7f, Color(0xFF3A3360))
        // The summary card slides into the screen's corner.
        val card = Offset(size.width - 20f - 170f * slide, 34f)
        roundRect(card, Size(160f, 120f), 16f, Color(0xFF3C3489))
        drawCircle(Color(0xFFF26BFF), 10f, Offset(card.x + 22f, card.y + 22f))
        roundRect(Offset(card.x + 40f, card.y + 15f), Size(90f, 12f), 6f, Ink)
        for (l in 0 until 3) {
            val shownLine = ((t - 0.35f - l * 0.12f) / 0.12f).coerceIn(0f, 1f)
            roundRect(Offset(card.x + 16f, card.y + 46f + l * 22f), Size(128f * shownLine * (1f - l * 0.15f), 9f), 4f, Ink.copy(alpha = 0.7f))
        }
    }
}

private fun DrawScope.roundRect(at: Offset, size: Size, radius: Float, color: Color) =
    drawRoundRect(color, at, size, CornerRadius(radius, radius))
