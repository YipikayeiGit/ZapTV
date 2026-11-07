package com.zap.iptv

import android.content.Context

/**
 * Trae tu "Seguir viendo" de Plex a la app: pelis a medias, capítulos a medias
 * y las series que estás viendo. Solo se aplica si en Plex es más reciente que en la app.
 */
object PlexSync {
    private const val WEEK = 7L * 24 * 60 * 60 * 1000

    fun importProgress(ctx: Context) {
        val progress = Catalog.plex.progress
        if (progress.isEmpty()) return

        val moviesByRef = HashMap<String, Movie>()
        val moviesById = HashMap<String, Movie>()
        for (m in Catalog.movies) {
            for (s in m.sources) if (s.plexKey != null) moviesByRef["${s.plexServer}/${s.plexKey}"] = m
            for (id in m.ids) moviesById[id] = m
        }
        val showsByRef = HashMap<String, Show>()
        val showsById = HashMap<String, Show>()
        for (sh in Catalog.shows) {
            for (r in sh.plex) showsByRef["${r.server}/${r.key}"] = sh
            for (id in sh.ids) showsById[id] = sh
        }

        val resume = ctx.getSharedPreferences("resume", Context.MODE_PRIVATE)
        val times = ctx.getSharedPreferences("resume_t", Context.MODE_PRIVATE)
        val series = ctx.getSharedPreferences("series", Context.MODE_PRIVATE)
        val r = resume.edit()
        val t = times.edit()
        val se = series.edit()
        // Lo que Plex sugiere sin fecha (el siguiente capítulo) va detrás de lo reciente
        val fallbackTime = System.currentTimeMillis() - WEEK

        for (p in progress) {
            val at = if (p.viewedAt > 0) p.viewedAt * 1000 else fallbackTime
            if (p.type == "movie") {
                val m = moviesByRef["${p.server}/${p.key}"] ?: p.guid?.let { moviesById[it] } ?: continue
                val key = "movie:${m.key}"
                if (p.offsetMs > 0 && at > times.getLong(key, 0L)) {
                    r.putLong(key, p.offsetMs)
                    t.putLong(key, at)
                }
            } else {
                val sh = p.showKey?.let { showsByRef["${p.server}/$it"] }
                    ?: p.showGuid?.let { showsById[it] }
                    ?: continue
                if (p.season < 0 || p.number < 0) continue
                if (at <= series.getLong("t:${sh.key}", 0L)) continue
                if (p.offsetMs > 0) {
                    val key = "ep:${sh.key}:${p.season}:${p.number}"
                    r.putLong(key, p.offsetMs)
                    t.putLong(key, at)
                }
                // Capítulo a medias: vas por él. Siguiente capítulo sin empezar: viste el anterior.
                val code = p.season * 10_000 + if (p.offsetMs > 0) p.number else (p.number - 1).coerceAtLeast(0)
                se.putInt("p:${sh.key}", maxOf(series.getInt("p:${sh.key}", 0), code))
                se.putLong("t:${sh.key}", at)
            }
        }
        r.apply()
        t.apply()
        se.apply()
    }
}
