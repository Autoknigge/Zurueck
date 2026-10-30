package de.autoknigge.zurueck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Hält die Musik am Laufen, wenn eine andere App geöffnet wird oder der Bildschirm aus ist.
 * Zeigt dafür eine Benachrichtigung mit Zurück, Pause/Play, Weiter und Beenden.
 */
class MusicService : Service() {

    companion object {
        private const val CHANNEL = "musik"
        private const val ID = 11
        private const val A_TOGGLE = "de.autoknigge.zurueck.TOGGLE"
        private const val A_NEXT = "de.autoknigge.zurueck.NEXT"
        private const val A_PREV = "de.autoknigge.zurueck.PREV"
        private const val A_CLOSE = "de.autoknigge.zurueck.CLOSE"

        fun ensureRunning(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MusicService::class.java))
            } catch (e: Exception) {
                // Start im Hintergrund nicht erlaubt: die Musik läuft dann nur, solange die App offen ist.
            }
        }
    }

    private lateinit var player: MusicPlayer
    private val listener: () -> Unit = { refresh() }

    override fun onCreate() {
        super.onCreate()
        player = MusicHub.get(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Musik", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Steuerung der Musik im Spiel"
                setShowBadge(false)
            }
        )
        player.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_TOGGLE -> player.toggle()
            A_NEXT -> player.next()
            A_PREV -> player.previous()
            A_CLOSE -> {
                player.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Nach startForegroundService muss sofort eine Benachrichtigung erscheinen.
        startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        refresh()
        return START_NOT_STICKY
    }

    private fun action(a: String, icon: Int, label: String, code: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, icon), label,
            PendingIntent.getService(
                this, code, Intent(this, MusicService::class.java).setAction(a),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ).build()

    private fun build(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val title = player.current()?.name ?: "Musik"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText("Zurück · Musik")
            .setContentIntent(open)
            .setOngoing(player.playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(A_PREV, android.R.drawable.ic_media_previous, "Zurück", 1))
            .addAction(
                if (player.playing) action(A_TOGGLE, android.R.drawable.ic_media_pause, "Pause", 2)
                else action(A_TOGGLE, android.R.drawable.ic_media_play, "Abspielen", 2)
            )
            .addAction(action(A_NEXT, android.R.drawable.ic_media_next, "Weiter", 3))
            .addAction(action(A_CLOSE, android.R.drawable.ic_menu_close_clear_cancel, "Beenden", 4))
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun refresh() {
        val nm = getSystemService(NotificationManager::class.java)
        if (player.tracks.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (player.playing) {
            startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            // Pause: Benachrichtigung bleibt, lässt sich aber wegwischen.
            stopForeground(STOP_FOREGROUND_DETACH)
            nm.notify(ID, build())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        player.removeListener(listener)
        super.onDestroy()
    }
}
