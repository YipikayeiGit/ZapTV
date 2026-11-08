package com.zap.iptv

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.abs

/**
 * Reproductor alternativo (VLC) para formatos que el principal no abre: AVI/DivX/XviD,
 * H.264 de 10 bits, etc. Decodifica en el propio dispositivo, sin pedir nada al servidor:
 * se ve el archivo original.
 *
 * Mando: ← → avanzan/retroceden (cada vez más), OK pausa, ↓ audio y subtítulos, ↑ información.
 */
class VlcPlayerActivity : AppCompatActivity() {

    private lateinit var libVlc: LibVLC
    private lateinit var mp: MediaPlayer
    private lateinit var label: TextView
    private lateinit var loading: View
    private lateinit var bar: View
    private val handler = Handler(Looper.getMainLooper())
    private val hideLabel = Runnable { label.animate().alpha(0f).setDuration(300).start() }
    private val hideBar = Runnable { if (mp.isPlaying) bar.isVisible = false }
    private val resume by lazy { getSharedPreferences("resume", MODE_PRIVATE) }

    private var item: PlayItem? = null
    private var startAt = 0L
    private var started = false
    private var tracksApplied = false
    private var scrobbled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val it = Playback.item
        if (it == null) {
            finish()
            return
        }
        item = it
        startAt = intent.getLongExtra(EXTRA_START, -1L).takeIf { s -> s >= 0 }
            ?: if (it.fromStart) 0L else it.resumeKey?.let { k -> resume.getLong(k, 0L) } ?: 0L

        setContentView(R.layout.activity_vlc)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        label = findViewById(R.id.label)
        loading = findViewById(R.id.loading)
        bar = findViewById(R.id.bar)
        findViewById<TextView>(R.id.barTitle).text = it.title

        val options = arrayListOf(
            "--network-caching=3000",
            "--file-caching=3000",
            "--audio-time-stretch",
            "--sub-text-scale=${(SubStyle.get(this, SubStyle.SIZE).let { s -> listOf(80, 100, 125, 150)[s] })}",
        )
        libVlc = LibVLC(this, options)
        mp = MediaPlayer(libVlc)
        mp.attachViews(findViewById<VLCVideoLayout>(R.id.video), null, true, false)

        val media = Media(libVlc, Uri.parse(Plex.fixUrl(it.url)))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":http-user-agent=${Http.UA}")
        mp.setMedia(media)
        media.release()
        for (s in it.subtitles) mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(s.url), false)

        mp.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Playing -> {
                    loading.isVisible = false
                    if (!started) {
                        started = true
                        if (startAt > 0) mp.setTime(startAt)
                        showLabel(it.title)
                    }
                }
                MediaPlayer.Event.Buffering -> loading.isVisible = ev.buffering < 100f
                MediaPlayer.Event.ESAdded -> if (!tracksApplied) handler.postDelayed({ applyLanguages() }, 800)
                MediaPlayer.Event.EndReached -> {
                    savePosition(finished = true)
                    finish()
                }
                MediaPlayer.Event.EncounteredError -> {
                    loading.isVisible = false
                    showLabel("No se puede reproducir este archivo ni con el reproductor alternativo.", sticky = true)
                }
            }
        }
        mp.play()
    }

    // ---------- Idiomas por defecto (por el nombre de la pista) ----------

    private fun applyLanguages() {
        if (tracksApplied) return
        tracksApplied = true
        val audio = Languages.audioOrDevice(this)
        mp.audioTracks?.filter { it.id >= 0 }
            ?.firstOrNull { matches(it.name, audio) }
            ?.let { mp.setAudioTrack(it.id) }
        val subs = Languages.subs(this)
        val spu = mp.spuTracks?.filter { it.id >= 0 }.orEmpty()
        if (subs.isEmpty()) {
            mp.setSpuTrack(-1)
        } else {
            spu.filter { matches(it.name, subs) }
                .minByOrNull { if (it.name.contains("forz", true) || it.name.contains("forced", true)) 1 else 0 }
                ?.let { mp.setSpuTrack(it.id) }
        }
    }

    private fun matches(name: String?, code: String): Boolean {
        val f = androidx.media3.common.Format.Builder().setLabel(name).build()
        return Languages.matches(f, code)
    }

    // ---------- Mando ----------

    private val swallowed = HashSet<Int>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && swallowed.remove(code)) return true
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val handled = when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekStep(+1); true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { seekStep(-1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.repeatCount == 0) togglePause()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.repeatCount == 0) showTracksMenu()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.repeatCount == 0) showLabel(techInfo(), sticky = false, long = true)
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (bar.isVisible) {
                    if (event.repeatCount == 0) {
                        bar.isVisible = false
                        if (!mp.isPlaying) mp.play()
                    }
                    true
                } else {
                    false
                }
            }
            else -> false
        }
        if (handled) {
            swallowed += code
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun togglePause() {
        commitSeek()
        if (mp.isPlaying) {
            mp.pause()
            showBar(stay = true)
        } else {
            mp.play()
            showBar(stay = false)
        }
    }

    private fun showBar(stay: Boolean) {
        val len = mp.length
        val pos = mp.time
        findViewById<ProgressBar>(R.id.barProgress).progress = if (len > 0) (pos * 1000 / len).toInt() else 0
        findViewById<TextView>(R.id.barTime).text =
            "${clock(pos)} / ${clock(len)}" + if (!mp.isPlaying) "   ·   Pausa (OK para seguir · ↓ audio y subtítulos)" else ""
        bar.isVisible = true
        handler.removeCallbacks(hideBar)
        if (!stay) handler.postDelayed(hideBar, 3_000)
    }

    private fun showTracksMenu() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Audio y subtítulos")
            .setItems(arrayOf("Audio", "Subtítulos")) { _, which ->
                if (which == 0) pickTrack(audio = true) else pickTrack(audio = false)
            }
            .show()
    }

    private fun pickTrack(audio: Boolean) {
        val tracks = (if (audio) mp.audioTracks else mp.spuTracks)?.toList().orEmpty()
        if (tracks.isEmpty()) {
            showLabel(if (audio) "No hay otras pistas de audio" else "Este vídeo no tiene subtítulos")
            return
        }
        val current = if (audio) mp.audioTrack else mp.spuTrack
        val names = tracks.map { if (it.id < 0) "Desactivados" else it.name }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (audio) "Audio" else "Subtítulos")
            .setSingleChoiceItems(names.toTypedArray(), tracks.indexOfFirst { it.id == current }) { d, which ->
                if (audio) mp.setAudioTrack(tracks[which].id) else mp.setSpuTrack(tracks[which].id)
                d.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ---------- Avanzar / retroceder (igual que el reproductor principal) ----------

    private var seekDir = 0
    private var seekCount = 0
    private var seekOffset = 0L
    private val seekCommit = Runnable { commitSeek() }

    private fun seekStep(dir: Int) {
        if (dir != seekDir && seekOffset != 0L) commitSeek()
        seekDir = dir
        seekCount++
        val step = when {
            seekCount <= 3 -> 30_000L
            seekCount <= 6 -> 60_000L
            seekCount <= 9 -> 120_000L
            else -> 300_000L
        }
        seekOffset += dir * step
        val sign = if (seekOffset >= 0) "⏩  +" else "⏪  −"
        showLabel("$sign${clock(abs(seekOffset))}   →   ${clock(seekTarget())}")
        handler.removeCallbacks(seekCommit)
        handler.postDelayed(seekCommit, 700)
    }

    private fun seekTarget(): Long {
        val len = mp.length
        var t = (mp.time + seekOffset).coerceAtLeast(0L)
        if (len > 0) t = t.coerceAtMost(len - 1_000L)
        return t
    }

    private fun commitSeek() {
        handler.removeCallbacks(seekCommit)
        if (seekOffset != 0L) mp.setTime(seekTarget())
        seekOffset = 0L
        seekCount = 0
        seekDir = 0
    }

    // ---------- Progreso (en Zap y en tu cuenta de Plex) ----------

    private val reporter = object : Runnable {
        override fun run() {
            reportPlex(stopped = false)
            handler.postDelayed(this, 15_000)
        }
    }

    private fun savePosition(finished: Boolean = false) {
        val it = item ?: return
        val key = it.resumeKey ?: return
        val len = mp.length
        val pos = if (finished) len else mp.time
        if (len <= 0) return
        resume.edit().apply {
            if (pos > 30_000 && pos < len * 0.95) putLong(key, pos) else remove(key)
        }.apply()
        getSharedPreferences("resume_t", MODE_PRIVATE).edit().putLong(key, System.currentTimeMillis()).apply()
        if (pos >= len * 0.9) {
            val w = getSharedPreferences("watched", MODE_PRIVATE)
            val keys = HashSet(w.getStringSet("keys", emptySet()).orEmpty())
            if (keys.add(key)) w.edit().putStringSet("keys", keys).apply()
        }
    }

    private fun reportPlex(stopped: Boolean) {
        val it = item ?: return
        val server = it.plexServer ?: return
        val key = it.plexKey ?: return
        val len = mp.length.takeIf { l -> l > 0 } ?: return
        val pos = mp.time
        val state = when {
            stopped -> "stopped"
            mp.isPlaying -> "playing"
            else -> "paused"
        }
        val finished = !scrobbled && pos > len * 0.9
        if (finished) scrobbled = true
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { Plex.timeline(server, key, state, pos, len) }
            if (finished) runCatching { Plex.scrobble(server, key) }
        }
    }

    // ---------- Etiquetas ----------

    private fun techInfo(): String = listOfNotNull(
        "Reproductor alternativo (VLC)",
        item?.techNote,
        "${clock(mp.time)} / ${clock(mp.length)}",
        mp.audioTracks?.firstOrNull { it.id == mp.audioTrack }?.let { "Audio: ${it.name}" },
        mp.spuTracks?.firstOrNull { it.id == mp.spuTrack && it.id >= 0 }?.let { "Subtítulos: ${it.name}" },
    ).joinToString("\n")

    private fun clock(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun showLabel(text: String, sticky: Boolean = false, long: Boolean = false) {
        label.text = text
        label.animate().cancel()
        label.alpha = 1f
        handler.removeCallbacks(hideLabel)
        if (!sticky) handler.postDelayed(hideLabel, if (long) 6_000 else 2_500)
    }

    // ---------- Ciclo de vida ----------

    override fun onStart() {
        super.onStart()
        if (::mp.isInitialized && started) mp.play()
        if (item?.plexKey != null) {
            handler.removeCallbacks(reporter)
            handler.postDelayed(reporter, 10_000)
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(reporter)
        if (::mp.isInitialized) {
            savePosition()
            reportPlex(stopped = true)
            mp.pause()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::mp.isInitialized) {
            mp.stop()
            mp.detachViews()
            mp.release()
            libVlc.release()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_START = "start"
    }
}
