package de.autoknigge.zurueck

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Kleiner Musikspieler für eigene Dateien (Audio und der Ton von Videos). Er spielt, solange die App
 * offen ist. Die Liste merkt sich die App nur lokal (Adresse und Name der gewählten Dateien).
 */
class MusicPlayer(private val ctx: Context, private val prefs: SharedPreferences) {

    class Track(val uri: Uri, val name: String)

    val tracks = ArrayList<Track>()
    var index = -1
        private set
    var playing = false
        private set
    var shuffle = prefs.getBoolean("music_shuffle", false)
        set(v) {
            field = v
            prefs.edit().putBoolean("music_shuffle", v).apply()
            changed()
        }
    var repeat = prefs.getBoolean("music_repeat", true)
        set(v) {
            field = v
            prefs.edit().putBoolean("music_repeat", v).apply()
            changed()
        }
    var volume = prefs.getFloat("music_volume", 0.8f)
        set(v) {
            field = v.coerceIn(0f, 1f)
            prefs.edit().putFloat("music_volume", field).apply()
            applyVolume()
        }

    private val listeners = ArrayList<() -> Unit>()

    /** Oberfläche und Benachrichtigung hören auf Änderungen. */
    fun addListener(l: () -> Unit) {
        if (l !in listeners) listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun changed() {
        listeners.toList().forEach { it() }
    }

    private var mp: MediaPlayer? = null
    private var errors = 0
    private var ducked = false
    private var resumeAfterFocus = false
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeAfterFocus = false
                    pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeAfterFocus = playing
                    pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    ducked = true
                    applyVolume()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    ducked = false
                    applyVolume()
                    if (resumeAfterFocus) {
                        resumeAfterFocus = false
                        resume()
                    }
                }
            }
        }
        .build()

    init {
        try {
            val a = JSONArray(prefs.getString("music_tracks", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                tracks.add(Track(Uri.parse(o.getString("u")), o.getString("n")))
            }
            index = prefs.getInt("music_index", -1).takeIf { it in tracks.indices } ?: -1
        } catch (e: Exception) {
            tracks.clear()
        }
    }

    private fun save() {
        val a = JSONArray()
        tracks.forEach { a.put(JSONObject().put("u", it.uri.toString()).put("n", it.name)) }
        prefs.edit().putString("music_tracks", a.toString()).putInt("music_index", index).apply()
    }

    fun nameOf(uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)?.substringBeforeLast('.') ?: uri.lastPathSegment ?: "Titel"
            }
        } catch (e: Exception) {
            // Name nicht lesbar
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Titel"
    }

    /** Fügt gewählte Dateien an. Startet nichts von selbst, außer die Liste war leer. */
    fun add(uris: List<Uri>) {
        val wasEmpty = tracks.isEmpty()
        for (u in uris) {
            try {
                ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // Nur für diese Sitzung gültig
            }
            if (tracks.none { it.uri == u }) tracks.add(Track(u, nameOf(u)))
        }
        if (tracks.isNotEmpty() && index < 0) index = 0
        save()
        changed()
        if (wasEmpty && tracks.isNotEmpty()) play(0)
    }

    fun remove(i: Int) {
        if (i !in tracks.indices) return
        val current = i == index
        tracks.removeAt(i)
        if (tracks.isEmpty()) {
            stop()
            index = -1
        } else if (current) {
            index = i.coerceAtMost(tracks.lastIndex)
            if (playing) play(index) else stopPlayerOnly()
        } else if (i < index) {
            index--
        }
        save()
        changed()
    }

    fun clear() {
        stop()
        tracks.clear()
        index = -1
        save()
        changed()
    }

    fun play(i: Int) {
        if (i !in tracks.indices) return
        index = i
        save()
        stopPlayerOnly()
        if (am.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            playing = false
            changed()
            return
        }
        val player = MediaPlayer()
        mp = player
        try {
            player.setAudioAttributes(attrs)
            player.setWakeMode(ctx, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            player.setDataSource(ctx, tracks[i].uri)
            player.setOnPreparedListener {
                if (mp === player) {
                    applyVolume()
                    player.start()
                    playing = true
                    errors = 0
                    MusicService.ensureRunning(ctx)
                    changed()
                }
            }
            player.setOnCompletionListener { if (mp === player) next(true) }
            player.setOnErrorListener { _, _, _ ->
                if (mp === player) {
                    errors++
                    if (errors < tracks.size) next(true) else stop()
                }
                true
            }
            player.prepareAsync()
            playing = true
        } catch (e: Exception) {
            errors++
            if (errors < tracks.size) next(true) else stop()
        }
        changed()
    }

    fun toggle() {
        if (tracks.isEmpty()) return
        if (playing) pause() else if (mp != null) resume() else play(if (index >= 0) index else 0)
    }

    fun pause() {
        try {
            mp?.pause()
        } catch (e: Exception) {
            // ignorieren
        }
        playing = false
        changed()
    }

    private fun resume() {
        val player = mp ?: return
        if (am.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        try {
            player.start()
            playing = true
        } catch (e: Exception) {
            playing = false
        }
        changed()
    }

    fun next(auto: Boolean = false) {
        if (tracks.isEmpty()) return
        var idx = if (shuffle && tracks.size > 1) {
            var r: Int
            do {
                r = Random.nextInt(tracks.size)
            } while (r == index)
            r
        } else {
            index + 1
        }
        if (idx >= tracks.size) {
            if (auto && !repeat) {
                stop()
                index = 0
                save()
                changed()
                return
            }
            idx = 0
        }
        play(idx)
    }

    fun previous() {
        if (tracks.isEmpty()) return
        val pos = try {
            mp?.currentPosition ?: 0
        } catch (e: Exception) {
            0
        }
        if (pos > 3000) play(index) else play(if (index <= 0) tracks.lastIndex else index - 1)
    }

    private fun stopPlayerOnly() {
        mp?.let {
            try {
                it.setOnCompletionListener(null)
                it.setOnErrorListener(null)
                it.release()
            } catch (e: Exception) {
                // ignorieren
            }
        }
        mp = null
    }

    fun stop() {
        stopPlayerOnly()
        playing = false
        try {
            am.abandonAudioFocusRequest(focusRequest)
        } catch (e: Exception) {
            // ignorieren
        }
        changed()
    }

    private fun applyVolume() {
        val v = if (ducked) volume * 0.3f else volume
        try {
            mp?.setVolume(v, v)
        } catch (e: Exception) {
            // ignorieren
        }
    }

    fun release() {
        stop()
    }

    fun current(): Track? = tracks.getOrNull(index)
}
