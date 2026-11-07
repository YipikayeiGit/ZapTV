package com.zap.iptv

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.JsonReader
import android.util.JsonToken
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
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

    /** Informe de la última carga, para Ajustes → Plex → Estado. */
    var status: String
        get() = prefs.getString("plex_status", null) ?: "Todavía no se ha cargado Plex."
        private set(v) = prefs.edit().putString("plex_status", v).apply()

    suspend fun refresh(ctx: Context): PlexData = coroutineScope {
        val tok = token ?: return@coroutineScope PlexData.EMPTY
        val res = JSONArray(
            Http.get("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1&includeIPv6=1", headers(tok))
        )
        val candidates = (0 until res.length())
            .mapNotNull { res.optJSONObject(it) }
            .filter { it.optString("provides").contains("server") }

        val conns = candidates.map { r -> async(Dispatchers.IO) { r to connect(r, tok) } }.awaitAll()
        val found = conns.mapNotNull { it.second.first }
        servers = found.associateBy { it.id }
        val multi = found.size > 1

        val parts = conns.map { (r, c) ->
            async(Dispatchers.IO) {
                val shared = if (r.optBoolean("owned")) "" else " (compartido)"
                val name = r.optString("name").ifEmpty { "Servidor" } + shared
                val s = c.first
                if (s == null) {
                    PlexData.EMPTY to "• $name: ${c.second}"
                } else {
                    try {
                        val d = library(s, multi)
                        d to "• $name: conectado (${c.second}) · ${d.movies.size} pelis · ${d.shows.size} series"
                    } catch (e: Exception) {
                        PlexData.EMPTY to "• $name: conectado (${c.second}) pero no se pudo leer la biblioteca: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
        }.awaitAll()

        status = buildString {
            append("Cuenta: ").append(user ?: "conectada").append('\n')
            if (candidates.isEmpty()) {
                append("No aparece ningún servidor en esta cuenta. Comprueba que el dueño ha compartido sus bibliotecas con este usuario de Plex y que has aceptado la invitación.")
            } else {
                append(parts.joinToString("\n") { it.second })
            }
        }

        val data = PlexData(found, parts.flatMap { it.first.movies }, parts.flatMap { it.first.shows })
        save(ctx, data)
        data
    }

    /**
     * Prueba todas las direcciones del servidor a la vez y se queda con la mejor:
     * local, luego remota directa y por último el relay de Plex.
     */
    private suspend fun connect(r: JSONObject, fallbackToken: String): Pair<PlexServer?, String> = coroutineScope {
        val tok = r.str("accessToken") ?: fallbackToken
        val conns = r.optJSONArray("connections")
        val list = if (conns == null) emptyList() else (0 until conns.length()).mapNotNull { conns.optJSONObject(it) }
        if (list.isEmpty()) return@coroutineScope (null to "el servidor no publica ninguna dirección (¿está apagado?)")

        val ok = list.map { c ->
            async(Dispatchers.IO) {
                val uri = c.str("uri") ?: return@async null
                if (runCatching { Http.get("$uri/identity", headers(tok), timeout = 7_000) }.isSuccess) c else null
            }
        }.awaitAll().filterNotNull()

        val best = ok.minByOrNull { c ->
            when {
                c.optBoolean("relay") -> 2
                c.optBoolean("local") -> 0
                else -> 1
            }
        } ?: return@coroutineScope (null to "sin conexión (${list.size} direcciones probadas). Puede que el servidor esté apagado o sin acceso remoto.")

        val kind = when {
            best.optBoolean("relay") -> "relay de Plex, calidad limitada"
            best.optBoolean("local") -> "red local"
            else -> "remoto"
        }
        PlexServer(r.optString("clientIdentifier"), r.optString("name"), best.getString("uri"), tok) to kind
    }

    private fun library(s: PlexServer, multi: Boolean): PlexData {
        val sections = JSONObject(Http.get("${s.uri}/library/sections", headers(s.token)))
            .optJSONObject("MediaContainer")?.optJSONArray("Directory") ?: return PlexData.EMPTY
        val dirs = (0 until sections.length()).mapNotNull { sections.optJSONObject(it) }
        val movieLibs = dirs.count { it.optString("type") == "movie" }
        val showLibs = dirs.count { it.optString("type") == "show" }
        val movies = ArrayList<Movie>()
        val shows = ArrayList<Show>()
        var lastError: Exception? = null

        for (d in dirs) {
            val type = d.optString("type")
            if (type != "movie" && type != "show") continue
            // Con varias bibliotecas del mismo tipo, su nombre sirve de categoría ("Infantil", "4K"…)
            val libName = d.str("title")?.takeIf { (if (type == "movie") movieLibs else showLibs) > 1 }
            try {
                readSection(s, d.optString("key")) { x ->
                    val title = x.title ?: return@readSection
                    val poster = x.thumb?.let { poster(s, it) }
                    val rating = (if (x.audienceRating > 0) x.audienceRating else x.rating)
                        .takeIf { it > 0 }?.toFloat()
                    val genres = if (libName != null) x.genres + libName else x.genres
                    val year = x.year.takeIf { it > 0 }
                    if (type == "movie") {
                        val label = listOfNotNull("Plex", resLabel(x.resolution), s.name.takeIf { multi }).joinToString(" · ")
                        movies += Movie(
                            title, year, poster,
                            listOf(SourceRef(Origin.PLEX, label, plexServer = s.id, plexKey = x.key)),
                            x.summary, x.added, rating, genres,
                        )
                    } else {
                        shows += Show(
                            title, year, poster, x.summary, plex = listOf(PlexRef(s.id, x.key)),
                            added = x.added, rating = rating, genres = genres,
                        )
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (movies.isEmpty() && shows.isEmpty()) lastError?.let { throw it }
        return PlexData(listOf(s), movies, shows)
    }

    /** Lo mínimo que necesitamos de cada elemento de la biblioteca. */
    private class RawItem {
        var key = ""
        var title: String? = null
        var year = 0
        var thumb: String? = null
        var summary: String? = null
        var added = 0L
        var audienceRating = 0.0
        var rating = 0.0
        var resolution: String? = null
        val genres = ArrayList<String>(3)
    }

    /** Recorre una biblioteca de 500 en 500 sin cargar el JSON entero en memoria. */
    private fun readSection(s: PlexServer, sectionKey: String, onItem: (RawItem) -> Unit) {
        val page = 500
        var start = 0
        while (true) {
            var total = -1
            var count = 0
            val url = "${s.uri}/library/sections/$sectionKey/all" +
                "?X-Plex-Container-Start=$start&X-Plex-Container-Size=$page"
            Http.stream(url, headers(s.token), timeout = 120_000) { input ->
                val r = JsonReader(InputStreamReader(input, Charsets.UTF_8))
                r.isLenient = true
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "MediaContainer" || r.peek() != JsonToken.BEGIN_OBJECT) {
                        r.skipValue()
                        continue
                    }
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "totalSize" -> total = r.numOrZero().toInt()
                            "Metadata" -> {
                                if (r.peek() != JsonToken.BEGIN_ARRAY) {
                                    r.skipValue()
                                } else {
                                    r.beginArray()
                                    while (r.hasNext()) {
                                        if (r.peek() == JsonToken.BEGIN_OBJECT) {
                                            onItem(readItem(r))
                                        } else {
                                            r.skipValue()
                                        }
                                        count++
                                    }
                                    r.endArray()
                                }
                            }
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                }
                r.endObject()
            }
            start += page
            if (count == 0) break
            if (total >= 0 && start >= total) break
            if (total < 0 && count < page) break
        }
    }

    private fun readItem(r: JsonReader): RawItem {
        val x = RawItem()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "ratingKey" -> x.key = r.strOrNull() ?: ""
                "title" -> x.title = r.strOrNull()
                "year" -> x.year = r.numOrZero().toInt()
                "thumb" -> x.thumb = r.strOrNull()
                "summary" -> x.summary = r.strOrNull()?.takeIf { it.isNotBlank() }
                "addedAt" -> x.added = r.numOrZero().toLong()
                "audienceRating" -> x.audienceRating = r.numOrZero()
                "rating" -> x.rating = r.numOrZero()
                "Genre" -> r.eachObject { name -> if (name == "tag") r.strOrNull()?.let { x.genres += it } else r.skipValue() }
                "Media" -> r.eachObject { name ->
                    if (name == "videoResolution" && x.resolution == null) x.resolution = r.strOrNull() else r.skipValue()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return x
    }

    private fun JsonReader.strOrNull(): String? = when (peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        JsonToken.NULL -> { nextNull(); null }
        else -> { skipValue(); null }
    }

    private fun JsonReader.numOrZero(): Double = when (peek()) {
        JsonToken.NUMBER, JsonToken.STRING -> nextString().toDoubleOrNull() ?: 0.0
        else -> { skipValue(); 0.0 }
    }

    /** Recorre un array de objetos llamando a [field] con el nombre de cada campo. */
    private inline fun JsonReader.eachObject(field: (String) -> Unit) {
        if (peek() != JsonToken.BEGIN_ARRAY) {
            skipValue()
            return
        }
        beginArray()
        while (hasNext()) {
            if (peek() != JsonToken.BEGIN_OBJECT) {
                skipValue()
                continue
            }
            beginObject()
            while (hasNext()) field(nextName())
            endObject()
        }
        endArray()
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

    /** Siempre el archivo original (Direct Play): Plex nunca convierte el vídeo. */
    data class Details(val label: String, val url: String, val subs: List<Sub>)

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

        return Details(label, withToken("${s.uri}$partKey", s.token), subs)
    }

    private fun withToken(url: String, token: String) =
        url + (if ('?' in url) "&" else "?") + "X-Plex-Token=$token"

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
                    .put("d", m.plot ?: "").put("s", src.plexServer).put("k", src.plexKey).put("l", src.label)
                    .put("a", m.added).put("r", (m.rating ?: 0f).toDouble()).put("g", JSONArray(m.genres)))
            }
        })
        j.put("shows", JSONArray().apply {
            d.shows.forEach { s ->
                val ref = s.plex.first()
                put(JSONObject().put("t", s.title).put("y", s.year ?: 0).put("p", s.poster ?: "")
                    .put("d", s.plot ?: "").put("s", ref.server).put("k", ref.key)
                    .put("a", s.added).put("r", (s.rating ?: 0f).toDouble()).put("g", JSONArray(s.genres)))
            }
        })
        cacheFile(ctx).writeText(j.toString())
    }

    private fun genresOf(o: JSONObject): List<String> {
        val a = o.optJSONArray("g") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf { g -> g.isNotEmpty() } }
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
                        o.optLong("a"),
                        o.optDouble("r").takeIf { !it.isNaN() && it > 0 }?.toFloat(),
                        genresOf(o),
                    )
                }
            }
            val shows = j.getJSONArray("shows").let { a ->
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Show(
                        o.getString("t"), o.optInt("y").takeIf { it > 0 }, o.str("p"), o.str("d"),
                        plex = listOf(PlexRef(o.getString("s"), o.getString("k"))),
                        added = o.optLong("a"),
                        rating = o.optDouble("r").takeIf { !it.isNaN() && it > 0 }?.toFloat(),
                        genres = genresOf(o),
                    )
                }
            }
            PlexData(sv, movies, shows)
        }.getOrNull()
    }
}
