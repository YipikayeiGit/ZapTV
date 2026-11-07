package com.zap.iptv

import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

enum class Origin(val label: String) { PLEX("Plex"), IPTV("IPTV") }

/** Una forma concreta de reproducir algo: un enlace IPTV o un elemento de Plex. */
data class SourceRef(
    val origin: Origin,
    val label: String,
    val url: String? = null,
    val plexServer: String? = null,
    val plexKey: String? = null,
    /** Identificador global de Plex (plex://movie/…), para cruzar con Mi lista. */
    val plexGuid: String? = null,
    /** "4K" o "HD" cuando se sabe. */
    val quality: String? = null,
    /** Identificadores externos (imdb://…, tmdb://…) para emparejar fuentes. */
    val extIds: List<String> = emptyList(),
)

data class PlexRef(val server: String, val key: String, val guid: String? = null)

/** Un título de "Mi lista" de Plex (puede no estar en ninguna fuente). */
data class WatchItem(
    val guid: String?,
    val title: String,
    val year: Int?,
    val type: String,
    val thumb: String? = null,
    val extIds: List<String> = emptyList(),
)

/** Calidad de imagen para las etiquetas de las carátulas. */
object Quality {
    private val RANK = mapOf("4K" to 2, "HD" to 1)
    private val UHD = Regex("""(?i)(?<![a-z0-9])(4k|uhd|2160p?)(?![a-z0-9])""")
    private val HD = Regex("""(?i)(?<![a-z0-9])(fhd|hd|1080p?|720p?)(?![a-z0-9])""")

    fun best(list: List<String?>): String? = list.filterNotNull().maxByOrNull { RANK[it] ?: 0 }

    fun fromPlex(res: String?): String? = when (res?.lowercase()) {
        "4k" -> "4K"
        "1080", "720" -> "HD"
        else -> null
    }

    /** Busca "4K", "UHD", "FHD", "1080p"… en el nombre o la categoría del proveedor. */
    fun fromText(vararg texts: String?): String? {
        val t = texts.filterNotNull().joinToString(" ")
        return when {
            UHD.containsMatchIn(t) -> "4K"
            HD.containsMatchIn(t) -> "HD"
            else -> null
        }
    }
}

interface CatalogItem {
    val title: String
    val year: Int?
    val poster: String?
    val plot: String?
    val key: String
    val origins: Set<Origin>
    /** Fecha de alta (segundos), para ordenar por novedades. */
    val added: Long
    /** Valoración 0–10 si la hay. */
    val rating: Float?
    /** Géneros o categorías. */
    val genres: List<String>
    val genreKeys: Set<String>
    /** "4K", "HD" o null. */
    val quality: String?
    /** plex://…, imdb://…, tmdb://… para cruzar fuentes y Mi lista. */
    val ids: List<String>
    /** Título sin espacios ni signos ("spiderman"), para buscar. */
    val searchKey: String
    val available: Boolean get() = origins.isNotEmpty()
    val badge: String
        get() = if (!available) "No disponible"
        else Origin.entries.filter { it in origins }.joinToString(" · ") { it.label }
}

data class Movie(
    override val title: String,
    override val year: Int?,
    override val poster: String?,
    val sources: List<SourceRef>,
    override val plot: String? = null,
    override val added: Long = 0,
    override val rating: Float? = null,
    override val genres: List<String> = emptyList(),
    /** Ids de Plex cuando la peli solo está en Mi lista. */
    val extIds: List<String> = emptyList(),
    override val key: String = Titles.key(title),
) : CatalogItem {
    override val origins: Set<Origin> get() = sources.mapTo(HashSet()) { it.origin }
    override val genreKeys: Set<String> by lazy { genres.mapTo(HashSet()) { Titles.genreKey(it) } }
    override val quality: String? get() = Quality.best(sources.map { it.quality })
    override val ids: List<String> get() = sources.flatMap { listOfNotNull(it.plexGuid) + it.extIds } + extIds
    override val searchKey: String by lazy { key.replace(" ", "") }
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
    override val added: Long = 0,
    override val rating: Float? = null,
    override val genres: List<String> = emptyList(),
    override val quality: String? = null,
    val extIds: List<String> = emptyList(),
    override val key: String = Titles.key(title),
) : CatalogItem {
    override val genreKeys: Set<String> by lazy { genres.mapTo(HashSet()) { Titles.genreKey(it) } }
    override val ids: List<String> get() = plex.mapNotNull { it.guid } + extIds
    override val searchKey: String by lazy { key.replace(" ", "") }

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

data class PlexData(
    val servers: List<PlexServer>,
    val movies: List<Movie>,
    val shows: List<Show>,
    val watchlist: List<WatchItem> = emptyList(),
) {
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
    @Volatile var movieGenres: List<String> = emptyList()
    @Volatile var showGenres: List<String> = emptyList()
    @Volatile var watchMovies: List<CatalogItem> = emptyList()
    @Volatile var watchShows: List<CatalogItem> = emptyList()
    /** Series que estabas viendo y tienen capítulos nuevos. */
    @Volatile var newEpisodes: List<CatalogItem> = emptyList()

    fun rebuild() {
        val i = iptv
        val p = plex
        live = i.live
        val m = Merge.movies(p.movies + i.movies)
        val s = Merge.shows(p.shows + i.shows)
        movieGenres = Home.genres(m)
        showGenres = Home.genres(s)
        movies = m
        shows = s
        rebuildWatch()
    }

    fun rebuildWatch() {
        val watch = plex.watchlist
        watchMovies = Watch.match(movies, watch.filter { it.type != "show" }, shows = false)
        watchShows = Watch.match(shows, watch.filter { it.type == "show" }, shows = true)
    }
}

/** "Mi lista" de Plex cruzada con lo que hay en tus fuentes. */
object Watch {
    fun match(items: List<CatalogItem>, watch: List<WatchItem>, shows: Boolean): List<CatalogItem> {
        if (watch.isEmpty()) return emptyList()
        val byId = HashMap<String, CatalogItem>()
        val byKey = HashMap<String, MutableList<CatalogItem>>()
        for (i in items) {
            for (id in i.ids) byId[id] = i
            byKey.getOrPut(i.key) { ArrayList(1) } += i
        }
        return watch.map { w ->
            (listOfNotNull(w.guid) + w.extIds).firstNotNullOfOrNull { byId[it] }
                ?: byKey[Titles.key(w.title)]?.firstOrNull { c ->
                    val cy = c.year
                    w.year == null || cy == null || abs(cy - w.year) <= 1
                }
                ?: placeholder(w, shows)
        }.distinct()
    }

    /** Lo que está en Mi lista pero no en tus fuentes: se muestra como "No disponible". */
    private fun placeholder(w: WatchItem, shows: Boolean): CatalogItem {
        val ids = listOfNotNull(w.guid) + w.extIds
        return if (shows) Show(w.title, w.year, w.thumb, extIds = ids)
        else Movie(w.title, w.year, w.thumb, emptyList(), extIds = ids)
    }

    fun contains(item: CatalogItem): Boolean {
        val list = if (item is Show) Catalog.watchShows else Catalog.watchMovies
        val ids = item.ids.toSet()
        return list.any { it === item || it.key == item.key || it.ids.any { id -> id in ids } }
    }

    /** El identificador de Plex (plex://movie/…) de un título, si lo tiene. */
    fun plexGuid(item: CatalogItem): String? = item.ids.firstOrNull { it.startsWith("plex://") }
}

/** Búsqueda tolerante: "spiderman" encuentra "Spider-Man: No Way Home". */
object Search {
    class Query(raw: String) {
        val key = Titles.key(raw)
        val compact = key.replace(" ", "")
        val words = key.split(' ').filter { it.isNotEmpty() }
        val isEmpty get() = compact.isEmpty()
    }

    /** 0 = no coincide; cuanto más alto, mejor coincidencia. */
    fun score(key: String, compact: String, q: Query): Int = when {
        q.isEmpty -> 1
        key == q.key || compact == q.compact -> 100
        compact.startsWith(q.compact) -> 80
        key.startsWith(q.key) -> 75
        (" $key").contains(" ${q.key}") -> 60
        compact.contains(q.compact) -> 50
        q.words.size > 1 && q.words.all { key.contains(it) } -> 40
        else -> 0
    }

    fun items(src: List<CatalogItem>, raw: String): List<CatalogItem> {
        val q = Query(raw)
        return src.asSequence()
            .map { it to score(it.key, it.searchKey, q) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<CatalogItem, Int>> { it.second }
                    .thenByDescending { it.first.year ?: 0 }
            )
            .map { it.first }
            .toList()
    }

    fun channels(src: List<Channel>, raw: String): List<Channel> {
        val q = Query(raw)
        if (q.isEmpty) return src
        return src.filter { score(it.skey, it.compact, q) > 0 }
    }
}

/** Ficha ampliada de una película (de Plex o del proveedor IPTV). */
data class Person(val name: String, val role: String?, val photo: String?)

data class Info(
    val tagline: String? = null,
    val plot: String? = null,
    val minutes: Int? = null,
    val contentRating: String? = null,
    val studio: String? = null,
    val rating: Float? = null,
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val cast: List<Person> = emptyList(),
    val genres: List<String> = emptyList(),
    /** Títulos que Plex considera parecidos. */
    val similar: List<String> = emptyList(),
)

/** Lo que se va a reproducir (compartido con el reproductor). */
data class Sub(val url: String, val lang: String?, val label: String?, val mime: String)

data class PlayItem(
    val title: String,
    val url: String,
    val subtitles: List<Sub> = emptyList(),
    val resumeKey: String? = null,
    /** Empezar desde el principio aunque haya una posición guardada. */
    val fromStart: Boolean = false,
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
        val byId = HashMap<String, Int>()
        for (m in all) {
            if (m.key.isEmpty()) continue
            val mIds = m.ids
            // Primero por identificador (IMDb/TMDb/Plex), si no por título y año
            val hit = mIds.firstNotNullOfOrNull { byId[it] }
                ?: index[m.key]?.firstOrNull { yearsMatch(out[it].year, m.year) }
            val pos: Int
            if (hit != null) {
                pos = hit
                val o = out[hit]
                out[hit] = o.copy(
                    sources = o.sources + m.sources,
                    year = o.year ?: m.year,
                    poster = o.poster ?: m.poster,
                    plot = o.plot ?: m.plot,
                    added = maxOf(o.added, m.added),
                    rating = o.rating ?: m.rating,
                    genres = mergeGenres(o.genres, m.genres),
                    extIds = (o.extIds + m.extIds).distinct(),
                )
            } else {
                pos = out.size
                index.getOrPut(m.key) { ArrayList(1) } += pos
                out += m
            }
            for (id in mIds) byId.putIfAbsent(id, pos)
        }
        out.sortBy { it.key }
        return out
    }

    fun shows(all: List<Show>): List<Show> {
        val out = ArrayList<Show>(all.size)
        val index = HashMap<String, MutableList<Int>>()
        val byId = HashMap<String, Int>()
        for (s in all) {
            if (s.key.isEmpty()) continue
            val sIds = s.ids
            val hit = sIds.firstNotNullOfOrNull { byId[it] }
                ?: index[s.key]?.firstOrNull { yearsMatch(out[it].year, s.year) }
            val pos: Int
            if (hit != null) {
                pos = hit
                val o = out[hit]
                out[hit] = o.copy(
                    year = o.year ?: s.year,
                    poster = o.poster ?: s.poster,
                    plot = o.plot ?: s.plot,
                    plex = o.plex + s.plex,
                    xtreamIds = o.xtreamIds + s.xtreamIds,
                    episodes = o.episodes + s.episodes,
                    added = maxOf(o.added, s.added),
                    rating = o.rating ?: s.rating,
                    genres = mergeGenres(o.genres, s.genres),
                    quality = Quality.best(listOf(o.quality, s.quality)),
                    extIds = (o.extIds + s.extIds).distinct(),
                )
            } else {
                pos = out.size
                index.getOrPut(s.key) { ArrayList(1) } += pos
                out += s
            }
            for (id in sIds) byId.putIfAbsent(id, pos)
        }
        out.sortBy { it.key }
        return out
    }

    private fun mergeGenres(a: List<String>, b: List<String>): List<String> =
        if (b.isEmpty()) a else (a + b).distinctBy { Titles.genreKey(it) }

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

    private val GENRE_KEYS = ConcurrentHashMap<String, String>()
    fun genreKey(g: String): String = GENRE_KEYS.getOrPut(g) { key(g) }

    // "ES | ACCIÓN", "VOD - Comedia", "PELÍCULAS: Terror" → "Acción", "Comedia", "Terror"
    private val CAT_PREFIX = Regex(
        """^\s*(?:[|\[(]?\s*(?:[A-Z]{2,3}|(?i:vod|pelis|pel[iíÍ]culas|movies|series?))\s*[|\]):\-–]+\s*)+"""
    )
    private val CAT_SYMBOLS = Regex("""[|\[\]★☆•]+""")

    fun category(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var t = raw.replace(CAT_PREFIX, "").replace(CAT_SYMBOLS, " ").replace(SPACES, " ").trim()
        if (t.isEmpty()) return null
        if (t == t.uppercase() && t.any { it.isLetter() }) t = titleCase(t)
        return t
    }

    private val SMALL = setOf("de", "del", "la", "el", "los", "las", "y", "en", "a")

    /** "CIENCIA FICCIÓN" → "Ciencia ficción", pero "4K UHD" se queda igual. */
    private fun titleCase(t: String): String = t.split(' ').mapIndexed { i, w ->
        val low = w.lowercase()
        when {
            w.any { it.isDigit() } -> w
            i > 0 && low in SMALL -> low
            w.length <= 3 && i > 0 -> w
            i == 0 -> low.replaceFirstChar { it.uppercase() }
            else -> low
        }
    }.joinToString(" ")
}

data class Row(val title: String, val items: List<CatalogItem>)

/** Filas de la pantalla de inicio de Películas y Series. */
object Home {
    fun genres(items: List<CatalogItem>, min: Int = 6): List<String> {
        val counts = HashMap<String, Int>()
        val names = HashMap<String, String>()
        for (item in items) for (g in item.genres) {
            val k = Titles.genreKey(g)
            if (k.isEmpty()) continue
            counts[k] = (counts[k] ?: 0) + 1
            names.getOrPut(k) { g }
        }
        return counts.entries.filter { it.value >= min }
            .sortedByDescending { it.value }
            .mapNotNull { names[it.key] }
    }

    fun byGenre(items: List<CatalogItem>, genre: String): List<CatalogItem> {
        val k = Titles.genreKey(genre)
        return items.filter { k in it.genreKeys }.sortedByDescending { it.added }
    }

    /** Plataformas: se detectan por las categorías de tu proveedor IPTV ("NETFLIX", "HBO MAX"…). */
    private val PLATFORMS = listOf(
        "Netflix" to listOf("netflix"),
        "Prime Video" to listOf("amazon", "prime video", "prime"),
        "HBO Max" to listOf("hbo"),
        "Disney+" to listOf("disney"),
        "Apple TV+" to listOf("apple"),
        "Movistar+" to listOf("movistar"),
        "SkyShowtime" to listOf("skyshowtime", "sky showtime"),
        "Paramount+" to listOf("paramount"),
        "Filmin" to listOf("filmin"),
        "Atresplayer" to listOf("atresplayer"),
    )

    private fun isPlatform(genreKey: String, words: List<String>): Boolean = words.any { w ->
        if (' ' in w) genreKey.contains(w) else genreKey.split(' ').contains(w)
    }

    fun rows(
        items: List<CatalogItem>,
        resume: List<CatalogItem>,
        genres: List<String>,
        watch: List<CatalogItem> = emptyList(),
        newEpisodes: List<CatalogItem> = emptyList(),
    ): List<Row> {
        val out = ArrayList<Row>()
        if (newEpisodes.isNotEmpty()) out += Row("Nuevos capítulos", newEpisodes)
        if (resume.isNotEmpty()) out += Row("Seguir viendo", resume)
        if (watch.isNotEmpty()) out += Row("Mi lista de Plex", watch)

        val recent = items.filter { it.added > 0 }.sortedByDescending { it.added }.take(40)
        if (recent.isNotEmpty()) out += Row("Novedades", recent)

        val both = items.any { Origin.PLEX in it.origins } && items.any { Origin.IPTV in it.origins }
        if (both) {
            val plex = items.filter { Origin.PLEX in it.origins }.sortedByDescending { it.added }.take(40)
            if (plex.isNotEmpty()) out += Row("Novedades en Plex", plex)
        }

        val year = Calendar.getInstance().get(Calendar.YEAR)
        val fresh = items.filter { (it.year ?: 0) >= year - 1 }
            .sortedWith(compareByDescending<CatalogItem> { it.year ?: 0 }.thenByDescending { it.added })
            .take(40)
        if (fresh.size >= 5) out += Row("Estrenos", fresh)

        // Novedades por plataforma (si tu proveedor las tiene como categorías)
        for ((name, words) in PLATFORMS) {
            val list = items.filter { it.genreKeys.any { g -> isPlatform(g, words) } }
                .sortedByDescending { it.added }
                .take(40)
            if (list.size >= 5) out += Row("Novedades $name", list)
        }

        val top = items.filter { (it.rating ?: 0f) >= 7f }.sortedByDescending { it.rating ?: 0f }.take(40)
        if (top.size >= 8) out += Row("Mejor valoradas", top)

        val platformWords = PLATFORMS.flatMap { it.second }
        for (g in genres.filter { !isPlatform(Titles.genreKey(it), platformWords) }.take(14)) {
            val list = byGenre(items, g).take(30)
            if (list.size >= 5) out += Row(g, list)
        }
        if (out.isEmpty() && items.isNotEmpty()) out += Row("Todo", items.take(60))
        return out
    }
}
