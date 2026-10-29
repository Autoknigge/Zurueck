package de.autoknigge.zurueck

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Das Spielbrett: zeichnet Felder und Figuren, animiert Züge und meldet angetippte Figuren. */
class BoardView(context: Context) : View(context) {

    companion object {
        val COLORS = intArrayOf(0xFFD93A3A.toInt(), 0xFF2FA84F.toInt(), 0xFFF2C230.toInt(), 0xFF2E6FE0.toInt())
        val NAMES = arrayOf("Rot", "Grün", "Gelb", "Blau")
        private fun tint(c: Int, a: Float): Int {
            val r = (Color.red(c) * a + 255 * (1 - a)).toInt()
            val g = (Color.green(c) * a + 255 * (1 - a)).toInt()
            val b = (Color.blue(c) * a + 255 * (1 - a)).toInt()
            return Color.rgb(r, g, b)
        }
    }

    var game: Game? = null
        set(v) {
            field = v
            invalidate()
        }

    /** Figuren, die gerade angetippt werden dürfen (Schlüssel Farbe*4+Figur). */
    var movable: Set<Int> = emptySet()
        set(v) {
            field = v
            invalidate()
        }

    /** Wird mit (Farbe, Figur) aufgerufen, wenn eine ziehbare Figur angetippt wird. */
    var onFigure: ((Int, Int) -> Unit)? = null

    private class Anim(val color: Int, val fig: Int, val pt: PointF, var scale: Float)

    private val anims = HashMap<Int, Anim>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var pulse = 0f
    private val pulser = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            pulse = it.animatedValue as Float
            if (movable.isNotEmpty()) invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulser.start()
    }

    override fun onDetachedFromWindow() {
        pulser.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = min(MeasureSpec.getSize(w), if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(h))
        setMeasuredDimension(s, s)
    }

    private fun cell() = min(width, height) / 11f

    private fun cx(col: Float) = (col + 0.5f) * cell()

    // ---------------------------------------------------------------- Zeichnen

    override fun onDraw(canvas: Canvas) {
        val c = cell()
        val s = c * 11f
        // Brett
        rect.set(0f, 0f, s, s)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = 0xFFF4E7C5.toInt()
        canvas.drawRoundRect(rect, c * 0.5f, c * 0.5f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = c * 0.12f
        paint.color = 0xFFC9A648.toInt()
        canvas.drawRoundRect(rect, c * 0.5f, c * 0.5f, paint)

        val g = game
        // Häuser (Eckfelder)
        val corners = arrayOf(0 to 0, 9 to 0, 9 to 9, 0 to 9)
        for (col in 0 until 4) {
            val (hx, hy) = corners[col]
            rect.set(hx * c + c * 0.1f, hy * c + c * 0.1f, (hx + 2) * c - c * 0.1f, (hy + 2) * c - c * 0.1f)
            paint.style = Paint.Style.FILL
            paint.color = tint(COLORS[col], 0.35f)
            canvas.drawRoundRect(rect, c * 0.4f, c * 0.4f, paint)
            val activeSeat = g != null && g.seat(col).kind != Kind.OFF
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = c * if (g != null && g.current == col) 0.12f else 0.05f
            paint.color = if (g != null && g.current == col) 0xFFFFD54F.toInt() else COLORS[col]
            paint.alpha = if (activeSeat) 255 else 90
            canvas.drawRoundRect(rect, c * 0.4f, c * 0.4f, paint)
            paint.alpha = 255
            for ((sc, sr) in Game.HOME[col]) {
                paint.style = Paint.Style.FILL
                paint.color = tint(COLORS[col], 0.15f)
                canvas.drawCircle(cx(sc.toFloat()), cx(sr.toFloat()), c * 0.36f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = c * 0.04f
                paint.color = COLORS[col]
                canvas.drawCircle(cx(sc.toFloat()), cx(sr.toFloat()), c * 0.36f, paint)
            }
        }
        // Ringfelder
        for ((i, cell) in Game.TRACK_CELLS.withIndex()) {
            val startOf = Game.START.indexOf(i)
            paint.style = Paint.Style.FILL
            paint.color = if (startOf >= 0) tint(COLORS[startOf], 0.55f) else 0xFFFFFBEF.toInt()
            canvas.drawCircle(cx(cell.first.toFloat()), cx(cell.second.toFloat()), c * 0.4f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = c * 0.05f
            paint.color = if (startOf >= 0) COLORS[startOf] else 0xFF8A6D3B.toInt()
            canvas.drawCircle(cx(cell.first.toFloat()), cx(cell.second.toFloat()), c * 0.4f, paint)
        }
        // Zielfelder
        for (col in 0 until 4) {
            for ((sc, sr) in Game.LANE[col]) {
                paint.style = Paint.Style.FILL
                paint.color = tint(COLORS[col], 0.35f)
                canvas.drawCircle(cx(sc.toFloat()), cx(sr.toFloat()), c * 0.38f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = c * 0.06f
                paint.color = COLORS[col]
                canvas.drawCircle(cx(sc.toFloat()), cx(sr.toFloat()), c * 0.38f, paint)
            }
        }
        // Mitte
        rect.set(4.5f * c, 4.5f * c, 6.5f * c, 6.5f * c)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(5.5f * c, 5.5f * c, c * 1.1f, 0xFFFFF0B8.toInt(), 0xFFC9A038.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, c * 0.5f, c * 0.5f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = c * 0.06f
        paint.color = 0xFF8A6D3B.toInt()
        canvas.drawRoundRect(rect, c * 0.5f, c * 0.5f, paint)

        if (g == null) return
        // Figuren (zuerst ruhende, dann animierte)
        for (animated in listOf(false, true)) {
            for (col in 0 until 4) {
                if (g.seat(col).kind == Kind.OFF) continue
                for (fig in 0 until 4) {
                    val a = anims[col * 4 + fig]
                    if ((a != null) != animated) continue
                    val (px, py, sc) = if (a != null) {
                        Triple(a.pt.x, a.pt.y, a.scale)
                    } else {
                        val cell = Game.cellOf(col, fig, g.pos[col][fig])
                        Triple(cell.first.toFloat(), cell.second.toFloat(), 1f)
                    }
                    drawFigure(canvas, col, cx(px), cx(py), c, sc, (col * 4 + fig) in movable)
                }
            }
        }
    }

    private fun drawFigure(canvas: Canvas, col: Int, x: Float, y: Float, c: Float, scale: Float, hint: Boolean) {
        val r = c * 0.33f * scale
        // Schatten
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = 0x55000000
        canvas.drawOval(x - r, y + r * 0.55f, x + r, y + r * 1.25f, paint)
        // Körper mit Verlauf
        val base = COLORS[col]
        paint.shader = RadialGradient(
            x - r * 0.35f, y - r * 0.4f, r * 1.5f,
            intArrayOf(tint(base, 0.35f), base, darker(base)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y - r * 0.15f, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = c * 0.05f
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(x, y - r * 0.15f, r, paint)
        // Glanzlicht
        paint.style = Paint.Style.FILL
        paint.color = 0x88FFFFFF.toInt()
        canvas.drawCircle(x - r * 0.35f, y - r * 0.5f, r * 0.22f, paint)
        if (hint) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = c * 0.07f
            paint.color = 0xFFFFD54F.toInt()
            paint.alpha = (140 + 115 * pulse).toInt()
            canvas.drawCircle(x, y - r * 0.15f, r * (1.25f + 0.15f * pulse), paint)
            paint.alpha = 255
        }
    }

    private fun darker(col: Int): Int =
        Color.rgb((Color.red(col) * 0.62f).toInt(), (Color.green(col) * 0.62f).toInt(), (Color.blue(col) * 0.62f).toInt())

    // ---------------------------------------------------------------- Animation

    /** Hält eine Figur auf einem Feld fest (z. B. eine geschlagene, bis die ziehende Figur angekommen ist). */
    fun holdAt(color: Int, fig: Int, cell: Pair<Int, Int>) {
        anims[color * 4 + fig] = Anim(color, fig, PointF(cell.first.toFloat(), cell.second.toFloat()), 1f)
        invalidate()
    }

    /** Lässt die Figur über die Felder [cells] laufen und ruft danach [onEnd] auf. */
    fun animatePath(color: Int, fig: Int, cells: List<Pair<Int, Int>>, perStep: Long, onEnd: () -> Unit) {
        if (cells.size < 2) {
            onEnd()
            return
        }
        val key = color * 4 + fig
        val a = Anim(color, fig, PointF(cells[0].first.toFloat(), cells[0].second.toFloat()), 1f)
        anims[key] = a
        val total = (cells.size - 1).toFloat()
        ValueAnimator.ofFloat(0f, total).apply {
            duration = (cells.size - 1) * perStep
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val i = min(floor(t).toInt(), cells.size - 2)
                val f = t - i
                a.pt.set(
                    cells[i].first + (cells[i + 1].first - cells[i].first) * f,
                    cells[i].second + (cells[i + 1].second - cells[i].second) * f
                )
                a.scale = 1f + 0.3f * sin(PI.toFloat() * f)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    anims.remove(key)
                    invalidate()
                    onEnd()
                }
            })
            start()
        }
    }

    // ---------------------------------------------------------------- Antippen

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return movable.isNotEmpty()
        if (e.action != MotionEvent.ACTION_UP) return true
        val g = game ?: return true
        val c = cell()
        var best = -1
        var bestD = c * 0.75f
        for (key in movable) {
            val col = key / 4
            val fig = key % 4
            // Figuren im Haus: jede Figur im Haus ist antippbar (es kommt ohnehin die erste heraus).
            val homeKeys = if (g.pos[col][fig] < 0) (0 until 4).filter { g.pos[col][it] < 0 } else listOf(fig)
            for (f in homeKeys) {
                val cell = Game.cellOf(col, f, g.pos[col][f])
                val dx = e.x - cx(cell.first.toFloat())
                val dy = e.y - cx(cell.second.toFloat())
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                if (d < bestD) {
                    bestD = d
                    best = key
                }
            }
        }
        if (best >= 0) {
            performClick()
            onFigure?.invoke(best / 4, best % 4)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
