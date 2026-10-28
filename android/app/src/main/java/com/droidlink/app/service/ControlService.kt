package com.droidlink.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.droidlink.app.feature.ClipboardWatcher

/**
 * Lets the Mac control the phone during standard mirroring, without debugging: taps, swipes,
 * Back/Home/Recents, and typing into the focused text field. The user turns it on once in
 * Settings → Accessibility. Coordinates arrive normalized (0–1) across the screen.
 *
 * It also powers automatic phone→Mac clipboard without debugging: Android won't let a
 * background app read the clipboard, but this service can see the moment something is copied
 * (a tap on a "Copy" button, or the system's "Copied" confirmation) and hand off to the
 * invisible clipboard reader, which may read it because it briefly has focus.
 */
class ControlService : AccessibilityService() {
    companion object {
        @Volatile var instance: ControlService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private val copyWord by lazy { getString(android.R.string.copy).lowercase() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && looksLikeCopy(event)) ClipboardWatcher.copyDetected(this)
    }

    private fun looksLikeCopy(event: AccessibilityEvent): Boolean {
        val text = (event.text.joinToString(" ") + " " + (event.contentDescription ?: "")).lowercase()
        return when (event.eventType) {
            // "Copy" in the text-selection bar, or an app's "Copy link" / "Copy text" button.
            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                (text.contains(copyWord) || text.contains("copy")) && !text.contains("paste")
            // The "Copied to clipboard" toast (Samsung and many apps).
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                event.parcelableData !is android.app.Notification &&
                    (text.contains("copied") || text.contains("clipboard"))
            // Android 13+'s clipboard preview overlay.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                event.packageName == "com.android.systemui" &&
                    ((event.className?.toString() ?: "").contains("lipboard") || text.contains("copied"))
            else -> false
        }
    }
    override fun onInterrupt() {}

    private fun screen(): Pair<Float, Float> {
        val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return b.width().toFloat() to b.height().toFloat()
    }

    fun tap(x: Double, y: Double, longPress: Boolean) {
        val (w, h) = screen()
        val path = Path().apply { moveTo((x * w).toFloat(), (y * h).toFloat()) }
        dispatch(path, if (longPress) 650L else 40L)
    }

    fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, durationMs: Long) {
        val (w, h) = screen()
        val path = Path().apply {
            moveTo((x1 * w).toFloat(), (y1 * h).toFloat())
            lineTo((x2 * w).toFloat(), (y2 * h).toFloat())
        }
        dispatch(path, durationMs.coerceIn(60L, 2_000L))
    }

    private fun dispatch(path: Path, durationMs: Long) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun key(name: String) {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return
        }
        performGlobalAction(action)
    }

    /** Types into the focused field: "\b" deletes the last character, "\n" presses Enter. */
    fun type(text: String) {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (text == "\n") {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            return
        }
        // An empty field reports its hint as its text.
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val updated = if (text == "\b") current.dropLast(1) else current + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
