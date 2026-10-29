package de.autoknigge.zurueck

import kotlin.random.Random

/** Wer sitzt auf einem Platz? */
enum class Kind { HUMAN, COMPUTER, OFF }

/** Ein Platz am Tisch. [color]: 0 Rot, 1 Grün, 2 Gelb, 3 Blau. */
class Seat(val color: Int, var kind: Kind, var name: String)

/** Ein möglicher Zug: Figur [fig] von Farbe [color] um [dice] Felder. */
class Move(val color: Int, val fig: Int, val from: Int, val to: Int, val enters: Boolean)

/** Ergebnis eines ausgeführten Zuges für die Animation. */
class MoveResult(
    val path: List<Pair<Int, Int>>,
    val captured: List<Pair<Int, Int>>, // (Farbe, Figur)
    val reachedLane: Boolean
)

/**
 * Spielregeln: 40 Felder im Kreis, je Farbe 4 Figuren, 4 Zielfelder.
 * Fortschritt p einer Figur: -1 = im Haus, 0 … 39 = auf dem Brett (0 = eigenes Startfeld),
 * 40 … 43 = Zielfelder.
 */
class Game(val seats: List<Seat>) {

    companion object {
        const val TRACK = 40
        const val LAST = 43

        /** Die 40 Felder im Uhrzeigersinn als (Spalte, Zeile) auf dem 11×11-Raster. */
        val TRACK_CELLS: List<Pair<Int, Int>> = listOf(
            0 to 4, 1 to 4, 2 to 4, 3 to 4, 4 to 4, 4 to 3, 4 to 2, 4 to 1, 4 to 0, 5 to 0,
            6 to 0, 6 to 1, 6 to 2, 6 to 3, 6 to 4, 7 to 4, 8 to 4, 9 to 4, 10 to 4, 10 to 5,
            10 to 6, 9 to 6, 8 to 6, 7 to 6, 6 to 6, 6 to 7, 6 to 8, 6 to 9, 6 to 10, 5 to 10,
            4 to 10, 4 to 9, 4 to 8, 4 to 7, 4 to 6, 3 to 6, 2 to 6, 1 to 6, 0 to 6, 0 to 5
        )

        /** Startfelder (Index in TRACK_CELLS) je Farbe. */
        val START = intArrayOf(0, 10, 20, 30)

        val LANE: List<List<Pair<Int, Int>>> = listOf(
            listOf(1 to 5, 2 to 5, 3 to 5, 4 to 5),
            listOf(5 to 1, 5 to 2, 5 to 3, 5 to 4),
            listOf(9 to 5, 8 to 5, 7 to 5, 6 to 5),
            listOf(5 to 9, 5 to 8, 5 to 7, 5 to 6)
        )

        val HOME: List<List<Pair<Int, Int>>> = listOf(
            listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1),
            listOf(9 to 0, 10 to 0, 9 to 1, 10 to 1),
            listOf(9 to 9, 10 to 9, 9 to 10, 10 to 10),
            listOf(0 to 9, 1 to 9, 0 to 10, 1 to 10)
        )

        fun cellOf(color: Int, fig: Int, p: Int): Pair<Int, Int> = when {
            p < 0 -> HOME[color][fig]
            p < TRACK -> TRACK_CELLS[(START[color] + p) % TRACK]
            else -> LANE[color][p - TRACK]
        }
    }

    /** pos[Farbe][Figur] */
    val pos = Array(4) { IntArray(4) { -1 } }
    var current = seats.first { it.kind != Kind.OFF }.color
    var dice = 0
    var triesLeft = 3
    var winner = -1

    val active: List<Int> get() = seats.filter { it.kind != Kind.OFF }.map { it.color }

    fun seat(color: Int): Seat = seats.first { it.color == color }

    /** Feldnummer auf dem Ring, wenn die Figur auf dem Brett steht, sonst -1. */
    private fun ring(color: Int, p: Int): Int = if (p in 0 until TRACK) (START[color] + p) % TRACK else -1

    private fun homeCount(color: Int) = pos[color].count { it < 0 }

    /** Muss der Spieler bis zu drei Mal würfeln (keine Figur auf dem Brett, die ziehen könnte)? */
    fun needsThreeTries(color: Int): Boolean {
        val onTrack = pos[color].any { it in 0 until TRACK }
        val laneMovable = pos[color].any { p ->
            p in TRACK until LAST && (p + 1..LAST).any { c -> pos[color].none { it == c } }
        }
        return !onTrack && !laneMovable
    }

    /** Alle erlaubten Züge für die Augenzahl [d]. */
    fun legalMoves(color: Int, d: Int): List<Move> {
        val out = ArrayList<Move>()
        val firstHome = pos[color].indexOfFirst { it < 0 }
        val startOwn = pos[color].indexOfFirst { it == 0 }
        if (d == 6 && firstHome >= 0) {
            if (startOwn < 0) {
                // Mit der 6 muss eine Figur herauskommen.
                out.add(Move(color, firstHome, -1, 0, true))
                return out
            }
            // Startfeld ist durch die eigene Figur belegt: sie muss erst weiterziehen.
            val m = moveFor(color, startOwn, d)
            if (m != null) return listOf(m)
        }
        for (i in 0 until 4) {
            val p = pos[color][i]
            if (p < 0) continue
            moveFor(color, i, d)?.let { out.add(it) }
        }
        return out
    }

    private fun moveFor(color: Int, fig: Int, d: Int): Move? {
        val p = pos[color][fig]
        val np = p + d
        if (np > LAST) return null
        if (pos[color].any { it == np }) return null
        return Move(color, fig, p, np, false)
    }

    /** Führt den Zug aus und liefert den Weg und geschlagene Figuren für die Animation. */
    fun apply(m: Move): MoveResult {
        val path = ArrayList<Pair<Int, Int>>()
        val captured = ArrayList<Pair<Int, Int>>()
        val from = m.from
        if (m.enters) {
            path.add(cellOf(m.color, m.fig, -1))
            path.add(cellOf(m.color, m.fig, 0))
        } else {
            for (q in from..m.to) path.add(cellOf(m.color, m.fig, q))
        }
        pos[m.color][m.fig] = m.to
        if (m.to in 0 until TRACK) {
            val r = ring(m.color, m.to)
            for (c in 0 until 4) {
                if (c == m.color) continue
                for (i in 0 until 4) {
                    if (ring(c, pos[c][i]) == r) {
                        pos[c][i] = -1
                        captured.add(c to i)
                    }
                }
            }
        }
        if (pos[m.color].all { it >= TRACK }) winner = m.color
        return MoveResult(path, captured, m.to >= TRACK)
    }

    /** Wirft den Würfel für den aktuellen Spieler (nur Auswertung; die Animation macht die Oberfläche). */
    fun roll(): Int {
        if (triesLeft == 0) triesLeft = if (needsThreeTries(current)) 3 else 1
        dice = Random.nextInt(1, 7)
        return dice
    }

    /** Nach einem Wurf ohne Zug: noch einmal würfeln (Dreier-Versuche) oder der nächste ist dran. Gibt true bei Wiederholung. */
    fun noMoveAfterRoll(): Boolean {
        triesLeft--
        dice = 0
        if (triesLeft > 0) return true
        next()
        return false
    }

    /** Nach einem Zug: bei einer 6 darf nochmal gewürfelt werden, sonst ist der Nächste dran. */
    fun afterMove(): Boolean {
        val again = dice == 6 && winner < 0
        dice = 0
        triesLeft = 0
        if (!again) next()
        return again
    }

    fun next() {
        val a = active
        var i = a.indexOf(current)
        do {
            i = (i + 1) % a.size
        } while (pos[a[i]].all { it >= TRACK } && a.size > 1)
        current = a[i]
        triesLeft = 0
        dice = 0
    }

    /** Anzahl der Figuren im Ziel. */
    fun finished(color: Int) = pos[color].count { it >= TRACK }

    /** Fortschritt der Figur für die Bewertung (0 … 43, -1 = Haus). */
    fun ringOf(color: Int, p: Int) = ring(color, p)
}
