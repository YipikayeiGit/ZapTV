package com.zap.iptv

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

object Plex {
    private const val PRODUCT = "Zap"
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("zap", Context.MODE_PRIVATE)
    }

    val clientId: String
        get() = prefs.getString("plex_cid", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("plex_cid", it).apply() }

    var token: String?
        get() = prefs.getString("plex_token", null)
        set(v) = prefs.edit().putString("plex_token", v).apply()

    var user: String?
        get() = prefs.getString("plex_user", null)
        set(v) = prefs.edit().putString("plex_user", v).apply()

    val loggedIn: Boolean get() = token != null

    @Volatile
    private var servers: Map<String, PlexServer> = emptyMap()

    private fun headers(tok: String? = token): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("X-Plex-Client-Identifier", clientId)
        put("X-Plex-Product", PRODUCT)
        put("X-Plex-Version", "1.2")
        put("X-Plex-Platform", "Android")
        put("X-Plex-Device", Build.MODEL ?: "Android")
        put("X-Plex-Device-Name", "Zap (${Build.MODEL ?: "Android"})")
        if (tok != null) put("X-Plex-Token", tok)
    }

    // ---------- Inicio de sesión con código (plex.tv/link) ----------

    data class Pin(val id: Long, val code: String)

    fun createPin(): Pin {
        val j = JSONObject(Http.post("https://plex.tv/api/v2/pins?strong=false", headers(null)))
        return Pin(j.getLong("id"), j.getString("code"))
    }

    /** Devuelve el token cuando el usuario ya ha introducido el código. */
    fun checkPin(id: Long): String? =
        JSONObject(Http.get("https://plex.tv/api/v2/pins/$id", headers(null))).str("authToken")

    fun fetchUser(): String? = runCatching {
        val j = JSONObject(Http.get("https://plex.tv/api/v2/user", headers()))
        j.str("username") ?: j.str("title")
    }.getOrNull()

    fun logout(ctx: Context) {
        prefs.edit().remove("plex_token").remove("plex_user").apply()
        servers = emptyMap()
        cacheFile(ctx).delete()
    }

    // ---------- Biblioteca ----------

    suspend fun refresh(ctx: Context): PlexData = coroutineScope {
        val tok = token ?: return@coroutineScope PlexData.EMPTY
        val res = JSONArray(
            Http.get("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", headers(tok))
        )
        val candidates = (0 until res.length())
            .mapNotNull { res.optJSONObject(it) }
            .filter { it.optString("provides").contains("server") }

        val found = candidates.map { r -> async(Dispatchers.IO) { connect(r, tok) } }.awaitAll().filterNotNull()
        servers = found.associateBy { it.id }
        val multi = found.size > 1

        val parts = found.map { s ->
            async(Dispatchers.IO) { runCatching { library(s, multi) }.getOrDefault(PlexData.EMPTY) }
        }.awaitAll()

        val data = PlexData(found, parts.flatMap { it.movies }, parts.flatMap { it.shows })
        save(ctx, data)
        data
    }

    /** Prueba las conexiones del servidor: primero la local, luego la remota y por último el relay. */
    private fun connect(r: JSONObject, fallbackToken: String): PlexServer? {
        val tok = r.str("accessToken") ?: fallbackToken
        val conns = r.optJSONArray("connections") ?: return null
        val list = (0 until conns.length())
            .mapNotNull { conns.optJSONObject(it) }
            .sortedBy { c ->
                when {
                    c.optBoolean("relay") -> 2
                    c.optBoolean("local") -> 0
                    else -> 1
                }
            }
        for (c in list) {
            val uri = c.str("uri") ?: continue
            val ok = runCatching { Http.get("$uri/identity", headers(tok), timeout = 4_000) }.isSuccess
            if (ok) return PlexServer(r.optString("clientIdentifier"), r.optString("name"), uri, tok)
        }
        return null
    }

    private fun library(s: PlexServer, multi: Boolean): PlexData {
        val sections = JSONObject(Http.get("${s.uri}/library/sections", headers(s.token)))
            .optJSONObject("MediaContainer")?.optJSONArray("Directory") ?: return PlexData.EMPTY
        val movies = ArrayList<Movie>()
        val shows = ArrayList<Show>()
        for (i in 0 until sections.length()) {
            val d = sections.optJSONObject(i) ?: continue
            val type = d.optString("type")
            if (type != "movie" && type != "show") continue
            val items = JSONObject(
                Http.get("${s.uri}/library/sections/${d.optString("key")}/all", headers(s.token), timeout = 90_000)
            ).optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: continue

            for (j in 0 until items.length()) {
                val m = items.optJSONObject(j) ?: continue
                val title = m.str("title") ?: continue
                val year = m.optInt("year").takeIf { it > 0 }
                val poster = m.str("thumb")?.let { poster(s, it) }
                val key = m.optString("ratingKey")
                val plot = m.str("summary")
                if (type == "movie") {
                    val res = m.optJSONArray("Media")?.optJSONObject(0)?.str("videoResolution")
                    val label = listOfNotNull("Plex", resLabel(res), s.name.takeIf { multi }).joinToString(" · ")
                    movies += Movie(
                        title, year, poster,
                        listOf(SourceRef(Origin.PLEX, label, plexServer = s.id, plexKey = key)),
                        plot,
                    )
                } else {
                    shows += Show(title, year, poster, plot, plex = listOf(PlexRef(s.id, key)))
                }
            }
        }
        return PlexData(listOf(s), movies, shows)
    }

    private fun poster(s: PlexServer, thumb: String): String =
        "${s.uri}/photo/:/transcode?width=240&height=360&minSize=1&upscale=1" +
            "&url=${URLEncoder.encode(thumb, "UTF-8")}&X-Plex-Token=${s.token}"

    private fun resLabel(r: String?): String? {
        if (r.isNullOrEmpty()) return null
        return when (r.lowercase()) {
            "4k" -> "4K"
            "sd" -> "SD"
            else -> if (r.all { it.isDigit() }) "${r}p" else r.uppercase()
        }
    }

    fun episodes(ref: PlexRef): List<Episode> {
        val s = servers[ref.server] ?: return emptyList()
        val items = JSONObject(
            Http.get("${s.uri}/library/metadata/${ref.key}/allLeaves", headers(s.token), timeout = 30_000)
        ).optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: return emptyList()
        val multi = servers.size > 1
        return (0 until items.length()).mapNotNull { i ->
            val m = items.optJSONObject(i) ?: return@mapNotNull null
            val season = m.optInt("parentIndex", -1)
            val num = m.optInt("index", -1)
            if (season < 0 || num < 0) return@mapNotNull null
            val res = m.optJSONArray("Media")?.optJSONObject(0)?.str("videoResolution")
            val label = listOfNotNull("Plex", resLabel(res), s.name.takeIf { multi }).joinToString(" · ")
            Episode(
                season, num, m.str("title"),
                listOf(SourceRef(Origin.PLEX, label, plexServer = s.id, plexKey = m.optString("ratingKey"))),
            )
        }
    }

    // ---------- Reproducción ----------

    data class Details(val label: String, val url: String, val subs: List<Sub>, val fallback: String)

    /** Pide a Plex el archivo, idiomas y subtítulos de una peli o episodio. */
    fun details(serverId: String, key: String): Details {
        val s = servers[serverId] ?: throw IOException("servidor Plex no disponible")
        val m = JSONObject(Http.get("${s.uri}/library/metadata/$key", headers(s.token)))
            .getJSONObject("MediaContainer").getJSONArray("Metadata").getJSONObject(0)
        val media = m.optJSONArray("Media")?.optJSONObject(0) ?: throw IOException("sin vídeo")
        val part = media.optJSONArray("Part")?.optJSONObject(0) ?: throw IOException("sin vídeo")
        val partKey = part.getString("key")

        val audio = LinkedHashSet<String>()
        val subLangs = LinkedHashSet<String>()
        val subs = ArrayList<Sub>()
        val streams = part.optJSONArray("Stream")
        if (streams != null) for (i in 0 until streams.length()) {
            val st = streams.optJSONObject(i) ?: continue
            val lang = st.str("languageTag")?.take(2)?.uppercase() ?: st.str("languageCode")?.let { lang3(it) }
            when (st.optInt("streamType")) {
                2 -> lang?.let { audio += it }
                3 -> {
                    lang?.let { subLangs += it }
                    val k = st.str("key")
                    val mime = when (st.str("codec")?.lowercase()) {
                        "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
                        "ass", "ssa" -> MimeTypes.TEXT_SSA
                        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
                        else -> null
                    }
                    if (k != null && mime != null) {
                        subs += Sub(withToken("${s.uri}$k", s.token), lang?.lowercase(), st.str("displayTitle"), mime)
                    }
                }
            }
        }
        val label = listOfNotNull(
            "Plex",
            resLabel(media.str("videoResolution")),
            audio.takeIf { it.isNotEmpty() }?.joinToString("/"),
            subLangs.takeIf { it.isNotEmpty() }?.let { "Subs " + it.joinToString("/") },
        ).joinToString(" · ")

        return Details(label, withToken("${s.uri}$partKey", s.token), subs, transcode(s, key))
    }

    private fun withToken(url: String, token: String) =
        url + (if ('?' in url) "&" else "?") + "X-Plex-Token=$token"

    /** Plan B si el dispositivo no puede reproducir el archivo original. */
    private fun transcode(s: PlexServer, key: String): String {
        val p = linkedMapOf(
            "path" to "/library/metadata/$key",
            "mediaIndex" to "0", "partIndex" to "0",
            "protocol" to "hls", "fastSeek" to "1",
            "directPlay" to "0", "directStream" to "1", "directStreamAudio" to "1",
            "videoQuality" to "100", "maxVideoBitrate" to "20000",
            "subtitles" to "auto", "location" to "wan",
            "session" to UUID.randomUUID().toString(),
            "X-Plex-Client-Identifier" to clientId,
            "X-Plex-Product" to PRODUCT,
            "X-Plex-Platform" to "Chrome",
            "X-Plex-Token" to s.token,
        )
        return "${s.uri}/video/:/transcode/universal/start.m3u8?" +
            p.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }

    private fun lang3(code: String): String = when (code.lowercase()) {
        "spa" -> "ES"; "eng" -> "EN"; "fre", "fra" -> "FR"; "ger", "deu" -> "DE"; "ita" -> "IT"
        "por" -> "PT"; "jpn" -> "JA"; "cat" -> "CA"; "kor" -> "KO"; "chi", "zho" -> "ZH"
        else -> code.take(2).uppercase()
    }

    // ---------- Caché en disco ----------

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "plex.json")

    private fun save(ctx: Context, d: PlexData) {
        val j = JSONObject()
        j.put("servers", JSONArray().apply {
            d.servers.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("uri", it.uri).put("token", it.token)) }
        })
        j.put("movies", JSONArray().apply {
            d.movies.forEach { m ->
                val src = m.sources.first()
                put(JSONObject().put("t", m.title).put("y", m.year ?: 0).put("p", m.poster ?: "")
                    .put("d", m.plot ?: "").put("s", src.plexServer).put("k", src.plexKey).put("l", src.label))
            }
        })
        j.put("shows", JSONArray().apply {
            d.shows.forEach { s ->
                val ref = s.plex.first()
                put(JSONObject().put("t", s.title).put("y", s.year ?: 0).put("p", s.poster ?: "")
                    .put("d", s.plot ?: "").put("s", ref.server).put("k", ref.key))
            }
        })
        cacheFile(ctx).writeText(j.toString())
    }

    fun loadCached(ctx: Context): PlexData? {
        if (!loggedIn) return null
        val f = cacheFile(ctx)
        if (!f.exists()) return null
        return runCatching {
            val j = JSONObject(f.readText())
            val sv = j.getJSONArray("servers").let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }
                    .map { PlexServer(it.getString("id"), it.getString("name"), it.getString("uri"), it.getString("token")) }
            }
            servers = sv.associateBy { it.id }
            val movies = j.getJSONArray("movies").let { a ->
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Movie(
                        o.getString("t"), o.optInt("y").takeIf { it > 0 }, o.str("p"),
                        listOf(SourceRef(Origin.PLEX, o.optString("l", "Plex"), plexServer = o.getString("s"), plexKey = o.getString("k"))),
                        o.str("d"),
                    )
                }
            }
            val shows = j.getJSONArray("shows").let { a ->
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Show(
                        o.getString("t"), o.optInt("y").takeIf { it > 0 }, o.str("p"), o.str("d"),
                        plex = listOf(PlexRef(o.getString("s"), o.getString("k"))),
                    )
                }
            }
            PlexData(sv, movies, shows)
        }.getOrNull()
    }
}
