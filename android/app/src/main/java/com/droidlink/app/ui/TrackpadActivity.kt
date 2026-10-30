package com.droidlink.app.ui

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.droidlink.app.feature.MacControl
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The phone as the Mac's trackpad and keyboard: one finger moves the pointer, tap to click,
 * two-finger tap to right-click, two fingers to scroll, press and hold to drag.
 */
class TrackpadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, onSurface = Color.White)) {
                Box(Modifier.fillMaxSize().background(BackdropBrush)) { Trackpad(::finish) }
            }
        }
    }
}

@Composable
private fun Trackpad(close: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var typing by remember { mutableStateOf(false) }
    var touching by remember { mutableFloatStateOf(0f) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Trackpad", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { typing = !typing }) {
                Icon(Icons.Filled.Keyboard, "Keyboard", tint = if (typing) Cyan else Color.White)
            }
        }
        Spacer(Modifier.height(10.dp))

        // The pad: gestures turn into pointer, click and scroll messages for the Mac.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(GlassFill)
                .background(Indigo.copy(alpha = 0.06f + 0.08f * touching))
                .border(1.dp, GlassEdge, RoundedCornerShape(28.dp))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent()
                            if (down.changes.none { it.pressed }) continue
                            touching = 1f
                            val start = SystemClock.uptimeMillis()
                            var maxFingers = 1
                            var moved = 0f
                            var dragging = false
                            var last = down.changes.first { it.pressed }.position
                            var lastTime = start
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                maxFingers = maxOf(maxFingers, pressed.size)
                                val now = SystemClock.uptimeMillis()
                                if (pressed.size >= 2) {
                                    // Two fingers: scroll, the content following the fingers.
                                    val d = pressed.map { it.position - it.previousPosition }
                                    val dx = d.sumOf { it.x.toDouble() }.toFloat() / d.size
                                    val dy = d.sumOf { it.y.toDouble() }.toFloat() / d.size
                                    moved += abs(dx) + abs(dy)
                                    if (dx != 0f || dy != 0f) MacControl.scroll(dx / density * 1.4f, dy / density * 1.4f)
                                } else if (maxFingers == 1) {
                                    val p = pressed.first().position
                                    val delta = p - last
                                    val dt = (now - lastTime).coerceAtLeast(1)
                                    last = p
                                    lastTime = now
                                    moved += hypot(delta.x, delta.y)
                                    // Hold still, then move: drag (select text, move windows).
                                    if (!dragging && moved < 12f && now - start > 450) {
                                        dragging = true
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        MacControl.click("down")
                                    }
                                    if (delta.x != 0f || delta.y != 0f) {
                                        // Faster swipes go further, like a trackpad's acceleration.
                                        val speed = hypot(delta.x, delta.y) / density / dt
                                        val gain = 1.4f + (speed * 2.2f).coerceAtMost(3f)
                                        MacControl.pointer(delta.x / density * gain, delta.y / density * gain)
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            }
                            touching = 0f
                            val duration = SystemClock.uptimeMillis() - start
                            when {
                                dragging -> MacControl.click("up")
                                moved < 14f && duration < 300 && maxFingers >= 2 -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    MacControl.click("right")
                                }
                                moved < 14f && duration < 300 -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    MacControl.click("left")
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(0.5f)) {
                Icon(Icons.Filled.Mouse, null, tint = Color.White, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(10.dp))
                Text("Move with one finger · tap to click", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Text("Two fingers to scroll · two-finger tap to right-click", color = Color.White, style = MaterialTheme.typography.bodySmall)
                Text("Hold, then move to drag", color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))
        if (typing) Keyboard() else Buttons()
    }
}

@Composable
private fun Buttons() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PadButton("Click", Modifier.weight(1f)) { MacControl.click("left") }
        PadButton("Right-click", Modifier.weight(1f)) { MacControl.click("right") }
    }
}

/** Typing goes straight to the Mac; the keys row covers what a phone keyboard lacks. */
@Composable
private fun Keyboard() {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // A sentinel space so Backspace on an "empty" field still reaches us.
    var value by remember { mutableStateOf(TextFieldValue(" ", TextRange(1))) }
    Column {
        BasicTextField(
            value = value,
            onValueChange = { new ->
                val old = value.text
                when {
                    new.text.length > old.length && new.text.startsWith(old) -> MacControl.type(new.text.substring(old.length))
                    new.text.length < old.length -> repeat(old.length - new.text.length) { MacControl.key("delete") }
                    new.text != old -> {
                        repeat(old.length - 1) { MacControl.key("delete") }
                        MacControl.type(new.text.drop(1))
                    }
                }
                // Keep just the sentinel so the field never grows.
                value = TextFieldValue(" ", TextRange(1))
            },
            cursorBrush = SolidColor(Color.Transparent),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Transparent),
            modifier = Modifier.size(1.dp).focusRequester(focus)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("esc" to "escape", "tab" to "tab", "←" to "left", "↑" to "up", "↓" to "down", "→" to "right", "⏎" to "return")
                .forEach { (label, key) -> PadButton(label, Modifier.weight(1f)) { MacControl.key(key) } }
        }
        Spacer(Modifier.height(8.dp))
        PadButton("Show keyboard", Modifier.fillMaxWidth()) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
}

@Composable
private fun PadButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.09f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.width(0.dp))
}
