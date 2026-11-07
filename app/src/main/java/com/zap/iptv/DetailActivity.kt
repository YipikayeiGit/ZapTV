package com.zap.iptv

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailActivity : AppCompatActivity() {

    private lateinit var item: CatalogItem
    private lateinit var sourcesBox: LinearLayout
    private lateinit var seasonsView: RecyclerView
    private lateinit var episodesView: RecyclerView
    private lateinit var progress: View
    private lateinit var msg: TextView

    private var episodes: List<Episode> = emptyList()
    private var seasons: List<Int> = emptyList()
    private val plexDetails = HashMap<String, Plex.Details>()

    private val seasonAdapter = ChipAdapter { pos -> showSeason(pos) }
    private val episodeAdapter = EpisodeAdapter { ep -> pickSource(ep) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Plex.init(this)
        val sel = Playback.selected
        if (sel == null) {
            finish()
            return
        }
        item = sel
        setContentView(R.layout.activity_detail)

        sourcesBox = findViewById(R.id.sources)
        seasonsView = findViewById(R.id.seasons)
        episodesView = findViewById(R.id.episodes)
        progress = findViewById(R.id.progress)
        msg = findViewById(R.id.msg)

        findViewById<TextView>(R.id.title).text = item.title
        findViewById<TextView>(R.id.meta).text =
            listOfNotNull(item.year?.toString(), item.badge).joinToString("  ·  ")
        findViewById<TextView>(R.id.plot).apply {
            text = item.plot
            isVisible = !item.plot.isNullOrBlank()
        }
        val poster = findViewById<ImageView>(R.id.poster)
        poster.clipToOutline = true
        item.poster?.let { poster.load(it) { crossfade(false) } }

        when (val x = item) {
            is Movie -> showMovie(x)
            is Show -> showSeries(x)
        }
    }

    // ---------- Película: un botón por fuente ----------

    private fun showMovie(m: Movie) {
        // Plex primero: suele tener mejor calidad
        val sources = m.sources.sortedBy { it.origin.ordinal }
        val buttons = sources.map { src -> addSourceButton(src.label) { play(m.title, src, "movie:${m.key}") } }
        buttons.firstOrNull()?.requestFocus()

        // Completar las etiquetas de Plex con idiomas y subtítulos
        sources.forEachIndexed { i, src ->
            if (src.origin != Origin.PLEX) return@forEachIndexed
            lifecycleScope.launch {
                val d = withContext(Dispatchers.IO) {
                    runCatching { Plex.details(src.plexServer!!, src.plexKey!!) }.getOrNull()
                } ?: return@launch
                plexDetails[src.plexKey!!] = d
                buttons[i].text = d.label
            }
        }
    }

    private fun addSourceButton(label: String, onClick: () -> Unit): TextView {
        val b = LayoutInflater.from(this).inflate(R.layout.item_source, sourcesBox, false) as TextView
        b.text = label
        b.setOnClickListener { onClick() }
        sourcesBox.addView(b)
        return b
    }

    // ---------- Serie: temporadas y capítulos de todas las fuentes ----------

    private fun showSeries(s: Show) {
        seasonsView.isFocusable = false
        episodesView.isFocusable = false
        seasonsView.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        seasonsView.adapter = seasonAdapter
        episodesView.layoutManager = LinearLayoutManager(this)
        episodesView.adapter = episodeAdapter
        episodesView.isVisible = true
        progress.isVisible = true

        val acc = Xtream.Account.parse(getSharedPreferences("zap", MODE_PRIVATE).getString("src", null))
        lifecycleScope.launch {
            val lists = withContext(Dispatchers.IO) {
                val plex = s.plex.map { ref -> async { runCatching { Plex.episodes(ref) }.getOrDefault(emptyList()) } }
                val xt: List<Deferred<List<Episode>>> = if (acc == null) emptyList() else s.xtreamIds.map { id ->
                    async { runCatching { Xtream.episodes(acc, id) }.getOrDefault(emptyList()) }
                }
                (plex + xt).awaitAll() + listOf(s.episodes)
            }
            episodes = Merge.episodes(lists)
            progress.isVisible = false
            if (episodes.isEmpty()) {
                msg.text = "No se encontraron capítulos"
                msg.isVisible = true
                return@launch
            }
            seasons = episodes.map { it.season }.distinct()
            seasonAdapter.submit(seasons.map { if (it == 0) "Especiales" else "Temporada $it" })
            seasonsView.isVisible = seasons.size > 1
            showSeason(0)
            episodesView.post { episodesView.getChildAt(0)?.requestFocus() }
        }
    }

    private fun showSeason(pos: Int) {
        val season = seasons.getOrNull(pos) ?: return
        episodeAdapter.submit(episodes.filter { it.season == season })
        episodesView.scrollToPosition(0)
    }

    private fun pickSource(ep: Episode) {
        val title = "${item.title} · T${ep.season}E${ep.number}"
        val key = "ep:${item.key}:${ep.season}:${ep.number}"
        val sources = ep.sources.sortedBy { it.origin.ordinal }
        if (sources.size == 1) {
            play(title, sources[0], key)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Episodio ${ep.number}")
            .setItems(sources.map { it.label }.toTypedArray()) { _, which -> play(title, sources[which], key) }
            .show()
    }

    // ---------- Reproducir ----------

    private fun play(title: String, src: SourceRef, resumeKey: String) {
        lifecycleScope.launch {
            val playItem = try {
                withContext(Dispatchers.IO) {
                    if (src.origin == Origin.IPTV) {
                        PlayItem(title, src.url!!, resumeKey = resumeKey)
                    } else {
                        val d = plexDetails[src.plexKey!!] ?: Plex.details(src.plexServer!!, src.plexKey!!)
                        PlayItem(title, d.url, d.subs, d.fallback, resumeKey)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    this@DetailActivity,
                    "No se pudo abrir: ${e.message ?: e.javaClass.simpleName}",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            Playback.item = playItem
            startActivity(
                Intent(this@DetailActivity, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_MODE, PlayerActivity.MODE_VOD)
            )
        }
    }
}
