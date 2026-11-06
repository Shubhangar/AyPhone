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
import androidx.compose.material.icons.filled.Draw
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
            AyPhoneTheme {
                Box(Modifier.fillMaxSize().background(BackdropBrush)) {
                    Trackpad(::finish) {
                        startActivity(android.content.Intent(this@TrackpadActivity, PadActivity::class.java))
                        finish()
                    }
                }
            }
        }
    }
}

@Composable
private fun Trackpad(close: () -> Unit, openPad: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var typing by remember { mutableStateOf(false) }
    /** What's been typed since the last click, shown above the keyboard. */
    var typed by remember { mutableStateOf("") }
    var touching by remember { mutableFloatStateOf(0f) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Trackpad", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = openPad) { Icon(Icons.Filled.Draw, "Pad", tint = Color.White) }
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
                                    typed = ""
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    MacControl.click("right")
                                }
                                moved < 14f && duration < 300 -> {
                                    // A click usually moves the cursor somewhere else: start afresh.
                                    typed = ""
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
        if (typing) Keyboard(typed) { typed = it } else Buttons()
    }
}

@Composable
private fun Buttons() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PadButton("Click", Modifier.weight(1f)) { MacControl.click("left") }
        PadButton("Right-click", Modifier.weight(1f)) { MacControl.click("right") }
    }
}

/** Typing goes straight to the Mac; the keys row covers what a phone keyboard lacks. Above it,
 *  what you've typed, so you needn't look up at the Mac to catch a typo. */
@Composable
private fun Keyboard(typed: String, setTyped: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // A sentinel space so Backspace on an "empty" field still reaches us.
    var value by remember { mutableStateOf(TextFieldValue(" ", TextRange(1))) }
    Column {
        // Tapping the strip brings the keyboard back if it was put away.
        TypedStrip(typed, clear = { setTyped("") }) {
            focus.requestFocus()
            keyboard?.show()
        }
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = value,
            onValueChange = { new ->
                // The field holds the sentinel plus the word being typed, so the phone's keyboard
                // shows it with its suggestions. Whatever changed (a letter, Backspace, a tapped
                // suggestion or autocorrect) is replayed on the Mac: delete what differs, type
                // the rest. Backspace past the sentinel deletes on the Mac as usual.
                val old = value.text
                val common = old.commonPrefixWith(new.text).length
                val deleted = old.length - common
                val added = new.text.substring(common)
                repeat(deleted) { MacControl.key("delete") }
                if (added.isNotEmpty()) MacControl.type(added)
                if (deleted > 0 || added.isNotEmpty()) setTyped((typed.dropLast(deleted) + added).takeLast(400))
                // A finished word (space, return, punctuation from a suggestion) starts a new one.
                value = if (new.text.isEmpty() || !new.text.startsWith(" ") || new.text.drop(1).any { it.isWhitespace() }) {
                    TextFieldValue(" ", TextRange(1))
                } else {
                    new
                }
            },
            cursorBrush = SolidColor(Color.Transparent),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Transparent),
            modifier = Modifier.size(1.dp).focusRequester(focus)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("esc" to "escape", "tab" to "tab", "←" to "left", "↑" to "up", "↓" to "down", "→" to "right", "⏎" to "return")
                .forEach { (label, key) ->
                    PadButton(label, Modifier.weight(1f)) {
                        MacControl.key(key)
                        when (key) {
                            "return" -> setTyped((typed + "\n").takeLast(400))
                            // Moving around or escaping: what's shown no longer matches the Mac.
                            "escape", "tab", "up", "down" -> setTyped("")
                        }
                    }
                }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
}

/** The word being typed, large, like the keyboard's own bar shows it. */
@Composable
private fun TypedStrip(typed: String, clear: () -> Unit, showKeyboard: () -> Unit) {
    val word = typed.substringAfterLast(' ').substringAfterLast('\n')
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(GlassFill)
            .border(1.dp, GlassEdge, RoundedCornerShape(16.dp)).clickable(onClick = showKeyboard)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (word.isEmpty()) "The word you're typing shows here" else "$word▏",
            color = if (word.isEmpty()) Color.White.copy(alpha = 0.4f) else Color.White,
            style = if (word.isEmpty()) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.headlineSmall,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (word.isNotEmpty()) {
            Text("Clear", color = Cyan, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 10.dp).clickable(onClick = clear))
        }
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
