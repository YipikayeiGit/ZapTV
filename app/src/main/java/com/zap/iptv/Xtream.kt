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

    private fun cats(file: File): Map<String, String> =
        readFlatObjects(file, setOf("category_id", "category_name"))
            .mapNotNull { m -> m["category_id"]?.let { id -> id to (m["category_name"] ?: "") } }
            .toMap()

    private fun rating(v: String?): Float? = v?.replace(',', '.')?.toFloatOrNull()?.takeIf { it > 0f && it <= 10f }

    fun parse(dir: File, acc: Account): IptvData {
        val liveCats = cats(File(dir, "live_cats.json"))
        val vodCats = cats(File(dir, "vod_cats.json"))
        val seriesCats = cats(File(dir, "series_cats.json"))

        val live = readFlatObjects(File(dir, "live.json"), setOf("stream_id", "name", "stream_icon", "category_id"))
            .mapNotNull { m ->
                val id = m["stream_id"] ?: return@mapNotNull null
                Channel(
                    name = m["name"].orEmpty().trim(),
                    url = acc.live(id),
                    logo = m["stream_icon"]?.takeIf { it.isNotBlank() },
                    group = liveCats[m["category_id"]]?.takeIf { it.isNotBlank() } ?: "Sin grupo",
                )
            }

        val movies = readFlatObjects(
            File(dir, "vod.json"),
            setOf("stream_id", "name", "stream_icon", "container_extension", "year", "added", "rating", "category_id"),
        ).mapNotNull { m ->
            val id = m["stream_id"] ?: return@mapNotNull null
            val name = m["name"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val ext = m["container_extension"]?.takeIf { it.isNotBlank() } ?: "mp4"
            val c = Titles.clean(name)
            Movie(
                title = c.title,
                year = m["year"]?.take(4)?.toIntOrNull() ?: c.year,
                poster = m["stream_icon"]?.takeIf { it.isNotBlank() },
                sources = listOf(SourceRef(Origin.IPTV, "IPTV · ${ext.uppercase()}", url = acc.movie(id, ext))),
                added = m["added"]?.toLongOrNull() ?: 0L,
                rating = rating(m["rating"]),
                genres = listOfNotNull(Titles.category(vodCats[m["category_id"]])),
            )
        }

        val shows = readFlatObjects(
            File(dir, "series.json"),
            setOf("series_id", "name", "cover", "plot", "year", "releaseDate", "last_modified", "rating", "category_id"),
        ).mapNotNull { m ->
            val id = m["series_id"] ?: return@mapNotNull null
            val name = m["name"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val c = Titles.clean(name)
            Show(
                title = c.title,
                year = m["year"]?.take(4)?.toIntOrNull()
                    ?: m["releaseDate"]?.take(4)?.toIntOrNull()
                    ?: c.year,
                poster = m["cover"]?.takeIf { it.isNotBlank() },
                plot = m["plot"]?.takeIf { it.isNotBlank() },
                xtreamIds = listOf(id),
                added = m["last_modified"]?.toLongOrNull() ?: 0L,
                rating = rating(m["rating"]),
                genres = listOfNotNull(Titles.category(seriesCats[m["category_id"]])),
            )
        }
        return IptvData(live, movies, shows)
    }

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
