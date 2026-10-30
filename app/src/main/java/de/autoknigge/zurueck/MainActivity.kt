package de.autoknigge.zurueck

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import org.json.JSONArray
import org.json.JSONObject

/** Zurück – Brettspiel für 1 bis 4 Personen (Menschen und Computer) an einem Gerät. */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val URL_HOME = "https://autoknigge.de"
        private const val URL_APPS = "https://autoknigge.de/apps/"
        private const val URL_IMPRINT = "https://autoknigge.de/impressum"
        private const val URL_PRIVACY = "https://autoknigge.de/zurueck/datenschutz.html"
        private val GOLD = Color.parseColor("#D4AF37")
        private val TEXT = Color.parseColor("#F8E7A4")
        private val DIM = Color.parseColor("#CFC38E")
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var adManager: AdManager

    // Einrichtung
    private val kinds = arrayOf(Kind.HUMAN, Kind.COMPUTER, Kind.COMPUTER, Kind.COMPUTER)
    private val names = arrayOf("", "", "", "")
    private var easy = false
    private lateinit var setupView: ScrollView
    private lateinit var kindButtons: List<TextView>
    private lateinit var nameFields: List<EditText>
    private lateinit var levelButton: TextView
    private lateinit var designButton: TextView
    private val seatDots = ArrayList<View>()
    private lateinit var continueButton: TextView

    // Spiel
    private lateinit var gameView: LinearLayout
    private lateinit var board: BoardView
    private lateinit var dice: DiceView
    private lateinit var status: TextView
    private lateinit var chipBoxes: List<LinearLayout>
    private lateinit var chipNames: List<TextView>
    private lateinit var chipSubs: List<TextView>
    private lateinit var chipDots: List<View>
    private lateinit var turnText: TextView
    private lateinit var pulseDot: View
    private lateinit var ctrlTitle: TextView
    private lateinit var ctrlAttempts: TextView
    private lateinit var rollText: TextView
    private lateinit var rollBtn: LinearLayout
    private lateinit var bottomHint: TextView
    private var game: Game? = null
    private var busy = false
    private var session = 0
    private var clickSound: android.media.SoundPool? = null
    private lateinit var music: MusicPlayer
    private val musicListener: () -> Unit = {
        runOnUiThread {
            musicRefresh?.invoke()
            if (::musicTop.isInitialized) musicTop.alpha = if (music.playing) 1f else 0.65f
        }
    }
    private val askNotifications = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    private var musicRefresh: (() -> Unit)? = null
    private lateinit var musicTop: TextView
    private val pickMusic = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            askNotificationPermission()
            music.add(uris)
        }
    }

    /** Ab Android 13 braucht die Benachrichtigung mit den Musik-Knöpfen eine Erlaubnis (einmal fragen). */
    private fun askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("asked_notifications", false)
        ) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private var clickId = 0

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ Aufbau

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = getSharedPreferences("zurueck", MODE_PRIVATE)
        music = MusicHub.get(this)
        music.addListener(musicListener)
        loadSetup()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_screen)
        }
        Ui.insets(root)
        val content = FrameLayout(this)
        buildSetup()
        buildGame()
        content.addView(setupView, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(gameView, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val adSettings = link("Werbe-Einstellungen", null)
        val footer = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
        }
        footer.addView(link("Datenschutz", URL_PRIVACY))
        footer.addView(dot())
        footer.addView(link("Impressum", URL_IMPRINT))
        footer.addView(dot())
        footer.addView(adSettings)
        footer.addView(dot())
        footer.addView(link("Weitere Apps", URL_APPS))
        root.addView(footer, LinearLayout.LayoutParams(MATCH, WRAP))
        footerView = footer

        val adBox = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_ad)
            minimumHeight = dp(60)
        }
        root.addView(adBox, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)
        adManager = AdManager(this, adBox, adSettings, true)

        clickSound = android.media.SoundPool.Builder().setMaxStreams(2).build().also {
            clickId = it.load(this, R.raw.click, 1)
        }
        showSetup()
        showIntro { adManager.start() }
    }

    private var footerView: View? = null

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun link(label: String, url: String?): TextView = text(label, 12f, GOLD).apply {
        paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
        setPadding(dp(6), dp(8), dp(6), dp(8))
        maxLines = 1
        if (url != null) setOnClickListener { Ui.openUrl(this@MainActivity, url) }
    }

    private fun dot() = text("·", 12f, GOLD)

    private fun goldButton(label: String, onClick: () -> Unit): TextView = text(label, 20f, Color.parseColor("#1B3A2B"), true).apply {
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_btn_gold)
        setPadding(dp(16), dp(14), dp(16), dp(20))
        minHeight = dp(64)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun darkButton(label: String, onClick: () -> Unit): TextView = text(label, 15f, TEXT, true).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#1B2E22"))
            setStroke(dp(2), Color.parseColor("#C9A648"))
            cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(14), dp(10), dp(14), dp(10))
        minHeight = dp(44)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun buildSetup() {
        setupView = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
        }
        // Logo mit Versionsnummer in der rechten oberen Ecke
        val logoBox = FrameLayout(this)
        logoBox.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.logo_zurueck)
            adjustViewBounds = true
            contentDescription = "Zurück"
        }, FrameLayout.LayoutParams(dp(230), WRAP))
        val ver = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        logoBox.addView(
            text(ver, 10f, Color.parseColor("#FFE07A")),
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(16)
                rightMargin = dp(26)
            }
        )
        col.addView(logoBox, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
        col.addView(text("Brettspiel 1 - 4 Personen", 15f, DIM).apply { gravity = Gravity.CENTER })

        col.addView(text("Wer spielt mit?", 17f, TEXT, true), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(22) })
        val kb = ArrayList<TextView>()
        val nf = ArrayList<EditText>()
        for (i in 0 until 4) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(View(this).apply {
                seatDots.add(this)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(BoardView.palette[i])
                    setStroke(dp(2), Color.WHITE)
                }
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(12) })
            val et = EditText(this).apply {
                hint = BoardView.names[i]
                setText(names[i])
                setTextColor(TEXT)
                setHintTextColor(Color.parseColor("#8E8A6A"))
                textSize = 17f
                maxLines = 1
                filters = arrayOf(InputFilter.LengthFilter(12))
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                backgroundTintList = android.content.res.ColorStateList.valueOf(GOLD)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            }
            nf.add(et)
            row.addView(et, LinearLayout.LayoutParams(0, WRAP, 1f))
            val b = darkButton("", {}).apply { minWidth = dp(112) }
            b.setOnClickListener { cycleKind(i) }
            kb.add(b)
            row.addView(b, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10) })
            col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }
        kindButtons = kb
        nameFields = nf

        levelButton = darkButton("") { easy = !easy; updateSetup() }
        col.addView(levelButton, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) })

        designButton = darkButton("") { cycleStyle() }
        col.addView(designButton, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        col.addView(goldButton("Neues Spiel starten") { startNewGame() }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(22) })
        continueButton = goldButton("Spiel fortsetzen") { resumeGame() }
        col.addView(continueButton, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        col.addView(darkButton("🎵  Musik") { showMusic() }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
        col.addView(darkButton("Spielregeln") { showRules() }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        col.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))
        col.addView(link("Eine Idee von autoknigge.de", URL_HOME), LinearLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(16)
        })
        setupView.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
    }

    /** Wechselt das Brett-Design A → B → C → A und merkt es sich. */
    private val STYLE_ORDER = intArrayOf(3, 1, 4, 2, 5, 6)

    private fun cycleStyle() {
        val pos = (prefs.getInt("design_pos", 0) + 1) % 6
        prefs.edit().putInt("design_pos", pos).apply()
        board.style = STYLE_ORDER[pos]
        clickSound?.play(clickId, 0.7f, 0.7f, 1, 0, 1f)
        updateSetup()
        game?.let {
            dice.accent = BoardView.palette[it.current]
            updateChips()
        }
    }

    private fun cycleKind(i: Int) {
        syncNames()
        kinds[i] = when (kinds[i]) {
            Kind.HUMAN -> Kind.COMPUTER
            Kind.COMPUTER -> Kind.OFF
            Kind.OFF -> Kind.HUMAN
        }
        // Mindestens zwei Plätze müssen besetzt sein.
        if (kinds.count { it != Kind.OFF } < 2) kinds[i] = Kind.HUMAN
        updateSetup()
    }

    private fun updateSetup() {
        for (i in 0 until 4) {
            kindButtons[i].text = when (kinds[i]) {
                Kind.HUMAN -> "👤 Mensch"
                Kind.COMPUTER -> "🤖 Computer"
                Kind.OFF -> "— Aus"
            }
            nameFields[i].alpha = if (kinds[i] == Kind.OFF) 0.4f else 1f
            nameFields[i].isEnabled = kinds[i] != Kind.OFF
        }
        levelButton.text = "Computer-Stufe: " + if (easy) "Leicht" else "Normal"
        designButton.text = "Brett-Design: " + when (prefs.getInt("design_pos", 0)) {
            0 -> "A (dunkles Holz)"
            1 -> "B (helles Holz)"
            2 -> "C (Naturholz mit Rinde)"
            3 -> "D (dunkelgrün, Erdfarben)"
            4 -> "E (roter Granit)"
            else -> "F (Sandstein)"
        }
        for (i in 0 until 4) {
            nameFields[i].hint = BoardView.names[i]
            (seatDots.getOrNull(i)?.background as? GradientDrawable)?.setColor(BoardView.palette[i])
        }
        continueButton.visibility = if (prefs.contains("game")) View.VISIBLE else View.GONE
    }

    private fun syncNames() {
        for (i in 0 until 4) names[i] = nameFields[i].text.toString().trim()
    }

    private fun showSetup() {
        updateSetup()
        gameView.visibility = View.GONE
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupView.visibility = View.VISIBLE
        footerView?.visibility = View.VISIBLE
    }

    private fun showIntro(then: () -> Unit) {
        if (prefs.getBoolean("intro_seen", false)) {
            then()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Kostenlos dank Werbung")
            .setMessage(
                "Diese App ist kostenlos und finanziert sich durch Werbung: eine kurze Anzeige beim Start und ein Banner am unteren Rand.\n\n" +
                    "Im nächsten Schritt kannst du der Nutzung deiner Daten für Werbezwecke zustimmen und damit die kostenlose App unterstützen. " +
                    "Deine Auswahl kannst du jederzeit unter „Werbe-Einstellungen“ ändern."
            )
            .setCancelable(false)
            .setPositiveButton("Weiter") { _, _ ->
                prefs.edit().putBoolean("intro_seen", true).apply()
                then()
            }
            .show()
    }

    private fun showMusic() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        val now = text("", 16f, TEXT, true).apply { maxLines = 2 }
        box.addView(now)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val play = darkButton("▶") { music.toggle() }
        controls.addView(darkButton("⏮") { music.previous() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
        controls.addView(play, LinearLayout.LayoutParams(0, WRAP, 1.4f).apply { rightMargin = dp(6) })
        controls.addView(darkButton("⏭") { music.next() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(controls, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        val modes = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val shuffle = darkButton("") { music.shuffle = !music.shuffle }
        val repeat = darkButton("") { music.repeat = !music.repeat }
        modes.addView(shuffle, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
        modes.addView(repeat, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(modes, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        box.addView(text("Lautstärke der Musik", 13f, DIM), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        val vol = android.widget.SeekBar(this).apply {
            max = 100
            progress = (music.volume * 100).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(GOLD)
            thumbTintList = android.content.res.ColorStateList.valueOf(GOLD)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar, p: Int, user: Boolean) {
                    if (user) music.volume = p / 100f
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar) {}
            })
        }
        box.addView(vol)
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listBox) }
        box.addView(scroll, LinearLayout.LayoutParams(MATCH, dp(180)).apply { topMargin = dp(8) })
        box.addView(darkButton("＋  Dateien hinzufügen (Musik oder Video)") {
            pickMusic.launch(arrayOf("audio/*", "video/*"))
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        box.addView(text("Es läuft nur der Ton. Die Musik läuft auch weiter, wenn du eine andere App öffnest. In der Benachrichtigung steuerst du sie.", 12f, DIM), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        val refresh = {
            val t = music.current()
            now.text = if (t == null) "Noch keine Titel gewählt" else (if (music.playing) "▶  " else "⏸  ") + t.name
            play.text = if (music.playing) "⏸" else "▶"
            shuffle.text = if (music.shuffle) "🔀 Zufall: an" else "🔀 Zufall: aus"
            repeat.text = if (music.repeat) "🔁 Wiederholen: an" else "🔁 Wiederholen: aus"
            listBox.removeAllViews()
            for ((i, tr) in music.tracks.withIndex()) {
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(2), 0, dp(2))
                }
                row.addView(text(tr.name, 15f, if (i == music.index) GOLD else TEXT, i == music.index).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(8), 0, dp(8))
                    setOnClickListener { music.play(i) }
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(text("✕", 17f, DIM).apply {
                    gravity = Gravity.CENTER
                    contentDescription = "Titel entfernen"
                    setOnClickListener { music.remove(i) }
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
                listBox.addView(row)
            }
        }
        refresh()
        musicRefresh = refresh
        AlertDialog.Builder(this)
            .setTitle("🎵  Musik")
            .setView(box)
            .setPositiveButton("Fertig", null)
            .setNeutralButton("Liste leeren") { _, _ -> music.clear() }
            .setOnDismissListener { musicRefresh = null }
            .show()
    }

    private fun showRules() {
        AlertDialog.Builder(this)
            .setTitle("Spielregeln")
            .setMessage(
                "Ziel: Bringe als Erster alle vier Figuren auf die Zielfelder deiner Farbe.\n\n" +
                    "• Gewürfelt wird reihum. Mit einer 6 kommt eine Figur aus dem Haus auf dein Startfeld und du würfelst gleich noch einmal.\n" +
                    "• Hast du keine Figur auf dem Brett, darfst du bis zu dreimal würfeln, um eine 6 zu bekommen.\n" +
                    "• Du ziehst eine Figur um die gewürfelte Zahl im Uhrzeigersinn. Tippe auf die Figur, die ziehen soll.\n" +
                    "• Landest du auf einer fremden Figur, wird sie geschlagen und muss zurück ins Haus.\n" +
                    "• Eigene Figuren dürfen nicht auf eigene Figuren ziehen. Steht eine Figur auf dem Startfeld, muss sie bei einer 6 zuerst weiterziehen.\n" +
                    "• Nach einem Umlauf geht es in die Zielfelder. Dort musst du die Zahl nicht genau treffen, aber nicht über das letzte Feld hinaus.\n\n" +
                    "Tipp: Ein Druck auf die Mitte des Bretts wechselt zwischen den sechs Designs A bis F (Holz, Grün und Stein). Das geht auch im Menü."
            )
            .setPositiveButton("Verstanden", null)
            .show()
    }

    // ------------------------------------------------------------------ Spielbrett

    private fun frameBox(label: String, onClick: () -> Unit): TextView = text(label, 24f, Color.parseColor("#F3DF9A")).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#123425"))
            setStroke(dp(1), Color.parseColor("#496D53"))
            cornerRadius = dp(14).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun buildGame() {
        gameView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(6))
        }
        // Kopfzeile: Zurück, Titel mit Spieler am Zug, Musik
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(frameBox("‹") { leaveGame() }.apply { contentDescription = "Zurück zum Menü" }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        heading.addView(text("ZURÜCK", 10f, Color.parseColor("#D7B45A"), true).apply {
            letterSpacing = 0.19f
            gravity = Gravity.CENTER
        })
        status = text("", 22f, Color.parseColor("#F7EFD9"), true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(13, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        heading.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))
        top.addView(heading, LinearLayout.LayoutParams(0, WRAP, 1f))
        musicTop = frameBox("♫") { showMusic() }.apply {
            alpha = 0.65f
            contentDescription = "Musik"
        }
        top.addView(musicTop, LinearLayout.LayoutParams(dp(42), dp(42)))
        gameView.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        // Zeile: wer ist am Zug (mit pulsierendem Punkt)
        val turn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        pulseDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D94B45"))
            }
        }
        turn.addView(pulseDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(8) })
        turnText = text("", 13f, Color.parseColor("#E6E4CF")).apply {
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        turn.addView(turnText, LinearLayout.LayoutParams(0, WRAP, 1f))
        gameView.addView(turn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8); bottomMargin = dp(4) })
        android.animation.ObjectAnimator.ofFloat(pulseDot, "alpha", 1f, 0.25f).apply {
            duration = 800
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            start()
        }

        // Brett (mit Goldrahmen, gezeichnet in der BoardView)
        board = BoardView(this)
        board.onFigure = { color, fig -> onFigureTapped(color, fig) }
        board.style = STYLE_ORDER[prefs.getInt("design_pos", 0).coerceIn(0, 5)]
        board.onCenterTap = {
            cycleStyle()
            board.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        gameView.addView(board, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // Spielerstand
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val boxes = ArrayList<LinearLayout>()
        val names = ArrayList<TextView>()
        val subs = ArrayList<TextView>()
        val dots = ArrayList<View>()
        for (i in 0 until 4) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(7), dp(4), dp(6))
            }
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val dot = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            }
            line.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(4) })
            val nm = text("", 12f, Color.parseColor("#F7EFD9"), true).apply { maxLines = 1 }
            line.addView(nm)
            box.addView(line)
            val sb = text("", 11f, Color.parseColor("#C7D2C9")).apply { gravity = Gravity.CENTER }
            box.addView(sb)
            boxes.add(box); names.add(nm); subs.add(sb); dots.add(dot)
            chipRow.addView(box, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        chipBoxes = boxes; chipNames = names; chipSubs = subs; chipDots = dots
        gameView.addView(chipRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        // Karte mit "Dein Zug" und Würfelknopf
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#173D2B"), Color.parseColor("#0C2419"))
            ).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.parseColor("#B18C3D"))
            }
        }
        val label = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ctrlTitle = text("Dein Zug", 15f, Color.parseColor("#F7EFD9"), true)
        ctrlAttempts = text("", 12f, Color.parseColor("#B8C8BD")).apply { gravity = Gravity.END }
        label.addView(ctrlTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
        label.addView(ctrlAttempts, LinearLayout.LayoutParams(WRAP, WRAP))
        card.addView(label, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })

        dice = DiceView(this)
        dice.show(0)
        rollText = text("Würfeln", 17f, Color.parseColor("#17281B"), true)
        rollBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(14))
            val edge = GradientDrawable().apply { setColor(Color.parseColor("#8D6324")); cornerRadius = dp(16).toFloat() }
            val face = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#F5D77D"), Color.parseColor("#C99A37"))
            ).apply { cornerRadius = dp(16).toFloat() }
            background = android.graphics.drawable.LayerDrawable(arrayOf(edge, face)).apply {
                setLayerInset(1, 0, 0, 0, dp(4))
            }
            isClickable = true
            setOnClickListener { onDiceTapped() }
        }
        rollBtn.addView(dice, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(12) })
        rollBtn.addView(rollText)
        card.addView(rollBtn, LinearLayout.LayoutParams(MATCH, WRAP))
        // die ganze Karte ist ein großer Würfelknopf (bessere Trefferquote)
        card.isClickable = true
        card.setOnClickListener { onDiceTapped() }
        gameView.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        bottomHint = text("", 12f, Color.parseColor("#B9CABE")).apply { gravity = Gravity.CENTER }
        gameView.addView(bottomHint, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        gameView.visibility = View.GONE
    }

    private fun setTurn(turn: String, hintText: String = "") {
        turnText.text = turn
        bottomHint.text = hintText
    }

    private fun setRoll(title: String, attempts: String, roll: String) {
        ctrlTitle.text = title
        ctrlAttempts.text = attempts
        rollText.text = roll
    }

    private fun playerName(color: Int): String {
        val g = game ?: return BoardView.names[color]
        val n = g.seat(color).name
        return if (n.isBlank()) BoardView.names[color] else n
    }

    private fun startNewGame() {
        syncNames()
        saveSetup()
        val seats = (0 until 4).map { Seat(it, kinds[it], names[it]) }
        game = Game(seats)
        prefs.edit().remove("game").apply()
        enterGame()
        beginTurn(false)
    }

    private fun resumeGame() {
        val g = loadGame() ?: return
        game = g
        easy = prefs.getBoolean("easy", false)
        enterGame()
        beginTurn(false)
    }

    private fun enterGame() {
        session++
        busy = false
        setupView.visibility = View.GONE
        footerView?.visibility = View.GONE
        gameView.visibility = View.VISIBLE
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        board.game = game
        board.movable = emptySet()
        dice.show(0)
        updateChips()
    }

    private fun leaveGame() {
        saveGame()
        session++
        busy = false
        board.movable = emptySet()
        showSetup()
    }

    override fun onBackPressed() {
        if (gameView.visibility == View.VISIBLE) leaveGame() else super.onBackPressed()
    }

    private fun updateChips() {
        val g = game ?: return
        for (i in 0 until 4) {
            val box = chipBoxes[i]
            if (g.seat(i).kind == Kind.OFF) {
                box.visibility = View.INVISIBLE
                continue
            }
            box.visibility = View.VISIBLE
            chipNames[i].text = playerName(i)
            chipSubs[i].text = "${g.finished(i)} von 4"
            (chipDots[i].background as GradientDrawable).setColor(BoardView.palette[i])
            val cur = g.current == i
            box.background = GradientDrawable().apply {
                cornerRadius = dp(13).toFloat()
                setColor(if (cur) Color.parseColor("#22FFFFFF") else Color.parseColor("#12FFFFFF"))
                setStroke(dp(if (cur) 2 else 1), if (cur) BoardView.palette[i] else Color.parseColor("#33FFFFFF"))
            }
        }
    }

    // ------------------------------------------------------------------ Ablauf

    private fun later(ms: Long, block: () -> Unit) {
        val s = session
        main.postDelayed({ if (s == session && game != null) block() }, ms)
    }

    private fun beginTurn(bonus: Boolean) {
        val g = game ?: return
        busy = false
        board.movable = emptySet()
        board.resumeIdle()
        dice.accent = BoardView.palette[g.current]
        updateChips()
        saveGame()
        val name = playerName(g.current)
        status.text = if (bonus) "$name: noch einmal!" else "$name ist dran"
        (pulseDot.background as GradientDrawable).setColor(BoardView.palette[g.current])
        if (g.seat(g.current).kind == Kind.COMPUTER) {
            setTurn("Computer ist am Zug")
            setRoll(name, "", "Computer würfelt …")
            rollBtn.alpha = 0.6f
            dice.enabledLook = false
            later(800) { rollDice() }
        } else {
            dice.enabledLook = true
            rollBtn.alpha = 1f
            setTurn("$name ist am Zug – würfle, um weiterzuspielen", "Tippe auf den Würfel, um zu würfeln.")
            setRoll("Dein Zug", if (g.needsThreeTries(g.current)) "Noch 3 Versuche für eine 6" else "", if (bonus) "Noch einmal würfeln" else "Würfeln")
        }
    }

    private fun onDiceTapped() {
        val g = game ?: return
        if (busy || g.dice != 0 || g.seat(g.current).kind != Kind.HUMAN) return
        rollDice()
    }

    private fun rollDice() {
        val g = game ?: return
        if (busy) return
        busy = true
        val value = g.roll()
        dice.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        board.rollCube(value)
        dice.roll(value) { later(50) { afterRoll() } }
    }

    private fun afterRoll() {
        val g = game ?: return
        val moves = g.legalMoves(g.current, g.dice)
        val human = g.seat(g.current).kind == Kind.HUMAN
        if (moves.isEmpty()) {
            setTurn("Kein Zug möglich")
            later(1000) {
                val again = g.noMoveAfterRoll()
                if (again) {
                    status.text = "${playerName(g.current)}: noch ein Versuch"
                    busy = false
                    if (g.seat(g.current).kind == Kind.COMPUTER) later(600) { rollDice() } else {
                        setTurn("Noch ein Versuch", "Tippe auf den Würfel, um zu würfeln.")
                        setRoll("Dein Zug", "", "Nochmal würfeln")
                    }
                } else {
                    beginTurn(false)
                }
            }
            return
        }
        if (human && moves.size > 1) {
            setTurn("Wähle eine blinkende Figur", "Tippe auf eine blinkende Figur, um zu ziehen.")
            board.movable = moves.map { it.color * 4 + it.fig }.toSet()
            busy = false
            return
        }
        // Computer oder nur ein möglicher Zug
        val m = if (human) moves[0] else Ai.choose(g, moves, easy)
        if (human) setTurn("Es gibt nur einen Zug") else setTurn("${playerName(g.current)} zieht")
        later(if (human) 500 else 700) { doMove(m) }
    }

    private fun onFigureTapped(color: Int, fig: Int) {
        val g = game ?: return
        if (busy || color != g.current) return
        val m = g.legalMoves(color, g.dice).firstOrNull { it.fig == fig } ?: return
        doMove(m)
    }

    private fun doMove(m: Move) {
        val g = game ?: return
        busy = true
        board.movable = emptySet()
        val res = g.apply(m)
        // Geschlagene Figuren bleiben auf dem Feld, bis die ziehende angekommen ist.
        val target = res.path.last()
        for ((c, i) in res.captured) board.holdAt(c, i, target)
        board.animatePath(m.color, m.fig, res.path, 160) {
            if (res.captured.isNotEmpty()) {
                board.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                var left = res.captured.size
                for ((c, i) in res.captured) {
                    val home = Game.cellOf(c, i, -1)
                    board.animatePath(c, i, listOf(target, home), 320) {
                        left--
                        if (left == 0) afterMove()
                    }
                }
            } else {
                afterMove()
            }
        }
    }

    private fun afterMove() {
        val g = game ?: return
        updateChips()
        if (g.winner >= 0) {
            showWinner(g.winner)
            return
        }
        val again = g.afterMove()
        beginTurn(again)
    }

    private fun showWinner(color: Int) {
        prefs.edit().remove("game").apply()
        status.text = "${playerName(color)} hat gewonnen!"
        setTurn("Glückwunsch!")
        AlertDialog.Builder(this)
            .setTitle("🏆  ${playerName(color)} gewinnt!")
            .setMessage("Alle vier Figuren sind im Ziel.")
            .setCancelable(false)
            .setPositiveButton("Neues Spiel") { _, _ -> startNewGame() }
            .setNegativeButton("Zum Menü") { _, _ -> leaveToMenu() }
            .show()
    }

    private fun leaveToMenu() {
        session++
        game = null
        board.game = null
        showSetup()
    }

    // ------------------------------------------------------------------ Speichern

    private fun saveSetup() {
        val a = JSONArray()
        for (i in 0 until 4) a.put(JSONObject().put("k", kinds[i].name).put("n", names[i]))
        prefs.edit().putString("setup", a.toString()).putBoolean("easy", easy).apply()
    }

    private fun loadSetup() {
        easy = prefs.getBoolean("easy", false)
        try {
            val a = JSONArray(prefs.getString("setup", null) ?: return)
            for (i in 0 until 4) {
                val o = a.getJSONObject(i)
                kinds[i] = Kind.valueOf(o.getString("k"))
                names[i] = o.optString("n", "")
            }
        } catch (e: Exception) {
            // Standardwerte
        }
    }

    private fun saveGame() {
        val g = game ?: return
        if (g.winner >= 0) return
        val o = JSONObject()
        o.put("seats", JSONArray().apply {
            g.seats.forEach { put(JSONObject().put("k", it.kind.name).put("n", it.name)) }
        })
        o.put("pos", JSONArray().apply {
            for (c in 0 until 4) put(JSONArray().apply { for (f in 0 until 4) put(g.pos[c][f]) })
        })
        o.put("cur", g.current)
        o.put("tries", g.triesLeft)
        prefs.edit().putString("game", o.toString()).putBoolean("easy", easy).apply()
    }

    private fun loadGame(): Game? = try {
        val o = JSONObject(prefs.getString("game", null) ?: return null)
        val sa = o.getJSONArray("seats")
        val seats = (0 until 4).map {
            val s = sa.getJSONObject(it)
            Seat(it, Kind.valueOf(s.getString("k")), s.optString("n", ""))
        }
        val g = Game(seats)
        val pa = o.getJSONArray("pos")
        for (c in 0 until 4) for (f in 0 until 4) g.pos[c][f] = pa.getJSONArray(c).getInt(f)
        g.current = o.getInt("cur")
        g.triesLeft = 0
        g
    } catch (e: Exception) {
        null
    }

    override fun onPause() {
        if (gameView.visibility == View.VISIBLE && !busy) saveGame()
        if (::adManager.isInitialized) adManager.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::adManager.isInitialized) adManager.resume()
    }

    override fun onDestroy() {
        if (::music.isInitialized) music.removeListener(musicListener)
        clickSound?.release()
        clickSound = null
        if (::adManager.isInitialized) adManager.destroy()
        super.onDestroy()
    }
}
