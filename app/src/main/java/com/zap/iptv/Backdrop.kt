package com.zap.iptv

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest

/**
 * Fondo a pantalla completa como en Plex: la imagen del título (difuminada hacia la izquierda y abajo)
 * y un tinte del color de la imagen sobre toda la pantalla.
 * Se coloca detrás del contenido de la actividad sin tocar su diseño.
 */
class Backdrop(private val act: Activity) {
    private val image = ImageView(act)
    private val tint = View(act)
    private val handler = Handler(Looper.getMainLooper())
    private var current: String? = null
    private var tintColor = Color.TRANSPARENT
    private var tintAnim: ValueAnimator? = null
    private val bg = ContextCompat.getColor(act, R.color.bg)

    init {
        val root = act.findViewById<FrameLayout>(android.R.id.content)
        root.getChildAt(0)?.background = null
        root.setBackgroundColor(bg)
        val full = { FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.alpha = 0f
        image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        root.addView(image, 0, full())
        // Oscurecer a la izquierda (texto) y abajo (filas)
        val side = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(withAlpha(bg, 0.97f), withAlpha(bg, 0.75f), withAlpha(bg, 0.15f)),
        )
        val bottom = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(withAlpha(bg, 0.1f), withAlpha(bg, 0.65f), withAlpha(bg, 0.98f)),
        )
        val shade = View(act).apply { background = LayerDrawable(arrayOf(side, bottom)) }
        root.addView(shade, 1, full())
        root.addView(tint, 2, full())
    }

    /** Cambia el fondo (con una pequeña espera para no cargar imágenes al pasar rápido con el mando). */
    fun show(url: String?, delayMs: Long = 250) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ load(url) }, delayMs)
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        current = null
        image.animate().alpha(0f).setDuration(250).start()
        animateTint(Color.TRANSPARENT)
    }

    private fun load(url: String?) {
        if (url == null) {
            hide()
            return
        }
        if (url == current) return
        current = url
        val dm = act.resources.displayMetrics
        val req = ImageRequest.Builder(act)
            .data(Plex.fixUrl(url))
            .apply { stableCache(url) }
            .size(minOf(dm.widthPixels, 1280), minOf(dm.heightPixels, 720))
            .allowHardware(false) // para poder sacar el color
            .target(
                onSuccess = { d ->
                    if (current != url || act.isFinishing) return@target
                    image.animate().cancel()
                    image.alpha = 0.35f
                    image.setImageDrawable(d)
                    image.animate().alpha(1f).setDuration(350).start()
                    val bmp = (d as? BitmapDrawable)?.bitmap ?: return@target
                    Palette.from(bmp).maximumColorCount(12).generate { p ->
                        if (current != url || p == null) return@generate
                        val c = p.getDarkVibrantColor(p.getDarkMutedColor(p.getDominantColor(Color.TRANSPARENT)))
                        animateTint(if (c == Color.TRANSPARENT) c else withAlpha(c, 0.32f))
                    }
                },
                onError = { if (current == url) image.animate().alpha(0f).setDuration(250).start() },
            )
            .build()
        act.imageLoader.enqueue(req)
    }

    private fun animateTint(to: Int) {
        tintAnim?.cancel()
        val from = tintColor
        tintAnim = ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 400
            addUpdateListener {
                tintColor = it.animatedValue as Int
                tint.setBackgroundColor(tintColor)
            }
            start()
        }
    }

    fun release() = handler.removeCallbacksAndMessages(null)

    private fun withAlpha(c: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
}
