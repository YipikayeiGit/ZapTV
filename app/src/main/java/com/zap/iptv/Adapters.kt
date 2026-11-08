package com.zap.iptv

import android.annotation.SuppressLint
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

class ChannelAdapter(
    private val isFav: (Channel) -> Boolean = { false },
    private val onLong: (Int) -> Unit = {},
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    init {
        // Ids estables: al reordenar (favoritos) el foco del mando no salta
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items[position].url.hashCode().toLong()

    var items: List<Channel> = emptyList()
        private set

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Channel>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.num)
        val name: TextView = v.findViewById(R.id.name)
        val initial: TextView = v.findViewById(R.id.initial)
        val logo: ImageView = v.findViewById(R.id.logo)
        val fav: TextView = v.findViewById(R.id.fav)

        init {
            v.findViewById<View>(R.id.logoBox).clipToOutline = true
            v.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(p)
            }
            // Mantener pulsado OK: añadir o quitar de favoritos
            v.setOnLongClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onLong(p)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val ch = items[position]
        h.num.text = (position + 1).toString()
        h.name.text = ch.name
        h.fav.visibility = if (isFav(ch)) View.VISIBLE else View.GONE
        h.initial.text = ch.name.firstOrNull()?.uppercase() ?: ""
        h.initial.visibility = View.VISIBLE
        if (ch.logo != null) {
            h.logo.load(Plex.fixUrl(ch.logo)) {
                stableCache(ch.logo)
                crossfade(false)
                size(160)
                listener(onSuccess = { _, _ -> h.initial.visibility = View.INVISIBLE })
            }
        } else {
            h.logo.dispose()
            h.logo.setImageDrawable(null)
        }
    }

    override fun getItemCount() = items.size
}

/** Lista de opciones seleccionables (pastillas, temporadas o el panel de categorías). */
class ChipAdapter(
    private val layout: Int = R.layout.item_group,
    /** Pastillas que solo lanzan una acción (sin quedar marcadas). */
    private val noSelection: Boolean = false,
    private val onPick: (Int) -> Unit,
) : RecyclerView.Adapter<ChipAdapter.VH>() {

    private var labels: List<String> = emptyList()
    var selected = 0
        private set

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<String>, select: Int = 0) {
        labels = list
        // -1 = ninguna marcada (p. ej. búsquedas recientes)
        selected = if (select < 0) -1 else select.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        notifyDataSetChanged()
    }

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(layout, parent, false) as TextView
        val vh = VH(tv)
        tv.setOnClickListener {
            val p = vh.bindingAdapterPosition
            if (p == RecyclerView.NO_POSITION || (p == selected && selected >= 0)) return@setOnClickListener
            val old = selected
            if (!noSelection) {
                selected = p
                if (old >= 0) notifyItemChanged(old)
                notifyItemChanged(p)
            }
            onPick(p)
        }
        return vh
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        h.tv.text = labels[position]
        h.tv.isSelected = position == selected
    }

    override fun getItemCount() = labels.size
}

/** Cuadrícula de carátulas para pelis y series. */
class PosterAdapter(
    private val spans: () -> Int,
    /** Ancho fijo (filas horizontales); 0 = repartir el ancho en columnas. */
    private val fixedWidthPx: Int = 0,
    /** Tarjetas apaisadas (16:9) con la imagen de fondo, como Plex. */
    private val landscape: Boolean = false,
    private val onClick: (CatalogItem) -> Unit,
) : RecyclerView.Adapter<PosterAdapter.VH>() {

    var items: List<CatalogItem> = emptyList()
        private set

    /** Mostrar la etiqueta SERIE / PELÍCULA (donde se mezclan ambas). */
    var showKind = false

    /** ↑/↓ con el mando sobre una carátula: (posición, -1 arriba / +1 abajo) → ¿gestionado? */
    var onVertical: ((Int, Int) -> Boolean)? = null

    /** Carátula que acaba de recibir el foco (para recordar dónde estabas en cada fila). */
    var onFocusIndex: ((Int) -> Unit)? = null

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<CatalogItem>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val box: View = v.findViewById(R.id.box)
        val kind: TextView = v.findViewById(R.id.kind)
        val img: ImageView = v.findViewById(R.id.img)
        val ph: TextView = v.findViewById(R.id.ph)
        val badge: TextView = v.findViewById(R.id.badge)
        val title: TextView = v.findViewById(R.id.title)
        val year: TextView = v.findViewById(R.id.year)
        val quality: TextView = v.findViewById(R.id.quality)

        init {
            box.clipToOutline = true
            v.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(items[p])
            }
            // Como en Plex: la carátula enfocada crece un poco y el fondo de la pantalla cambia
            v.setOnFocusChangeListener { view, focused ->
                val s = if (focused) 1.07f else 1f
                view.animate().scaleX(s).scaleY(s).setDuration(140).start()
                view.translationZ = if (focused) 4f else 0f
                val p = bindingAdapterPosition
                if (focused && p != RecyclerView.NO_POSITION) {
                    onFocusIndex?.invoke(p)
                    onFocus?.invoke(items[p])
                }
            }
            v.setOnKeyListener { _, code, ev ->
                val dir = when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> -1
                    KeyEvent.KEYCODE_DPAD_DOWN -> 1
                    else -> 0
                }
                val cb = onVertical
                if (dir == 0 || cb == null || ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val p = bindingAdapterPosition
                p != RecyclerView.NO_POSITION && cb(p, dir)
            }
        }
    }

    companion object {
        /** Avisa de qué título tiene el foco (la pantalla principal lo usa para el fondo y la cabecera). */
        var onFocus: ((CatalogItem) -> Unit)? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
        val h = VH(v)
        val total = if (parent.width > 0) parent.width - parent.paddingLeft - parent.paddingRight
        else parent.resources.displayMetrics.widthPixels
        val pad = v.paddingLeft + v.paddingRight
        val w = if (fixedWidthPx > 0) {
            v.layoutParams = v.layoutParams.apply { width = fixedWidthPx + pad }
            fixedWidthPx
        } else {
            total / spans() - pad
        }
        h.box.layoutParams = h.box.layoutParams.apply {
            height = if (landscape) w * 9 / 16 else (w * 1.5f).toInt()
        }
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        if (!h.itemView.isFocused) {
            h.itemView.scaleX = 1f
            h.itemView.scaleY = 1f
            h.itemView.translationZ = 0f
        }
        h.title.text = it.title
        h.year.text = it.year?.toString() ?: ""
        val fresh = it is Show && Catalog.newEpisodes.any { n -> n.key == it.key }
        h.badge.text = if (fresh) "NUEVO CAPÍTULO" else it.badge
        h.badge.setBackgroundResource(if (fresh) R.drawable.bg_badge_new else R.drawable.bg_badge)
        if (showKind) {
            h.kind.text = if (it is Show) "SERIE" else "PELÍCULA"
            h.kind.visibility = View.VISIBLE
        } else {
            h.kind.visibility = View.GONE
        }
        val q = it.quality
        h.quality.text = q ?: ""
        h.quality.visibility = if (q != null) View.VISIBLE else View.GONE
        h.ph.text = it.title
        h.ph.visibility = View.VISIBLE
        val image = if (landscape) it.art ?: it.poster else it.poster
        if (image != null) {
            h.img.load(Plex.fixUrl(image)) {
                stableCache(image)
                crossfade(false)
                if (landscape) size(480, 270) else size(300, 450)
                listener(onSuccess = { _, _ -> h.ph.visibility = View.INVISIBLE })
            }
        } else {
            h.img.dispose()
            h.img.setImageDrawable(null)
        }
    }

    override fun getItemCount() = items.size
}

class EpisodeAdapter(
    private val isNew: (Episode) -> Boolean = { false },
    /** ¿Visto? (en Plex o en la app) */
    private val isWatched: (Episode) -> Boolean = { false },
    /** Parte vista (0–1) si está a medias. */
    private val progress: (Episode) -> Float = { 0f },
    private val onClick: (Episode) -> Unit,
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    @SuppressLint("NotifyDataSetChanged")
    fun refreshMarks() = notifyDataSetChanged()

    private var items: List<Episode> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Episode>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumbBox: View = v.findViewById(R.id.thumbBox)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val num: TextView = v.findViewById(R.id.num)
        val title: TextView = v.findViewById(R.id.title)
        val summary: TextView = v.findViewById(R.id.summary)
        val badge: TextView = v.findViewById(R.id.badge)
        val seen: View = v.findViewById(R.id.seen)
        val track: View = v.findViewById(R.id.progressTrack)
        val fill: View = v.findViewById(R.id.progressFill)

        init {
            thumbBox.clipToOutline = true
            // Tarjeta horizontal, más grande en la tele que en el móvil
            val dm = v.resources.displayMetrics
            val wDp = if (dm.widthPixels / dm.density > 700) 260 else 200
            val w = (wDp * dm.density).toInt()
            thumbBox.layoutParams = thumbBox.layoutParams.apply {
                width = w
                height = w * 9 / 16
            }
            v.layoutParams = v.layoutParams.apply { width = w + v.paddingLeft + v.paddingRight }
            v.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(items[p])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false))

    private fun fitSummary(h: VH) {
        val list = h.itemView.parent as? View ?: return
        val line = h.summary.lineHeight.takeIf { it > 0 } ?: return
        val avail = list.height - list.paddingBottom - h.itemView.top - h.itemView.paddingBottom - h.summary.top
        val lines = (avail / line).coerceAtMost(3)
        if (lines < 1) {
            h.summary.visibility = View.INVISIBLE
        } else if (lines != h.summary.maxLines) {
            h.summary.maxLines = lines
        }
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.num.text = e.number.toString()
        h.title.text = "${e.number}. " + (e.title ?: "Episodio ${e.number}")
        h.summary.text = e.summary ?: ""
        h.summary.visibility = if (e.summary.isNullOrBlank()) View.GONE else View.VISIBLE
        // La sinopsis usa solo las líneas que caben en la tarjeta (si no, se cortaba por abajo)
        h.summary.maxLines = 3
        if (h.summary.visibility == View.VISIBLE) h.summary.post { fitSummary(h) }
        val origins = Origin.entries.filter { o -> e.sources.any { it.origin == o } }
            .joinToString(" · ") { it.label }
        h.badge.text = listOfNotNull(
            "NUEVO".takeIf { isNew(e) },
            e.minutes?.let { "$it min" },
            origins,
        ).joinToString(" · ")
        val watched = isWatched(e)
        h.seen.visibility = if (watched) View.VISIBLE else View.GONE
        val part = if (watched) 0f else progress(e).coerceIn(0f, 1f)
        if (part > 0.01f) {
            h.track.visibility = View.VISIBLE
            h.fill.layoutParams = h.fill.layoutParams.apply { width = (h.thumbBox.layoutParams.width * part).toInt() }
        } else {
            h.track.visibility = View.GONE
        }
        h.thumb.alpha = if (watched) 0.6f else 1f
        h.num.visibility = View.VISIBLE
        if (e.thumb != null) {
            h.thumb.load(Plex.fixUrl(e.thumb)) {
                stableCache(e.thumb)
                crossfade(false)
                size(400, 225)
                listener(onSuccess = { _, _ -> h.num.visibility = View.INVISIBLE })
            }
        } else {
            h.thumb.dispose()
            h.thumb.setImageDrawable(null)
        }
    }

    override fun getItemCount() = items.size
}

/** Pantalla de inicio: una fila horizontal de carátulas por categoría. */
class RowsAdapter(private val onClick: (CatalogItem) -> Unit) :
    RecyclerView.Adapter<RowsAdapter.VH>() {

    private var rows: List<Row> = emptyList()

    /** Carátula en la que estabas en cada fila (por número de fila). */
    private val remembered = HashMap<Int, Int>()
    private var outer: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        outer = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (outer === recyclerView) outer = null
    }

    /**
     * Subir/bajar de fila: se va a la carátula en la que estabas en esa fila
     * (o a la primera), nunca a "la más cercana en pantalla", que hacía que el
     * foco se fuera corriendo hacia la derecha.
     */
    private fun moveRow(from: Int, dir: Int): Boolean {
        val rv = outer ?: return false
        val target = from + dir
        if (target < 0) return false // desde la primera fila, hacia arriba: pestañas
        if (target >= rows.size) return true // última fila: no hacer nada
        fun focusIn(): Boolean {
            val vh = rv.findViewHolderForAdapterPosition(target) as? VH ?: return false
            val idx = (remembered[target] ?: 0).coerceIn(0, (vh.adapter.itemCount - 1).coerceAtLeast(0))
            val lm = vh.list.layoutManager as LinearLayoutManager
            val v = lm.findViewByPosition(idx)
            if (v != null && v.left >= 0 && v.right <= vh.list.width) {
                v.requestFocus()
            } else {
                lm.scrollToPositionWithOffset(idx, vh.list.paddingLeft)
                vh.list.post { lm.findViewByPosition(idx)?.requestFocus() }
            }
            return true
        }
        if (!focusIn()) {
            rv.scrollToPosition(target)
            rv.post { focusIn() }
        }
        return true
    }

    /** Mostrar la etiqueta SERIE / PELÍCULA en las carátulas. */
    var showKind = false

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Row>) {
        rows = list
        remembered.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = if (rows[position].landscape) 1 else 0

    inner class VH(v: View, landscape: Boolean) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val list: RecyclerView = v.findViewById(R.id.rowList)
        val adapter: PosterAdapter

        init {
            val dm = v.resources.displayMetrics
            val wideScreen = dm.widthPixels / dm.density > 700
            val wDp = when {
                landscape && wideScreen -> 230
                landscape -> 172
                wideScreen -> 130
                else -> 108
            }
            adapter = PosterAdapter({ 1 }, (wDp * dm.density).toInt(), landscape, onClick)
            adapter.onVertical = { _, dir ->
                val row = bindingAdapterPosition
                row != RecyclerView.NO_POSITION && moveRow(row, dir)
            }
            adapter.onFocusIndex = { i ->
                val row = bindingAdapterPosition
                if (row != RecyclerView.NO_POSITION) remembered[row] = i
            }
            list.layoutManager = LinearLayoutManager(v.context, RecyclerView.HORIZONTAL, false)
            list.adapter = adapter
            list.isFocusable = false
            list.setHasFixedSize(true)
            list.setItemViewCacheSize(8)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false), viewType == 1)

    override fun onBindViewHolder(h: VH, position: Int) {
        val row = rows[position]
        h.title.text = row.title
        h.adapter.showKind = showKind
        h.adapter.submit(row.items)
        val idx = remembered[position] ?: 0
        (h.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 0)
    }

    override fun getItemCount() = rows.size
}

/** Reparto: foto redonda, nombre y personaje. */
class CastAdapter(
    private val people: List<Person>,
    private val onClick: (Person) -> Unit = {},
) : RecyclerView.Adapter<CastAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val photo: ImageView = v.findViewById(R.id.photo)
        val initials: TextView = v.findViewById(R.id.initials)
        val name: TextView = v.findViewById(R.id.name)
        val role: TextView = v.findViewById(R.id.role)

        init {
            v.findViewById<View>(R.id.photoBox).clipToOutline = true
            v.isClickable = true
            v.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(people[p])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_cast, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val p = people[position]
        h.name.text = p.name
        h.role.text = p.role ?: ""
        h.initials.text = p.name.split(' ').filter { it.isNotEmpty() }.take(2)
            .joinToString("") { it.first().uppercase() }
        h.initials.visibility = View.VISIBLE
        if (p.photo != null) {
            h.photo.load(Plex.fixUrl(p.photo)) {
                stableCache(p.photo)
                crossfade(false)
                size(200)
                listener(onSuccess = { _, _ -> h.initials.visibility = View.INVISIBLE })
            }
        } else {
            h.photo.dispose()
            h.photo.setImageDrawable(null)
        }
    }

    override fun getItemCount() = people.size
}

/** Tráileres de una película. */
class TrailerAdapter(
    private val trailers: List<Trailer>,
    private val onClick: (Trailer) -> Unit,
) : RecyclerView.Adapter<TrailerAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)

        init {
            v.findViewById<View>(R.id.box).clipToOutline = true
            v.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(trailers[p])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_trailer, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = trailers[position]
        h.title.text = t.title
        if (t.thumb != null) {
            h.thumb.load(Plex.fixUrl(t.thumb)) {
                stableCache(t.thumb)
                crossfade(false)
                size(440, 248)
            }
        } else {
            h.thumb.dispose()
            h.thumb.setImageDrawable(null)
        }
    }

    override fun getItemCount() = trailers.size
}
