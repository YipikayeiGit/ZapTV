package com.zap.iptv

import android.content.Context
import java.util.Locale

/** Idiomas por defecto de audio y subtítulos (se aplican si el vídeo los tiene). */
object Languages {
    /** Código ISO → nombre. "" = según el dispositivo (audio) o desactivados (subtítulos). */
    val options = listOf(
        "es" to "Español", "en" to "Inglés", "ca" to "Catalán", "fr" to "Francés",
        "it" to "Italiano", "de" to "Alemán", "pt" to "Portugués", "ja" to "Japonés",
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("zap", Context.MODE_PRIVATE)

    fun audio(ctx: Context): String = prefs(ctx).getString("audio_lang", "") ?: ""
    fun subs(ctx: Context): String = prefs(ctx).getString("sub_lang", "") ?: ""

    fun setAudio(ctx: Context, code: String) = prefs(ctx).edit().putString("audio_lang", code).apply()
    fun setSubs(ctx: Context, code: String) = prefs(ctx).edit().putString("sub_lang", code).apply()

    /** Idioma de audio a pedir al reproductor. */
    fun audioOrDevice(ctx: Context): String = audio(ctx).ifEmpty { Locale.getDefault().language }

    fun audioLabel(ctx: Context): String =
        options.firstOrNull { it.first == audio(ctx) }?.second ?: "El del dispositivo"

    fun subsLabel(ctx: Context): String =
        options.firstOrNull { it.first == subs(ctx) }?.second ?: "Desactivados"
}
