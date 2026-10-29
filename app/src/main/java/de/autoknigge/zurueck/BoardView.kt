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
        val COLORS = intArrayOf(0xFFD3221F.toInt(), 0xFF1F9A3F.toInt(), 0xFFF5B81B.toInt(), 0xFF1E56C8.toInt())
        /** Erdfarben für Design C: Erdrot, Oliv, Ocker, Braun. */
        val EARTH = intArrayOf(0xFFB8432A.toInt(), 0xFF7F9A2C.toInt(), 0xFFE8A61E.toInt(), 0xFF7A4A2B.toInt())
        val NAMES_STD = arrayOf("Rot", "Grün", "Gelb", "Blau")
        val NAMES_EARTH = arrayOf("Erdrot", "Oliv", "Ocker", "Braun")

        /** Farben und Namen des gerade gewählten Designs. */
        var palette: IntArray = COLORS
        var names: Array<String> = NAMES_STD
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

    /** Design: 0 = A (grüner Filz), 1 = B (hell), 2 = C (dunkelgrün mit Erdfarben). */
    var style = 0
        set(v) {
            field = v
            palette = if (v == 2) EARTH else COLORS
            names = if (v == 2) NAMES_EARTH else NAMES_STD
            invalidate()
        }

    /** Ein Druck auf die Mitte wechselt das Design. */
    var onCenterTap: (() -> Unit)? = null

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

    private val gold = 0xFFD4AF37.toInt()
    private val goldLight = 0xFFFFE38A.toInt()

    private fun ring(canvas: Canvas, x: Float, y: Float, r: Float, c: Float, width: Float = 0.06f) {
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = c * width
        paint.color = gold
        canvas.drawCircle(x, y, r, paint)
    }

    private fun disc(canvas: Canvas, x: Float, y: Float, r: Float, light: Int, dark: Int) {
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(x - r * 0.3f, y - r * 0.35f, r * 1.4f, light, dark, Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, r, paint)
        paint.shader = null
    }

    override fun onDraw(canvas: Canvas) {
        if (style == 1) drawClassic(canvas) else drawFelt(canvas, style == 2)
    }

    private fun drawFelt(canvas: Canvas, earth: Boolean) {
        val c = cell()
        val s = c * 11f
        val g = game
        // Brett: dunkelgrüner Filz mit goldenem Doppelrahmen
        rect.set(0f, 0f, s, s)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            s / 2, s / 2, s * 0.75f,
            if (earth) 0xFF0F4D24.toInt() else 0xFF145A2B.toInt(), if (earth) 0xFF03150A.toInt() else 0xFF082A12.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, c * 0.7f, c * 0.7f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = c * 0.13f
        paint.color = gold
        rect.set(c * 0.05f, c * 0.05f, s - c * 0.05f, s - c * 0.05f)
        canvas.drawRoundRect(rect, c * 0.7f, c * 0.7f, paint)
        paint.strokeWidth = c * 0.04f
        paint.color = goldLight
        rect.set(c * 0.2f, c * 0.2f, s - c * 0.2f, s - c * 0.2f)
        canvas.drawRoundRect(rect, c * 0.6f, c * 0.6f, paint)

        // Dunkle Bahnen unter dem Kreuz
        paint.style = Paint.Style.FILL
        paint.color = 0x55000000
        rect.set(0.35f * c, 3.6f * c, s - 0.35f * c, 7.4f * c)
        canvas.drawRoundRect(rect, c * 0.4f, c * 0.4f, paint)
        rect.set(3.6f * c, 0.35f * c, 7.4f * c, s - 0.35f * c)
        canvas.drawRoundRect(rect, c * 0.4f, c * 0.4f, paint)

        // Häuser: farbige Platten mit Goldrand
        val corners = arrayOf(0 to 0, 9 to 0, 9 to 9, 0 to 9)
        for (col in 0 until 4) {
            val (hx, hy) = corners[col]
            rect.set(hx * c + c * 0.12f, hy * c + c * 0.12f, (hx + 2) * c - c * 0.12f, (hy + 2) * c - c * 0.12f)
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom, tint(palette[col], 0.85f), darker(palette[col]), Shader.TileMode.CLAMP
            )
            val activeSeat = g != null && g.seat(col).kind != Kind.OFF
            paint.alpha = if (activeSeat) 255 else 110
            canvas.drawRoundRect(rect, c * 0.35f, c * 0.35f, paint)
            paint.shader = null
            paint.alpha = 255
            val isCur = g != null && g.current == col
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = c * if (isCur) 0.14f else 0.06f
            paint.color = if (isCur) goldLight else gold
            canvas.drawRoundRect(rect, c * 0.35f, c * 0.35f, paint)
            for ((sc, sr) in Game.HOME[col]) {
                paint.style = Paint.Style.FILL
                paint.color = 0x33000000
                canvas.drawCircle(cx(sc.toFloat()), cx(sr.toFloat()), c * 0.34f, paint)
            }
        }

        // Ringfelder: elfenbeinfarbene Scheiben mit Goldring, Startfelder in Farbe
        for ((i, cl) in Game.TRACK_CELLS.withIndex()) {
            val x = cx(cl.first.toFloat())
            val y = cx(cl.second.toFloat())
            val startOf = Game.START.indexOf(i)
            if (startOf >= 0) {
                disc(canvas, x, y, c * 0.4f, tint(palette[startOf], 0.7f), palette[startOf])
            } else {
                disc(canvas, x, y, c * 0.4f, 0xFFFFFDF0.toInt(), 0xFFE5D8B0.toInt())
            }
            ring(canvas, x, y, c * 0.4f, c)
        }
        // Zielfelder
        for (col in 0 until 4) {
            for ((sc, sr) in Game.LANE[col]) {
                val x = cx(sc.toFloat())
                val y = cx(sr.toFloat())
                disc(canvas, x, y, c * 0.4f, tint(palette[col], 0.75f), palette[col])
                ring(canvas, x, y, c * 0.4f, c)
            }
        }
        // Mitte: goldene Platte mit kleinem Würfel
        rect.set(4.55f * c, 4.55f * c, 6.45f * c, 6.45f * c)
        paint.style = Paint.Style.FILL
        paint.shader = android.graphics.LinearGradient(rect.left, rect.top, rect.right, rect.bottom, 0xFFFFE38A.toInt(), 0xFFB98A1E.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, c * 0.4f, c * 0.4f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = c * 0.07f
        paint.color = 0xFF8A6D1B.toInt()
        canvas.drawRoundRect(rect, c * 0.4f, c * 0.4f, paint)
        // Würfel (Fünf)
        val dr = RectF(5.0f * c, 5.0f * c, 6.0f * c, 6.0f * c)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFF8E7A4.toInt()
        canvas.drawRoundRect(dr, c * 0.18f, c * 0.18f, paint)
        paint.color = 0xFF1B3A2B.toInt()
        for ((dx, dy) in listOf(-1 to -1, 1 to -1, 0 to 0, -1 to 1, 1 to 1)) {
            canvas.drawCircle(5.5f * c + dx * c * 0.24f, 5.5f * c + dy * c * 0.24f, c * 0.075f, paint)
        }

        if (g == null) return
        // Figuren: zuerst ruhende, dann animierte; von oben nach unten, damit sich die Figuren natürlich überdecken
        for (animated in listOf(false, true)) {
            val list = ArrayList<Triple<Int, Int, Triple<Float, Float, Float>>>()
            for (col in 0 until 4) {
                if (g.seat(col).kind == Kind.OFF) continue
                for (fig in 0 until 4) {
                    val a = anims[col * 4 + fig]
                    if ((a != null) != animated) continue
                    val t = if (a != null) {
                        Triple(a.pt.x, a.pt.y, a.scale)
                    } else {
                        val cl = Game.cellOf(col, fig, g.pos[col][fig])
                        Triple(cl.first.toFloat(), cl.second.toFloat(), 1f)
                    }
                    list.add(Triple(col, fig, t))
                }
            }
            for ((col, fig, t) in list.sortedBy { it.third.second }) {
                drawToken(canvas, col, cx(t.first), cx(t.second), c, t.third, (col * 4 + fig) in movable)
            }
        }
    }

    private fun drawClassic(canvas: Canvas) {
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
            paint.color = tint(palette[col], 0.35f)
            canvas.drawRoundRect(rect, c * 0.4f, c * 0.4f, paint)
            val activeSeat = g != null && g.seat(col).kind != Kind.OFF
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = c * if (g != null && g.current == col) 0.12f else 0.05f
            paint.color = if (g != null && g.current == col) 0xFFFFD54F.toInt() else palette[col]
            paint.alpha = if (activeSeat) 255 else 90
            canvas.drawRoundRect(rect, c * 0.4f, c * 0.4f, paint)
            paint.alpha = 255
            for ((sc, sr) in Game.HOME[col]) {
                paint.style = Paint.Style.FILL
                paint.color = tint(palette[col], 0.15f)
                canvas.drawCircle(cx(sc.toFloat()), cx(sr.toFloat()), c * 0.36f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = c * 0.04f
                paint.color = palette[col]
                canvas.drawCircle(cx(sc.toFloat()), cx(sr.toFloat()), c * 0.36f, paint)
            }
        }
        // Ringfelder
        for ((i, cell) in Game.TRACK_CELLS.withIndex()) {
            val startOf = Game.START.indexOf(i)
            paint.style = Paint.Style.FILL
            paint.color = if (startOf >= 0) tint(palette[startOf], 0.55f) else 0xFFFFFBEF.toInt()
            canvas.drawCircle(cx(cell.first.toFloat()), cx(cell.second.toFloat()), c * 0.4f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = c * 0.05f
            paint.color = if (startOf >= 0) palette[startOf] else 0xFF8A6D3B.toInt()
            canvas.drawCircle(cx(cell.first.toFloat()), cx(cell.second.toFloat()), c * 0.4f, paint)
        }
        // Zielfelder
        for (col in 0 until 4) {
            for ((sc, sr) in Game.LANE[col]) {
                paint.style = Paint.Style.FILL
                paint.color = tint(palette[col], 0.35f)
                canvas.drawCircle(cx(sc.toFloat()), cx(sr.toFloat()), c * 0.38f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = c * 0.06f
                paint.color = palette[col]
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
                    drawToken(canvas, col, cx(px), cx(py), c, sc, (col * 4 + fig) in movable)
                }
            }
        }
    }

    /**
     * Spielfigur als runde Scheibe in kräftiger Spielerfarbe mit Glanz, dunkler Kontur und hellem oder
     * dunklem Punkt in der Mitte. Der Punkt blinkt bei Figuren, die gezogen werden dürfen.
     */
    private fun drawToken(canvas: Canvas, col: Int, x: Float, y: Float, c: Float, scale: Float, hint: Boolean) {
        val base = palette[col]
        val r = c * 0.37f * scale
        if (hint) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = c * 0.09f
            paint.color = 0xFFFFE38A.toInt()
            paint.alpha = (150 + 105 * pulse).toInt()
            canvas.drawCircle(x, y, r + c * (0.1f + 0.07f * pulse), paint)
            paint.alpha = 255
        }
        // Schatten
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = 0x66000000
        canvas.drawOval(x - r * 0.95f, y + r * 0.55f, x + r * 0.95f, y + r * 1.15f, paint)
        // Kontur und Körper
        paint.color = darker(darker(base))
        canvas.drawCircle(x, y, r + c * 0.04f, paint)
        paint.shader = RadialGradient(
            x - r * 0.35f, y - r * 0.4f, r * 1.5f,
            intArrayOf(tint(base, 0.45f), base, darker(base)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, r, paint)
        paint.shader = null
        // heller Innenring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = c * 0.045f
        paint.color = 0xAAFFFFFF.toInt()
        canvas.drawCircle(x, y, r * 0.78f, paint)
        // Punkt in der Mitte: hell auf dunkler Farbe, dunkel auf heller Farbe
        val lum = (0.299f * Color.red(base) + 0.587f * Color.green(base) + 0.114f * Color.blue(base)) / 255f
        val dot = if (lum > 0.55f) 0xFF2B1806.toInt() else 0xFFFFF6D0.toInt()
        paint.style = Paint.Style.FILL
        paint.color = dot
        paint.alpha = if (hint) (90 + 165 * pulse).toInt() else 255
        canvas.drawCircle(x, y, r * 0.36f, paint)
        paint.alpha = 255
        // Glanzlicht
        paint.color = 0x77FFFFFF.toInt()
        canvas.drawCircle(x - r * 0.42f, y - r * 0.5f, r * 0.17f, paint)
    }

    private fun darker(col: Int): Int =
        Color.rgb((Color.red(col) * 0.55f).toInt(), (Color.green(col) * 0.55f).toInt(), (Color.blue(col) * 0.55f).toInt())

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
        if (e.action == MotionEvent.ACTION_DOWN) return true
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
        } else {
            val mx = e.x - 5.5f * c
            val my = e.y - 5.5f * c
            if (mx * mx + my * my < (0.95f * c) * (0.95f * c)) {
                performClick()
                onCenterTap?.invoke()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
