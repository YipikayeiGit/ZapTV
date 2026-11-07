package com.zap.iptv

import android.content.Context
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Capítulos de una serie (de todas las fuentes) y aviso de capítulos nuevos. */
object Episodes {

    suspend fun load(ctx: Context, show: Show): List<Episode> = coroutineScope {
        val acc = Xtream.Account.parse(ctx.getSharedPreferences("zap", Context.MODE_PRIVATE).getString("src", null))
        val plex = show.plex.map { ref ->
            async(Dispatchers.IO) { runCatching { Plex.episodes(ref) }.getOrDefault(emptyList()) }
        }
        val xt: List<Deferred<List<Episode>>> = if (acc == null) emptyList() else show.xtreamIds.map { id ->
            async(Dispatchers.IO) { runCatching { Xtream.episodes(acc, id) }.getOrDefault(emptyList()) }
        }
        Merge.episodes((plex + xt).awaitAll() + listOf(show.episodes))
    }

    // ---------- Seguimiento de lo que ves ----------
    // p:<serie> = último capítulo visto, k:<serie> = último capítulo que existía, t:<serie> = cuándo

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("series", Context.MODE_PRIVATE)

    fun code(e: Episode) = e.season * 10_000 + e.number

    fun maxCode(eps: List<Episode>) = eps.maxOfOrNull { code(it) } ?: 0

    /** Al ver un capítulo: guardar hasta dónde vas y cuántos capítulos había. */
    fun markWatched(ctx: Context, show: CatalogItem, ep: Episode, known: Int) {
        val p = prefs(ctx)
        val k = show.key
        val last = p.getInt("p:$k", 0)
        p.edit()
            .putInt("p:$k", maxOf(last, code(ep)))
            .putInt("k:$k", maxOf(p.getInt("k:$k", 0), known))
            .putLong("t:$k", System.currentTimeMillis())
            .apply()
    }

    /** Capítulos que existían la última vez que abriste la serie (0 si nunca la has visto). */
    fun known(ctx: Context, show: CatalogItem): Int = prefs(ctx).getInt("k:${show.key}", 0)

    fun watching(ctx: Context, show: CatalogItem): Boolean = prefs(ctx).contains("p:${show.key}")

    /** Al abrir la ficha, los capítulos nuevos ya están vistos como "conocidos". */
    fun markSeen(ctx: Context, show: CatalogItem, known: Int) {
        if (!watching(ctx, show)) return
        val p = prefs(ctx)
        p.edit().putInt("k:${show.key}", maxOf(p.getInt("k:${show.key}", 0), known)).apply()
        val keys = newKeys(ctx).toMutableSet()
        if (keys.remove(show.key)) saveNewKeys(ctx, keys)
    }

    fun newKeys(ctx: Context): Set<String> = prefs(ctx).getStringSet("new", emptySet()).orEmpty()

    private fun saveNewKeys(ctx: Context, keys: Set<String>) {
        prefs(ctx).edit().putStringSet("new", HashSet(keys)).apply()
    }

    fun lastCheck(ctx: Context): Long = prefs(ctx).getLong("checked", 0)

    /** Revisa las series que estás viendo (las 20 más recientes) buscando capítulos nuevos. */
    suspend fun checkNew(ctx: Context, shows: List<Show>): Set<String> = coroutineScope {
        val p = prefs(ctx)
        val keys = p.all.keys.filter { it.startsWith("p:") }.map { it.removePrefix("p:") }
            .sortedByDescending { p.getLong("t:$it", 0) }
            .take(20)
        val byKey = shows.associateBy { it.key }
        val found = keys.mapNotNull { byKey[it] }.map { s ->
            async(Dispatchers.IO) {
                val eps = runCatching { load(ctx, s) }.getOrDefault(emptyList())
                if (eps.isNotEmpty() && maxCode(eps) > p.getInt("k:${s.key}", 0)) s.key else null
            }
        }.awaitAll().filterNotNull().toSet()
        saveNewKeys(ctx, found)
        p.edit().putLong("checked", System.currentTimeMillis()).apply()
        found
    }
}
