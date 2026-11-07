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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private enum class Tab { LIVE, MOVIES, SHOWS }

    private val prefs by lazy { getSharedPreferences("zap", MODE_PRIVATE) }

    private var tab = Tab.LIVE
    private var liveGroups: List<String> = emptyList()
    private var liveGroup: String? = null
    private var vodOrigin: Origin? = null
    private var query = ""
    private var typingJob: Job? = null
    private var filterJob: Job? = null
    private var busy = 0

    private lateinit var tabs: Map<Tab, TextView>
    private lateinit var chips: RecyclerView
    private lateinit var content: RecyclerView
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var emptyAdd: View
    private lateinit var progress: View
    private lateinit var busyBar: View

    private val channelAdapter = ChannelAdapter { pos -> openChannel(pos) }
    private val posterAdapter = PosterAdapter({ spans() }) { item -> openDetail(item) }
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
            Tab.LIVE to findViewById(R.id.tabLive),
            Tab.MOVIES to findViewById(R.id.tabMovies),
            Tab.SHOWS to findViewById(R.id.tabShows),
        )
        chips = findViewById(R.id.chips)
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

        tab = runCatching { Tab.valueOf(prefs.getString("tab", "LIVE")!!) }.getOrDefault(Tab.LIVE)
        for ((t, v) in tabs) v.setOnClickListener { selectTab(t) }

        findViewById<View>(R.id.settings).setOnClickListener { showSources() }
        findViewById<View>(R.id.reload).setOnClickListener { reloadAll() }
        emptyAdd.setOnClickListener { if (tab == Tab.LIVE) showAddDialog() else showSources() }
        findViewById<EditText>(R.id.search).doAfterTextChanged { text ->
            query = fold(text?.toString()?.trim().orEmpty())
            typingJob?.cancel()
            typingJob = lifecycleScope.launch {
                delay(150)
                refresh(scrollTop = true)
            }
        }

        selectTab(tab)

        // Arranque instantáneo con lo guardado y actualización en segundo plano
        val handled = savedInstanceState == null && handleIntent(intent)
        lifecycleScope.launch {
            val src = prefs.getString(KEY_SRC, null)
            val (iptv, plex) = withContext(Dispatchers.IO) {
                (if (handled) null else runCatching { Iptv.loadCached(this@MainActivity, src) }.getOrNull()) to
                    Plex.loadCached(this@MainActivity)
            }
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
        }
    }

    // ---------- Pestañas y filtros ----------

    private fun spans(): Int {
        val dp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (dp / 130f).toInt().coerceIn(3, 8)
    }

    private fun selectTab(t: Tab) {
        tab = t
        prefs.edit { putString("tab", t.name) }
        for ((k, v) in tabs) v.isSelected = k == t
        if (t == Tab.LIVE) {
            content.layoutManager = LinearLayoutManager(this)
            content.adapter = channelAdapter
        } else {
            content.layoutManager = GridLayoutManager(this, spans())
            content.adapter = posterAdapter
        }
        renderChips()
        refresh(scrollTop = true)
    }

    private fun renderChips() {
        if (tab == Tab.LIVE) {
            liveGroups = Catalog.live.map { it.group }.distinct()
            val lg = liveGroup
            if (lg != null && lg !in liveGroups) liveGroup = null
            chipAdapter.submit(listOf("Todos") + liveGroups, liveGroup?.let { liveGroups.indexOf(it) + 1 } ?: 0)
            chips.isVisible = liveGroups.size > 1
        } else {
            val list: List<CatalogItem> = if (tab == Tab.MOVIES) Catalog.movies else Catalog.shows
            val both = list.any { Origin.PLEX in it.origins } && list.any { Origin.IPTV in it.origins }
            if (!both) vodOrigin = null
            chipAdapter.submit(listOf("Todo", "Plex", "IPTV"), when (vodOrigin) {
                Origin.PLEX -> 1; Origin.IPTV -> 2; null -> 0
            })
            chips.isVisible = both
        }
    }

    private fun onChip(pos: Int) {
        if (tab == Tab.LIVE) liveGroup = if (pos == 0) null else liveGroups.getOrNull(pos - 1)
        else vodOrigin = when (pos) { 1 -> Origin.PLEX; 2 -> Origin.IPTV; else -> null }
        refresh(scrollTop = true)
    }

    private fun refresh(scrollTop: Boolean = false) {
        val t = tab
        val q = query
        val g = liveGroup
        val o = vodOrigin
        filterJob?.cancel()
        filterJob = lifecycleScope.launch {
            if (t == Tab.LIVE) {
                val src = Catalog.live
                val res = withContext(Dispatchers.Default) {
                    if (g == null && q.isEmpty()) src
                    else src.filter { (g == null || it.group == g) && (q.isEmpty() || it.key.contains(q)) }
                }
                channelAdapter.submit(res)
            } else {
                val src: List<CatalogItem> = if (t == Tab.MOVIES) Catalog.movies else Catalog.shows
                val res = withContext(Dispatchers.Default) {
                    if (o == null && q.isEmpty()) src
                    else src.filter { (o == null || o in it.origins) && (q.isEmpty() || it.key.contains(q)) }
                }
                posterAdapter.submit(res)
            }
            if (scrollTop) content.scrollToPosition(0)
            updateEmpty()
        }
    }

    private fun updateEmpty() {
        val count = if (tab == Tab.LIVE) channelAdapter.itemCount else posterAdapter.itemCount
        val nothing = when (tab) {
            Tab.LIVE -> Catalog.live.isEmpty()
            Tab.MOVIES -> Catalog.movies.isEmpty()
            Tab.SHOWS -> Catalog.shows.isEmpty()
        }
        progress.isVisible = busy > 0 && nothing
        busyBar.isVisible = busy > 0 && !nothing
        empty.isVisible = busy == 0 && (nothing || count == 0)
        emptyAdd.isVisible = nothing
        emptyText.text = when {
            !nothing -> "Sin resultados"
            tab == Tab.LIVE -> "Añade una lista IPTV para ver canales"
            else -> "Añade una lista IPTV o conecta Plex"
        }
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
            withContext(Dispatchers.Default) { Catalog.rebuild() }
            renderChips()
            refresh()
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
                val data = withContext(Dispatchers.IO) { Iptv.refresh(this@MainActivity, src, preloaded) }
                Catalog.iptv = data
                prefs.edit { putLong("iptv_time", System.currentTimeMillis()) }
                rebuild()
                if (!silent) toast("IPTV: ${data.live.size} canales · ${data.movies.size} pelis · ${data.shows.size} series")
            } catch (e: CancellationException) {
                throw e
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
                val data = withContext(Dispatchers.IO) { Plex.refresh(this@MainActivity) }
                Catalog.plex = data
                prefs.edit { putLong("plex_time", System.currentTimeMillis()) }
                rebuild()
                if (!silent) {
                    if (data.servers.isEmpty()) toast("Plex: no hay servidores disponibles")
                    else toast("Plex: ${data.movies.size} pelis · ${data.shows.size} series")
                }
            } catch (e: CancellationException) {
                throw e
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
        MaterialAlertDialogBuilder(this)
            .setTitle("Fuentes")
            .setItems(arrayOf("Lista IPTV\n$iptv", "Plex\n$plex")) { _, which ->
                if (which == 0) showAddDialog()
                else if (Plex.loggedIn) showPlexMenu() else plexLogin()
            }
            .show()
    }

    private fun showPlexMenu() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Plex")
            .setItems(arrayOf("Recargar biblioteca", "Cerrar sesión")) { _, which ->
                if (which == 0) refreshPlex()
                else {
                    Plex.logout(this)
                    Catalog.plex = PlexData.EMPTY
                    rebuild()
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
        private const val STALE = 12 * 60 * 60 * 1000L
        private val URL_RE = Regex("""https?://\S+""")
    }
}
