package com.zap.iptv

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

class ChannelAdapter(private val onClick: (Int) -> Unit) :
    RecyclerView.Adapter<ChannelAdapter.VH>() {

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

        init {
            v.findViewById<View>(R.id.logoBox).clipToOutline = true
            v.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(p)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val ch = items[position]
        h.num.text = (position + 1).toString()
        h.name.text = ch.name
        h.initial.text = ch.name.firstOrNull()?.uppercase() ?: ""
        h.initial.visibility = View.VISIBLE
        if (ch.logo != null) {
            h.logo.load(ch.logo) {
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

/** Fila de "pastillas" (grupos, filtros, temporadas). */
class ChipAdapter(private val onPick: (Int) -> Unit) :
    RecyclerView.Adapter<ChipAdapter.VH>() {

    private var labels: List<String> = emptyList()
    var selected = 0
        private set

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<String>, select: Int = 0) {
        labels = list
        selected = select.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        notifyDataSetChanged()
    }

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_group, parent, false) as TextView
        val vh = VH(tv)
        tv.setOnClickListener {
            val p = vh.bindingAdapterPosition
            if (p == RecyclerView.NO_POSITION || p == selected) return@setOnClickListener
            val old = selected
            selected = p
            notifyItemChanged(old)
            notifyItemChanged(p)
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
    private val onClick: (CatalogItem) -> Unit,
) : RecyclerView.Adapter<PosterAdapter.VH>() {

    var items: List<CatalogItem> = emptyList()
        private set

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<CatalogItem>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val box: View = v.findViewById(R.id.box)
        val img: ImageView = v.findViewById(R.id.img)
        val ph: TextView = v.findViewById(R.id.ph)
        val badge: TextView = v.findViewById(R.id.badge)
        val title: TextView = v.findViewById(R.id.title)
        val year: TextView = v.findViewById(R.id.year)

        init {
            box.clipToOutline = true
            v.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(items[p])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
        val h = VH(v)
        val total = if (parent.width > 0) parent.width - parent.paddingLeft - parent.paddingRight
        else parent.resources.displayMetrics.widthPixels
        val pad = v.paddingLeft + v.paddingRight
        val w = total / spans() - pad
        h.box.layoutParams = h.box.layoutParams.apply { height = (w * 1.5f).toInt() }
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        h.title.text = it.title
        h.year.text = it.year?.toString() ?: ""
        h.badge.text = it.badge
        h.ph.text = it.title
        h.ph.visibility = View.VISIBLE
        if (it.poster != null) {
            h.img.load(it.poster) {
                crossfade(false)
                size(300, 450)
                listener(onSuccess = { _, _ -> h.ph.visibility = View.INVISIBLE })
            }
        } else {
            h.img.dispose()
            h.img.setImageDrawable(null)
        }
    }

    override fun getItemCount() = items.size
}

class EpisodeAdapter(private val onClick: (Episode) -> Unit) :
    RecyclerView.Adapter<EpisodeAdapter.VH>() {

    private var items: List<Episode> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Episode>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.num)
        val title: TextView = v.findViewById(R.id.title)
        val badge: TextView = v.findViewById(R.id.badge)

        init {
            v.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(items[p])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.num.text = e.number.toString()
        h.title.text = e.title ?: "Episodio ${e.number}"
        h.badge.text = Origin.entries.filter { o -> e.sources.any { it.origin == o } }
            .joinToString(" · ") { it.label }
    }

    override fun getItemCount() = items.size
}
