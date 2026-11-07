package com.zap.iptv

import java.text.Normalizer

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
) {
    /** Nombre normalizado (sin tildes, minúsculas) para buscar rápido. */
    val key: String = fold(name)
}

private val DIACRITICS = Regex("\\p{Mn}+")

fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase()
