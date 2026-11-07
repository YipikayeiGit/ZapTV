package com.zap.iptv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/** Cómo se ven los subtítulos: tamaño, color, fondo, posición y letra. */
@OptIn(UnstableApi::class)
object SubStyle {
    class Option(val title: String, val key: String, val values: List<String>, val default: Int)

    val SIZE = Option("Tamaño", "sub_size", listOf("Pequeño", "Normal", "Grande", "Muy grande"), 1)
    val COLOR = Option("Color", "sub_color", listOf("Blanco", "Amarillo", "Gris claro", "Cian"), 0)
    val BACK = Option("Fondo", "sub_back", listOf("Contorno negro", "Sombra", "Caja oscura", "Sin fondo"), 0)
    val POSITION = Option("Posición", "sub_pos", listOf("Abajo del todo", "Abajo", "Un poco más arriba"), 1)
    val FONT = Option("Letra", "sub_font", listOf("Normal", "Negrita", "Estrecha"), 0)
    val EMBEDDED = Option(
        "Estilo del archivo", "sub_embedded",
        listOf("Usar el mío (recomendado)", "Respetar el del archivo"), 0,
    )

    val ALL = listOf(SIZE, COLOR, BACK, POSITION, FONT, EMBEDDED)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("zap", Context.MODE_PRIVATE)

    fun get(ctx: Context, o: Option): Int = prefs(ctx).getInt(o.key, o.default).coerceIn(0, o.values.lastIndex)

    fun set(ctx: Context, o: Option, value: Int) = prefs(ctx).edit().putInt(o.key, value).apply()

    fun label(ctx: Context, o: Option): String = o.values[get(ctx, o)]

    fun summary(ctx: Context): String =
        listOf(SIZE, COLOR, BACK).joinToString(" · ") { label(ctx, it) }

    fun apply(ctx: Context, view: SubtitleView) {
        val scale = when (get(ctx, SIZE)) {
            0 -> 0.8f
            2 -> 1.25f
            3 -> 1.5f
            else -> 1f
        }
        val color = when (get(ctx, COLOR)) {
            1 -> 0xFFFFE94D.toInt()
            2 -> 0xFFD6D6D6.toInt()
            3 -> 0xFF7FE7FF.toInt()
            else -> Color.WHITE
        }
        val back = get(ctx, BACK)
        val bg = if (back == 2) 0xB3000000.toInt() else Color.TRANSPARENT
        val edge = when (back) {
            0 -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            1 -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            else -> CaptionStyleCompat.EDGE_TYPE_NONE
        }
        val typeface = when (get(ctx, FONT)) {
            1 -> Typeface.DEFAULT_BOLD
            2 -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
        val embedded = get(ctx, EMBEDDED) == 1
        view.setApplyEmbeddedStyles(embedded)
        view.setApplyEmbeddedFontSizes(embedded)
        view.setStyle(CaptionStyleCompat(color, bg, Color.TRANSPARENT, edge, Color.BLACK, typeface))
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
        view.setBottomPaddingFraction(
            when (get(ctx, POSITION)) {
                0 -> 0.03f
                2 -> 0.16f
                else -> SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
            }
        )
    }
}
