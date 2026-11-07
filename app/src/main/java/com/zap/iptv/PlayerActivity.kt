package com.zap.iptv

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlin.math.abs

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var label: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val hideLabel = Runnable { label.animate().alpha(0f).setDuration(300).start() }
    private val resume by lazy { getSharedPreferences("resume", MODE_PRIVATE) }

    private var live = true
    private val channels get() = Playback.live
    private var index = 0
    private var triedHls = false

    private var item: PlayItem? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        live = intent.getStringExtra(EXTRA_MODE) != MODE_VOD
        item = if (live) null else Playback.item
        if ((live && channels.isEmpty()) || (!live && item == null)) {
            finish()
            return
        }
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        playerView = findViewById(R.id.player)
        label = findViewById(R.id.label)

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        // Si el Fire TV no sabe decodificar un audio (DTS, TrueHD…), lo hace FFmpeg en la app
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        // Idiomas elegidos en Ajustes (si el vídeo los tiene)
        val subLang = Languages.subs(this)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(Languages.audioOrDevice(this))
            .apply {
                if (subLang.isNotEmpty()) {
                    setPreferredTextLanguage(subLang)
                    setSelectUndeterminedTextLanguage(false)
                }
            }
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = handleError(error)
        })
        playerView.player = player
        // El reproductor recibe las teclas del mando (OK, flechas, play/pausa)
        playerView.isFocusable = true
        playerView.requestFocus()

        if (live) setupLive() else startVod()
    }

    // ---------- Directo ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupLive() {
        playerView.setShowFastForwardButton(false)
        playerView.setShowRewindButton(false)
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { v ->
                if (v == View.VISIBLE) showLabel("${index + 1}  ${channels[index].name}")
            }
        )
        // Deslizar arriba/abajo para cambiar de canal
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (abs(dy) > abs(dx) && abs(dy) > 120 && abs(vy) > 400) {
                    playChannel(if (dy < 0) index + 1 else index - 1)
                    return true
                }
                return false
            }
        })
        playerView.setOnTouchListener { _, e -> detector.onTouchEvent(e) }
        playChannel(intent.getIntExtra(EXTRA_INDEX, 0))
    }

    private fun playChannel(i: Int, forceHls: Boolean = false) {
        val size = channels.size
        index = ((i % size) + size) % size
        triedHls = forceHls
        val ch = channels[index]
        val url = if (forceHls && ch.url.endsWith(".ts")) ch.url.dropLast(3) + ".m3u8" else ch.url
        val mi = MediaItem.Builder().setUri(url).apply {
            if (forceHls) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaItem(mi)
        player.prepare()
        player.playWhenReady = true
        if (!forceHls) showLabel("${index + 1}  ${ch.name}")
    }

    // ---------- Pelis y series ----------

    private fun startVod() {
        val it = item ?: return
        val start = savedPosition(it)
        playVod(it.url, startAt = start)
        if (start > 0) {
            val m = start / 60_000
            showLabel("${it.title} · sigue en ${m / 60}:${"%02d".format(m % 60)}")
        } else {
            showLabel(it.title)
        }
    }

    private fun playVod(url: String, startAt: Long) {
        val it = item ?: return
        val subs = it.subtitles.map { s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                .setMimeType(s.mime)
                .setLanguage(s.lang)
                .setLabel(s.label)
                .build()
        }
        val mi = MediaItem.Builder().setUri(url).setSubtitleConfigurations(subs).apply {
            if (url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaItem(mi, startAt)
        player.prepare()
        player.playWhenReady = true
    }

    private fun savedPosition(it: PlayItem): Long = it.resumeKey?.let { k -> resume.getLong(k, 0L) } ?: 0L

    private fun savePosition() {
        val key = item?.resumeKey ?: return
        val pos = player.currentPosition
        val dur = player.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        resume.edit().apply {
            if (pos > 30_000 && pos < dur * 0.95) putLong(key, pos) else remove(key)
        }.apply()
        // Cuándo se vio, para ordenar "Seguir viendo"
        getSharedPreferences("resume_t", MODE_PRIVATE).edit().putLong(key, System.currentTimeMillis()).apply()
    }

    // ---------- Errores ----------

    private fun handleError(error: PlaybackException) {
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        if (live) {
            // Muchas URLs IPTV son HLS sin extensión .m3u8: reintentar como HLS
            if (!triedHls) playChannel(index, forceHls = true)
            else showLabel("No se puede reproducir · ${channels[index].name}", sticky = true)
            return
        }
        // Sin plan B de conversión: se reproduce siempre el archivo original
        val reason = when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ->
                "Este dispositivo no puede reproducir el formato original de este archivo"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "El servidor ha rechazado la reproducción (¿hace falta pase remoto de Plex?)"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "No se puede conectar con el servidor"
            else -> "No se puede reproducir (${error.errorCodeName})"
        }
        showLabel(reason, sticky = true)
    }

    private fun showLabel(text: String, sticky: Boolean = false) {
        label.text = text
        label.animate().cancel()
        label.alpha = 1f
        handler.removeCallbacks(hideLabel)
        if (!sticky) handler.postDelayed(hideLabel, 2500)
    }

    // Mandos de TV / Android TV
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Atrás con los controles visibles: solo ocultarlos
        if (event.keyCode == KeyEvent.KEYCODE_BACK && ::playerView.isInitialized &&
            playerView.isControllerFullyVisible
        ) {
            if (event.action == KeyEvent.ACTION_UP) playerView.hideController()
            return true
        }
        if (live && ::playerView.isInitialized && event.action == KeyEvent.ACTION_DOWN &&
            !playerView.isControllerFullyVisible
        ) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    playChannel(index + 1); return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    playChannel(index - 1); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized) player.play()
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) {
            if (!live) savePosition()
            player.pause()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INDEX = "i"
        const val EXTRA_MODE = "mode"
        const val MODE_LIVE = "live"
        const val MODE_VOD = "vod"
    }
}
