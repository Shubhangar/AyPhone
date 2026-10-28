package com.droidlink.app.feature

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg
import com.droidlink.app.service.PhoneNotificationListener

/** Now-playing info and transport controls. Needs notification access to see media sessions. */
object Media {
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var lastSent: String? = null
    private var artKey: String? = null
    private var artBase64: String? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = sendCurrent()
        override fun onPlaybackStateChanged(state: PlaybackState?) = sendCurrent()
        override fun onSessionDestroyed() = track(null)
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        track(list?.firstOrNull())
    }

    fun attach(ctx: Context) {
        context = ctx.applicationContext
        val m = ctx.getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(ctx, PhoneNotificationListener::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            manager = m
            track(m.getActiveSessions(component).firstOrNull())
        } catch (_: SecurityException) {
        }
    }

    fun detach() {
        manager?.removeOnActiveSessionsChangedListener(sessionsListener)
        controller?.unregisterCallback(callback)
        controller = null
        manager = null
    }

    private fun track(c: MediaController?) {
        if (c != null && c.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = c
        c?.registerCallback(callback, main)
        sendCurrent()
    }

    @Synchronized
    fun sendCurrent(force: Boolean = false) {
        val c = controller
        val metadata = c?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val m = msg("media")
        if (c != null && metadata != null && !title.isNullOrEmpty()) {
            m.put("title", title)
                .put("artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
                .put("app", appLabel(c.packageName))
                .put("playing", c.playbackState?.state == PlaybackState.STATE_PLAYING)
                .put("art", art(metadata, title))
        }
        val text = m.toString()
        if (!force && text == lastSent) return
        lastSent = text
        Link.send(m)
    }

    /** Album art is encoded once per track, not on every playback-state callback. */
    private fun art(metadata: MediaMetadata, title: String): String? {
        val key = title + "|" + metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        if (key == artKey) return artBase64
        artKey = key
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        artBase64 = bitmap?.let { try { Icons.jpegBase64(it, 240) } catch (_: Exception) { null } }
        return artBase64
    }

    fun perform(action: String) {
        if (action == "volumeUp" || action == "volumeDown") {
            val audio = context?.getSystemService(AudioManager::class.java) ?: return
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (action == "volumeUp") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
            return
        }
        val t = controller?.transportControls ?: return
        when (action) {
            "play" -> t.play()
            "pause" -> t.pause()
            "next" -> t.skipToNext()
            "previous" -> t.skipToPrevious()
        }
    }

    private fun appLabel(pkg: String): String {
        val pm = context?.packageManager ?: return pkg
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }
}
