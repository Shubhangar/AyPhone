package com.droidlink.app.feature

import com.droidlink.app.link.Link
import com.droidlink.app.link.msg
import kotlinx.coroutines.flow.MutableStateFlow

/** The phone as a remote for the Mac: lock it, make it ring, control its music and pointer. */
object MacControl {
    /** Whether the Mac is ringing because this phone asked (until either side stops it). */
    val finding = MutableStateFlow(false)

    fun lock() = Link.send(msg("macLock"))

    fun find(start: Boolean) {
        finding.value = start
        Link.send(msg("findMac").put("action", if (start) "start" else "stop"))
    }

    /** playPause, next, previous, volumeUp, volumeDown. */
    fun media(action: String) = Link.send(msg("macMedia").put("action", action))

    fun pointer(dx: Float, dy: Float) = Link.send(msg("pointer").put("x", dx.toDouble()).put("y", dy.toDouble()))
    fun scroll(dx: Float, dy: Float) = Link.send(msg("scroll").put("x", dx.toDouble()).put("y", dy.toDouble()))
    /** left, right, double, or down/up around a drag. */
    fun click(kind: String) = Link.send(msg("click").put("action", kind))
    fun type(text: String) = Link.send(msg("type").put("text", text))
    /** return, delete, tab, escape, space, left/right/up/down. */
    fun key(name: String) = Link.send(msg("key").put("action", name))
}
