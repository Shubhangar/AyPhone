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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
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
                Text(page.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
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

/** A canvas drawn in dp: (0,0) to (w,h), so the art is the same size on every screen. */
@Composable
private fun Art(w: Int, h: Int, draw: DrawScope.(Size) -> Unit) {
    Canvas(Modifier.size(w.dp, h.dp)) {
        scale(density, density, pivot = Offset.Zero) { draw(Size(w.toFloat(), h.toFloat())) }
    }
}

@Composable
private fun TabsArt(t: Float) {
    val active = (t * 4).toInt().coerceIn(0, 3)
    Art(240, 220) { s ->
        // A phone with the four tabs at the bottom; the page above changes with the tab.
        val phone = Offset(50f, 10f)
        val pw = s.width - 100f
        roundRect(phone, Size(pw, 200f), 26f, Color(0xFF221D3D))
        for (row in 0 until 3) roundRect(Offset(phone.x + 14f, 30f + row * 30f), Size((pw - 28f) * (0.5f + 0.2f * ((row + active) % 3)), 18f), 9f, Faint)
        val bar = Offset(phone.x + 8f, 150f)
        roundRect(bar, Size(pw - 16f, 50f), 18f, Color(0xFF1C1834))
        val slot = (pw - 16f) / 4
        for (i in 0..3) {
            val cx = bar.x + slot * i + slot / 2
            if (i == active) roundRect(Offset(cx - 14f, bar.y + 8f), Size(28f, 20f), 10f, Indigo)
            drawCircle(if (i == active) Ink else Ink.copy(alpha = 0.4f), 5f, Offset(cx, bar.y + 18f))
            roundRect(Offset(cx - 10f, bar.y + 34f), Size(20f, 4f), 2f, if (i == active) Ink else Ink.copy(alpha = 0.3f))
        }
    }
}

@Composable
private fun DeckArt(t: Float) {
    val lit = (t * 9).toInt().coerceIn(0, 8)
    Art(220, 230) { s ->
        // The app in front on the Mac, then its buttons; one lights up at a time.
        roundRect(Offset(30f, 8f), Size(34f, 34f), 10f, Color(0xFFA259FF))
        roundRect(Offset(76f, 18f), Size(100f, 14f), 7f, Ink.copy(alpha = 0.7f))
        val cell = (s.width - 40f) / 3
        for (i in 0 until 9) {
            val x = 20f + (i % 3) * cell
            val y = 56f + (i / 3) * 58f
            val on = i == lit
            roundRect(Offset(x + 5f, y), Size(cell - 10f, 50f), 16f, if (on) Indigo else Faint)
            drawCircle(if (on) Ink else Ink.copy(alpha = 0.5f), 8f, Offset(x + cell / 2, y + 25f))
        }
    }
}

@Composable
private fun ScanArt(t: Float) {
    Art(260, 200) { s ->
        // A page, a scan line passing over it, then the page flies to the Mac.
        val page = Offset(20f, 30f)
        val ps = Size(110f, 140f)
        roundRect(page, ps, 8f, Color(0xFFF4F1FA))
        for (l in 0 until 6) roundRect(Offset(page.x + 14f, page.y + 20f + l * 19f), Size(82f - (l % 2) * 26f, 7f), 3.5f, Color(0xFFB9B2CF))
        if (t < 0.6f) {
            val scan = page.y + (t / 0.6f) * ps.height
            drawLine(Cyan, Offset(page.x - 6f, scan), Offset(page.x + ps.width + 6f, scan), 4f, StrokeCap.Round)
        }
        val mac = Offset(s.width - 120f, 56f)
        roundRect(mac, Size(108f, 72f), 8f, Color(0xFF2A2547))
        roundRect(Offset(mac.x - 8f, mac.y + 72f), Size(124f, 10f), 5f, Color(0xFF3A3360))
        val fly = ((t - 0.6f) / 0.35f).coerceIn(0f, 1f)
        if (fly > 0f) {
            val from = Offset(page.x + ps.width / 2, page.y + ps.height / 2)
            val to = Offset(mac.x + 54f, mac.y + 36f)
            val p = Offset(from.x + (to.x - from.x) * fly, from.y + (to.y - from.y) * fly - 40f * kotlin.math.sin(fly * Math.PI.toFloat()))
            val k = 1f - 0.4f * fly
            roundRect(Offset(p.x - 18f * k, p.y - 24f * k), Size(36f * k, 48f * k), 4f, Color(0xFFF4F1FA))
        }
    }
}

@Composable
private fun PadArt(t: Float) {
    Art(260, 170) { s ->
        roundRect(Offset(8f, 8f), Size(s.width - 16f, s.height - 16f), 22f, Color(0xFFF4F1FA))
        drawLine(Color(0x33000000), Offset(32f, s.height - 40f), Offset(s.width - 32f, s.height - 40f), 2f)
        // A signature written out over the loop (drawn at 0.62 scale to sit on the line).
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
        translate(4f, 14f) {
            scale(0.62f, 0.62f, pivot = Offset.Zero) {
                drawPath(part, Color(0xFF1B1530), style = Stroke(6f, cap = StrokeCap.Round))
            }
        }
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
    Art(260, 190) { s ->
        // A Mac screen; the summary card slides into its top-right corner and fills in.
        val screen = Offset(14f, 14f)
        val ss = Size(s.width - 28f, 140f)
        roundRect(screen, ss, 12f, Color(0xFF2A2547))
        roundRect(Offset(4f, screen.y + ss.height), Size(s.width - 8f, 12f), 6f, Color(0xFF3A3360))
        clipRect(screen.x, screen.y, screen.x + ss.width, screen.y + ss.height) {
            val card = Offset(screen.x + ss.width - 140f * slide, screen.y + 12f)
            roundRect(card, Size(128f, 100f), 12f, Color(0xFF3C3489))
            drawCircle(Color(0xFFF26BFF), 8f, Offset(card.x + 18f, card.y + 18f))
            roundRect(Offset(card.x + 32f, card.y + 13f), Size(76f, 10f), 5f, Ink)
            for (l in 0 until 3) {
                val shown = ((t - 0.35f - l * 0.12f) / 0.12f).coerceIn(0f, 1f)
                roundRect(Offset(card.x + 12f, card.y + 40f + l * 18f), Size(104f * shown * (1f - l * 0.15f), 8f), 4f, Ink.copy(alpha = 0.7f))
            }
        }
    }
}

private fun DrawScope.roundRect(at: Offset, size: Size, radius: Float, color: Color) =
    drawRoundRect(color, at, size, CornerRadius(radius, radius))
