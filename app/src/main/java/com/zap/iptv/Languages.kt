package com.zap.iptv

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import java.text.Normalizer
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

    /** Formas en que puede venir escrito cada idioma en una pista ("Spanish", "Castellano", "spa"…). */
    private val ALIASES = mapOf(
        "es" to setOf("es", "spa", "esp", "spanish", "espanol", "castellano", "castilian"),
        "en" to setOf("en", "eng", "english", "ingles"),
        "ca" to setOf("ca", "cat", "catalan", "catala"),
        "fr" to setOf("fr", "fre", "fra", "french", "francais", "frances"),
        "it" to setOf("it", "ita", "italian", "italiano"),
        "de" to setOf("de", "ger", "deu", "german", "deutsch", "aleman"),
        "pt" to setOf("pt", "por", "portuguese", "portugues"),
        "ja" to setOf("ja", "jpn", "japanese", "japones"),
    )

    private val NON_LETTERS = Regex("[^a-z]+")

    private fun words(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        val plain = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
        return plain.split(NON_LETTERS).filter { it.isNotEmpty() }
    }

    /** ¿Esta pista está en el idioma [code]? Mira el código de idioma y también el nombre. */
    fun matches(f: Format, code: String): Boolean {
        val aliases = ALIASES[code] ?: setOf(code)
        val lang = f.language?.lowercase()?.substringBefore('-')?.substringBefore('_')
        if (lang != null && lang in aliases) return true
        return words(f.label).any { it in aliases }
    }

    private val FORCED = setOf("forced", "forzado", "forzados", "forzada", "forzadas", "force", "forces", "forcat", "forcats")

    /** Menor es mejor: castellano antes que latino, completos antes que forzados o para sordos. */
    fun penalty(f: Format): Int {
        val w = words(f.label) + words(f.language)
        var p = 0
        if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0 || w.any { it in FORCED }) p += 4
        if ("latino" in w || "latin" in w || "latam" in w || "419" in (f.language ?: "")) p += 2
        if ("sdh" in w || "cc" in w) p += 1
        return p
    }
}
