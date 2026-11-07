package com.zap.iptv

import java.text.Normalizer

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
) {
    /** Nombre normalizado para buscar ("la 1" / "la1"). */
    val skey: String by lazy { Titles.key(name) }
    val compact: String by lazy { skey.replace(" ", "") }
}

private val DIACRITICS = Regex("\\p{Mn}+")

fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase()
