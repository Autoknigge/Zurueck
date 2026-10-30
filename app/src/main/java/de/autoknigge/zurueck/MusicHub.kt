package de.autoknigge.zurueck

import android.content.Context

/** Ein Musikspieler für den ganzen App-Prozess: Oberfläche und Hintergrunddienst nutzen denselben. */
object MusicHub {
    @Volatile private var player: MusicPlayer? = null

    fun get(ctx: Context): MusicPlayer {
        val app = ctx.applicationContext
        return player ?: synchronized(this) {
            player ?: MusicPlayer(app, app.getSharedPreferences("zurueck", Context.MODE_PRIVATE)).also { player = it }
        }
    }
}
