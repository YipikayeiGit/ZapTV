package com.zap.iptv

import android.util.Base64
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/** Programación (guía) de los canales en directo, pedida al proveedor Xtream. */
object Epg {
    data class Prog(val title: String, val start: Long, val end: Long)
    data class NowNext(val now: Prog?, val next: Prog?)

    private class Entry(val fetched: Long, val progs: List<Prog>)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** Id del canal en Xtream: …/live/usuario/clave/ID.ts (o sin /live/). */
    fun liveId(url: String): String? =
        url.substringBefore('?').substringAfterLast('/').substringBefore('.')
            .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    /**
     * Lo que ponen ahora y después, o null si hay que pedirlo (no está o se ha quedado viejo).
     * Si el canal no tiene guía devuelve NowNext(null, null).
     */
    fun nowNext(url: String): NowNext? {
        val e = cache[url] ?: return null
        val now = System.currentTimeMillis()
        val current = e.progs.firstOrNull { it.start <= now && now < it.end }
        val next = e.progs.firstOrNull { it.start >= (current?.end ?: now) }
        val stale = if (e.progs.isEmpty()) now - e.fetched > 30 * 60_000L
        else current == null || next == null || now - e.fetched > 2 * 60 * 60_000L
        if (stale && now - e.fetched > 60_000L) return null
        return NowNext(current, next)
    }

    fun fetch(acc: Xtream.Account, url: String) {
        val id = liveId(url)
        val progs = if (id == null) emptyList() else runCatching {
            val j = JSONObject(Http.get(acc.api("get_short_epg", "&stream_id=$id&limit=4"), timeout = 15_000))
            val list = j.optJSONArray("epg_listings")
            (0 until (list?.length() ?: 0)).mapNotNull { i ->
                val o = list!!.optJSONObject(i) ?: return@mapNotNull null
                val start = o.optString("start_timestamp").toLongOrNull()?.times(1000) ?: parse(o.optString("start"))
                val end = o.optString("stop_timestamp").toLongOrNull()?.times(1000) ?: parse(o.optString("end"))
                val title = decode(o.optString("title")).trim()
                if (start == null || end == null || title.isEmpty()) null else Prog(title, start, end)
            }.sortedBy { it.start }
        }.getOrDefault(emptyList())
        cache[url] = Entry(System.currentTimeMillis(), progs)
    }

    /** Los títulos vienen en Base64 casi siempre; si no lo son, se dejan tal cual. */
    private fun decode(s: String): String {
        if (s.isBlank()) return ""
        val d = runCatching { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
        return if (d != null && d.isNotBlank() && d.none { it == '�' || (it < ' ' && it != '\n') }) d else s
    }

    private fun parse(s: String): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }.parse(s)?.time
    }.getOrNull()

    private val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun time(ms: Long): String = hhmm.format(Date(ms))
}
