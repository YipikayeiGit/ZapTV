package com.zap.iptv

import kotlin.math.abs

enum class Origin(val label: String) { PLEX("Plex"), IPTV("IPTV") }

/** Una forma concreta de reproducir algo: un enlace IPTV o un elemento de Plex. */
data class SourceRef(
    val origin: Origin,
    val label: String,
    val url: String? = null,
    val plexServer: String? = null,
    val plexKey: String? = null,
)

data class PlexRef(val server: String, val key: String)

interface CatalogItem {
    val title: String
    val year: Int?
    val poster: String?
    val plot: String?
    val key: String
    val origins: Set<Origin>
    val badge: String get() = Origin.entries.filter { it in origins }.joinToString(" · ") { it.label }
}

data class Movie(
    override val title: String,
    override val year: Int?,
    override val poster: String?,
    val sources: List<SourceRef>,
    override val plot: String? = null,
    override val key: String = Titles.key(title),
) : CatalogItem {
    override val origins: Set<Origin> get() = sources.mapTo(HashSet()) { it.origin }
}

data class Show(
    override val title: String,
    override val year: Int?,
    override val poster: String?,
    override val plot: String? = null,
    val plex: List<PlexRef> = emptyList(),
    val xtreamIds: List<String> = emptyList(),
    /** Episodios que vienen directamente en una lista M3U normal. */
    val episodes: List<Episode> = emptyList(),
    override val key: String = Titles.key(title),
) : CatalogItem {
    override val origins: Set<Origin>
        get() = HashSet<Origin>().apply {
            if (plex.isNotEmpty()) add(Origin.PLEX)
            if (xtreamIds.isNotEmpty() || episodes.isNotEmpty()) add(Origin.IPTV)
        }
}

data class Episode(
    val season: Int,
    val number: Int,
    val title: String?,
    val sources: List<SourceRef>,
)

data class IptvData(val live: List<Channel>, val movies: List<Movie>, val shows: List<Show>) {
    companion object {
        val EMPTY = IptvData(emptyList(), emptyList(), emptyList())
    }
}

data class PlexServer(val id: String, val name: String, val uri: String, val token: String)

data class PlexData(val servers: List<PlexServer>, val movies: List<Movie>, val shows: List<Show>) {
    companion object {
        val EMPTY = PlexData(emptyList(), emptyList(), emptyList())
    }
}

/** Todo el contenido junto, ya emparejado. */
object Catalog {
    @Volatile var iptv: IptvData = IptvData.EMPTY
    @Volatile var plex: PlexData = PlexData.EMPTY

    @Volatile var live: List<Channel> = emptyList()
    @Volatile var movies: List<Movie> = emptyList()
    @Volatile var shows: List<Show> = emptyList()

    fun rebuild() {
        val i = iptv
        val p = plex
        live = i.live
        movies = Merge.movies(p.movies + i.movies)
        shows = Merge.shows(p.shows + i.shows)
    }
}

/** Lo que se va a reproducir (compartido con el reproductor). */
data class Sub(val url: String, val lang: String?, val label: String?, val mime: String)

data class PlayItem(
    val title: String,
    val url: String,
    val subtitles: List<Sub> = emptyList(),
    val fallbackUrl: String? = null,
    val resumeKey: String? = null,
)

object Playback {
    var live: List<Channel> = emptyList()
    var item: PlayItem? = null
    var selected: CatalogItem? = null
}

object Merge {
    private fun yearsMatch(a: Int?, b: Int?) = a == null || b == null || abs(a - b) <= 1

    fun movies(all: List<Movie>): List<Movie> {
        val out = ArrayList<Movie>(all.size)
        val index = HashMap<String, MutableList<Int>>()
        for (m in all) {
            if (m.key.isEmpty()) continue
            val hit = index[m.key]?.firstOrNull { yearsMatch(out[it].year, m.year) }
            if (hit != null) {
                val o = out[hit]
                out[hit] = o.copy(
                    sources = o.sources + m.sources,
                    year = o.year ?: m.year,
                    poster = o.poster ?: m.poster,
                    plot = o.plot ?: m.plot,
                )
            } else {
                index.getOrPut(m.key) { ArrayList(1) } += out.size
                out += m
            }
        }
        out.sortBy { it.key }
        return out
    }

    fun shows(all: List<Show>): List<Show> {
        val out = ArrayList<Show>(all.size)
        val index = HashMap<String, MutableList<Int>>()
        for (s in all) {
            if (s.key.isEmpty()) continue
            val hit = index[s.key]?.firstOrNull { yearsMatch(out[it].year, s.year) }
            if (hit != null) {
                val o = out[hit]
                out[hit] = o.copy(
                    year = o.year ?: s.year,
                    poster = o.poster ?: s.poster,
                    plot = o.plot ?: s.plot,
                    plex = o.plex + s.plex,
                    xtreamIds = o.xtreamIds + s.xtreamIds,
                    episodes = o.episodes + s.episodes,
                )
            } else {
                index.getOrPut(s.key) { ArrayList(1) } += out.size
                out += s
            }
        }
        out.sortBy { it.key }
        return out
    }

    fun episodes(lists: List<List<Episode>>): List<Episode> {
        val map = LinkedHashMap<Long, Episode>()
        for (list in lists) for (e in list) {
            val k = e.season.toLong() * 100_000 + e.number
            val o = map[k]
            map[k] = if (o == null) e else o.copy(title = o.title ?: e.title, sources = o.sources + e.sources)
        }
        return map.values.sortedWith(compareBy({ it.season }, { it.number }))
    }
}

/** Limpia nombres sucios de listas IPTV para poder emparejarlos. */
object Titles {
    private val YEAR = Regex("""(?<!\d)(19[2-9]\d|20[0-4]\d)(?!\d)""")
    private val PREFIX = Regex("""^\s*(?:[\[|(]?\s*[A-Z]{2,3}(?:-[A-Z]{2})?\s*[\]|):\-]+\s*)+""")
    private val BRACKETS = Regex("""[\[(][^\])]*[\])]""")
    private val TAGS = Regex(
        """\b(4k|uhd|fhd|hd|sd|hevc|h\.?265|x265|1080p?|720p?|2160p?|multi|dual|lat|cast|vose|vos|subs?|esp|castellano|latino)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val TRAIL = Regex("""[\s\-|_.:]+$""")
    private val SPACES = Regex("""\s{2,}""")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    data class Clean(val title: String, val year: Int?)

    fun clean(raw: String): Clean {
        val year = YEAR.findAll(raw).lastOrNull()?.value?.toIntOrNull()
        var t = raw.replace(PREFIX, "").replace(BRACKETS, " ")
        if (year != null) t = t.replace(year.toString(), " ")
        t = t.replace(TAGS, " ").replace(SPACES, " ").trim().replace(TRAIL, "").trim()
        return Clean(t.ifEmpty { raw.trim() }, year)
    }

    fun key(title: String): String = fold(title).replace(NON_ALNUM, " ").trim()
}
