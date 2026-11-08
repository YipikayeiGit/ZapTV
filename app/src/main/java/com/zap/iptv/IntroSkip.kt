package com.zap.iptv

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Botón "Saltar intro" como en Netflix: aparece mientras suena la entradilla
 * (según las marcas que Plex detecta en cada capítulo) y con OK se salta.
 */
class IntroSkip(
    private val act: Activity,
    root: FrameLayout,
    private val start: Long,
    private val end: Long,
    private val position: () -> Long,
    private val seek: (Long) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val d = act.resources.displayMetrics.density
    private var dismissed = false
    private val pill = TextView(act).apply {
        text = "Saltar intro   ▶ OK"
        setTextColor(Color.parseColor("#0E0E10"))
        textSize = 16f
        paint.isFakeBoldText = true
        setPadding((22 * d).toInt(), (12 * d).toInt(), (22 * d).toInt(), (12 * d).toInt())
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 28 * d
        }
        elevation = 8 * d
        visibility = android.view.View.GONE
    }

    val visible get() = pill.visibility == android.view.View.VISIBLE

    init {
        root.addView(
            pill,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                .apply { setMargins(0, 0, (48 * d).toInt(), (64 * d).toInt()) },
        )
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = position()
            val inside = p in start until (end - 2_000)
            if (!inside) dismissed = false
            val show = inside && !dismissed
            if (show != visible) {
                pill.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            }
            handler.postDelayed(this, 500)
        }
    }

    /** OK con el botón a la vista: saltar. Atrás: ocultarlo (esta vez). */
    fun handleKey(event: KeyEvent): Boolean {
        if (!visible || event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                pill.visibility = android.view.View.GONE
                dismissed = true
                seek(end)
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                pill.visibility = android.view.View.GONE
                dismissed = true
                true
            }
            else -> false
        }
    }

    fun release() = handler.removeCallbacksAndMessages(null)

    init {
        handler.post(tick)
    }
}
