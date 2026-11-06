package com.droidlink.app.ui

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg

/**
 * The Deck: big buttons for the app in front on the Mac (Figma, Zoom, Spotify…), which change
 * as you switch apps there. Each one presses that app's shortcut on the Mac. Keeps the screen
 * on while open, so the phone can sit next to the keyboard.
 */
class DeckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Color.Transparent, surface = Color.Transparent)) {
                DeckScreen()
            }
        }
    }
}

@Composable
private fun DeckScreen() {
    val deck by Link.macDeck.collectAsStateWithLifecycle()
    val connected by Link.state.collectAsStateWithLifecycle()
    val macName by Link.macName.collectAsStateWithLifecycle()
    val nowPlaying by Link.macNowPlaying.collectAsStateWithLifecycle()
    // Each press's answer from the Mac, by button: a glow when done, a shake when it couldn't be.
    val results = remember { mutableStateMapOf<String, Pair<Boolean, Int>>() }
    LaunchedEffect(Unit) {
        Link.deckDone.collect { (id, ok) -> results[id] = ok to ((results[id]?.second ?: 0) + 1) }
    }
    val accent by animateColorAsState(deck.color?.let { Color(it) } ?: Indigo, tween(500), label = "accent")
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to accent.copy(alpha = 0.38f), 0.55f to Color(0xFF14112A), 1f to Color(0xFF0E0C1E))
        )
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val columns = if (maxWidth > maxHeight) 4 else 3
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "header") {
                    DeckHeader(deck, subtitle = when {
                        connected != Link.State.Connected -> "Not connected to your Mac"
                        deck.buttons.any { it.icon == "playpause" } && nowPlaying.title.isNotEmpty() ->
                            listOf(nowPlaying.title, nowPlaying.artist).filter { it.isNotEmpty() }.joinToString(" · ")
                        else -> "On ${macName ?: "your Mac"}"
                    })
                }
                // Consecutive buttons that share a heading (or a big style) form one group.
                val groups = mutableListOf<Pair<String?, MutableList<Link.DeckButton>>>()
                deck.buttons.forEach { b ->
                    val key = b.style ?: b.section
                    if (b.style != null || groups.lastOrNull()?.first != key) groups.add(key to mutableListOf(b)) else groups.last().second.add(b)
                }
                groups.forEachIndexed { index, (_, buttons) ->
                    val first = buttons.first()
                    if (first.style == null && first.section != null) {
                        item(key = "h$index${deck.pkg}") {
                            Text(
                                first.section.uppercase(), style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.55f), letterSpacing = 1.sp,
                                modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                            )
                        }
                    }
                    if (first.style != null) {
                        item(key = "b$index${deck.pkg}") { DeckKey(first, Modifier.fillMaxWidth(), accent, results[first.id], big = true) }
                    } else {
                        buttons.chunked(columns).forEachIndexed { r, row ->
                            item(key = "r$index-$r${deck.pkg}") {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    row.forEach { DeckKey(it, Modifier.weight(1f), accent, results[it.id]) }
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun DeckHeader(deck: Link.MacDeck, subtitle: String) {
    val icon = remember(deck.icon) {
        deck.icon?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(icon, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "icon") { bitmap ->
            if (bitmap != null) {
                Image(bitmap, null, Modifier.size(48.dp))
            } else {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Keyboard, null, tint = Color.White)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            AnimatedContent(deck.name, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "app") {
                Text(it, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DeckKey(button: Link.DeckButton, modifier: Modifier, accent: Color, result: Pair<Boolean, Int>?, big: Boolean = false) {
    val view = LocalView.current
    val nowPlaying by Link.macNowPlaying.collectAsStateWithLifecycle()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "press")
    // Glow when the Mac did it; shake when it couldn't.
    val glow = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(result) {
        val (ok, count) = result ?: return@LaunchedEffect
        if (count == 0) return@LaunchedEffect
        if (ok) {
            glow.snapTo(1f)
            glow.animateTo(0f, tween(450))
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
            for (x in listOf(10f, -8f, 6f, -4f, 0f)) shake.animateTo(x, tween(45))
        }
    }
    val playing = button.icon == "playpause" && nowPlaying.playing
    val danger = button.style == "danger"
    val primary = button.style == "primary"
    val fill = when {
        danger -> Color(0xFFC2202E)
        primary -> accent
        playing -> accent.copy(alpha = 0.55f)
        else -> Color.White.copy(alpha = 0.07f)
    }
    val shape = RoundedCornerShape(if (big) 20.dp else 18.dp)
    val content: @Composable () -> Unit = {
        Icon(if (playing) Icons.Filled.Pause else deckIcon(button.icon), null, tint = Color.White, modifier = Modifier.size(if (big) 26.dp else 24.dp))
    }
    val label = if (playing) "Pause" else button.label
    Box(
        modifier
            .graphicsLayer { translationX = shake.value }
            .scale(scale)
            .clip(shape)
            .background(fill)
            .background(Color.White.copy(alpha = 0.22f * glow.value))
            .border(0.5.dp, Color.White.copy(alpha = if (big) 0f else 0.08f + 0.5f * glow.value), shape)
            .clickable(interaction, indication = null) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                Link.send(msg("deckPress").put("id", button.id))
            }
            .then(if (big) Modifier.height(64.dp) else Modifier.aspectRatio(1f)),
        contentAlignment = Alignment.Center
    ) {
        if (big) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                content()
                Spacer(Modifier.width(10.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                content()
                Spacer(Modifier.height(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                if (button.hint != null) {
                    Text(button.hint, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.45f), maxLines = 1)
                }
            }
        }
    }
}

/** The Mac names icons (Deck.swift); unknown names get a keyboard. */
private fun deckIcon(name: String): ImageVector = when (name) {
    "frame" -> Icons.Filled.CropFree
    "autolayout" -> Icons.Filled.ViewStream
    "component" -> Icons.Filled.Widgets
    "zoomin" -> Icons.Filled.ZoomIn
    "zoomout" -> Icons.Filled.ZoomOut
    "fit" -> Icons.Filled.FitScreen
    "undo" -> Icons.AutoMirrored.Filled.Undo
    "redo" -> Icons.AutoMirrored.Filled.Redo
    "duplicate" -> Icons.Filled.ContentCopy
    "present" -> Icons.Filled.Slideshow
    "rectangle" -> Icons.Filled.CropSquare
    "text" -> Icons.Filled.TextFields
    "pen" -> Icons.Filled.Draw
    "group" -> Icons.Filled.Layers
    "ungroup" -> Icons.Filled.LayersClear
    "comment" -> Icons.AutoMirrored.Filled.Comment
    "hideui" -> Icons.Filled.VisibilityOff
    "export" -> Icons.Filled.IosShare
    "mic" -> Icons.Filled.Mic
    "camera" -> Icons.Filled.Videocam
    "share" -> Icons.Filled.ScreenShare
    "hand" -> Icons.Filled.PanTool
    "chat" -> Icons.AutoMirrored.Filled.Chat
    "people" -> Icons.Filled.People
    "leave" -> Icons.Filled.CallEnd
    "calendar" -> Icons.Filled.CalendarMonth
    "search" -> Icons.Filled.Search
    "inbox" -> Icons.Filled.Inbox
    "threads" -> Icons.Filled.Forum
    "mention" -> Icons.Filled.AlternateEmail
    "headset" -> Icons.Filled.Headset
    "back" -> Icons.AutoMirrored.Filled.ArrowBack
    "forward" -> Icons.AutoMirrored.Filled.ArrowForward
    "done" -> Icons.Filled.DoneAll
    "add" -> Icons.Filled.Add
    "close" -> Icons.Filled.Close
    "reload" -> Icons.Filled.Refresh
    "previous" -> Icons.Filled.SkipPrevious
    "next" -> Icons.Filled.SkipNext
    "playpause", "play" -> Icons.Filled.PlayArrow
    "volumedown" -> Icons.AutoMirrored.Filled.VolumeDown
    "volumeup" -> Icons.AutoMirrored.Filled.VolumeUp
    "like" -> Icons.Filled.Favorite
    "shuffle" -> Icons.Filled.Shuffle
    "repeat" -> Icons.Filled.Repeat
    "command" -> Icons.Filled.Code
    "file" -> Icons.AutoMirrored.Filled.InsertDriveFile
    "terminal" -> Icons.Filled.Terminal
    "sidebar" -> Icons.Filled.ViewSidebar
    "format" -> Icons.AutoMirrored.Filled.FormatAlignLeft
    "split" -> Icons.Filled.VerticalSplit
    "notes" -> Icons.AutoMirrored.Filled.Notes
    "windows" -> Icons.Filled.SpaceDashboard
    "switch" -> Icons.Filled.SwapHoriz
    "screenshot" -> Icons.Filled.Screenshot
    "lock" -> Icons.Filled.Lock
    else -> Icons.Filled.Keyboard
}
