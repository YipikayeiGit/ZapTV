package com.zap.iptv

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import kotlin.math.abs

/** Lo que el modo sofá necesita del reproductor (ExoPlayer o VLC). Estas órdenes NO avisan a la otra tele. */
interface SofaPlayer {
    fun position(): Long
    fun isPlaying(): Boolean
    fun isBuffering(): Boolean
    fun play()
    fun pause()
    fun seek(ms: Long)
    fun setRate(rate: Float)
}

/**
 * Modo sofá dentro del reproductor: reparte pausas, cuenta atrás y saltos entre las dos teles,
 * y las mantiene a la par.
 */
class SofaController(private val act: Activity, root: FrameLayout, private val p: SofaPlayer) {
    private val handler = Handler(Looper.getMainLooper())
    private val d = act.resources.displayMetrics.density

    /** Hasta que el vídeo arranca de verdad no se mandan ni se aplican órdenes. */
    var ready = false
        private set

    /** Ajuste fino solo de esta tele (versiones con unos segundos de diferencia). */
    private var offset = 0L
    private var counting = false
    private var autoPaused = false
    private var lastAction = 0L
    private var lastBuffering = false
    private var playingSecs = 0
    private var tickN = 0
    private var rateSet = 1f
    private var released = false

    private val badge = pill(13f, "#CC000000", Color.WHITE)
    private val notice = pill(16f, "#E6000000", Color.WHITE)
    private val countdown = TextView(act).apply {
        textSize = 96f
        setTextColor(Color.WHITE)
        paint.isFakeBoldText = true
        setShadowLayer(18f, 0f, 0f, Color.BLACK)
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    private val tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()

    init {
        val wrap = FrameLayout.LayoutParams.WRAP_CONTENT
        root.addView(badge, FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.START).apply {
            setMargins((24 * d).toInt(), (20 * d).toInt(), 0, 0)
        })
        root.addView(notice, FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(0, 0, 0, (110 * d).toInt())
        })
        root.addView(countdown, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
        notice.visibility = View.GONE
        Sofa.listener = ::onMessage
        Sofa.position = { canon() }
        Sofa.playing = { p.isPlaying() }
    }

    private fun canon() = p.position() - offset

    private fun pill(size: Float, bg: String, fg: Int) = TextView(act).apply {
        textSize = size
        setTextColor(fg)
        setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        background = GradientDrawable().apply {
            setColor(Color.parseColor(bg))
            cornerRadius = 22 * d
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            handler.postDelayed(this, 1_000)
            val n = if (Sofa.peerOnline) 2 else 1
            badge.text = "🛋 ${Sofa.code} · ${if (n == 2) "2 teles" else "esperando a la otra tele"}"
            if (!ready) {
                playingSecs = if (p.isPlaying() && !p.isBuffering()) playingSecs + 1 else 0
                if (playingSecs >= 2) {
                    ready = true
                    heartbeat()
                }
                return
            }
            val buf = p.isBuffering()
            if (buf != lastBuffering || ++tickN % 4 == 0) {
                lastBuffering = buf
                heartbeat()
            }
        }
    }

    private fun heartbeat() = Sofa.send("hb") {
        put("p", canon())
        put("pl", p.isPlaying() || counting)
        put("buf", p.isBuffering())
    }

    // ---------- Lo que haces tú ----------

    /** Has pausado: se pausa también la otra tele y eliges el motivo. */
    fun userPaused() {
        if (!ready) return
        lastAction = now()
        cancelCountdown()
        Sofa.send("pause") { put("p", canon()) }
        val reasons = REASONS
        MaterialAlertDialogBuilder(act)
            .setTitle("¿Por qué paras? (se lo decimos a la otra tele)")
            .setItems(reasons.toTypedArray()) { _, which -> Sofa.send("reason") { put("r", reasons[which]) } }
            .setNegativeButton("Sin motivo", null)
            .show()
    }

    /** Has dado al play: cuenta atrás en las dos teles. */
    fun userPlayRequested() {
        if (!ready) {
            p.play()
            return
        }
        lastAction = now()
        p.pause()
        val at = canon()
        Sofa.send("cd") { put("p", at) }
        startCountdown(at)
    }

    /** Has avanzado o retrocedido. */
    fun userSeeked(pos: Long) {
        if (!ready) return
        lastAction = now()
        Sofa.send("seek") { put("p", pos - offset) }
    }

    /** Con el vídeo en pausa, ← y → ajustan solo esta tele de segundo en segundo. */
    fun handleKey(event: KeyEvent): Boolean {
        if (!ready || p.isPlaying() || counting || event.action != KeyEvent.ACTION_DOWN) return false
        val step = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1_000L
            KeyEvent.KEYCODE_DPAD_LEFT -> -1_000L
            else -> return false
        }
        offset += step
        p.seek(p.position() + step)
        val s = offset / 1000
        show("Ajuste fino: ${if (s > 0) "+" else ""}$s s (solo tu tele)", 2_500)
        return true
    }

    // ---------- Lo que hace la otra tele ----------

    private fun onMessage(j: JSONObject) {
        if (released) return
        when (j.optString("k")) {
            "close" -> act.finish()
            "pause" -> {
                cancelCountdown()
                p.pause()
                align(j.optLong("p"), 1_500)
                autoPaused = false
                show("⏸ La otra tele ha pausado", 0)
            }
            "reason" -> show("⏸ La otra tele ha pausado · ${j.optString("r")}", 0)
            "cd" -> startCountdown(j.optLong("p"))
            "seek" -> {
                align(j.optLong("p"), 0)
                show("⏩ La otra tele ha ido a ${clock(j.optLong("p"))}", 3_000)
            }
            "left" -> show("La otra tele ha salido del vídeo", 4_000)
            "hb" -> sync(j)
        }
    }

    private fun sync(j: JSONObject) {
        if (!ready || counting) return
        val remotePlaying = j.optBoolean("pl")
        val remoteBuf = j.optBoolean("buf")
        val pos = j.optLong("p")
        // La otra tele está cargando: esperarla
        if (remoteBuf && p.isPlaying()) {
            p.pause()
            autoPaused = true
            show("⌛ Esperando a la otra tele…", 0)
            return
        }
        if (autoPaused && !remoteBuf) {
            autoPaused = false
            align(pos + LATENCY, 800)
            if (remotePlaying) p.play()
            hideNotice()
            return
        }
        if (now() - lastAction < 4_000) return
        // Se perdió un aviso: igualar el estado
        if (!remotePlaying && p.isPlaying()) {
            p.pause()
            align(pos, 1_500)
            show("⏸ La otra tele está en pausa", 0)
            return
        }
        // Ir a la par: quien se unió sigue a quien creó la sesión
        if (Sofa.host || !remotePlaying || !p.isPlaying() || p.isBuffering()) return
        val diff = canon() - (pos + LATENCY)
        when {
            abs(diff) > 2_000 -> {
                rate(1f)
                p.seek(pos + LATENCY + offset)
            }
            abs(diff) > 500 -> rate(if (diff > 0) 0.95f else 1.05f)
            abs(diff) < 250 -> rate(1f)
        }
    }

    private fun rate(r: Float) {
        if (r != rateSet) {
            rateSet = r
            p.setRate(r)
        }
    }

    private fun align(pos: Long, tolerance: Long) {
        if (abs(canon() - pos) > tolerance) p.seek(pos + offset)
    }

    // ---------- Cuenta atrás 3, 2, 1 ----------

    private fun startCountdown(at: Long) {
        cancelCountdown()
        counting = true
        autoPaused = false
        hideNotice()
        p.pause()
        align(at, 800)
        countdown.visibility = View.VISIBLE
        val t0 = android.os.SystemClock.uptimeMillis()
        for (i in 0..3) {
            handler.postAtTime({
                if (!counting) return@postAtTime
                if (i < 3) {
                    countdown.text = "${3 - i}"
                    tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
                } else {
                    tones?.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
                    countdown.visibility = View.GONE
                    counting = false
                    rate(1f)
                    p.play()
                }
            }, COUNT_TOKEN, t0 + i * 1_000L)
        }
    }

    private fun cancelCountdown() {
        if (!counting) return
        counting = false
        handler.removeCallbacksAndMessages(COUNT_TOKEN)
        countdown.visibility = View.GONE
    }

    // ---------- Avisos en pantalla ----------

    private val hideRunnable = Runnable { notice.visibility = View.GONE }

    /** [ms] = 0: se queda hasta que algo lo cambie. */
    private fun show(text: String, ms: Long) {
        notice.text = text
        notice.visibility = View.VISIBLE
        handler.removeCallbacks(hideRunnable)
        if (ms > 0) handler.postDelayed(hideRunnable, ms)
    }

    private fun hideNotice() {
        handler.removeCallbacks(hideRunnable)
        notice.visibility = View.GONE
    }

    fun release() {
        if (released) return
        released = true
        if (ready) Sofa.send("left")
        handler.removeCallbacksAndMessages(null)
        Sofa.listener = null
        Sofa.position = null
        Sofa.playing = null
        runCatching { tones?.release() }
    }

    private fun now() = System.currentTimeMillis()

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    init {
        handler.post(tick)
    }

    companion object {
        val REASONS = listOf("🚽 Baño", "🍕 Comida", "📞 Llamada", "⏳ Dame un momento")
        private const val LATENCY = 300L
        private val COUNT_TOKEN = Any()
    }
}
