package com.zap.iptv

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private enum class Tab { HOME, MOVIES, SHOWS, WATCH, LIVE }

    private val prefs by lazy { getSharedPreferences("zap", MODE_PRIVATE) }

    private var tab = Tab.HOME
    /** Categorías del panel lateral: null = Todos, FAV = Favoritos. */
    private var sideKeys: List<String?> = emptyList()
    private var liveGroup: String? = null
    private val favorites = HashSet<String>()
    private sealed class VodFilter {
        object Home : VodFilter()
        object AZ : VodFilter()
        data class From(val origin: Origin) : VodFilter()
        data class Genre(val name: String) : VodFilter()
    }

    private var vodFilters: List<VodFilter> = listOf(VodFilter.Home)
    private var vodFilter: VodFilter = VodFilter.Home
    private var query = ""
    private var typingJob: Job? = null
    private var filterJob: Job? = null
    private var busy = 0
    private val loadLock = Mutex()

    private lateinit var tabs: Map<Tab, TextView>
    private lateinit var chips: RecyclerView
    private lateinit var side: RecyclerView
    private lateinit var content: RecyclerView
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var emptyAdd: View
    private lateinit var progress: View
    private lateinit var busyBar: View

    private val channelAdapter = ChannelAdapter(
        isFav = { it.url in favorites },
        onLong = { pos -> toggleFavorite(pos) },
    ) { pos -> openChannel(pos) }
    private val sideAdapter = ChipAdapter(R.layout.item_side) { pos -> onSide(pos) }
    private val posterAdapter = PosterAdapter({ spans() }) { item -> openDetail(item) }
    private val rowsAdapter = RowsAdapter { item -> openDetail(item) }
    private val chipAdapter = ChipAdapter { pos -> onChip(pos) }

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                setSource(uri.toString())
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Plex.init(this)
        setContentView(R.layout.activity_main)

        tabs = mapOf(
            Tab.HOME to findViewById(R.id.tabHome),
            Tab.MOVIES to findViewById(R.id.tabMovies),
            Tab.SHOWS to findViewById(R.id.tabShows),
            Tab.WATCH to findViewById(R.id.tabWatch),
            Tab.LIVE to findViewById(R.id.tabLive),
        )
        chips = findViewById(R.id.chips)
        side = findViewById(R.id.side)
        side.layoutManager = LinearLayoutManager(this)
        side.adapter = sideAdapter
        side.isFocusable = false
        // Panel más estrecho en pantallas pequeñas (móvil)
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        side.layoutParams = side.layoutParams.apply {
            width = ((if (widthDp > 700) 220 else 120) * resources.displayMetrics.density).toInt()
        }
        favorites += prefs.getStringSet("favs", emptySet()).orEmpty()
        content = findViewById(R.id.content)
        empty = findViewById(R.id.empty)
        emptyText = findViewById(R.id.emptyText)
        emptyAdd = findViewById(R.id.emptyAdd)
        progress = findViewById(R.id.progress)
        busyBar = findViewById(R.id.busyBar)

        chips.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        chips.adapter = chipAdapter
        // Con el mando, el foco va a los elementos y no a la lista vacía
        chips.isFocusable = false
        content.isFocusable = false
        content.setHasFixedSize(true)
        content.setItemViewCacheSize(24)

        tab = runCatching { Tab.valueOf(prefs.getString("tab", "HOME")!!) }.getOrDefault(Tab.HOME)
        for ((t, v) in tabs) v.setOnClickListener { selectTab(t) }

        findViewById<View>(R.id.settings).setOnClickListener { showSources() }
        findViewById<View>(R.id.reload).setOnClickListener { reloadAll() }
        emptyAdd.setOnClickListener { if (tab == Tab.LIVE) showAddDialog() else showSources() }
        findViewById<EditText>(R.id.search).doAfterTextChanged { text ->
            query = text?.toString()?.trim().orEmpty()
            typingJob?.cancel()
            typingJob = lifecycleScope.launch {
                delay(150)
                refresh(scrollTop = true)
            }
        }

        selectTab(tab)
        showLastCrash()

        // Arranque instantáneo con lo guardado y actualización en segundo plano
        val handled = savedInstanceState == null && handleIntent(intent)
        lifecycleScope.launch {
            val src = prefs.getString(KEY_SRC, null)
            val iptvJob = async(Dispatchers.IO) {
                if (handled) null else runCatching { Iptv.loadCached(this@MainActivity, src) }.getOrNull()
            }
            val plexJob = async(Dispatchers.IO) { runCatching { Plex.loadCached(this@MainActivity) }.getOrNull() }
            val iptv = iptvJob.await()
            val plex = plexJob.await()
            if (iptv != null) Catalog.iptv = iptv
            if (plex != null) Catalog.plex = plex
            rebuild()
            val now = System.currentTimeMillis()
            if (!handled && src != null && (iptv == null || now - prefs.getLong("iptv_time", 0) > STALE)) {
                refreshIptv(src, silent = iptv != null)
            }
            if (Plex.loggedIn && (plex == null || now - prefs.getLong("plex_time", 0) > STALE)) {
                refreshPlex(silent = plex != null)
            }
            if (now - prefs.getLong("update_check", 0) > STALE) checkUpdate(manual = false)
        }
    }

    /** Si la app se cerró la última vez, enseñar el motivo (para poder arreglarlo). */
    private fun showLastCrash() {
        val text = ZapApp.takeCrash(application) ?: return
        // Lo importante está en las primeras líneas
        val short = text.lineSequence().take(18).joinToString("\n")
        MaterialAlertDialogBuilder(this)
            .setTitle("La app se cerró inesperadamente")
            .setMessage("Haz una foto de esta pantalla para poder arreglarlo:\n\n$short")
            .setPositiveButton("Entendido", null)
            .show()
    }

    // ---------- Pestañas y filtros ----------

    private fun spans(): Int {
        val dp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (dp / 130f).toInt().coerceIn(3, 8)
    }

    private fun selectTab(t: Tab) {
        if (t != tab) vodFilter = VodFilter.Home
        tab = t
        prefs.edit { putString("tab", t.name) }
        for ((k, v) in tabs) v.isSelected = k == t
        renderChips()
        refresh(scrollTop = true)
    }

    /** Cambia la lista de contenido (canales, filas o cuadrícula) solo si hace falta. */
    private fun useAdapter(a: RecyclerView.Adapter<*>) {
        if (content.adapter === a) return
        content.layoutManager = if (a === posterAdapter) GridLayoutManager(this, spans()) else LinearLayoutManager(this)
        content.adapter = a
    }

    private fun renderChips() {
        side.isVisible = tab == Tab.LIVE && Catalog.live.isNotEmpty()
        if (tab == Tab.LIVE) {
            chips.isVisible = false
            renderSide()
        } else if (tab == Tab.HOME || tab == Tab.WATCH) {
            chips.isVisible = false
        } else {
            val list: List<CatalogItem> = if (tab == Tab.MOVIES) Catalog.movies else Catalog.shows
            val genres = if (tab == Tab.MOVIES) Catalog.movieGenres else Catalog.showGenres
            val both = list.any { Origin.PLEX in it.origins } && list.any { Origin.IPTV in it.origins }
            vodFilters = buildList {
                add(VodFilter.Home)
                add(VodFilter.AZ)
                if (both) {
                    add(VodFilter.From(Origin.PLEX))
                    add(VodFilter.From(Origin.IPTV))
                }
                genres.take(40).forEach { add(VodFilter.Genre(it)) }
            }
            if (vodFilter !in vodFilters) vodFilter = VodFilter.Home
            val labels = vodFilters.map { f ->
                when (f) {
                    VodFilter.Home -> "Inicio"
                    VodFilter.AZ -> "Todo A–Z"
                    is VodFilter.From -> "Solo ${f.origin.label}"
                    is VodFilter.Genre -> f.name
                }
            }
            chipAdapter.submit(labels, vodFilters.indexOf(vodFilter).coerceAtLeast(0))
            chips.isVisible = list.isNotEmpty()
        }
    }

    private fun onChip(pos: Int) {
        vodFilter = vodFilters.getOrElse(pos) { VodFilter.Home }
        refresh(scrollTop = true)
    }

    // ---------- TV en directo: categorías y favoritos ----------

    private fun renderSide() {
        val groups = Catalog.live.map { it.group }.distinct()
        val hasFavs = Catalog.live.any { it.url in favorites }
        sideKeys = buildList {
            if (hasFavs) add(FAV)
            add(null)
            addAll(groups)
        }
        val lg = liveGroup
        if (lg !in sideKeys) liveGroup = null
        sideAdapter.submit(
            sideKeys.map { k -> when (k) { FAV -> "★ Favoritos"; null -> "Todos"; else -> k } },
            sideKeys.indexOf(liveGroup).coerceAtLeast(0),
        )
    }

    private fun onSide(pos: Int) {
        liveGroup = sideKeys.getOrNull(pos)
        refresh(scrollTop = true)
    }

    private fun toggleFavorite(pos: Int) {
        val ch = channelAdapter.items.getOrNull(pos) ?: return
        if (!favorites.remove(ch.url)) {
            favorites += ch.url
            toast("★ ${ch.name} añadido a favoritos")
        } else {
            toast("${ch.name} quitado de favoritos")
        }
        prefs.edit { putStringSet("favs", HashSet(favorites)) }
        renderSide()
        refresh()
    }

    /**
     * Seguir viendo: pelis a medias y series que estás viendo (aunque hayas
     * terminado el último capítulo), de lo más reciente a lo más antiguo.
     */
    private fun resumeItems(t: Tab): List<CatalogItem> {
        val resume = getSharedPreferences("resume", MODE_PRIVATE).all.keys
        val times = getSharedPreferences("resume_t", MODE_PRIVATE)
        val series = getSharedPreferences("series", MODE_PRIVATE)
        val movies = Catalog.movies.associateBy { it.key }
        val shows = Catalog.shows.associateBy { it.key }
        val entries = ArrayList<Pair<CatalogItem, Long>>()
        if (t != Tab.SHOWS) {
            for (k in resume) if (k.startsWith("movie:")) {
                movies[k.removePrefix("movie:")]?.let { entries += it to times.getLong(k, 0L) }
            }
        }
        if (t != Tab.MOVIES) {
            for (k in resume) if (k.startsWith("ep:")) {
                shows[k.removePrefix("ep:").substringBefore(':')]?.let { entries += it to times.getLong(k, 0L) }
            }
            val since = System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000
            for ((k, v) in series.all) if (k.startsWith("t:") && v is Long && v > since) {
                shows[k.removePrefix("t:")]?.let { entries += it to v }
            }
        }
        return entries.sortedByDescending { it.second }.map { it.first }.distinct().take(25)
    }

    private fun refresh(scrollTop: Boolean = false) {
        val t = tab
        val q = query
        val g = liveGroup
        val f = vodFilter
        filterJob?.cancel()
        filterJob = lifecycleScope.launch {
            if (t == Tab.LIVE) {
                val src = Catalog.live
                val favs = HashSet(favorites)
                val res = withContext(Dispatchers.Default) {
                    val inGroup = when (g) {
                        FAV -> src.filter { it.url in favs }
                        // En "Todos", los favoritos primero
                        null -> if (favs.isEmpty()) src else src.filter { it.url in favs } + src.filter { it.url !in favs }
                        else -> src.filter { it.group == g }
                    }
                    if (q.isEmpty()) inGroup else Search.channels(inGroup, q)
                }
                useAdapter(channelAdapter)
                channelAdapter.submit(res)
            } else if (q.isNotEmpty()) {
                // La búsqueda encuentra pelis y series estés en la pestaña que estés
                val res = withContext(Dispatchers.Default) { Search.items(Catalog.movies + Catalog.shows, q) }
                useAdapter(posterAdapter)
                posterAdapter.showKind = true
                posterAdapter.submit(res)
            } else if (t == Tab.WATCH) {
                useAdapter(posterAdapter)
                posterAdapter.showKind = true
                posterAdapter.submit(Catalog.watchAll)
            } else if (t == Tab.HOME) {
                // Inicio: pelis y series mezcladas
                val resume = resumeItems(Tab.HOME)
                val newEps = Catalog.newEpisodes
                val rows = withContext(Dispatchers.Default) {
                    val genres = (Catalog.movieGenres + Catalog.showGenres).distinctBy { Titles.genreKey(it) }
                    Home.rows(Catalog.movies + Catalog.shows, resume, genres, emptyList(), newEps)
                }
                useAdapter(rowsAdapter)
                rowsAdapter.showKind = true
                rowsAdapter.submit(rows)
            } else {
                val shows = t == Tab.SHOWS
                val src: List<CatalogItem> = if (shows) Catalog.shows else Catalog.movies
                if (f == VodFilter.Home) {
                    val genres = if (shows) Catalog.showGenres else Catalog.movieGenres
                    val resume = resumeItems(t)
                    val newEps = if (shows) Catalog.newEpisodes else emptyList()
                    val rows = withContext(Dispatchers.Default) { Home.rows(src, resume, genres, emptyList(), newEps) }
                    useAdapter(rowsAdapter)
                    rowsAdapter.showKind = false
                    rowsAdapter.submit(rows)
                } else {
                    val res = withContext(Dispatchers.Default) {
                        when {
                            f is VodFilter.From -> src.filter { f.origin in it.origins }.sortedByDescending { it.added }
                            f is VodFilter.Genre -> Home.byGenre(src, f.name)
                            else -> src
                        }
                    }
                    useAdapter(posterAdapter)
                    posterAdapter.showKind = false
                    posterAdapter.submit(res)
                }
            }
            if (scrollTop) content.scrollToPosition(0)
            updateEmpty()
        }
    }

    override fun onResume() {
        super.onResume()
        // Al volver del reproductor, actualizar "Seguir viendo"
        // y "Mi lista" por si has añadido o quitado algo desde una ficha
        if (query.isEmpty() && (content.adapter === rowsAdapter || tab == Tab.WATCH)) {
            refresh()
        }
    }

    private fun updateEmpty() {
        val count = content.adapter?.itemCount ?: 0
        val nothing = when (tab) {
            Tab.LIVE -> Catalog.live.isEmpty()
            Tab.MOVIES -> Catalog.movies.isEmpty()
            Tab.SHOWS -> Catalog.shows.isEmpty()
            Tab.HOME -> Catalog.movies.isEmpty() && Catalog.shows.isEmpty()
            Tab.WATCH -> Catalog.watchAll.isEmpty()
        }
        progress.isVisible = busy > 0 && nothing
        busyBar.isVisible = busy > 0 && !nothing
        empty.isVisible = busy == 0 && (nothing || count == 0)
        emptyAdd.isVisible = nothing
        emptyText.text = when {
            !nothing -> "Sin resultados"
            tab == Tab.LIVE -> "Añade una lista IPTV para ver canales"
            tab == Tab.WATCH && !Plex.loggedIn -> "Conecta tu cuenta de Plex para ver tu lista"
            tab == Tab.WATCH -> "Tu lista de Plex está vacía.\nAñade títulos desde su ficha."
            else -> "Añade una lista IPTV o conecta Plex"
        }
        emptyAdd.isVisible = nothing && !(tab == Tab.WATCH && Plex.loggedIn)
        // Si el foco se ha quedado en algo que ya no se ve, llevarlo al botón
        if (emptyAdd.isShown) {
            val f = currentFocus
            if (f == null || !f.isShown || f === content) emptyAdd.requestFocus()
        }
    }

    // Botón ☰ (menú) del mando: abrir las fuentes
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showSources()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun rebuild() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    Catalog.rebuild()
                    // Tu "Seguir viendo" de Plex
                    runCatching { PlexSync.importProgress(this@MainActivity) }
                }
            } catch (e: OutOfMemoryError) {
                toast("Sin memoria suficiente para preparar el catálogo")
                return@launch
            }
            renderChips()
            refresh()
            checkNewEpisodes()
        }
    }

    private var newEpisodesJob: Job? = null

    /** Series que estabas viendo con capítulos nuevos (se revisa como mucho cada 2 horas). */
    private fun checkNewEpisodes() {
        val shows = Catalog.shows
        if (shows.isEmpty()) return
        val byKey = shows.associateBy { it.key }
        Catalog.newEpisodes = Episodes.newKeys(this).mapNotNull { byKey[it] }
        if (newEpisodesJob?.isActive == true) return
        if (System.currentTimeMillis() - Episodes.lastCheck(this) < 2 * 60 * 60 * 1000L) return
        newEpisodesJob = lifecycleScope.launch {
            val keys = withContext(Dispatchers.IO) {
                runCatching { Episodes.checkNew(this@MainActivity, shows) }.getOrNull()
            } ?: return@launch
            val found = keys.mapNotNull { byKey[it] }
            Catalog.newEpisodes = found
            if (found.isNotEmpty()) {
                toast(
                    if (found.size == 1) "Hay capítulos nuevos de ${found[0].title}"
                    else "Hay capítulos nuevos de ${found.size} series que estás viendo"
                )
            }
            if (tab == Tab.SHOWS || tab == Tab.HOME) refresh()
        }
    }

    // ---------- Abrir contenido ----------

    private fun openChannel(pos: Int) {
        Playback.live = channelAdapter.items
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_MODE, PlayerActivity.MODE_LIVE)
                .putExtra(PlayerActivity.EXTRA_INDEX, pos)
        )
    }

    private fun openDetail(item: CatalogItem) {
        Playback.selected = item
        startActivity(Intent(this, DetailActivity::class.java))
    }

    // ---------- Carga de fuentes ----------

    private fun setBusy(delta: Int) {
        busy = (busy + delta).coerceAtLeast(0)
        updateEmpty()
    }

    private fun reloadAll() {
        val src = prefs.getString(KEY_SRC, null)
        if (src == null && !Plex.loggedIn) {
            showSources()
            return
        }
        src?.let { refreshIptv(it) }
        if (Plex.loggedIn) refreshPlex()
    }

    private fun setSource(src: String, preloaded: String? = null) {
        prefs.edit { putString(KEY_SRC, src) }
        refreshIptv(src, preloaded)
    }

    private fun refreshIptv(src: String, preloaded: String? = null, silent: Boolean = false) {
        setBusy(1)
        lifecycleScope.launch {
            try {
                val data = loadLock.withLock {
                    withContext(Dispatchers.IO) { Iptv.refresh(this@MainActivity, src, preloaded) }
                }
                Catalog.iptv = data
                prefs.edit { putLong("iptv_time", System.currentTimeMillis()) }
                rebuild()
                if (!silent) toast("IPTV: ${data.live.size} canales · ${data.movies.size} pelis · ${data.shows.size} series")
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                toast("La lista es demasiado grande para la memoria de este dispositivo")
            } catch (e: Exception) {
                toast("No se pudo cargar la lista: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                setBusy(-1)
            }
        }
    }

    private fun refreshPlex(silent: Boolean = false) {
        setBusy(1)
        lifecycleScope.launch {
            try {
                val data = loadLock.withLock {
                    withContext(Dispatchers.IO) { Plex.refresh(this@MainActivity, Catalog.plex) }
                }
                Catalog.plex = data
                prefs.edit { putLong("plex_time", System.currentTimeMillis()) }
                rebuild()
                if (!silent) {
                    if (data.movies.isEmpty() && data.shows.isEmpty()) {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("Plex no ha devuelto contenido")
                            .setMessage(Plex.status)
                            .setPositiveButton("Entendido", null)
                            .show()
                    }
                    else toast("Plex: ${data.movies.size} pelis · ${data.shows.size} series")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                toast("La biblioteca de Plex es demasiado grande para la memoria de este dispositivo")
            } catch (e: Exception) {
                if (e is HttpException && e.code == 401) {
                    Plex.logout(this@MainActivity)
                    Catalog.plex = PlexData.EMPTY
                    rebuild()
                    toast("La sesión de Plex ha caducado. Vuelve a conectar.")
                } else {
                    toast("No se pudo cargar Plex: ${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                setBusy(-1)
            }
        }
    }

    // ---------- Recibir listas (QR, compartir, abrir archivo) ----------

    private fun receive(payload: String) {
        val text = payload.trim()
        val url = URL_RE.find(text)?.value
        if (!text.contains("#EXTINF", ignoreCase = true) && url != null) setSource(url)
        else setSource(Iptv.LOCAL, text)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    receive(text)
                    return true
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
            }
            else -> null
        }
        uri ?: return false
        if (uri.scheme == "http" || uri.scheme == "https") {
            setSource(uri.toString())
            return true
        }
        setBusy(1)
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { Source.read(this@MainActivity, uri.toString()) }.getOrNull()
            }
            setBusy(-1)
            if (text != null) receive(text) else toast("No se pudo abrir el archivo")
        }
        return true
    }

    // ---------- Diálogos ----------

    private fun showSources() {
        val iptv = Iptv.describe(prefs.getString(KEY_SRC, null))
        val plex = if (Plex.loggedIn) "Conectado" + (Plex.user?.let { " · $it" } ?: "") else "Sin conectar"
        val version = runCatching { Updater.versionName(this) }.getOrDefault("")
        MaterialAlertDialogBuilder(this)
            .setTitle("Ajustes")
            .setItems(
                arrayOf(
                    "Lista IPTV\n$iptv",
                    "Plex\n$plex",
                    "Audio por defecto\n${Languages.audioLabel(this)}",
                    "Subtítulos por defecto\n${Languages.subsLabel(this)}",
                    "Actualizar app\nVersión $version",
                )
            ) { _, which ->
                when (which) {
                    0 -> showAddDialog()
                    1 -> if (Plex.loggedIn) showPlexMenu() else plexLogin()
                    2 -> pickLanguage(audio = true)
                    3 -> pickLanguage(audio = false)
                    else -> checkUpdate(manual = true)
                }
            }
            .show()
    }

    private fun pickLanguage(audio: Boolean) {
        val none = if (audio) "El del dispositivo" else "Desactivados"
        val codes = listOf("") + Languages.options.map { it.first }
        val names = listOf(none) + Languages.options.map { it.second }
        val current = if (audio) Languages.audio(this) else Languages.subs(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(if (audio) "Audio por defecto" else "Subtítulos por defecto")
            .setSingleChoiceItems(names.toTypedArray(), codes.indexOf(current).coerceAtLeast(0)) { d, which ->
                if (audio) Languages.setAudio(this, codes[which]) else Languages.setSubs(this, codes[which])
                toast((if (audio) "Audio: " else "Subtítulos: ") + names[which])
                d.dismiss()
            }
            .show()
    }

    // ---------- Actualizaciones ----------

    private fun checkUpdate(manual: Boolean) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Updater.latest() }.getOrNull() }
            prefs.edit { putLong("update_check", System.currentTimeMillis()) }
            val current = runCatching { Updater.currentBuild(this@MainActivity) }.getOrDefault(Long.MAX_VALUE)
            when {
                r == null -> if (manual) toast("No se pudo comprobar si hay actualizaciones")
                r.build <= current -> if (manual) toast("Ya tienes la última versión")
                !manual && prefs.getLong("update_offered", 0) == r.build -> Unit
                else -> {
                    prefs.edit { putLong("update_offered", r.build) }
                    val mb = if (r.size > 0) " · %.1f MB".format(r.size / 1_048_576.0) else ""
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Nueva versión disponible")
                        .setMessage("Hay una actualización de Zap (versión ${r.build}$mb).")
                        .setPositiveButton("Actualizar") { _, _ -> startUpdate(r) }
                        .setNegativeButton("Ahora no", null)
                        .show()
                        .getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
                }
            }
        }
    }

    private fun startUpdate(r: Updater.Release) {
        if (!Updater.canInstall(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Permitir actualizaciones")
                .setMessage(
                    "Para instalar la nueva versión, Zap necesita permiso para instalar apps.\n\n" +
                        "Actívalo en la pantalla que se abrirá y vuelve a pulsar Actualizar."
                )
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    if (!Updater.openInstallPermission(this)) {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Permitir actualizaciones")
                            .setMessage(
                                "En Fire TV: Ajustes → Mi Fire TV → Opciones para desarrolladores → " +
                                    "Instalar aplicaciones desconocidas → activa Zap."
                            )
                            .setPositiveButton("Entendido", null)
                            .show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null)
        val bar = view.findViewById<LinearProgressIndicator>(R.id.bar)
        val text = view.findViewById<TextView>(R.id.text)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Descargando actualización")
            .setView(view)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    Updater.download(this@MainActivity, r) { p ->
                        runOnUiThread {
                            bar.setProgressCompat(p, true)
                            text.text = "$p %"
                        }
                    }
                }
                dialog.dismiss()
                Updater.install(this@MainActivity, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dialog.dismiss()
                toast("No se pudo descargar: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun showPlexMenu() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Plex")
            .setItems(arrayOf("Estado de la conexión", "Recargar biblioteca", "Cerrar sesión")) { _, which ->
                when (which) {
                    0 -> MaterialAlertDialogBuilder(this)
                        .setTitle("Estado de Plex")
                        .setMessage(Plex.status)
                        .setPositiveButton("Recargar") { _, _ -> refreshPlex() }
                        .setNegativeButton("Cerrar", null)
                        .show()
                    1 -> refreshPlex()
                    else -> {
                        Plex.logout(this)
                        Catalog.plex = PlexData.EMPTY
                        rebuild()
                    }
                }
            }
            .show()
    }

    private fun plexLogin() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_plex, null)
        val code = view.findViewById<TextView>(R.id.code)
        view.findViewById<ImageView>(R.id.qr).setImageBitmap(Qr.bitmap("https://plex.tv/link"))

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Conectar con Plex")
            .setView(view)
            .setNegativeButton("Cancelar", null)
            .show()

        val job = lifecycleScope.launch {
            try {
                val pin = withContext(Dispatchers.IO) { Plex.createPin() }
                code.text = pin.code
                val deadline = System.currentTimeMillis() + 15 * 60_000
                while (System.currentTimeMillis() < deadline) {
                    delay(2_000)
                    val tok = withContext(Dispatchers.IO) { runCatching { Plex.checkPin(pin.id) }.getOrNull() }
                    if (tok != null) {
                        Plex.token = tok
                        withContext(Dispatchers.IO) { Plex.user = Plex.fetchUser() }
                        toast("Conectado a Plex")
                        refreshPlex()
                        dialog.dismiss()
                        return@launch
                    }
                }
                dialog.dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("No se pudo conectar con Plex: ${e.message ?: e.javaClass.simpleName}")
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener { job.cancel() }
    }

    private fun showAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add, null)
        val input = view.findViewById<EditText>(R.id.url)
        val qr = view.findViewById<ImageView>(R.id.qr)
        val qrHint = view.findViewById<TextView>(R.id.qrHint)
        val qrAddr = view.findViewById<TextView>(R.id.qrAddr)

        // Enlace copiado en el portapapeles, o el último usado
        val clip = runCatching {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull()?.let { URL_RE.find(it)?.value }
        (clip ?: prefs.getString(KEY_SRC, null)?.takeIf { it.startsWith("http") })
            ?.let { input.setText(it) }

        var dialog: AlertDialog? = null
        val server = LanServer { payload ->
            runOnUiThread {
                dialog?.dismiss()
                receive(payload)
            }
        }
        val ip = LanServer.localIp()
        if (ip != null && server.start()) {
            val address = "http://$ip:${server.port}"
            qr.setImageBitmap(Qr.bitmap(address))
            qrAddr.text = address
        } else {
            qr.isVisible = false
            qrHint.isVisible = false
            qrAddr.isVisible = false
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Lista IPTV")
            .setView(view)
            .setPositiveButton("Cargar") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) setSource(url)
            }
            .setNeutralButton("Archivo") { _, _ -> pickFile.launch(arrayOf("*/*")) }
            .setNegativeButton("Cancelar", null)
            .setOnDismissListener { server.stop() }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val KEY_SRC = "src"
        private const val FAV = "\u0000favoritos"
        private const val STALE = 12 * 60 * 60 * 1000L
        private val URL_RE = Regex("""https?://\S+""")
    }
}
