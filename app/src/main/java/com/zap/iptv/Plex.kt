package com.zap.iptv

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.JsonReader
import android.util.JsonToken
import android.util.Xml
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.StringReader
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

    /**
     * [previous] = lo que ya teníamos: si un servidor no responde ahora,
     * se siguen mostrando sus pelis y series guardadas en vez de perderlas.
     */
    suspend fun refresh(ctx: Context, previous: PlexData = PlexData.EMPTY): PlexData = coroutineScope {
        val tok = token ?: return@coroutineScope PlexData.EMPTY
        val oldServers = previous.servers.associateBy { it.id }
        val res = JSONArray(
            Http.get("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1&includeIPv6=1", headers(tok))
        )
        val candidates = (0 until res.length())
            .mapNotNull { res.optJSONObject(it) }
            .filter { it.optString("provides").contains("server") }

        val watchJob = async(Dispatchers.IO) { runCatching { watchlist(tok) } }
        val conns = candidates.map { r ->
            async(Dispatchers.IO) { r to connect(r, tok, oldServers[r.optString("clientIdentifier")]?.uri) }
        }.awaitAll()
        val found = conns.mapNotNull { it.second.first }
        servers = found.associateBy { it.id }
        val multi = found.size > 1

        val parts = conns.map { (r, c) ->
            async(Dispatchers.IO) {
                val shared = if (r.optBoolean("owned")) "" else " (compartido)"
                val name = r.optString("name").ifEmpty { "Servidor" } + shared
                val s = c.first
                val old = oldServers[r.optString("clientIdentifier")]
                if (s == null && old != null) {
                    // No responde ahora: conservamos lo guardado de este servidor
                    val keep = PlexData(
                        listOf(old),
                        previous.movies.filter { m -> m.sources.any { it.plexServer == old.id } },
                        previous.shows.filter { sh -> sh.plex.any { it.server == old.id } },
                        progress = previous.progress.filter { it.server == old.id },
                    )
                    keep to "• $name: ${c.second}\n   Se muestran sus ${keep.movies.size} pelis y ${keep.shows.size} series guardadas."
                } else if (s == null) {
                    PlexData.EMPTY to "• $name: ${c.second}"
                } else {
                    try {
                        val prog = runCatching { onDeck(s) }.getOrDefault(emptyList())
                        val d = library(s, multi).copy(progress = prog)
                        d to "• $name: conectado (${c.second}) · ${d.movies.size} pelis · ${d.shows.size} series" +
                            " · ${prog.size} en seguir viendo"
                    } catch (e: Exception) {
                        PlexData.EMPTY to "• $name: conectado (${c.second}) pero no se pudo leer la biblioteca: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
        }.awaitAll()

        // Servidores para reproducir: los conectados ahora y los que se conservan de antes
        servers = parts.flatMap { it.first.servers }.associateBy { it.id }
        val watch = watchJob.await()
        status = buildString {
            append("Cuenta: ").append(user ?: "conectada").append('\n')
            watch.fold(
                onSuccess = { append("Mi lista: ${it.size} títulos\n") },
                onFailure = { append("Mi lista: no se pudo leer (${it.message ?: it.javaClass.simpleName})\n") },
            )
            if (candidates.isEmpty()) {
                append("No aparece ningún servidor en esta cuenta. Comprueba que el dueño ha compartido sus bibliotecas con este usuario de Plex y que has aceptado la invitación.")
            } else {
                append(parts.joinToString("\n") { it.second })
            }
        }

        val data = PlexData(
            parts.flatMap { it.first.servers },
            parts.flatMap { it.first.movies },
            parts.flatMap { it.first.shows },
            watch.getOrDefault(emptyList()),
            parts.flatMap { it.first.progress },
        )
        save(ctx, data)
        data
    }

    /** "Mi lista" (watchlist) de la cuenta de Plex. */
    private fun watchlist(tok: String): List<WatchItem> {
        var lastError: Exception? = null
        var best: List<WatchItem>? = null
        for (host in listOf("https://discover.provider.plex.tv", "https://metadata.provider.plex.tv")) {
            try {
                val out = ArrayList<WatchItem>()
                var start = 0
                while (true) {
                    val text = Http.get(
                        "$host/library/sections/watchlist/all?includeCollections=1&includeExternalMedia=1" +
                            "&includeGuids=1&sort=watchlistedAt:desc" +
                            "&X-Plex-Container-Start=$start&X-Plex-Container-Size=100",
                        headers(tok),
                    )
                    val (items, total) = parseWatchPage(text)
                    out += items
                    start += 100
                    if (items.isEmpty() || (total in 0..start) || (total < 0 && items.size < 100)) break
                }
                if (out.size > (best?.size ?: -1)) best = out
                // Con resultados no hace falta probar el otro servicio
                if (out.isNotEmpty()) return out
            } catch (e: Exception) {
                lastError = e
            }
        }
        return best ?: throw (lastError ?: IOException("sin respuesta"))
    }

    /** Una página de Mi lista. Plex a veces responde en JSON y a veces en XML. */
    private fun parseWatchPage(text: String): Pair<List<WatchItem>, Int> {
        val t = text.trimStart()
        return if (t.startsWith("<")) parseWatchXml(t) else parseWatchJson(t)
    }

    private fun parseWatchJson(text: String): Pair<List<WatchItem>, Int> {
        val mc = JSONObject(text).optJSONObject("MediaContainer") ?: return emptyList<WatchItem>() to 0
        val arr = mc.optJSONArray("Metadata")
        val out = ArrayList<WatchItem>()
        if (arr != null) for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val title = m.str("title") ?: continue
            val guids = m.optJSONArray("Guid")
            val ext = if (guids == null) emptyList()
            else (0 until guids.length()).mapNotNull { k -> guids.optJSONObject(k)?.str("id") }
            out += WatchItem(
                m.str("guid"), title, m.optInt("year").takeIf { it > 0 }, m.optString("type"),
                thumb = m.str("thumb")?.takeIf { it.startsWith("http") },
                extIds = ext,
            )
        }
        return out to mc.optInt("totalSize", -1)
    }

    private fun parseWatchXml(text: String): Pair<List<WatchItem>, Int> {
        val p = Xml.newPullParser()
        p.setInput(StringReader(text))
        val out = ArrayList<WatchItem>()
        var total = -1
        var current: WatchItem? = null
        var ext = ArrayList<String>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "MediaContainer" -> total = p.getAttributeValue(null, "totalSize")?.toIntOrNull() ?: -1
                    "Video", "Directory", "Metadata" -> {
                        val title = p.getAttributeValue(null, "title")
                        ext = ArrayList()
                        current = if (title == null) null else WatchItem(
                            p.getAttributeValue(null, "guid"),
                            title,
                            p.getAttributeValue(null, "year")?.toIntOrNull(),
                            p.getAttributeValue(null, "type") ?: "",
                            thumb = p.getAttributeValue(null, "thumb")?.takeIf { it.startsWith("http") },
                        )
                    }
                    "Guid" -> p.getAttributeValue(null, "id")?.let { ext += it }
                }
                XmlPullParser.END_TAG -> if (p.name == "Video" || p.name == "Directory" || p.name == "Metadata") {
                    current?.let { out += it.copy(extIds = ext.toList()) }
                    current = null
                }
            }
            event = p.next()
        }
        return out to total
    }

    /**
     * Prueba todas las direcciones del servidor a la vez y se queda con la mejor:
     * local, luego remota directa y por último el relay de Plex.
     */
    private suspend fun connect(r: JSONObject, fallbackToken: String, lastUri: String?): Pair<PlexServer?, String> = coroutineScope {
        val tok = r.str("accessToken") ?: fallbackToken
        val conns = r.optJSONArray("connections")
        val list = (if (conns == null) emptyList() else (0 until conns.length()).mapNotNull { conns.optJSONObject(it) })
            .toMutableList()
        // La última dirección que funcionó, por si Plex no la anuncia ahora
        if (lastUri != null && list.none { it.str("uri") == lastUri }) {
            list += JSONObject().put("uri", lastUri).put("local", false).put("relay", false)
        }
        if (list.isEmpty()) return@coroutineScope (null to "el servidor no publica ninguna dirección (¿está apagado?)")

        suspend fun probe(timeout: Int) = list.map { c ->
            async(Dispatchers.IO) {
                val uri = c.str("uri") ?: return@async null
                if (runCatching { Http.get("$uri/identity", headers(tok), timeout = timeout) }.isSuccess) c else null
            }
        }.awaitAll().filterNotNull()

        var ok = probe(10_000)
        if (ok.isEmpty()) {
            // Segundo intento, con más paciencia (servidor ocupado o despertándose)
            delay(2_000)
            ok = probe(20_000)
        }

        val best = ok.minByOrNull { c ->
            when {
                c.optBoolean("relay") -> 2
                c.optBoolean("local") -> 0
                else -> 1
            }
        } ?: return@coroutineScope (null to "no responde ahora (${list.size} direcciones probadas, 2 intentos). Puede que el servidor esté apagado, reiniciándose o sin internet.")

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
                    val alts = listOfNotNull(x.originalTitle?.takeIf { it.isNotBlank() && it != title })
                    if (type == "movie") {
                        val label = listOfNotNull("Plex", resLabel(x.resolution), s.name.takeIf { multi }).joinToString(" · ")
                        movies += Movie(
                            title, year, poster,
                            listOf(
                                SourceRef(
                                    Origin.PLEX, label, plexServer = s.id, plexKey = x.key, plexGuid = x.guid,
                                    quality = Quality.fromPlex(x.resolution), extIds = x.extIds.toList(),
                                )
                            ),
                            x.summary, x.added, rating, genres, altTitles = alts,
                            art = x.art?.let { still(s, it) },
                        )
                    } else {
                        shows += Show(
                            title, year, poster, x.summary, plex = listOf(PlexRef(s.id, x.key, x.guid)),
                            added = x.added, rating = rating, genres = genres, extIds = x.extIds.toList(),
                            altTitles = alts,
                            art = x.art?.let { still(s, it) },
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
        var guid: String? = null
        var title: String? = null
        var originalTitle: String? = null
        var year = 0
        var thumb: String? = null
        var art: String? = null
        var summary: String? = null
        var added = 0L
        var audienceRating = 0.0
        var rating = 0.0
        var resolution: String? = null
        val genres = ArrayList<String>(3)
        /** imdb://…, tmdb://… */
        val extIds = ArrayList<String>(3)
    }

    /** Recorre una biblioteca de 500 en 500 sin cargar el JSON entero en memoria. */
    private fun readSection(s: PlexServer, sectionKey: String, onItem: (RawItem) -> Unit) {
        val page = 500
        var start = 0
        while (true) {
            var total = -1
            var count = 0
            val url = "${s.uri}/library/sections/$sectionKey/all" +
                "?includeGuids=1&X-Plex-Container-Start=$start&X-Plex-Container-Size=$page"
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
                "guid" -> x.guid = r.strOrNull()
                "Guid" -> r.eachObject { name -> if (name == "id") r.strOrNull()?.let { x.extIds += it } else r.skipValue() }
                "title" -> x.title = r.strOrNull()
                "originalTitle" -> x.originalTitle = r.strOrNull()
                "year" -> x.year = r.numOrZero().toInt()
                "thumb" -> x.thumb = r.strOrNull()
                "art" -> x.art = r.strOrNull()
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

    /** Fotograma de un capítulo en 16:9, pequeño para que cargue rápido. */
    private fun still(s: PlexServer, thumb: String): String =
        "${s.uri}/photo/:/transcode?width=400&height=225&minSize=1&upscale=1" +
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
                thumb = m.str("thumb")?.let { still(s, it) },
                summary = m.str("summary"),
                minutes = m.optLong("duration").takeIf { it > 0 }?.let { (it / 60_000).toInt() },
                watched = m.optInt("viewCount") > 0,
                offsetMs = m.optLong("viewOffset"),
                aired = parseDate(m.str("originallyAvailableAt")).takeIf { it > 0 } ?: (m.optLong("addedAt") * 1000),
            )
        }
    }

    // ---------- Seguir viendo: leer y guardar el progreso en tu cuenta ----------

    /** Lo que tienes a medias en este servidor (y el siguiente capítulo de tus series). */
    private fun onDeck(s: PlexServer): List<PlexProgress> {
        val j = JSONObject(Http.get("${s.uri}/library/onDeck?X-Plex-Container-Start=0&X-Plex-Container-Size=80", headers(s.token)))
        val arr = j.optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            val type = m.optString("type")
            if (type != "movie" && type != "episode") return@mapNotNull null
            PlexProgress(
                server = s.id,
                type = type,
                key = m.optString("ratingKey"),
                guid = m.str("guid"),
                showKey = m.str("grandparentRatingKey"),
                showGuid = m.str("grandparentGuid"),
                season = m.optInt("parentIndex", -1),
                number = m.optInt("index", -1),
                offsetMs = m.optLong("viewOffset"),
                viewedAt = m.optLong("lastViewedAt"),
            )
        }
    }

    /** Guarda en Plex por dónde vas (así la app oficial de Plex también lo sabe). */
    fun timeline(serverId: String, key: String, state: String, timeMs: Long, durationMs: Long) {
        val s = servers[serverId] ?: return
        Http.get(
            "${s.uri}/:/timeline?ratingKey=$key&key=%2Flibrary%2Fmetadata%2F$key" +
                "&state=$state&time=$timeMs&duration=$durationMs",
            headers(s.token), timeout = 8_000,
        )
    }

    /** Marca como visto en Plex. */
    fun scrobble(serverId: String, key: String) {
        val s = servers[serverId] ?: return
        Http.get("${s.uri}/:/scrobble?identifier=com.plexapp.plugins.library&key=$key", headers(s.token), timeout = 8_000)
    }

    // ---------- Reproducción ----------

    /** Siempre el archivo original (Direct Play): Plex nunca convierte el vídeo. */
    data class Details(
        val label: String,
        val url: String,
        val subs: List<Sub>,
        val info: Info,
        /** Formato del vídeo: códec, perfil y bits de color (p. ej. h264 · High 10 · 10 bits). */
        val container: String? = null,
        val videoCodec: String? = null,
        val videoProfile: String? = null,
        val bitDepth: Int = 8,
    )

    /** Pide a Plex el archivo, idiomas y subtítulos de una peli o episodio. */
    /** Notas de IMDb y Rotten Tomatoes (críticos y público) según el servidor Plex. */
    data class Ratings(val imdb: Float? = null, val critics: Int? = null, val fresh: Boolean = true, val audience: Int? = null, val tmdb: Float? = null) {
        val empty get() = imdb == null && critics == null && audience == null && tmdb == null
    }

    fun ratings(serverId: String, key: String): Ratings {
        val s = servers[serverId] ?: throw IOException("servidor Plex no disponible")
        val m = JSONObject(Http.get("${s.uri}/library/metadata/$key", headers(s.token), timeout = 8_000))
            .getJSONObject("MediaContainer").getJSONArray("Metadata").getJSONObject(0)
        var imdb: Float? = null
        var critics: Int? = null
        var fresh = true
        var audience: Int? = null
        var tmdb: Float? = null
        fun put(image: String?, value: Double) {
            if (image == null || value.isNaN() || value <= 0) return
            when {
                image.startsWith("imdb://") -> imdb = imdb ?: value.toFloat()
                image.startsWith("themoviedb://") -> tmdb = tmdb ?: value.toFloat()
                image.startsWith("rottentomatoes://image.rating.ripe") || image.startsWith("rottentomatoes://image.rating.rotten") -> {
                    critics = critics ?: (value * 10).toInt()
                    fresh = "rotten" !in image
                }
                image.startsWith("rottentomatoes://image.rating.") -> audience = audience ?: (value * 10).toInt()
            }
        }
        m.optJSONArray("Rating")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { r -> put(r.str("image"), r.optDouble("value")) }
        }
        put(m.str("ratingImage"), m.optDouble("rating"))
        put(m.str("audienceRatingImage"), m.optDouble("audienceRating"))
        return Ratings(imdb, critics, fresh, audience, tmdb)
    }

    /**
     * Lo mismo que hace la app de Plex con los AVI antiguos: el servidor cambia solo el
     * "envoltorio" del archivo (a HLS) copiando el vídeo tal cual, SIN recodificarlo.
     * Solo se devuelve si Plex confirma que el vídeo va copiado (calidad original); si no, null.
     */
    fun remux(serverId: String, key: String): String? {
        val s = servers[serverId] ?: return null
        val session = java.util.UUID.randomUUID().toString()
        val profile = "add-transcode-target(type=videoProfile&context=streaming&protocol=hls&container=mpegts" +
            "&videoCodec=h264,hevc,mpeg4,msmpeg4v3,msmpeg4v2,msmpeg4,mpeg2video,mpeg1video,vc1,wmv3,h263" +
            "&audioCodec=aac,ac3,eac3,mp3,mp2)"
        val params = linkedMapOf(
            "path" to "/library/metadata/$key",
            "mediaIndex" to "0",
            "partIndex" to "0",
            "protocol" to "hls",
            "fastSeek" to "1",
            "directPlay" to "0",
            "directStream" to "1",
            "directStreamAudio" to "1",
            "videoQuality" to "100",
            "maxVideoBitrate" to "200000",
            "videoResolution" to "3840x2160",
            "subtitles" to "none",
            "copyts" to "1",
            "session" to session,
            "X-Plex-Session-Identifier" to session,
            "X-Plex-Client-Profile-Extra" to profile,
            "X-Plex-Client-Identifier" to clientId,
            "X-Plex-Product" to PRODUCT,
            "X-Plex-Platform" to "Android",
            "X-Plex-Device" to (Build.MODEL ?: "Android"),
            "X-Plex-Token" to s.token,
        )
        val qs = params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val mc = JSONObject(Http.get("${s.uri}/video/:/transcode/universal/decision?$qs", headers(s.token), timeout = 20_000))
            .getJSONObject("MediaContainer")
        val streams = mc.optJSONArray("Metadata")?.optJSONObject(0)
            ?.optJSONArray("Media")?.optJSONObject(0)
            ?.optJSONArray("Part")?.optJSONObject(0)
            ?.optJSONArray("Stream") ?: return null
        val video = (0 until streams.length()).mapNotNull { streams.optJSONObject(it) }
            .firstOrNull { it.optInt("streamType") == 1 } ?: return null
        if (video.optString("decision") != "copy") return null
        return "${s.uri}/video/:/transcode/universal/start.m3u8?$qs"
    }

    fun details(serverId: String, key: String): Details {
        val s = servers[serverId] ?: throw IOException("servidor Plex no disponible")
        // Sin tráileres: así es rápida (los tráileres se piden aparte con trailersFor)
        val m = JSONObject(Http.get("${s.uri}/library/metadata/$key", headers(s.token), timeout = 30_000))
            .getJSONObject("MediaContainer").getJSONArray("Metadata").getJSONObject(0)
        val media = m.optJSONArray("Media")?.optJSONObject(0) ?: throw IOException("sin vídeo")
        val part = media.optJSONArray("Part")?.optJSONObject(0) ?: throw IOException("sin vídeo")
        val partKey = part.getString("key")

        val audio = LinkedHashSet<String>()
        val subLangs = LinkedHashSet<String>()
        val subs = ArrayList<Sub>()
        var vCodec: String? = media.str("videoCodec")
        var vProfile: String? = media.str("videoProfile")
        var vBits = 8
        val streams = part.optJSONArray("Stream")
        if (streams != null) for (i in 0 until streams.length()) {
            val st = streams.optJSONObject(i) ?: continue
            val lang = st.str("languageTag")?.take(2)?.uppercase() ?: st.str("languageCode")?.let { lang3(it) }
            when (st.optInt("streamType")) {
                1 -> {
                    vCodec = st.str("codec") ?: vCodec
                    vProfile = st.str("profile") ?: vProfile
                    vBits = st.optInt("bitDepth", 8).takeIf { it > 0 } ?: 8
                }
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
                        subs += Sub(
                            withToken("${s.uri}$k", s.token), lang?.lowercase(), st.str("displayTitle"), mime,
                            forced = st.optBoolean("forced") || st.optInt("forced") == 1,
                        )
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

        return Details(
            label, withToken("${s.uri}$partKey", s.token), subs, info(s, m),
            media.str("container"), vCodec?.lowercase(), vProfile, vBits,
        )
    }

    private fun tags(m: JSONObject, name: String): List<String> {
        val a = m.optJSONArray(name) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.str("tag") }
    }

    /** Director, guion, reparto, duración… como en la ficha de Plex. */
    private fun people(s: PlexServer, m: JSONObject, field: String, kind: String): List<Person> {
        val a = m.optJSONArray(field) ?: return emptyList()
        val section = m.optString("librarySectionID").takeIf { it.isNotEmpty() }
        return (0 until a.length()).mapNotNull { i ->
            val r = a.optJSONObject(i) ?: return@mapNotNull null
            val name = r.str("tag") ?: return@mapNotNull null
            val photo = r.str("thumb")?.let { if (it.startsWith("/")) withToken("${s.uri}$it", s.token) else it }
            Person(name, r.str("role"), photo, r.str("id"), kind, s.id, section)
        }
    }

    /** Tráileres de una película (petición aparte: la primera vez el servidor puede tardar en buscarlos). */
    fun trailersFor(serverId: String, key: String): List<Trailer> {
        val s = servers[serverId] ?: return emptyList()
        val j = JSONObject(Http.get("${s.uri}/library/metadata/$key/extras", headers(s.token), timeout = 60_000))
        val mc = j.optJSONObject("MediaContainer") ?: return emptyList()
        return trailers(s, JSONObject().put("Extras", JSONObject().put("Metadata", mc.optJSONArray("Metadata") ?: JSONArray())))
    }

    /** Tráileres que tiene el servidor para esta película. */
    private fun trailers(s: PlexServer, m: JSONObject): List<Trailer> {
        val extras = m.optJSONObject("Extras")?.optJSONArray("Metadata") ?: return emptyList()
        return (0 until extras.length()).mapNotNull { i ->
            val x = extras.optJSONObject(i) ?: return@mapNotNull null
            val isTrailer = x.optString("subtype") == "trailer" || x.optInt("extraType") == 1
            if (!isTrailer) return@mapNotNull null
            val part = x.optJSONArray("Media")?.optJSONObject(0)?.optJSONArray("Part")?.optJSONObject(0)
            val key = part?.str("key") ?: return@mapNotNull null
            Trailer(
                x.str("title") ?: "Tráiler",
                x.str("thumb")?.let { still(s, it) },
                url = if (key.startsWith("http")) key else withToken("${s.uri}$key", s.token),
            )
        }
    }

    private fun info(s: PlexServer, m: JSONObject): Info {
        val cast = people(s, m, "Role", "actor")
        val rating = (m.optDouble("audienceRating").takeIf { !it.isNaN() && it > 0 }
            ?: m.optDouble("rating").takeIf { !it.isNaN() && it > 0 })?.toFloat()
        return Info(
            tagline = m.str("tagline"),
            plot = m.str("summary"),
            minutes = m.optLong("duration").takeIf { it > 0 }?.let { (it / 60_000).toInt() },
            contentRating = m.str("contentRating"),
            studio = m.str("studio"),
            rating = rating,
            directors = tags(m, "Director"),
            writers = tags(m, "Writer"),
            cast = cast,
            genres = tags(m, "Genre"),
            similar = tags(m, "Similar"),
            directorPeople = people(s, m, "Director", "director"),
            trailers = trailers(s, m),
        )
    }

    /** Películas de un actor o director en esa biblioteca de Plex. */
    fun byPerson(p: Person): List<Movie> {
        val s = servers[p.server ?: return emptyList()] ?: return emptyList()
        val section = p.section ?: return emptyList()
        val id = p.id ?: return emptyList()
        val j = JSONObject(
            Http.get("${s.uri}/library/sections/$section/all?type=1&${p.kind}=$id", headers(s.token), timeout = 30_000)
        )
        val arr = j.optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = m.str("title") ?: return@mapNotNull null
            val res = m.optJSONArray("Media")?.optJSONObject(0)?.str("videoResolution")
            Movie(
                title, m.optInt("year").takeIf { it > 0 }, m.str("thumb")?.let { poster(s, it) },
                listOf(
                    SourceRef(
                        Origin.PLEX, listOfNotNull("Plex", resLabel(res)).joinToString(" · "),
                        plexServer = s.id, plexKey = m.optString("ratingKey"), plexGuid = m.str("guid"),
                        quality = Quality.fromPlex(res),
                    )
                ),
                m.str("summary"),
                m.optLong("addedAt"),
            )
        }
    }

    // ---------- Dirección del servidor siempre al día ----------

    private val reconnectLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Comprueba rápido la dirección actual de los servidores (sin recargar la biblioteca).
     * Los servidores de casa cambian de dirección a menudo; con la antigua todo da timeout.
     */
    suspend fun reconnect(): Boolean = reconnectLock.withLock {
        coroutineScope {
            val tok = token ?: return@coroutineScope false
            val res = JSONArray(
                Http.get("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1&includeIPv6=1", headers(tok))
            )
            val known = servers
            val found = (0 until res.length())
                .mapNotNull { res.optJSONObject(it) }
                .filter { it.optString("provides").contains("server") }
                .map { r -> async(Dispatchers.IO) { connect(r, tok, known[r.optString("clientIdentifier")]?.uri).first } }
                .awaitAll()
                .filterNotNull()
            if (found.isEmpty()) return@coroutineScope false
            val changes = found.mapNotNull { f -> known[f.id]?.uri?.takeIf { it != f.uri }?.let { it to f.uri } }
            if (changes.isNotEmpty()) moved = moved + changes
            servers = known + found.associateBy { it.id }
            true
        }
    }

    /** El servidor listo para usar: si no responde en su dirección guardada, se busca la nueva. */
    suspend fun ensure(serverId: String): PlexServer? {
        val s = servers[serverId]
        if (s != null && runCatching { Http.get("${s.uri}/identity", headers(s.token), timeout = 5_000) }.isSuccess) {
            return s
        }
        runCatching { reconnect() }
        return servers[serverId]
    }

    fun currentServers(): List<PlexServer> = servers.values.toList()

    /** Direcciones antiguas → nuevas, para que las imágenes guardadas sigan cargando. */
    @Volatile
    private var moved: Map<String, String> = emptyMap()

    fun fixUrl(url: String?): String? {
        if (url == null || moved.isEmpty()) return url
        for ((old, new) in moved) if (url.startsWith(old)) return new + url.substring(old.length)
        return url
    }

    private fun withToken(url: String, token: String) =
        url + (if ('?' in url) "&" else "?") + "X-Plex-Token=$token"

    private fun lang3(code: String): String = when (code.lowercase()) {
        "spa" -> "ES"; "eng" -> "EN"; "fre", "fra" -> "FR"; "ger", "deu" -> "DE"; "ita" -> "IT"
        "por" -> "PT"; "jpn" -> "JA"; "cat" -> "CA"; "kor" -> "KO"; "chi", "zho" -> "ZH"
        else -> code.take(2).uppercase()
    }

    // ---------- Caché en disco ----------

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "plex.bin")

    private fun save(ctx: Context, d: PlexData) {
        Snapshot.writePlex(cacheFile(ctx), d)
        File(ctx.filesDir, "plex.json").delete()
    }

    private fun genresOf(o: JSONObject): List<String> = strings(o, "g")

    private fun strings(o: JSONObject, name: String): List<String> {
        val a = o.optJSONArray(name) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf { g -> g.isNotEmpty() } }
    }

    /** Guarda la caché tras un cambio local (p. ej. añadir a Mi lista). */
    fun saveCache(ctx: Context, d: PlexData) = runCatching { save(ctx, d) }

    // ---------- Mi lista: añadir y quitar ----------

    /** Añade o quita de Mi lista un título por su identificador de Plex (plex://movie/ID). */
    fun setWatchlist(guid: String, add: Boolean) {
        val tok = token ?: throw IOException("no has iniciado sesión en Plex")
        val id = guid.substringAfterLast('/')
        val action = if (add) "addToWatchlist" else "removeFromWatchlist"
        Http.put("https://discover.provider.plex.tv/actions/$action?ratingKey=$id", headers(tok))
    }

    /** Si el título no tiene identificador de Plex, lo busca en el catálogo de Plex por nombre y año. */
    fun findGuid(title: String, year: Int?, show: Boolean): String? {
        val tok = token ?: return null
        val q = URLEncoder.encode(title, "UTF-8")
        val type = if (show) "tv" else "movies"
        val j = JSONObject(
            Http.get(
                "https://discover.provider.plex.tv/library/search?query=$q&limit=15" +
                    "&searchTypes=$type&searchProviders=discover&includeMetadata=1",
                headers(tok),
            )
        )
        val want = Titles.key(title)
        val prefix = if (show) "plex://show/" else "plex://movie/"
        val found = ArrayList<JSONObject>()
        collectMetadata(j, prefix, found)
        return (found.firstOrNull { m ->
            Titles.key(m.optString("title")) == want &&
                (year == null || m.optInt("year") == 0 || kotlin.math.abs(m.optInt("year") - year) <= 1)
        } ?: found.firstOrNull { Titles.key(it.optString("title")) == want })?.str("guid")
    }

    /**
     * Lo que es tendencia ahora en Plex (pelis y series), en el orden que da Plex.
     * Se buscan los apartados de "Tendencias"/"Populares" de Plex Discover; si no aparecen
     * con ese nombre, se usa todo lo que Plex destaca en su portada.
     */
    fun trending(): List<WatchItem> {
        val tok = token ?: return emptyList()
        val h = headers(tok) + ("X-Plex-Language" to "es")
        val trendWords = Regex("(?i)trend|popular|tendenc|m[aá]s vist|most|top")
        val picked = ArrayList<JSONObject>()
        val other = ArrayList<JSONObject>()
        for (base in listOf("https://discover.provider.plex.tv", "https://metadata.provider.plex.tv")) {
            for (section in listOf("home", "movie", "tv", "promoted")) {
                val url = if (section == "promoted") "$base/hubs/promoted?includeMeta=1&count=40"
                else "$base/hubs/sections/$section?includeMeta=1&count=40"
                val json = runCatching {
                    val text = Http.get(url, h, timeout = 20_000).trimStart()
                    if (text.startsWith("{")) JSONObject(text) else null
                }.getOrNull() ?: continue
                val hubs = ArrayList<JSONObject>()
                collectHubs(json, hubs)
                if (hubs.isEmpty()) collectMetadata(json, "plex://", other)
                for (hub in hubs) {
                    val name = listOf("title", "hubIdentifier", "context", "key").joinToString(" ") { hub.optString(it) }
                    val metas = ArrayList<JSONObject>()
                    collectMetadata(hub, "plex://", metas)
                    if (!trendWords.containsMatchIn(name)) {
                        other += metas
                        continue
                    }
                    picked += metas
                    // El apartado completo tiene más títulos que la portada
                    val key = hub.optString("key")
                    if (key.startsWith("/")) runCatching {
                        val more = Http.get("$base$key${if ('?' in key) "&" else "?"}count=60", h, timeout = 20_000).trimStart()
                        if (more.startsWith("{")) collectMetadata(JSONObject(more), "plex://", picked)
                    }
                }
            }
            if (picked.isNotEmpty() || other.isNotEmpty()) break
        }
        return picked.ifEmpty { other }
            .filter { it.optString("type") == "movie" || it.optString("type") == "show" }
            .distinctBy { it.optString("guid") }
            .mapNotNull { m ->
                val title = m.str("title") ?: return@mapNotNull null
                WatchItem(m.str("guid"), title, m.optInt("year").takeIf { it > 0 }, m.optString("type"))
            }
            .take(120)
    }

    /** Busca los apartados ("hubs") de una respuesta de Plex Discover. */
    private fun collectHubs(v: Any?, out: MutableList<JSONObject>) {
        when (v) {
            is JSONObject -> {
                if (v.has("hubIdentifier")) {
                    out += v
                    return
                }
                for (k in v.keys()) collectHubs(v.opt(k), out)
            }
            is JSONArray -> for (i in 0 until v.length()) collectHubs(v.opt(i), out)
        }
    }

    /** Recorre la respuesta (su formato varía) buscando fichas con guid plex://… */
    private fun collectMetadata(v: Any?, prefix: String, out: MutableList<JSONObject>) {
        when (v) {
            is JSONObject -> {
                if (v.optString("guid").startsWith(prefix)) out += v
                for (k in v.keys()) collectMetadata(v.opt(k), prefix, out)
            }
            is JSONArray -> for (i in 0 until v.length()) collectMetadata(v.opt(i), prefix, out)
        }
    }

    fun loadCached(ctx: Context): PlexData? {
        if (!loggedIn) return null
        val d = Snapshot.readPlex(cacheFile(ctx)) ?: return null
        servers = d.servers.associateBy { it.id }
        return d
    }
}
