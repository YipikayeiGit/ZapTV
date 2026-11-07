package com.zap.iptv

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Copia compacta en binario del catálogo, para abrir la app al instante
 * sin volver a leer los archivos enormes del proveedor.
 */
object Snapshot {
    private const val VERSION = 4

    // ---------- Escritura ----------

    private fun DataOutputStream.str(s: String?) {
        if (s == null) {
            writeInt(-1)
        } else {
            val b = s.toByteArray(Charsets.UTF_8)
            writeInt(b.size)
            write(b)
        }
    }

    private fun DataOutputStream.strs(list: List<String>) {
        writeInt(list.size)
        list.forEach { str(it) }
    }

    private fun DataOutputStream.source(s: SourceRef) {
        writeInt(s.origin.ordinal)
        str(s.label); str(s.url); str(s.plexServer); str(s.plexKey); str(s.plexGuid); str(s.quality)
        strs(s.extIds)
    }

    private fun DataOutputStream.episode(e: Episode) {
        writeInt(e.season); writeInt(e.number); str(e.title)
        writeInt(e.sources.size); e.sources.forEach { source(it) }
    }

    private fun DataOutputStream.movie(m: Movie) {
        str(m.title); writeInt(m.year ?: -1); str(m.poster); str(m.plot)
        writeLong(m.added); writeFloat(m.rating ?: -1f); strs(m.genres)
        writeInt(m.sources.size); m.sources.forEach { source(it) }
        strs(m.extIds)
    }

    private fun DataOutputStream.show(s: Show) {
        str(s.title); writeInt(s.year ?: -1); str(s.poster); str(s.plot)
        writeInt(s.plex.size); s.plex.forEach { str(it.server); str(it.key); str(it.guid) }
        strs(s.xtreamIds)
        writeInt(s.episodes.size); s.episodes.forEach { episode(it) }
        writeLong(s.added); writeFloat(s.rating ?: -1f); strs(s.genres); str(s.quality); strs(s.extIds)
    }

    private fun write(file: File, tag: String, body: DataOutputStream.() -> Unit) {
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 256 * 1024)).use { o ->
            o.writeInt(VERSION)
            o.str(tag)
            o.body()
        }
        tmp.renameTo(file)
    }

    fun writeIptv(file: File, src: String, d: IptvData) = runCatching {
        write(file, src) {
            writeInt(d.live.size)
            d.live.forEach { str(it.name); str(it.url); str(it.logo); str(it.group) }
            writeInt(d.movies.size); d.movies.forEach { movie(it) }
            writeInt(d.shows.size); d.shows.forEach { show(it) }
        }
    }

    fun writePlex(file: File, d: PlexData) = runCatching {
        write(file, "plex") {
            writeInt(d.servers.size)
            d.servers.forEach { str(it.id); str(it.name); str(it.uri); str(it.token) }
            writeInt(d.movies.size); d.movies.forEach { movie(it) }
            writeInt(d.shows.size); d.shows.forEach { show(it) }
            writeInt(d.watchlist.size)
            d.watchlist.forEach { w ->
                str(w.guid); str(w.title); writeInt(w.year ?: -1); str(w.type); str(w.thumb); strs(w.extIds)
            }
        }
    }

    // ---------- Lectura ----------

    /** Lee textos reutilizando los repetidos (categorías, grupos…) para ahorrar memoria. */
    private class Reader(val inp: DataInputStream) {
        private val pool = HashMap<String, String>()

        fun str(): String? {
            val n = inp.readInt()
            if (n < 0) return null
            val b = ByteArray(n)
            inp.readFully(b)
            return String(b, Charsets.UTF_8)
        }

        fun shared(): String? = str()?.let { pool.getOrPut(it) { it } }
        fun int() = inp.readInt()
        fun long() = inp.readLong()
        fun float() = inp.readFloat()
        fun strs(): List<String> = List(int()) { shared() ?: "" }

        fun source() = SourceRef(
            Origin.entries[int()], shared() ?: "", str(), shared(), str(), str(), shared(), strs(),
        )

        fun episode(): Episode {
            val season = int()
            val number = int()
            val title = str()
            val sources = List(int()) { source() }
            return Episode(season, number, title, sources)
        }

        fun movie(): Movie {
            val title = str() ?: ""
            val year = int().takeIf { it >= 0 }
            val poster = str()
            val plot = str()
            val added = long()
            val rating = float().takeIf { it >= 0f }
            val genres = strs()
            val sources = List(int()) { source() }
            val extIds = strs()
            return Movie(title, year, poster, sources, plot, added, rating, genres, extIds)
        }

        fun show(): Show {
            val title = str() ?: ""
            val year = int().takeIf { it >= 0 }
            val poster = str()
            val plot = str()
            val plex = List(int()) { PlexRef(shared() ?: "", str() ?: "", str()) }
            val xtream = strs()
            val episodes = List(int()) { episode() }
            val added = long()
            val rating = float().takeIf { it >= 0f }
            val genres = strs()
            val quality = shared()
            val extIds = strs()
            return Show(
                title, year, poster, plot, plex, xtream, episodes, added, rating, genres, quality, extIds,
            )
        }
    }

    private fun <T> read(file: File, tag: String, body: Reader.() -> T): T? {
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file), 256 * 1024)).use { inp ->
                if (inp.readInt() != VERSION) return null
                val r = Reader(inp)
                if (r.str() != tag) return null
                r.body()
            }
        }.getOrNull()
    }

    fun readIptv(file: File, src: String): IptvData? = read(file, src) {
        val live = List(int()) { Channel(str() ?: "", str() ?: "", str(), shared() ?: "") }
        val movies = List(int()) { movie() }
        val shows = List(int()) { show() }
        IptvData(live, movies, shows)
    }

    fun readPlex(file: File): PlexData? = read(file, "plex") {
        val servers = List(int()) { PlexServer(str() ?: "", str() ?: "", str() ?: "", str() ?: "") }
        val movies = List(int()) { movie() }
        val shows = List(int()) { show() }
        val watch = List(int()) {
            WatchItem(str(), str() ?: "", int().takeIf { it >= 0 }, str() ?: "", str(), strs())
        }
        PlexData(servers, movies, shows, watch)
    }
}
