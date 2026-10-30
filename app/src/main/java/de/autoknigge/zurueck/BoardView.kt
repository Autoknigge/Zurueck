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
        val COLORS = intArrayOf(0xFFD94B45.toInt(), 0xFF29A66C.toInt(), 0xFFEFBD3D.toInt(), 0xFF3D82DF.toInt())
        /** Erdfarben für Design C (heißen Rot, Grün, Gelb, Braun). */
        val EARTH = intArrayOf(0xFFB8432A.toInt(), 0xFF7F9A2C.toInt(), 0xFFE8A61E.toInt(), 0xFF7A4A2B.toInt())
        /** Hölzer für Design D: Kirsche (rötlich), Eiche (grünlich-braun gebeizt wird nicht – daher Eiche mittel), Ahorn (hell), Nussbaum (dunkel). */
        val WOOD = intArrayOf(0xFFB5502D.toInt(), 0xFFA8793F.toInt(), 0xFFE9CB93.toInt(), 0xFF5A3720.toInt())
        val NAMES_WOOD = arrayOf("Kirsche", "Eiche", "Ahorn", "Nussbaum")
        val STONE = intArrayOf(0xFFB5493F.toInt(), 0xFF3F8A5E.toInt(), 0xFFD9AE68.toInt(), 0xFF7A88A6.toInt())
        val NAMES_STONE = arrayOf("Porphyr", "Serpentin", "Sandstein", "Basalt")
        val NAMES_STD = arrayOf("Rot", "Grün", "Gelb", "Blau")
        val NAMES_EARTH = arrayOf("Rot", "Grün", "Gelb", "Braun")

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

    /** Design: 0 = A (Filz), 1 = B (hell), 2 = C (Erdfarben), 3 = D (dunkles Holz), 4 = E (Naturholz mit Rinde). */
    var style = 0
        set(v) {
            field = v
            palette = when (v) {
                2 -> EARTH
                1, 3, 4 -> WOOD
                5, 6 -> STONE
                else -> COLORS
            }
            names = when (v) {
                2 -> NAMES_EARTH
                1, 3, 4 -> NAMES_WOOD
                5, 6 -> NAMES_STONE
                else -> NAMES_STD
            }
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
    private val swirl = android.graphics.Path()
    private val pipPath = android.graphics.Path()
    private val swirlB = RectF()
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
        reducedMotion = try {
            android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: Exception) {
            false
        }
        pulser.start()
        if (!reducedMotion) spinner.start()
    }

    override fun onDetachedFromWindow() {
        pulser.cancel()
        spinner.cancel()
        rollAnim?.cancel()
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
        when (style) {
            1, 3, 5, 6 -> drawWood(canvas)
            4 -> drawRustic(canvas)
            else -> drawFelt(canvas, style == 2)
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * t + Color.red(b) * (1 - t)).toInt(),
        (Color.green(a) * t + Color.green(b) * (1 - t)).toInt(),
        (Color.blue(a) * t + Color.blue(b) * (1 - t)).toInt()
    )

    /** Design A und C: dunkelgrünes Brett mit Goldrahmen, Elfenbeinfeldern, farbigen Häusern und Zielfeldern. */
    private fun drawFelt(canvas: Canvas, earth: Boolean) {
        val c = cell()
        val s = c * 11f
        val g = game
        // Goldrahmen
        rect.set(0f, 0f, s, s)
        paint.style = Paint.Style.FILL
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, s, s,
            intArrayOf(0xFFF4D980.toInt(), 0xFF98712B.toInt(), 0xFFF6DC8C.toInt(), 0xFF8E6726.toInt(), 0xFFF5D982.toInt()),
            floatArrayOf(0f, 0.28f, 0.52f, 0.78f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, c * 0.75f, c * 0.75f, paint)
        // Brettfläche
        val f = c * 0.16f
        rect.set(f, f, s - f, s - f)
        paint.shader = RadialGradient(
            s / 2, s * 0.45f, s * 0.7f,
            if (earth) 0xFF1B4A32.toInt() else 0xFF1D4D35.toInt(), if (earth) 0xFF0A2217.toInt() else 0xFF0D2B1E.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, c * 0.58f, c * 0.58f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = c * 0.03f
        paint.color = 0xFF6D8B61.toInt()
        canvas.drawRoundRect(rect, c * 0.58f, c * 0.58f, paint)

        // Heimat: je Farbe vier goldgelbe Kreise nur als Rand (keine Fläche); stehen Figuren zu Hause, sitzen sie darin
        for (col in 0 until 4) {
            val activeSeat = g != null && g.seat(col).kind != Kind.OFF
            for (hi in 0 until 4) {
                val (sc, sr) = Game.cellOf(col, hi, -1)
                val x = cx(sc.toFloat())
                val y = cx(sr.toFloat())
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = c * 0.1f
                paint.color = if (style == 1) 0xFF9A7418.toInt() else 0xFFE8BC3E.toInt()
                paint.alpha = if (activeSeat) 255 else 70
                canvas.drawCircle(x, y, c * 0.4f, paint)
                // zarter heller Innenrand für etwas Tiefe
                paint.strokeWidth = c * 0.03f
                paint.color = if (style == 1) 0xFFD6B25A.toInt() else 0xFFFFF0B0.toInt()
                canvas.drawCircle(x, y, c * 0.35f, paint)
                paint.alpha = 255
            }
        }

        // Ringfelder: elfenbeinfarbene Scheiben mit Goldring, Startfelder in Farbe
        for ((i, cl) in Game.TRACK_CELLS.withIndex()) {
            val x = cx(cl.first.toFloat())
            val y = cx(cl.second.toFloat())
            val startOf = Game.START.indexOf(i)
            val r = c * 0.4f
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = 0x55000000
            canvas.drawCircle(x, y + c * 0.05f, r, paint)
            val fill = if (startOf >= 0) mix(palette[startOf], 0xFFFFFFFF.toInt(), 0.5f) else 0xFFF7EFD9.toInt()
            paint.shader = RadialGradient(x - r * 0.3f, y - r * 0.35f, r * 1.4f, 0xFFFFFFFF.toInt(), fill, Shader.TileMode.CLAMP)
            paint.alpha = 255
            canvas.drawCircle(x, y, r, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = c * 0.07f
            paint.color = if (startOf >= 0) palette[startOf] else 0xFFC7A95E.toInt()
            canvas.drawCircle(x, y, r, paint)
        }
        // Zielfelder: kräftige Scheiben in Spielerfarbe
        for (col in 0 until 4) {
            for ((sc, sr) in Game.LANE[col]) {
                val x = cx(sc.toFloat())
                val y = cx(sr.toFloat())
                val r = c * 0.4f
                paint.style = Paint.Style.FILL
                paint.color = 0x55000000
                canvas.drawCircle(x, y + c * 0.05f, r, paint)
                paint.shader = RadialGradient(x - r * 0.3f, y - r * 0.35f, r * 1.4f, mix(palette[col], 0xFFFFFFFF.toInt(), 0.6f), palette[col], Shader.TileMode.CLAMP)
                canvas.drawCircle(x, y, r, paint)
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = c * 0.05f
                paint.color = 0xB3FFFFFF.toInt()
                canvas.drawCircle(x, y, r, paint)
            }
        }
        drawCenter(canvas, c)

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
        // Heimat: je Farbe vier goldgelbe Kreise nur als Rand (keine Fläche); stehen Figuren zu Hause, sitzen sie darin
        for (col in 0 until 4) {
            val activeSeat = g != null && g.seat(col).kind != Kind.OFF
            for (hi in 0 until 4) {
                val (sc, sr) = Game.cellOf(col, hi, -1)
                val x = cx(sc.toFloat())
                val y = cx(sr.toFloat())
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = c * 0.1f
                paint.color = if (style == 1) 0xFF9A7418.toInt() else 0xFFE8BC3E.toInt()
                paint.alpha = if (activeSeat) 255 else 70
                canvas.drawCircle(x, y, c * 0.4f, paint)
                // zarter heller Innenrand für etwas Tiefe
                paint.strokeWidth = c * 0.03f
                paint.color = if (style == 1) 0xFFD6B25A.toInt() else 0xFFFFF0B0.toInt()
                canvas.drawCircle(x, y, c * 0.35f, paint)
                paint.alpha = 255
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
        drawCenter(canvas, c)

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
        if (style != 0 && style != 2) {
            drawWoodToken(canvas, col, x, y, c, scale, hint)
            return
        }
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
        paint.color = 0xFF14201A.toInt()
        paint.alpha = 130
        canvas.drawCircle(x, y, r + c * 0.04f, paint)
        paint.alpha = 255
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

    // ---------------------------------------------------------------- Designs D und E: Holz

    private val bitmaps = HashMap<Int, android.graphics.Bitmap>()

    private fun bmp(res: Int): android.graphics.Bitmap =
        bitmaps.getOrPut(res) { android.graphics.BitmapFactory.decodeResource(resources, res) }

    /** Holzmaserung als Füllung; [scale] skaliert die Bilddatei, (dx, dy) verschiebt sie. */
    private fun tex(res: Int, scale: Float, dx: Float, dy: Float): android.graphics.BitmapShader {
        val sh = android.graphics.BitmapShader(bmp(res), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val m = android.graphics.Matrix()
        m.setScale(scale, scale)
        m.postTranslate(dx, dy)
        sh.setLocalMatrix(m)
        return sh
    }

    private fun shade(col: Int, f: Float): Int = Color.rgb(
        (Color.red(col) * f).toInt().coerceIn(0, 255),
        (Color.green(col) * f).toInt().coerceIn(0, 255),
        (Color.blue(col) * f).toInt().coerceIn(0, 255)
    )

    /** Hölzer der Spielsteine in der Reihenfolge der Spieler: Kirsche, Eiche, Ahorn, Nussbaum. */
    private val woodRes0 = intArrayOf(R.drawable.wood_kirsche, R.drawable.wood_eiche, R.drawable.wood_ahorn, R.drawable.wood_nussbaum)
    private val stoneRes = intArrayOf(R.drawable.stone_porphyr, R.drawable.stone_serpentin, R.drawable.stone_sandstein, R.drawable.stone_basalt)
    private val woodRes: IntArray get() = if (style == 5 || style == 6) stoneRes else woodRes0

    private val brass = 0xFFC9A648.toInt()

    /** Eingefräste Felder: ungefüllte Vollkreise mit dunkler Rille und hellem Rand darunter. */
    private fun groove(canvas: Canvas, x: Float, y: Float, r: Float, c: Float, gold: Boolean = true) {
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = c * 0.035f
        paint.color = 0x66F3D9A8
        canvas.drawCircle(x, y + c * 0.045f, r, paint)
        paint.strokeWidth = c * 0.07f
        paint.color = 0xCC140B05.toInt()
        canvas.drawCircle(x, y, r, paint)
        if (style == 5) {
            // Granit: Felder in leichtem Goldton umrandet, die vier Zielfelder schwarz
            paint.strokeWidth = c * (if (gold) 0.04f else 0.055f)
            paint.color = if (gold) 0xFFD2AE5E.toInt() else 0xFF000000.toInt()
            paint.alpha = 215
            canvas.drawCircle(x, y, r + c * 0.025f, paint)
            paint.alpha = 255
        }
    }

    private fun drawWoodFields(canvas: Canvas, c: Float, burnt: Boolean) {
        val g = game
        // Heimat: Ringe, keine Fläche
        for (col in 0 until 4) {
            val activeSeat = g != null && g.seat(col).kind != Kind.OFF
            for (hi in 0 until 4) {
                val (sc, sr) = Game.cellOf(col, hi, -1)
                val x = cx(sc)
                val y = cx(sr)
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = c * 0.09f
                paint.color = if (style == 5) 0xFFB9B2AA.toInt() else if (burnt) 0xFF2A170B.toInt() else brass
                paint.alpha = if (activeSeat) 255 else 70
                canvas.drawCircle(x, y, c * 0.4f, paint)
                paint.strokeWidth = c * 0.03f
                paint.color = if (burnt) 0x55F3D9A8 else 0xFFF0D98A.toInt()
                canvas.drawCircle(x, y, c * 0.35f, paint)
                paint.alpha = 255
            }
        }
        // Ringfelder: Vollkreise als Rille
        for ((i, cl) in Game.TRACK_CELLS.withIndex()) {
            val x = cx(cl.first.toFloat())
            val y = cx(cl.second.toFloat())
            groove(canvas, x, y, c * 0.4f, c)
            val startOf = Game.START.indexOf(i)
            if (startOf >= 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = c * 0.09f
                paint.color = palette[startOf]
                canvas.drawCircle(x, y, c * 0.31f, paint)
            }
        }
        // Zielfelder: Holzeinlagen in der Farbe des jeweiligen Holzes
        for (col in 0 until 4) {
            for ((sc, sr) in Game.LANE[col]) {
                val x = cx(sc.toFloat())
                val y = cx(sr.toFloat())
                val r = c * 0.4f
                paint.style = Paint.Style.FILL
                paint.color = 0x66000000
                canvas.drawCircle(x, y + c * 0.04f, r, paint)
                paint.shader = tex(woodRes[col], c / 512f * 1.6f, x, y)
                canvas.drawCircle(x, y, r * 0.92f, paint)
                paint.shader = RadialGradient(x - r * 0.3f, y - r * 0.35f, r * 1.3f, 0x30FFFFFF, 0x55000000, Shader.TileMode.CLAMP)
                canvas.drawCircle(x, y, r * 0.92f, paint)
                paint.shader = null
                groove(canvas, x, y, r, c, false)
            }
        }
        drawCenter(canvas, c)
        drawPieces(canvas, c)
    }

    private fun drawPieces(canvas: Canvas, c: Float) {
        val g = game ?: return
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
                        Triple(cl.first, cl.second, 1f)
                    }
                    list.add(Triple(col, fig, t))
                }
            }
            for ((col, fig, t) in list.sortedBy { it.third.second }) {
                drawToken(canvas, col, cx(t.first), cx(t.second), c, t.third, (col * 4 + fig) in movable)
            }
        }
    }

    /** Design D: eine durchgehende Platte aus Nussbaum, ohne Farbunterschied zwischen Feld und Weg. */
    private fun drawWood(canvas: Canvas) {
        val c = cell()
        val s = c * 11f
        rect.set(0f, 0f, s, s)
        paint.style = Paint.Style.FILL
        paint.shader = tex(when (style) { 1 -> R.drawable.wood_board_hell; 5 -> R.drawable.stone_granit; 6 -> R.drawable.stone_sand_board; else -> R.drawable.wood_board_d }, s / 1024f, 0f, 0f)
        canvas.drawRoundRect(rect, c * 0.7f, c * 0.7f, paint)
        // sanfte Abdunklung zum Rand, helle Kante oben links, dunkle unten rechts
        paint.shader = RadialGradient(s / 2, s / 2, s * 0.75f, 0x00000000, if (style == 1 || style == 6) 0x44502A0A else 0x77000000, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, c * 0.7f, c * 0.7f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = c * 0.05f
        paint.color = 0xFF1A0F08.toInt()
        rect.set(c * 0.03f, c * 0.03f, s - c * 0.03f, s - c * 0.03f)
        canvas.drawRoundRect(rect, c * 0.68f, c * 0.68f, paint)
        paint.strokeWidth = c * 0.03f
        paint.color = 0x55F3D9A8
        rect.set(c * 0.1f, c * 0.1f, s - c * 0.1f, s - c * 0.1f)
        canvas.drawRoundRect(rect, c * 0.6f, c * 0.6f, paint)
        drawWoodFields(canvas, c, false)
    }

    // ---------------------------------------------------------------- Design E: rohes Naturholz mit Rinde

    private val roughPath = android.graphics.Path()
    private var roughFor = 0f
    private val barkPatches = ArrayList<android.graphics.Path>()

    /** Unregelmäßiger Rand der Bohle und Rindenstücke an einigen Stellen (einmal je Größe berechnet). */
    private fun buildRustic(s: Float, c: Float) {
        if (roughFor == s) return
        roughFor = s
        roughPath.reset()
        barkPatches.clear()
        val rnd = kotlin.random.Random(21)
        val r = c * 0.55f
        val step = c * 0.28f
        // Punkte entlang des Umfangs (Uhrzeigersinn ab oben links)
        data class P(val x: Float, val y: Float, val nx: Float, val ny: Float)
        val pts = ArrayList<P>()
        fun edge(x0: Float, y0: Float, x1: Float, y1: Float, nx: Float, ny: Float) {
            val len = kotlin.math.hypot(x1 - x0, y1 - y0)
            val n = (len / step).toInt().coerceAtLeast(1)
            for (i in 0 until n) {
                val t = i / n.toFloat()
                pts.add(P(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, nx, ny))
            }
        }
        fun corner(cx0: Float, cy0: Float, a0: Double) {
            for (i in 0..3) {
                val a = a0 + i * Math.PI / 2 / 4
                pts.add(P(cx0 + (r * Math.cos(a)).toFloat(), cy0 + (r * Math.sin(a)).toFloat(), Math.cos(a).toFloat(), Math.sin(a).toFloat()))
            }
        }
        val e = c * 0.04f
        edge(r, e, s - r, e, 0f, -1f)
        corner(s - r, r + e, -Math.PI / 2)
        edge(s - e, r, s - e, s - r, 1f, 0f)
        corner(s - r, s - r - e, 0.0)
        edge(s - r, s - e, r, s - e, 0f, 1f)
        corner(r, s - r - e, Math.PI / 2)
        edge(e, s - r, e, r, -1f, 0f)
        corner(r, r + e, Math.PI)
        val jit = pts.map { p ->
            val j = (rnd.nextFloat() - 0.5f) * c * 0.1f
            Pair(p.x + p.nx * j, p.y + p.ny * j)
        }
        roughPath.moveTo(jit[0].first, jit[0].second)
        for (i in 1 until jit.size) roughPath.lineTo(jit[i].first, jit[i].second)
        roughPath.close()
        // Rindenstücke: (Kante, von, bis) in Zellen
        class Patch(val side: Int, val a: Float, val b: Float, val th: Float)
        val patches = listOf(
            Patch(0, 0.9f, 3.5f, 0.3f), Patch(1, 0.6f, 2.8f, 0.28f), Patch(2, 7.4f, 10.2f, 0.3f),
            Patch(3, 7.9f, 10.3f, 0.27f), Patch(0, 8.0f, 9.9f, 0.24f)
        )
        for (pa in patches) {
            val path = android.graphics.Path()
            val n = ((pa.b - pa.a) / 0.22f).toInt().coerceAtLeast(3)
            val outer = ArrayList<Pair<Float, Float>>()
            val inner = ArrayList<Pair<Float, Float>>()
            for (i in 0..n) {
                val t = pa.a + (pa.b - pa.a) * i / n
                val taper = kotlin.math.sin(Math.PI * i / n).toFloat().coerceAtLeast(0.15f)
                val d = (pa.th * taper + (rnd.nextFloat() - 0.3f) * 0.1f).coerceAtLeast(0.05f) * c
                val u = t * c
                val (ox, oy, ix, iy) = when (pa.side) {
                    0 -> listOf(u, 0f, u, d)
                    1 -> listOf(s, u, s - d, u)
                    2 -> listOf(s - u, s, s - u, s - d)
                    else -> listOf(0f, s - u, d, s - u)
                }
                outer.add(ox to oy)
                inner.add(ix to iy)
            }
            path.moveTo(outer[0].first, outer[0].second)
            for (q in outer) path.lineTo(q.first, q.second)
            for (q in inner.reversed()) path.lineTo(q.first, q.second)
            path.close()
            barkPatches.add(path)
        }
    }

    private fun drawRustic(canvas: Canvas) {
        val c = cell()
        val s = c * 11f
        buildRustic(s, c)
        // Schatten und Bohle
        canvas.save()
        canvas.translate(0f, c * 0.06f)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = 0x55000000
        canvas.drawPath(roughPath, paint)
        canvas.restore()
        paint.shader = tex(R.drawable.wood_board_e, s / 1024f, 0f, 0f)
        canvas.drawPath(roughPath, paint)
        paint.shader = RadialGradient(s / 2, s / 2, s * 0.75f, 0x00000000, 0x66000000, Shader.TileMode.CLAMP)
        canvas.drawPath(roughPath, paint)
        // Rinde
        canvas.save()
        canvas.clipPath(roughPath)
        for (patch in barkPatches) {
            paint.shader = tex(R.drawable.bark, c / 512f * 1.6f, 0f, 0f)
            paint.style = Paint.Style.FILL
            canvas.drawPath(patch, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = c * 0.05f
            paint.color = 0xCC140C06.toInt()
            canvas.drawPath(patch, paint)
        }
        canvas.restore()
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = c * 0.05f
        paint.color = 0xFF2A1A0E.toInt()
        canvas.drawPath(roughPath, paint)
        drawWoodFields(canvas, c, true)
    }

    /** Spielstein aus Holz: gedrechselte Scheibe mit echter Maserung, Lackglanz und Punkt in der Mitte. */
    private fun drawWoodToken(canvas: Canvas, col: Int, x: Float, y: Float, c: Float, scale: Float, hint: Boolean) {
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
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = 0x77000000
        canvas.drawOval(x - r * 0.95f, y + r * 0.55f, x + r * 0.95f, y + r * 1.15f, paint)
        paint.color = shade(base, 0.4f)
        canvas.drawCircle(x, y, r + c * 0.03f, paint)
        // kleine Goldumrandung, besonders bei dunklen Figuren auf dunklem Brett
        val lumT = (0.299f * Color.red(base) + 0.587f * Color.green(base) + 0.114f * Color.blue(base)) / 255f
        if (style == 3 || style == 4 || style == 5) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = c * (if (lumT < 0.5f) 0.045f else 0.03f)
            paint.color = 0xFFDDB75E.toInt()
            paint.alpha = if (lumT < 0.5f) 255 else 170
            canvas.drawCircle(x, y, r + c * 0.02f, paint)
            paint.alpha = 255
            paint.style = Paint.Style.FILL
        }
        paint.shader = tex(woodRes[col], r * 2.6f / 512f, x - r * 1.3f, y - r * 1.3f)
        canvas.drawCircle(x, y, r, paint)
        paint.shader = RadialGradient(
            x - r * 0.35f, y - r * 0.4f, r * 1.5f,
            intArrayOf(0x55FFFFFF, 0x00000000, 0x66000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = c * 0.035f
        paint.color = shade(base, 0.55f)
        paint.alpha = 190
        canvas.drawCircle(x, y, r * 0.78f, paint)
        paint.alpha = 255
        val lum = (0.299f * Color.red(base) + 0.587f * Color.green(base) + 0.114f * Color.blue(base)) / 255f
        paint.style = Paint.Style.FILL
        paint.color = if (lum > 0.5f) 0xFF2B1608.toInt() else 0xFFF3DFA8.toInt()
        paint.alpha = if (hint) (90 + 165 * pulse).toInt() else 255
        canvas.drawCircle(x, y, r * 0.34f, paint)
        paint.alpha = 255
        paint.color = 0x88FFFFFF.toInt()
        canvas.drawCircle(x - r * 0.42f, y - r * 0.5f, r * 0.16f, paint)
    }

    // ---------------------------------------------------------------- Mitte: runde Platte mit drehendem 3D-Würfel

    private var spin = 0f
    private enum class CubeMode { IDLE, ROLL, HOLD }
    private var cubeMode = CubeMode.IDLE
    private var cubeAy = 0.35f
    private var cubeAx = -0.3f
    private var idleBaseAy = 0.35f
    private var idleBaseAx = -0.3f
    private var idleSpinStart = 0f
    private var reducedMotion = false
    private var rollAnim: ValueAnimator? = null
    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 14000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            spin = it.animatedValue as Float
            if (cubeMode == CubeMode.IDLE) invalidateCenter()
        }
    }

    private fun invalidateCenter() {
        val c = cell()
        invalidate((4.1f * c).toInt(), (4.1f * c).toInt(), (7.0f * c).toInt(), (7.0f * c).toInt())
    }

    /** Orientierung (Drehung um Y, dann um X), bei der die Seite mit [n] Augen nach vorn zeigt. */
    private fun target(n: Int): Pair<Float, Float> {
        val pi = PI.toFloat()
        return when (n) {
            1 -> 0.35f to -0.3f
            6 -> pi + 0.35f to -0.3f
            2 -> -pi / 2 + 0.35f to -0.3f
            5 -> pi / 2 + 0.35f to -0.3f
            3 -> 0.35f to pi / 2 - 0.3f
            else -> 0.35f to -pi / 2 + 0.3f
        }
    }

    /** Der Würfel in der Mitte wirbelt schnell und bleibt auf [result] stehen. */
    fun rollCube(result: Int, ms: Long = 800) {
        rollAnim?.cancel()
        val (ty, tx) = target(result)
        val twoPi = (2 * PI).toFloat()
        // aktuelle Lage bestimmen (Ruhedrehung oder letzter Stand)
        val startY: Float
        val startX: Float
        if (cubeMode == CubeMode.IDLE && !reducedMotion) {
            val d = Math.toRadians((spin - idleSpinStart).toDouble()).toFloat()
            startY = idleBaseAy + d
            startX = idleBaseAx + 0.2f * sin(d * 2f)
        } else {
            startY = cubeAy
            startX = cubeAx
        }
        fun norm(a: Float): Float {
            var r = a % twoPi
            if (r < 0) r += twoPi
            return r
        }
        val dy = norm(ty - startY) + twoPi * 2
        val dx = norm(tx - startX) + twoPi
        cubeMode = CubeMode.ROLL
        if (reducedMotion) {
            cubeAy = ty
            cubeAx = tx
            cubeMode = CubeMode.HOLD
            invalidateCenter()
            return
        }
        rollAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                cubeAy = startY + dy * t
                cubeAx = startX + dx * t
                invalidateCenter()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    cubeAy = ty
                    cubeAx = tx
                    cubeMode = CubeMode.HOLD
                    invalidateCenter()
                }
            })
            start()
        }
    }

    /** Nach dem Zug dreht sich der Würfel wieder langsam von selbst. */
    fun resumeIdle() {
        if (cubeMode == CubeMode.IDLE) return
        rollAnim?.cancel()
        idleBaseAy = cubeAy
        idleBaseAx = cubeAx
        idleSpinStart = spin
        cubeMode = CubeMode.IDLE
    }

    private val path = android.graphics.Path()

    private class FaceDef(val n: FloatArray, val u: FloatArray, val v: FloatArray, val pips: Int)

    private val faces = listOf(
        FaceDef(floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), 1),
        FaceDef(floatArrayOf(0f, 0f, -1f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), 6),
        FaceDef(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 1f, 0f), 2),
        FaceDef(floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 1f, 0f), 5),
        FaceDef(floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f), 3),
        FaceDef(floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), 4)
    )

    private fun pipGrid(n: Int): List<Pair<Float, Float>> = when (n) {
        1 -> listOf(0f to 0f)
        2 -> listOf(-.5f to -.5f, .5f to .5f)
        3 -> listOf(-.5f to -.5f, 0f to 0f, .5f to .5f)
        4 -> listOf(-.5f to -.5f, .5f to -.5f, -.5f to .5f, .5f to .5f)
        5 -> listOf(-.5f to -.5f, .5f to -.5f, 0f to 0f, -.5f to .5f, .5f to .5f)
        else -> listOf(-.5f to -.5f, -.5f to 0f, -.5f to .5f, .5f to -.5f, .5f to 0f, .5f to .5f)
    }

    /** Dreht einen Punkt (x, y, z): erst um die senkrechte Achse, dann leicht gekippt. */
    private fun rot(x: Float, y: Float, z: Float, ay: Float, ax: Float): FloatArray {
        val cy = kotlin.math.cos(ay)
        val sy = sin(ay)
        val x1 = x * cy + z * sy
        val z1 = -x * sy + z * cy
        val cx = kotlin.math.cos(ax)
        val sx = sin(ax)
        return floatArrayOf(x1, y * cx - z1 * sx, y * sx + z1 * cx)
    }

    private val quad = FloatArray(8)

    /** Verkleinert das Viereck in [quad] zur Mitte hin; mit dicker, runder Kontur wirkt der Würfel abgerundet. */
    private fun rounded(f: Float): android.graphics.Path {
        val out = android.graphics.Path()
        val sx = (quad[0] + quad[2] + quad[4] + quad[6]) / 4f
        val sy = (quad[1] + quad[3] + quad[5] + quad[7]) / 4f
        for (i in 0 until 4) {
            val x = sx + (quad[i * 2] - sx) * f
            val y = sy + (quad[i * 2 + 1] - sy) * f
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        out.close()
        return out
    }

    private fun drawCenter(canvas: Canvas, c: Float) {
        val mx = 5.5f * c
        val my = 5.5f * c
        val wooden = style == 1 || style == 3 || style == 4
        val marble = style == 2 || style == 5 || style == 6
        if (false) {
            // goldene Platte (abgerundetes Quadrat) wie im Designentwurf
            val half = c * 1.21f
            rect.set(mx - half, my - half, mx + half, my + half)
            paint.style = Paint.Style.FILL
            paint.color = 0x88000000.toInt()
            canvas.drawRoundRect(rect.left, rect.top + c * 0.1f, rect.right, rect.bottom + c * 0.1f, half * 0.5f, half * 0.5f, paint)
            paint.shader = android.graphics.LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(0xFFF8DF91.toInt(), 0xFFB7862E.toInt(), 0xFFF0CF70.toInt()), floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, half * 0.5f, half * 0.5f, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = c * 0.08f
            paint.color = 0xFFF8E5A6.toInt()
            canvas.drawRoundRect(rect, half * 0.5f, half * 0.5f, paint)
            paint.strokeWidth = c * 0.025f
            paint.color = 0x99FFFFFF.toInt()
            rect.inset(half * 0.16f, half * 0.16f)
            canvas.drawRoundRect(rect, half * 0.4f, half * 0.4f, paint)

        }

        // Würfel: Ruhe-Drehung, Wurf-Drehung oder Stand auf dem Ergebnis
        val ay: Float
        val ax: Float
        when {
            cubeMode != CubeMode.IDLE -> {
                ay = cubeAy
                ax = cubeAx
            }
            reducedMotion -> {
                ay = 0.45f
                ax = -0.3f
            }
            else -> {
                val d = Math.toRadians((spin - idleSpinStart).toDouble()).toFloat()
                ay = idleBaseAy + d
                ax = idleBaseAx + 0.2f * sin(d * 2f)
            }
        }
        val k = c * (if (wooden) 0.62f else 0.55f)
        // Schatten des Würfels auf der Platte
        paint.style = Paint.Style.FILL
        paint.color = 0x44000000
        canvas.drawOval(mx - k * 1.2f, my + k * 1.15f, mx + k * 1.2f, my + k * 1.6f, paint)
        val lx = -0.35f
        val ly = -0.55f
        val lz = 0.75f
        for (f in faces) {
            val nr = rot(f.n[0], f.n[1], f.n[2], ay, ax)
            if (nr[2] <= 0.02f) continue
            // Eckpunkte der Fläche
            path.reset()
            val corners = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)
            for ((i, q) in corners.withIndex()) {
                val px = f.n[0] + q.first * f.u[0] + q.second * f.v[0]
                val py = f.n[1] + q.first * f.u[1] + q.second * f.v[1]
                val pz = f.n[2] + q.first * f.u[2] + q.second * f.v[2]
                val r = rot(px, py, pz, ay, ax)
                quad[i * 2] = mx + r[0] * k
                quad[i * 2 + 1] = my + r[1] * k
                if (i == 0) path.moveTo(mx + r[0] * k, my + r[1] * k) else path.lineTo(mx + r[0] * k, my + r[1] * k)
            }
            path.close()
            val light = (nr[0] * lx + nr[1] * ly + nr[2] * lz).coerceIn(0f, 1f)
            val shade = 0.8f + 0.28f * light
            fun mixc(a: Int, f2: Float) = Color.rgb(
                (Color.red(a) * f2).toInt().coerceAtMost(255),
                (Color.green(a) * f2).toInt().coerceAtMost(255),
                (Color.blue(a) * f2).toInt().coerceAtMost(255)
            )
            paint.style = Paint.Style.FILL
            if (wooden) {
                // heller Holzwürfel wie die Vorlage: weich gerundete Ecken, schlichte Maserung
                val rp = rounded(0.86f)
                paint.shader = tex(R.drawable.wood_ahorn, c * 1.4f / 512f, mx - c, my - c)
                canvas.drawPath(rp, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = c * 0.3f
                paint.strokeJoin = Paint.Join.ROUND
                canvas.drawPath(rp, paint)
                paint.shader = null
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(((1.08f - shade) * 220f).toInt().coerceIn(0, 50), 60, 30, 0)
                canvas.drawPath(rp, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = c * 0.3f
                paint.color = Color.argb(((1.08f - shade) * 220f).toInt().coerceIn(0, 50), 60, 30, 0)
                canvas.drawPath(rp, paint)
                paint.strokeJoin = Paint.Join.MITER
            } else if (marble) {
                // dunkelroter Marmor-Würfel mit Wirbeln und goldenen Augen
                paint.color = mixc(0xFF7C1612.toInt(), shade)
                canvas.drawPath(path, paint)
                canvas.save()
                canvas.clipPath(path)
                val fi = faces.indexOf(f)
                swirlB.set(0f,0f,0f,0f); path.computeBounds(swirlB, true); val b = swirlB
                val w = b.width()
                val h = b.height()
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                for (j in 0 until 7) {
                    val t = j + fi * 7
                    val x0 = b.left + w * (((t * 37) % 11) / 10f)
                    val y0 = b.top + h * (((t * 53) % 13) / 12f)
                    val x1 = b.left + w * (((t * 29 + 3) % 11) / 10f)
                    val y1 = b.top + h * (((t * 41 + 5) % 13) / 12f)
                    swirl.reset()
                    swirl.moveTo(x0, y0)
                    swirl.cubicTo(x0 + w * 0.5f, y0 - h * 0.4f, x1 - w * 0.5f, y1 + h * 0.4f, x1, y1)
                    paint.strokeWidth = c * (0.1f + 0.05f * (j % 3))
                    paint.color = if (j % 2 == 0) mixc(0xFF1A0605.toInt(), shade) else mixc(0xFFC8352B.toInt(), shade)
                    paint.alpha = 190
                    canvas.drawPath(swirl, paint)
                }
                paint.alpha = 255
                paint.strokeCap = Paint.Cap.BUTT
                canvas.restore()
                // Glanz und abgerundete Kanten
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = c * 0.05f
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = mixc(0xFF2A0807.toInt(), shade)
                paint.alpha = 200
                canvas.drawPath(path, paint)
                paint.alpha = 255
                paint.strokeJoin = Paint.Join.MITER
            } else {
                paint.color = mixc(0xFFFFE9A6.toInt(), shade)
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = c * 0.05f
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = mixc(0xFFD7A63B.toInt(), shade)
                canvas.drawPath(path, paint)
                paint.strokeJoin = Paint.Join.MITER
            }
            val pipColor = if (wooden) 0xFF2E1B0E.toInt() else if (marble) 0xFFF2B93E.toInt() else 0xFF103626.toInt()
            val pr = if (wooden) 0.2f else 0.17f
            // Augen: flache Einkerbungen, in die Fläche gelegt und perspektivisch verkürzt
            for ((a, b) in pipGrid(f.pips)) {
                pipPath.reset()
                for (i in 0 until 14) {
                    val t = i * 2f * PI.toFloat() / 14f
                    val da = a * 1.2f + pr * kotlin.math.cos(t)
                    val db = b * 1.2f + pr * sin(t)
                    val px = f.n[0] + da * f.u[0] + db * f.v[0]
                    val py = f.n[1] + da * f.u[1] + db * f.v[1]
                    val pz = f.n[2] + da * f.u[2] + db * f.v[2]
                    val r = rot(px, py, pz, ay, ax)
                    if (i == 0) pipPath.moveTo(mx + r[0] * k, my + r[1] * k) else pipPath.lineTo(mx + r[0] * k, my + r[1] * k)
                }
                pipPath.close()
                paint.style = Paint.Style.FILL
                paint.color = pipColor
                canvas.drawPath(pipPath, paint)
                // heller Rand unten als Andeutung der Vertiefung
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = c * 0.02f
                paint.color = Color.argb(70, 255, 255, 255)
                canvas.save()
                canvas.translate(0f, c * 0.015f)
                canvas.drawPath(pipPath, paint)
                canvas.restore()
            }
        }
    }

    // ---------------------------------------------------------------- Animation

    /** Hält eine Figur auf einem Feld fest (z. B. eine geschlagene, bis die ziehende Figur angekommen ist). */
    fun holdAt(color: Int, fig: Int, cell: Pair<Float, Float>) {
        anims[color * 4 + fig] = Anim(color, fig, PointF(cell.first.toFloat(), cell.second.toFloat()), 1f)
        invalidate()
    }

    /** Lässt die Figur über die Felder [cells] laufen und ruft danach [onEnd] auf. */
    fun animatePath(color: Int, fig: Int, cells: List<Pair<Float, Float>>, perStep: Long, onEnd: () -> Unit) {
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
            if (kotlin.math.abs(mx) < 1.2f * c && kotlin.math.abs(my) < 1.2f * c) {
                performClick()
                onCenterTap?.invoke()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
