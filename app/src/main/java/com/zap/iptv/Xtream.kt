package com.zap.iptv

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** Proveedores con enlaces tipo get.php?username=…&password=… */
object Xtream {

    data class Account(val base: String, val user: String, val pass: String) {
        private fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        fun api(action: String, extra: String = "") =
            "$base/player_api.php?username=${e(user)}&password=${e(pass)}&action=$action$extra"
        fun live(id: String) = "$base/live/$user/$pass/$id.ts"
        fun movie(id: String, ext: String) = "$base/movie/$user/$pass/$id.$ext"
        fun episode(id: String, ext: String) = "$base/series/$user/$pass/$id.$ext"
        val host: String get() = Uri.parse(base).host ?: base

        companion object {
            fun parse(src: String?): Account? {
                if (src == null || !src.startsWith("http")) return null
                val u = Uri.parse(src)
                val path = u.path ?: return null
                if (!path.endsWith("get.php") && !path.endsWith("player_api.php")) return null
                val user = u.getQueryParameter("username") ?: return null
                val pass = u.getQueryParameter("password") ?: return null
                val prefix = path.substringBeforeLast('/')
                return Account("${u.scheme}://${u.encodedAuthority}$prefix", user, pass)
            }
        }
    }

    private val FILES = listOf(
        "live_cats" to "get_live_categories",
        "live" to "get_live_streams",
        "vod_cats" to "get_vod_categories",
        "vod" to "get_vod_streams",
        "series_cats" to "get_series_categories",
        "series" to "get_series",
    )

    fun hasCache(dir: File) = FILES.all { File(dir, "${it.first}.json").exists() }

    suspend fun download(acc: Account, dir: File) = coroutineScope {
        dir.mkdirs()
        FILES.map { (name, action) ->
            async(Dispatchers.IO) { Http.download(acc.api(action), File(dir, "$name.json.tmp")) }
        }.awaitAll()
        for ((name, _) in FILES) File(dir, "$name.json.tmp").renameTo(File(dir, "$name.json"))
    }

    private fun cats(file: File): Map<String, String> {
        val out = HashMap<String, String>()
        forEachFlatObject(file, setOf("category_id", "category_name")) { m ->
            m["category_id"]?.let { id -> out[id] = m["category_name"] ?: "" }
        }
        return out
    }

    /** Algunos proveedores dan el id de TMDb: sirve para emparejar con Plex sin depender del título. */
    private fun tmdb(m: Map<String, String>): List<String> {
        val id = (m["tmdb"] ?: m["tmdb_id"])?.trim()?.takeIf { it.isNotEmpty() && it != "0" && it.all { c -> c.isDigit() } }
        return if (id == null) emptyList() else listOf("tmdb://$id")
    }

    private fun rating(v: String?): Float? = v?.replace(',', '.')?.toFloatOrNull()?.takeIf { it > 0f && it <= 10f }

    fun parse(dir: File, acc: Account): IptvData {
        val liveCats = cats(File(dir, "live_cats.json"))
        val vodCats = cats(File(dir, "vod_cats.json"))
        val seriesCats = cats(File(dir, "series_cats.json"))
        // Una sola lista de categoría por id, compartida por todos sus elementos
        val vodGenre = vodCats.mapValues { listOfNotNull(Titles.category(it.value)) }
        val seriesGenre = seriesCats.mapValues { listOfNotNull(Titles.category(it.value)) }

        val live = ArrayList<Channel>()
        forEachFlatObject(File(dir, "live.json"), setOf("stream_id", "name", "stream_icon", "category_id")) { m ->
                val id = m["stream_id"] ?: return@forEachFlatObject
                live += Channel(
                    name = m["name"].orEmpty().trim(),
                    url = acc.live(id),
                    logo = m["stream_icon"]?.takeIf { it.isNotBlank() },
                    group = liveCats[m["category_id"]]?.takeIf { it.isNotBlank() } ?: "Sin grupo",
                )
            }

        val movies = ArrayList<Movie>()
        forEachFlatObject(
            File(dir, "vod.json"),
            setOf("stream_id", "name", "stream_icon", "container_extension", "year", "added", "rating", "category_id", "tmdb", "tmdb_id"),
        ) { m ->
            val id = m["stream_id"] ?: return@forEachFlatObject
            val name = m["name"]?.takeIf { it.isNotBlank() } ?: return@forEachFlatObject
            val ext = m["container_extension"]?.takeIf { it.isNotBlank() } ?: "mp4"
            val c = Titles.clean(name)
            movies += Movie(
                title = c.title,
                year = m["year"]?.take(4)?.toIntOrNull() ?: c.year,
                poster = m["stream_icon"]?.takeIf { it.isNotBlank() },
                sources = listOf(
                    SourceRef(
                        Origin.IPTV, "IPTV · ${ext.uppercase()}", url = acc.movie(id, ext),
                        quality = Quality.fromText(name, vodCats[m["category_id"]]),
                        extIds = tmdb(m),
                    )
                ),
                added = m["added"]?.toLongOrNull() ?: 0L,
                rating = rating(m["rating"]),
                genres = vodGenre[m["category_id"]] ?: emptyList(),
            )
        }

        val shows = ArrayList<Show>()
        forEachFlatObject(
            File(dir, "series.json"),
            setOf("series_id", "name", "cover", "plot", "year", "releaseDate", "last_modified", "rating", "category_id", "tmdb", "tmdb_id"),
        ) { m ->
            val id = m["series_id"] ?: return@forEachFlatObject
            val name = m["name"]?.takeIf { it.isNotBlank() } ?: return@forEachFlatObject
            val c = Titles.clean(name)
            shows += Show(
                title = c.title,
                year = m["year"]?.take(4)?.toIntOrNull()
                    ?: m["releaseDate"]?.take(4)?.toIntOrNull()
                    ?: c.year,
                poster = m["cover"]?.takeIf { it.isNotBlank() },
                plot = m["plot"]?.takeIf { it.isNotBlank() },
                xtreamIds = listOf(id),
                added = m["last_modified"]?.toLongOrNull() ?: 0L,
                rating = rating(m["rating"]),
                genres = seriesGenre[m["category_id"]] ?: emptyList(),
                quality = Quality.fromText(name, seriesCats[m["category_id"]]),
                extIds = tmdb(m),
            )
        }
        return IptvData(live, movies, shows)
    }

    /** Ficha de una película del proveedor (director, reparto…). */
    fun vodInfo(acc: Account, streamId: String): Info? {
        val j = JSONObject(Http.get(acc.api("get_vod_info", "&vod_id=$streamId"), timeout = 20_000))
        val i = j.optJSONObject("info") ?: return null
        fun list(vararg names: String): List<String> = names.firstNotNullOfOrNull { i.str(it) }
            ?.split(',', '/')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val secs = i.optLong("duration_secs").takeIf { it > 0 }
            ?: i.str("duration")?.split(':')?.mapNotNull { it.trim().toLongOrNull() }
                ?.takeIf { it.size == 3 }?.let { it[0] * 3600 + it[1] * 60 + it[2] }
        return Info(
            plot = i.str("plot") ?: i.str("description"),
            minutes = secs?.let { (it / 60).toInt() }?.takeIf { it > 0 },
            contentRating = i.str("mpaa_rating") ?: i.str("age"),
            rating = i.str("rating")?.replace(',', '.')?.toFloatOrNull()?.takeIf { it in 0.1f..10f },
            directors = list("director"),
            cast = list("cast", "actors").take(20).map { Person(it, null, null) },
            genres = list("genre"),
        )
    }

    /** El id de una peli Xtream sale de su enlace: …/movie/usuario/clave/ID.ext */
    fun movieId(url: String): String? =
        url.takeIf { "/movie/" in it }?.substringAfterLast('/')?.substringBefore('.')?.takeIf { it.isNotEmpty() }

    fun episodes(acc: Account, seriesId: String): List<Episode> {
        val j = JSONObject(Http.get(acc.api("get_series_info", "&series_id=$seriesId"), timeout = 30_000))
        val out = ArrayList<Episode>()

        fun add(e: JSONObject) {
            val id = e.str("id") ?: return
            val season = e.optInt("season", -1)
            val num = e.optInt("episode_num", -1)
            if (season < 0 || num < 0) return
            val ext = e.str("container_extension") ?: "mp4"
            out += Episode(
                season, num, e.str("title"),
                listOf(SourceRef(Origin.IPTV, "IPTV · ${ext.uppercase()}", url = acc.episode(id, ext))),
            )
        }

        fun addAll(arr: JSONArray) {
            for (i in 0 until arr.length()) {
                when (val x = arr.opt(i)) {
                    is JSONObject -> add(x)
                    is JSONArray -> addAll(x)
                }
            }
        }

        when (val eps = j.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k -> eps.optJSONArray(k)?.let { addAll(it) } }
            is JSONArray -> addAll(eps)
        }
        return out
    }
}
