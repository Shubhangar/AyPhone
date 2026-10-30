package com.droidlink.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.pow

/**
 * A glow around the screen's edges when a file moves between the phone and the Mac, as if it
 * travelled through the top of the screen (the same as on the Mac). Sending: the edges warm
 * up, a band of light climbs both sides and gathers at the top before fading. Receiving: the
 * light arrives at the top and flows down the sides. Then a small "Sent" / "Received" drops in
 * from the top. Drawn over other apps, click-through.
 */
object TransferGlow {
    private var showing = false

    fun show(ctx: Context, receiving: Boolean) {
        Handler(Looper.getMainLooper()).post {
            if (showing || !Settings.canDrawOverlays(ctx)) return@post
            // Locked or screen off: nobody would see it, and animations don't run then anyway.
            val locked = ctx.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
            if (locked || !ctx.getSystemService(android.os.PowerManager::class.java).isInteractive) return@post
            showing = true
            val wm = ctx.getSystemService(WindowManager::class.java)
            val view = GlowView(ctx.applicationContext, receiving)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
            try {
                wm.addView(view, params)
            } catch (_: Exception) {
                showing = false
                return@post
            }
            val total = (if (receiving) 3.1f else 3.0f) + 1.6f
            // Whatever happens to the animation (the screen turning off midway…), take it down.
            Handler(Looper.getMainLooper()).postDelayed({
                if (view.isAttachedToWindow) try { wm.removeView(view) } catch (_: Exception) {}
                showing = false
            }, (total * 1000).toLong() + 1500)
            ValueAnimator.ofFloat(0f, total).apply {
                duration = (total * 1000).toLong()
                interpolator = LinearInterpolator()
                addUpdateListener { view.time = it.animatedValue as Float; view.invalidate() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        try { wm.removeView(view) } catch (_: Exception) {}
                        showing = false
                    }
                })
                start()
            }
        }
    }

    @SuppressLint("ViewConstructor")
    private class GlowView(ctx: Context, val receiving: Boolean) : View(ctx) {
        /** Seconds since the start. */
        var time = 0f
        /** When the glow itself has faded; the drop-down follows. */
        private val total = if (receiving) 3.1f else 3.0f
        private val density = ctx.resources.displayMetrics.density
        private val colors = intArrayOf(
            0xFF895AFF.toInt(), 0xFFF36BFF.toInt(), 0xFF5B6BFF.toInt(), 0xFFF36BFF.toInt(), 0xFF895AFF.toInt()
        )
        /** A soft glow from stacked strokes, widest and faintest first. */
        private val passes = listOf(56f to 0.08f, 38f to 0.12f, 24f to 0.2f, 13f to 0.34f, 5f to 0.8f)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val orb = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 0x22, 0x1B, 0x38) }
        private val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * ctx.resources.displayMetrics.density }
        private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 15 * ctx.resources.displayMetrics.density; isFakeBoldText = true
        }
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG)
        private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * ctx.resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }

        /** 0 before [a], 1 after [b], smooth in between. */
        private fun ramp(a: Float, b: Float): Float {
            val x = ((time - a) / (b - a)).coerceIn(0f, 1f)
            return x * x * (3 - 2 * x)
        }

        /** Rises from [a] to a peak at [peak], back to 0 by [b]. */
        private fun bump(a: Float, peak: Float, b: Float) = ramp(a, peak) * (1 - ramp(peak, b))

        private fun ease(x: Float) = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2

        private fun edge(canvas: Canvas, shader: Shader, strength: Float) {
            if (strength <= 0.01f) return
            val w = width.toFloat()
            val h = height.toFloat()
            val corner = 40 * density
            stroke.shader = shader
            for ((widthDp, alpha) in passes) {
                stroke.strokeWidth = widthDp * density
                stroke.alpha = (255 * alpha * strength).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(RectF(0f, 0f, w, h), corner, corner, stroke)
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val sweep = SweepGradient(w / 2, h / 2, colors, null)

            // The whole edge: warms up, holds, fades (with a last swell after a file arrives).
            val base = 0.45f * ramp(0f, 0.9f) * (1 - ramp(total - 0.8f, total)) +
                (if (receiving) 0.4f * bump(2.0f, 2.5f, 3.0f) else 0f)
            edge(canvas, sweep, base)

            // The band, travelling up (sending) or down (receiving) past the screen.
            val travel = if (receiving) ease(ramp(0.7f, 2.5f)) else ease(ramp(0.5f, 2.3f))
            val center = if (receiving) h * (-0.25f + 1.45f * travel) else h * (1.2f - 1.45f * travel)
            val half = h * 0.375f
            val white = Color.WHITE
            val clear = Color.TRANSPARENT
            val mask = if (receiving) {
                LinearGradient(0f, center - half, 0f, center + half,
                    intArrayOf(clear, Color.argb(140, 255, 255, 255), white, clear), floatArrayOf(0f, 0.6f, 0.88f, 1f), Shader.TileMode.CLAMP)
            } else {
                LinearGradient(0f, center - half, 0f, center + half,
                    intArrayOf(clear, white, Color.argb(140, 255, 255, 255), clear), floatArrayOf(0f, 0.12f, 0.4f, 1f), Shader.TileMode.CLAMP)
            }
            edge(canvas, ComposeShader(sweep, mask, PorterDuff.Mode.DST_IN), 1.6f)

            // The gathering at the top: as it leaves (sending), or as it arrives (receiving).
            val top = if (receiving) bump(0f, 0.45f, 1.3f) else bump(1.7f, 2.3f, 2.9f)
            if (top > 0.01f) {
                val radius = w * 0.55f
                orb.shader = RadialGradient(
                    w / 2, 0f, radius,
                    intArrayOf(Color.argb((230 * top).toInt(), 0xF3, 0x6B, 0xFF), Color.argb((150 * top).toInt(), 0x89, 0x5A, 0xFF), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
                )
                canvas.save()
                canvas.scale(1f, 0.45f, w / 2, 0f)
                canvas.drawCircle(w / 2, 0f, radius, orb)
                canvas.restore()
            }

            // The little drop-down once it's done.
            val drop = ease(ramp(total - 0.55f, total - 0.05f)) * (1 - ease(ramp(total + 1.0f, total + 1.5f)))
            if (drop > 0.01f) drawPill(canvas, w, drop)
        }

        private fun drawPill(canvas: Canvas, w: Float, drop: Float) {
            val d = density
            val label = if (receiving) "Received" else "Sent"
            val textWidth = pillText.measureText(label)
            val pillW = 18 * d + 20 * d + 8 * d + textWidth + 18 * d
            val pillH = 42 * d
            val top = -pillH + (pillH + 52 * d) * drop
            val rect = RectF(w / 2 - pillW / 2, top, w / 2 + pillW / 2, top + pillH)
            val alpha = (255 * drop).toInt()
            pillFill.alpha = (235 * drop).toInt()
            pillEdge.shader = LinearGradient(rect.left, 0f, rect.right, 0f, 0xB3F36BFF.toInt(), 0x66895AFF, Shader.TileMode.CLAMP)
            pillEdge.alpha = alpha
            canvas.drawRoundRect(rect, pillH / 2, pillH / 2, pillFill)
            canvas.drawRoundRect(rect, pillH / 2, pillH / 2, pillEdge)
            // A small accent badge: a check for sent, a down arrow for received.
            val cx = rect.left + 18 * d + 10 * d
            val cy = rect.centerY()
            badge.shader = LinearGradient(cx - 10 * d, cy - 10 * d, cx + 10 * d, cy + 10 * d, 0xFF895AFF.toInt(), 0xFFF36BFF.toInt(), Shader.TileMode.CLAMP)
            badge.alpha = alpha
            canvas.drawCircle(cx, cy, 10 * d, badge)
            mark.alpha = alpha
            if (receiving) {
                canvas.drawLine(cx, cy - 5 * d, cx, cy + 4 * d, mark)
                canvas.drawLine(cx - 4 * d, cy, cx, cy + 4 * d, mark)
                canvas.drawLine(cx + 4 * d, cy, cx, cy + 4 * d, mark)
            } else {
                canvas.drawLine(cx - 4.5f * d, cy, cx - 1 * d, cy + 3.5f * d, mark)
                canvas.drawLine(cx - 1 * d, cy + 3.5f * d, cx + 5 * d, cy - 3.5f * d, mark)
            }
            pillText.alpha = alpha
            canvas.drawText(label, cx + 10 * d + 8 * d, cy + pillText.textSize * 0.35f, pillText)
        }
    }
}
