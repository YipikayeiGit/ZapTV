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
            async(Dispatchers.IO) {
                runCatching {
                    Plex.ensure(ref.server)
                    Plex.episodes(ref)
                }.getOrDefault(emptyList())
            }
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
        // Ya lo estás viendo: se quita la etiqueta de "nuevo capítulo"
        val fresh = newKeys(ctx)
        if (k in fresh) saveNewKeys(ctx, fresh - k)
        p.edit().remove("n:$k").apply()
        Catalog.newEpisodes = Catalog.newEpisodes.filter { it.key != k }
    }

    /** Capítulos que existían la última vez que abriste la serie (0 si nunca la has visto). */
    fun known(ctx: Context, show: CatalogItem): Int = prefs(ctx).getInt("k:${show.key}", 0)

    fun watching(ctx: Context, show: CatalogItem): Boolean = prefs(ctx).contains("p:${show.key}")

    /** Al abrir la ficha, los capítulos nuevos ya están vistos como "conocidos". */
    fun markSeen(ctx: Context, show: CatalogItem, known: Int) {
        if (!watching(ctx, show)) return
        val p = prefs(ctx)
        p.edit().putInt("k:${show.key}", maxOf(p.getInt("k:${show.key}", 0), known)).apply()
    }

    /** Último capítulo visto (código temporada*10000+capítulo) y cuándo viste la serie por última vez. */
    fun lastCode(ctx: Context, show: CatalogItem): Int = prefs(ctx).getInt("p:${show.key}", 0)
    fun lastTime(ctx: Context, show: CatalogItem): Long = prefs(ctx).getLong("t:${show.key}", 0L)

    private const val RECENT = 90L * 24 * 60 * 60 * 1000

    /** Capítulos sin ver a partir de los cuales se entiende que has dejado la serie. */
    private const val MAX_PENDING = 2

    /** Cuándo se avisó del capítulo nuevo (0 si no hay aviso). */
    fun newSince(ctx: Context, key: String): Long = prefs(ctx).getLong("n:$key", 0L)

    /**
     * Capítulo nuevo de verdad: estrenado DESPUÉS de la última vez que viste la serie,
     * hace poco, y posterior al último que viste. (Una serie antigua que vas viendo
     * poco a poco no tiene "capítulos nuevos": solo te faltan por ver.)
     */
    fun isNew(e: Episode, lastCode: Int, lastTime: Long): Boolean =
        lastTime > 0 && e.aired > lastTime && code(e) > lastCode &&
            e.aired > System.currentTimeMillis() - RECENT

    fun newKeys(ctx: Context): Set<String> = prefs(ctx).getStringSet("new2", emptySet()).orEmpty()

    private fun saveNewKeys(ctx: Context, keys: Set<String>) {
        prefs(ctx).edit().putStringSet("new2", HashSet(keys)).apply()
    }

    fun lastCheck(ctx: Context): Long = prefs(ctx).getLong("checked2", 0)

    /** Revisa las series que estás viendo (vistas en los últimos 4 meses) buscando estrenos. */
    suspend fun checkNew(ctx: Context, shows: List<Show>): Set<String> = coroutineScope {
        val p = prefs(ctx)
        val since = System.currentTimeMillis() - 120L * 24 * 60 * 60 * 1000
        val keys = p.all.keys.filter { it.startsWith("t:") }.map { it.removePrefix("t:") }
            .filter { p.getLong("t:$it", 0) > since }
            .sortedByDescending { p.getLong("t:$it", 0) }
            .take(25)
        val byKey = shows.associateBy { it.key }
        val found = keys.mapNotNull { byKey[it] }.map { s ->
            async(Dispatchers.IO) {
                val eps = runCatching { load(ctx, s) }.getOrDefault(emptyList())
                val lc = p.getInt("p:${s.key}", 0)
                val lt = p.getLong("t:${s.key}", 0L)
                // Más de 2 capítulos sin ver desde el último que viste: la has dejado, no se avisa
                val pending = eps.count { code(it) > lc }
                if (pending <= MAX_PENDING && eps.any { isNew(it, lc, lt) }) s.key else null
            }
        }.awaitAll().filterNotNull().toSet()
        // Cuándo apareció el aviso: con esa fecha se coloca en "Seguir viendo"
        // (sale la primera en ese momento y luego se va desplazando como lo demás)
        val now = System.currentTimeMillis()
        p.edit().apply {
            for (k in found) if (!p.contains("n:$k")) putLong("n:$k", now)
            for (k in p.all.keys) if (k.startsWith("n:") && k.removePrefix("n:") !in found) remove(k)
        }.apply()
        saveNewKeys(ctx, found)
        p.edit().putLong("checked2", System.currentTimeMillis()).apply()
        found
    }
}
