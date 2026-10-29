package de.autoknigge.zurueck

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Ein Würfel im goldenen Stil, mit kurzer Würfelanimation. */
class DiceView(context: Context) : View(context) {

    var face = 0
        private set
    var accent = 0xFFD4AF37.toInt()
        set(v) {
            field = v
            invalidate()
        }
    var enabledLook = true
        set(v) {
            field = v
            invalidate()
        }

    private var bounce = 0f
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun show(f: Int) {
        face = f
        invalidate()
    }

    /** Würfelt: zeigt kurz wechselnde Augen und bleibt dann bei [result] stehen. */
    fun roll(result: Int, onEnd: () -> Unit) {
        anim?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            var last = -1L
            addUpdateListener {
                val now = System.currentTimeMillis()
                if (now - last > 70) {
                    face = Random.nextInt(1, 7)
                    last = now
                }
                bounce = sin(it.animatedFraction * Math.PI.toFloat() * 3f)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    face = result
                    bounce = 0f
                    invalidate()
                    onEnd()
                }
            })
        }
        anim = a
        a.start()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = (80 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(resolveSize(s, w), resolveSize(s, h))
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val pad = s * 0.12f
        val off = bounce * s * 0.04f
        rect.set(pad, pad - off, s - pad, s - pad - off)
        val r = s * 0.2f
        // Schatten
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = 0x66000000
        canvas.drawRoundRect(rect.left + s * 0.03f, rect.top + s * 0.06f, rect.right + s * 0.03f, rect.bottom + s * 0.06f, r, r, paint)
        // Würfelkörper
        paint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(0xFFFFF6D8.toInt(), 0xFFF2D27A.toInt(), 0xFFC9A038.toInt()), null, Shader.TileMode.CLAMP
        )
        paint.alpha = if (enabledLook) 255 else 150
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null
        paint.alpha = 255
        // kräftiger Rand in der Farbe des Spielers, mit Leuchten außen
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.2f
        paint.color = accent
        paint.alpha = 70
        canvas.drawRoundRect(rect, r, r, paint)
        paint.alpha = 255
        paint.strokeWidth = s * 0.115f
        paint.color = accent
        canvas.drawRoundRect(rect, r, r, paint)
        paint.strokeWidth = s * 0.02f
        paint.color = 0xCCFFFFFF.toInt()
        canvas.drawRoundRect(rect.left + s * 0.06f, rect.top + s * 0.06f, rect.right - s * 0.06f, rect.bottom - s * 0.06f, r * 0.8f, r * 0.8f, paint)
        // Augen
        paint.style = Paint.Style.FILL
        paint.color = 0xFF1B3A2B.toInt()
        val pr = s * 0.075f
        val cx = rect.centerX()
        val cy = rect.centerY()
        val d = rect.width() * 0.27f
        fun dot(dx: Int, dy: Int) = canvas.drawCircle(cx + dx * d, cy + dy * d, pr, paint)
        when (face) {
            1 -> dot(0, 0)
            2 -> { dot(-1, -1); dot(1, 1) }
            3 -> { dot(-1, -1); dot(0, 0); dot(1, 1) }
            4 -> { dot(-1, -1); dot(1, -1); dot(-1, 1); dot(1, 1) }
            5 -> { dot(-1, -1); dot(1, -1); dot(0, 0); dot(-1, 1); dot(1, 1) }
            6 -> { dot(-1, -1); dot(1, -1); dot(-1, 0); dot(1, 0); dot(-1, 1); dot(1, 1) }
        }
    }
}
