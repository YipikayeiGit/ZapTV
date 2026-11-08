package com.zap.iptv

import android.content.Context
import androidx.media3.common.Format

/**
 * Audio y subtítulos elegidos a mano en una serie: se recuerdan para todos sus capítulos
 * (y mandan sobre los idiomas por defecto de Ajustes).
 */
object SeriesTracks {
    /** Elección guardada: idioma (o "off" en subtítulos) y si eran forzados. */
    data class Pick(val lang: String, val forced: Boolean = false) {
        val off get() = lang == OFF
    }

    const val OFF = "off"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("series_tracks", Context.MODE_PRIVATE)

    /** "ep:<serie>:<temporada>:<capítulo>" → "<serie>"; null si no es un capítulo. */
    fun showOf(resumeKey: String?): String? {
        if (resumeKey == null || !resumeKey.startsWith("ep:")) return null
        return resumeKey.removePrefix("ep:").substringBeforeLast(':').substringBeforeLast(':').takeIf { it.isNotEmpty() }
    }

    fun audio(ctx: Context, resumeKey: String?): Pick? = read(ctx, "a", resumeKey)
    fun subs(ctx: Context, resumeKey: String?): Pick? = read(ctx, "s", resumeKey)

    private fun read(ctx: Context, kind: String, resumeKey: String?): Pick? {
        val show = showOf(resumeKey) ?: return null
        val v = prefs(ctx).getString("$kind:$show", null) ?: return null
        return Pick(v.substringBefore('|'), v.endsWith("|f"))
    }

    fun save(ctx: Context, resumeKey: String?, audio: Boolean, pick: Pick) {
        val show = showOf(resumeKey) ?: return
        val k = (if (audio) "a:" else "s:") + show
        prefs(ctx).edit().putString(k, pick.lang + if (pick.forced) "|f" else "").apply()
    }

    /** Lo que se guarda de una pista de ExoPlayer (null = subtítulos desactivados). */
    fun pickOf(f: Format?): Pick? {
        f ?: return Pick(OFF)
        val lang = Languages.codeOf(f) ?: return null
        return Pick(lang, Languages.isForced(f))
    }

    /** Lo mismo para VLC, que solo da el nombre de la pista. */
    fun pickOfName(name: String?, id: Int): Pick? {
        if (id < 0) return Pick(OFF)
        val f = Format.Builder().setLabel(name).build()
        val lang = Languages.codeOf(f) ?: return null
        return Pick(lang, Languages.isForced(f))
    }
}
