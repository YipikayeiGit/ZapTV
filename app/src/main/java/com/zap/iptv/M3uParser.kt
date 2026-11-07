package com.zap.iptv

object M3uParser {
    private val ATTR = Regex("""([\w-]+)="([^"]*)"""")
    private const val NO_GROUP = "Sin grupo"

    fun parse(raw: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var logo: String? = null
        var group: String? = null

        for (rawLine in raw.removePrefix("﻿").lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = ATTR.findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                    group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                    // El título va tras la coma que sigue al último atributo
                    val lastQuote = line.lastIndexOf('"')
                    val comma = line.indexOf(',', if (lastQuote >= 0) lastQuote else 0)
                    val title = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    name = title.ifEmpty { attrs["tvg-name"] }
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    group = line.substring(8).trim().ifEmpty { null }
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    out += Channel(
                        name = name?.takeIf { it.isNotBlank() } ?: line.substringAfterLast('/'),
                        url = line,
                        logo = logo,
                        group = group ?: NO_GROUP,
                    )
                    name = null; logo = null; group = null
                }
            }
        }
        return out
    }

    private val SE = Regex("""\bS(\d{1,2})\s*E(\d{1,3})\b""", RegexOption.IGNORE_CASE)

    private fun ext(url: String): String {
        val e = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
        return if (e.length in 2..4 && e.all { it.isLetterOrDigit() }) e.uppercase() else ""
    }

    private fun label(url: String) = ext(url).let { if (it.isEmpty()) "IPTV" else "IPTV · $it" }

    /** Separa una lista M3U en directos, pelis y series (según la URL). */
    fun toData(channels: List<Channel>): IptvData {
        val live = ArrayList<Channel>()
        val movies = ArrayList<Movie>()
        val shows = LinkedHashMap<String, Show>()
        for (c in channels) {
            when {
                c.url.contains("/movie/") -> {
                    val cl = Titles.clean(c.name)
                    movies += Movie(
                        cl.title, cl.year, c.logo,
                        listOf(SourceRef(Origin.IPTV, label(c.url), url = c.url, quality = Quality.fromText(c.name, c.group))),
                        genres = listOfNotNull(Titles.category(c.group.takeIf { it != "Sin grupo" })),
                    )
                }
                c.url.contains("/series/") -> {
                    val m = SE.find(c.name)
                    if (m == null) {
                        live += c
                    } else {
                        val cl = Titles.clean(c.name.substring(0, m.range.first))
                        val ep = Episode(
                            season = m.groupValues[1].toInt(),
                            number = m.groupValues[2].toInt(),
                            title = c.name.substring(m.range.last + 1).trim(' ', '-', '|', ':').ifEmpty { null },
                            sources = listOf(SourceRef(Origin.IPTV, label(c.url), url = c.url)),
                        )
                        val key = Titles.key(cl.title)
                        val old = shows[key]
                        shows[key] = old?.copy(episodes = old.episodes + ep)
                            ?: Show(
                                cl.title, cl.year, c.logo, episodes = listOf(ep),
                                genres = listOfNotNull(Titles.category(c.group.takeIf { it != "Sin grupo" })),
                            )
                    }
                }
                else -> live += c
            }
        }
        return IptvData(live, movies, shows.values.toList())
    }
}
