package com.zap.iptv

import android.content.Context
import java.io.File
import java.io.IOException

/** Carga la parte IPTV: Xtream si el enlace lo permite, si no la lista M3U. */
object Iptv {
    /** Fuente especial: lista recibida entera (desde el móvil o "Compartir"). */
    const val LOCAL = "local"

    private fun xtreamDir(ctx: Context) = File(ctx.filesDir, "xtream")
    private fun m3uCache(ctx: Context) = File(ctx.filesDir, "lista.m3u")
    private fun snapshot(ctx: Context) = File(ctx.filesDir, "iptv.bin")

    fun loadCached(ctx: Context, src: String?): IptvData? {
        src ?: return null
        // Copia compacta: lo más rápido
        Snapshot.readIptv(snapshot(ctx), src)?.let { return it }
        return loadRaw(ctx, src)?.also { Snapshot.writeIptv(snapshot(ctx), src, it) }
    }

    private fun loadRaw(ctx: Context, src: String): IptvData? {
        val acc = Xtream.Account.parse(src)
        val dir = xtreamDir(ctx)
        if (acc != null && Xtream.hasCache(dir)) return Xtream.parse(dir, acc)
        val f = m3uCache(ctx)
        return if (f.exists()) M3uParser.toData(M3uParser.parse(f.readText())) else null
    }

    suspend fun refresh(ctx: Context, src: String, preloaded: String? = null): IptvData {
        val data = fetch(ctx, src, preloaded)
        Snapshot.writeIptv(snapshot(ctx), src, data)
        return data
    }

    private suspend fun fetch(ctx: Context, src: String, preloaded: String?): IptvData {
        val acc = Xtream.Account.parse(src)
        if (acc != null && preloaded == null) {
            val data = runCatching {
                Xtream.download(acc, xtreamDir(ctx))
                Xtream.parse(xtreamDir(ctx), acc)
            }.getOrNull()
            if (data != null && (data.live.isNotEmpty() || data.movies.isNotEmpty() || data.shows.isNotEmpty())) {
                m3uCache(ctx).delete()
                return data
            }
            // Si la API falla, se intenta como lista M3U normal
        }
        val text = when {
            preloaded != null -> preloaded
            src == LOCAL -> m3uCache(ctx).takeIf { it.exists() }?.readText()
                ?: throw IOException("no hay lista guardada")
            else -> Source.read(ctx, src)
        }
        val channels = M3uParser.parse(text)
        if (channels.isEmpty()) throw IOException("la lista no tiene canales")
        m3uCache(ctx).writeText(text)
        xtreamDir(ctx).deleteRecursively()
        return M3uParser.toData(channels)
    }

    fun describe(src: String?): String = when {
        src == null -> "Sin configurar"
        src == LOCAL -> "Lista recibida"
        else -> Xtream.Account.parse(src)?.let { "Xtream · ${it.host}" }
            ?: android.net.Uri.parse(src).host
            ?: "Archivo"
    }
}
