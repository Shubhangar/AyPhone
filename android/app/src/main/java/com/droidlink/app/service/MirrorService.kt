package com.droidlink.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.droidlink.app.Notifications
import com.droidlink.app.link.Link
import com.droidlink.app.link.msg
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * Standard mirroring: Android's screen-capture API (MediaProjection) into the hardware H.264
 * encoder, streamed to the Mac over the existing link. No USB or wireless debugging needed;
 * Android asks the user to allow it each time it starts. App sound comes along via
 * AudioPlaybackCapture when RECORD_AUDIO is granted (apps can opt out, calls never included).
 */
class MirrorService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_MAX_SIZE = "maxSize"
        const val ACTION_STOP = "com.droidlink.app.MIRROR_STOP"
        private const val TAG = "AyPhoneMirror"

        @Volatile var instance: MirrorService? = null
            private set

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MirrorService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    @Volatile private var encoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var running = false
    private var maxSize = 1280
    private var videoWidth = 0
    private var videoHeight = 0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && running) restartVideoIfResized()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        // Android 14+: the media-projection foreground service must be running before the
        // projection is created, and only after the user has allowed it.
        ServiceCompat.startForeground(
            this, Notifications.MIRROR_ID, Notifications.mirroring(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        maxSize = intent.getIntExtra(EXTRA_MAX_SIZE, 1280)
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }, handler)
        projection = mp
        running = true
        instance = this
        try {
            startVideo()
        } catch (e: Exception) {
            Log.w(TAG, "could not start video: $e")
            Link.send(msg("mirrorState").put("state", "error"))
            stopSelf()
            return START_NOT_STICKY
        }
        startAudio()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        Link.send(msg("mirrorState").put("state", "running").put("control", ControlService.instance != null))
        return START_NOT_STICKY
    }

    /** Scaled screen size, rounded to multiples of 16 (what hardware encoders like best). */
    private fun targetSize(): Pair<Int, Int> {
        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val sw = bounds.width()
        val sh = bounds.height()
        val scale = min(1f, maxSize.toFloat() / max(sw, sh))
        return ((sw * scale).toInt() / 16 * 16) to ((sh * scale).toInt() / 16 * 16)
    }

    private fun newEncoder(width: Int, height: Int): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Keep sending frames when the screen is still, so the Mac never shows a stale picture.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun startVideo() {
        val (w, h) = targetSize()
        val codec = newEncoder(w, h)
        val surface = codec.createInputSurface()
        codec.start()
        videoWidth = w
        videoHeight = h
        encoder = codec
        // A projection may create only one virtual display; rotation reuses it (see below).
        display = projection!!.createVirtualDisplay(
            "AyPhone", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )
        startDrain(codec, w, h)
    }

    /** On rotation, swap in a new encoder of the new shape and point the same display at it. */
    private fun restartVideoIfResized() {
        val (w, h) = targetSize()
        if (w == videoWidth && h == videoHeight) return
        val old = encoder ?: return
        val codec = newEncoder(w, h)
        val surface = codec.createInputSurface()
        codec.start()
        videoWidth = w
        videoHeight = h
        encoder = codec
        display?.resize(w, h, resources.displayMetrics.densityDpi)
        display?.surface = surface
        startDrain(codec, w, h)
        try {
            old.stop()
        } catch (_: Exception) {}
        old.release()
    }

    private fun startDrain(codec: MediaCodec, width: Int, height: Int) = thread(name = "mirror-video") {
        val info = MediaCodec.BufferInfo()
        try {
            while (running && encoder === codec) {
                val index = codec.dequeueOutputBuffer(info, 100_000)
                if (index < 0) continue
                val buffer = codec.getOutputBuffer(index) ?: continue
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                codec.releaseOutputBuffer(index, false)
                if (bytes.isEmpty()) continue
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    // SPS/PPS: the Mac needs these to set up its decoder.
                    Link.sendNow(msg("mirrorConfig").put("width", width).put("height", height).put("data", b64))
                } else {
                    Link.sendNow(
                        msg("mirrorFrame").put("data", b64)
                            .put("keyFrame", info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                    )
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "link gone, stopping mirror: $e")
            handler.post { stopSelf() }
        } catch (e: IllegalStateException) {
            // Encoder released during rotation or shutdown.
        }
    }

    fun requestKeyFrame() {
        encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val mp = projection ?: return
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val chunk = 48_000 * 2 * 2 / 25 // 40 ms of 16-bit stereo
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(chunk * 4)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            record.startRecording()
            audioRecord = record
            thread(name = "mirror-audio") {
                val buf = ByteArray(chunk)
                while (running && audioRecord === record) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) Link.send(msg("mirrorAudio").put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "no mirror audio: $e")
        }
    }

    override fun onDestroy() {
        running = false
        instance = null
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioRecord = null
        display?.release()
        display = null
        encoder?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        encoder = null
        projection?.stop()
        projection = null
        Link.send(msg("mirrorState").put("state", "stopped"))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
