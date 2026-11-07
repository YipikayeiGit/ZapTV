package com.zap.iptv

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Películas de un actor o director (de la biblioteca de Plex donde está). */
class PersonActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Plex.init(this)
        val person = Playback.person
        if (person == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_person)
        findViewById<TextView>(R.id.title).text = person.name
        findViewById<TextView>(R.id.subtitle).text =
            if (person.kind == "director") "Películas que ha dirigido" else "Películas en las que aparece"

        val grid = findViewById<RecyclerView>(R.id.grid)
        val dp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val spans = (dp / 130f).toInt().coerceIn(3, 8)
        val adapter = PosterAdapter({ spans }) { item ->
            Playback.selected = item
            startActivity(Intent(this, DetailActivity::class.java))
        }
        grid.layoutManager = GridLayoutManager(this, spans)
        grid.adapter = adapter
        grid.isFocusable = false

        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    person.server?.let { Plex.ensure(it) }
                    Plex.byPerson(person)
                }.getOrDefault(emptyList())
            }
            // Si está en el catálogo, usar la versión completa (con sus otras fuentes)
            val byRef = HashMap<String, CatalogItem>()
            for (m in Catalog.movies) for (s in m.sources) if (s.plexKey != null) byRef["${s.plexServer}/${s.plexKey}"] = m
            val list = found.map { m ->
                val src = m.sources.first()
                byRef["${src.plexServer}/${src.plexKey}"] ?: m
            }.distinct().sortedByDescending { it.year ?: 0 }

            findViewById<android.view.View>(R.id.progress).isVisible = false
            if (list.isEmpty()) {
                findViewById<TextView>(R.id.msg).apply {
                    text = "No hay más películas de ${person.name} en Plex"
                    isVisible = true
                }
            } else {
                adapter.submit(list)
                grid.post { grid.getChildAt(0)?.requestFocus() }
            }
        }
    }
}
