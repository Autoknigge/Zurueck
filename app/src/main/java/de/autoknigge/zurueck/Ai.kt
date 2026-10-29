package de.autoknigge.zurueck

import kotlin.random.Random

/** Computergegner: bewertet jeden erlaubten Zug und nimmt meist den besten. */
object Ai {

    fun choose(game: Game, moves: List<Move>, easy: Boolean): Move {
        if (moves.size == 1) return moves[0]
        if (easy && Random.nextInt(100) < 45) return moves.random()
        return moves.maxByOrNull { score(game, it) + Random.nextFloat() * 3f } ?: moves[0]
    }

    private fun score(g: Game, m: Move): Float {
        val c = m.color
        var s = 0f
        if (m.enters) s += 70f
        if (m.to >= Game.TRACK) s += 40f + (m.to - 39) * 2f
        s += m.to * 0.35f
        if (m.to in 0 until Game.TRACK) {
            val r = (Game.START[c] + m.to) % Game.TRACK
            // Schlagen bringt am meisten, vor allem weit gelaufene Figuren.
            for (o in 0 until 4) {
                if (o == c) continue
                for (i in 0 until 4) {
                    val p = g.pos[o][i]
                    if (g.ringOf(o, p) == r) s += 100f + p * 0.5f
                }
            }
            s -= danger(g, c, r) * 28f
        }
        if (m.from in 0 until Game.TRACK) {
            // Gefährdete Figur in Sicherheit bringen.
            s += danger(g, c, (Game.START[c] + m.from) % Game.TRACK) * 22f
        }
        return s
    }

    /** Wie viele Gegnerfiguren könnten das Feld [r] mit einem Wurf erreichen? */
    private fun danger(g: Game, me: Int, r: Int): Float {
        var d = 0f
        for (o in 0 until 4) {
            if (o == me || g.seat(o).kind == Kind.OFF) continue
            for (i in 0 until 4) {
                val q = g.ringOf(o, g.pos[o][i])
                if (q < 0) continue
                val dist = (r - q + Game.TRACK) % Game.TRACK
                if (dist in 1..6) d += 1f
            }
            if (Game.START[o] == r && g.pos[o].any { it < 0 }) d += 0.5f
        }
        return d
    }
}
