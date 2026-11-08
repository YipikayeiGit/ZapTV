package com.zap.iptv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Buscar: últimas búsquedas, tendencias de Plex y resultados. */
class SearchActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("zap", MODE_PRIVATE) }
    private lateinit var input: EditText
    private lateinit var home: View
    private lateinit var results: RecyclerView
    private lateinit var msg: TextView
    private var typing: Job? = null

    private val recentAdapter = ChipAdapter(noSelection = true) { pos -> recents().getOrNull(pos)?.let { setQuery(it) } }
    private val resultAdapter = PosterAdapter({ spans() }) { item -> open(item) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Plex.init(this)
        setContentView(R.layout.activity_search)
        input = findViewById(R.id.query)
        home = findViewById(R.id.home)
        results = findViewById(R.id.results)
        msg = findViewById(R.id.msg)

        findViewById<RecyclerView>(R.id.recent).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = recentAdapter
            isFocusable = false
        }
        results.layoutManager = GridLayoutManager(this, spans())
        results.adapter = resultAdapter
        results.isFocusable = false
        resultAdapter.showKind = true

        input.doAfterTextChanged {
            typing?.cancel()
            typing = lifecycleScope.launch {
                delay(250)
                search(it?.toString()?.trim().orEmpty())
            }
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                remember(input.text.toString())
                true
            } else {
                false
            }
        }

        // Atrás: primero borra lo escrito; luego vuelve
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (input.text.isNotEmpty()) {
                    input.setText("")
                    input.requestFocus()
                } else {
                    finish()
                }
            }
        })

        showRecents()
        loadTrending()
        input.requestFocus()
    }

    private fun spans(): Int {
        val dp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (dp / 130f).toInt().coerceIn(3, 8)
    }

    // ---------- Búsquedas recientes (las 5 últimas) ----------

    private fun recents(): List<String> =
        prefs.getString("recent_searches", "").orEmpty().split('\u0001').filter { it.isNotBlank() }

    private fun remember(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        val list = (listOf(t) + recents().filterNot { it.equals(t, ignoreCase = true) }).take(5)
        prefs.edit().putString("recent_searches", list.joinToString("\u0001")).apply()
        showRecents()
    }

    private fun showRecents() {
        val list = recents()
        recentAdapter.submit(list, select = -1)
        findViewById<View>(R.id.recentTitle).isVisible = list.isNotEmpty()
        findViewById<View>(R.id.recent).isVisible = list.isNotEmpty()
    }

    private fun setQuery(q: String) {
        input.setText(q)
        input.setSelection(q.length)
    }

    // ---------- Tendencias ----------

    private fun loadTrending() {
        val view = findViewById<RecyclerView>(R.id.trending)
        val title = findViewById<TextView>(R.id.trendTitle)
        val dm = resources.displayMetrics
        val w = ((if (dm.widthPixels / dm.density > 700) 130 else 108) * dm.density).toInt()
        val adapter = PosterAdapter({ 1 }, w) { item -> open(item) }
        adapter.showKind = true
        view.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        view.adapter = adapter
        view.isFocusable = false

        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                val trend = runCatching { Plex.trending() }.getOrDefault(emptyList())
                val mIdx = Watch.Index(Catalog.movies)
                val sIdx = Watch.Index(Catalog.shows)
                trend.mapNotNull { w -> if (w.type == "show") sIdx.find(w) else mIdx.find(w) }.distinct()
            }
            findViewById<View>(R.id.trendProgress).isVisible = false
            val list = items.ifEmpty {
                // Sin tendencias de Plex: lo mejor valorado y reciente de tu catálogo
                title.text = "Populares en tu catálogo"
                withContext(Dispatchers.Default) {
                    (Catalog.movies + Catalog.shows)
                        .filter { (it.rating ?: 0f) >= 7.5f }
                        .sortedWith(compareByDescending<CatalogItem> { it.year ?: 0 }.thenByDescending { it.rating ?: 0f })
                        .take(30)
                }
            }
            if (list.isNotEmpty()) {
                title.isVisible = true
                view.isVisible = true
                adapter.submit(list)
            }
        }
    }

    // ---------- Resultados ----------

    private fun search(q: String) {
        if (q.isEmpty()) {
            results.isVisible = false
            msg.isVisible = false
            home.isVisible = true
            return
        }
        lifecycleScope.launch {
            val res = withContext(Dispatchers.Default) { Search.items(Catalog.movies + Catalog.shows, q) }
            home.isVisible = false
            resultAdapter.submit(res)
            results.isVisible = res.isNotEmpty()
            msg.text = "No hay resultados para \"$q\""
            msg.isVisible = res.isEmpty()
            results.scrollToPosition(0)
        }
    }

    private fun open(item: CatalogItem) {
        remember(input.text.toString())
        Playback.selected = item
        startActivity(Intent(this, DetailActivity::class.java))
    }
}
