package com.zap.iptv

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest

/**
 * Fondo como en Plex: la imagen del título arriba a la derecha (sin ocupar toda la pantalla,
 * con los bordes difuminados) y el resto un degradado con los colores de esa imagen.
 */
class Backdrop(private val act: Activity) {
    private val image = FadeImageView(act)
    private val gradient = View(act)
    private val handler = Handler(Looper.getMainLooper())
    private var current: String? = null
    private var colorA: Int
    private var colorB: Int
    private var anim: ValueAnimator? = null
    private val bg = ContextCompat.getColor(act, R.color.bg)
    private val grad = GradientDrawable(GradientDrawable.Orientation.TR_BL, intArrayOf(bg, bg, bg))

    init {
        colorA = bg
        colorB = bg
        val root = act.findViewById<FrameLayout>(android.R.id.content)
        root.getChildAt(0)?.background = null
        root.setBackgroundColor(bg)
        gradient.background = grad
        root.addView(gradient, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val dm = act.resources.displayMetrics
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.alpha = 0f
        image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // Arriba a la derecha, unos 2/3 del ancho y de alto
        val w = (dm.widthPixels * 0.66f).toInt()
        val h = (w * 9f / 16f).toInt().coerceAtMost((dm.heightPixels * 0.72f).toInt())
        root.addView(image, 1, FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.END))
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
        animateTo(bg, bg)
    }

    private fun load(url: String?) {
        if (url == null) {
            hide()
            return
        }
        if (url == current) return
        current = url
        val lp = image.layoutParams
        val req = ImageRequest.Builder(act)
            .data(Plex.fixUrl(url))
            .apply { stableCache(url) }
            .size(lp.width, lp.height)
            .allowHardware(false) // para poder sacar los colores
            .target(
                onSuccess = { d ->
                    if (current != url || act.isFinishing) return@target
                    image.animate().cancel()
                    image.alpha = 0.3f
                    image.setImageDrawable(d)
                    image.animate().alpha(1f).setDuration(350).start()
                    val bmp = (d as? BitmapDrawable)?.bitmap ?: return@target
                    Palette.from(bmp).maximumColorCount(16).generate { p ->
                        if (current != url || p == null) return@generate
                        val main = p.getDarkVibrantColor(p.getDarkMutedColor(p.getDominantColor(bg)))
                        val second = p.getDarkMutedColor(p.getMutedColor(main))
                        animateTo(mix(main, 0.55f), mix(second, 0.30f))
                    }
                },
                onError = { if (current == url) image.animate().alpha(0f).setDuration(250).start() },
            )
            .build()
        act.imageLoader.enqueue(req)
    }

    /** Color de la imagen mezclado con el fondo oscuro (para que el texto siga leyéndose). */
    private fun mix(c: Int, amount: Float) = ColorUtils.blendARGB(bg, ColorUtils.blendARGB(c, Color.BLACK, 0.25f), amount)

    private fun animateTo(a: Int, b: Int) {
        anim?.cancel()
        val fromA = colorA
        val fromB = colorB
        val ev = ArgbEvaluator()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            addUpdateListener {
                val f = it.animatedFraction
                colorA = ev.evaluate(f, fromA, a) as Int
                colorB = ev.evaluate(f, fromB, b) as Int
                grad.colors = intArrayOf(colorA, colorB, bg)
            }
            start()
        }
    }

    fun release() = handler.removeCallbacksAndMessages(null)
}

/** Imagen que se desvanece hacia la izquierda y hacia abajo (se funde con el degradado). */
class FadeImageView(ctx: Context) : ImageView(ctx) {
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var horizontal: Shader? = null
    private var vertical: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        horizontal = LinearGradient(0f, 0f, w * 0.55f, 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        vertical = LinearGradient(0f, h.toFloat(), 0f, h * 0.35f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val save = canvas.saveLayer(0f, 0f, w, h, null)
        super.onDraw(canvas)
        mask.shader = horizontal
        canvas.drawRect(0f, 0f, w, h, mask)
        mask.shader = vertical
        canvas.drawRect(0f, 0f, w, h, mask)
        canvas.restoreToCount(save)
    }
}
