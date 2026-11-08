package com.zap.iptv

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Fila horizontal que recuerda en qué carátula estabas.
 * Al subir o bajar con el mando desde otra fila, el foco vuelve a esa carátula
 * (o a la primera visible) en vez de buscar la más cercana en pantalla, que con
 * cada salto se iba corriendo hacia la derecha.
 */
class RowList @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {

    var remembered = 0

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (child != null) {
            val p = getChildAdapterPosition(child)
            if (p != NO_POSITION) remembered = p
        }
    }

    private fun isFullyShown(v: View) = v.left >= paddingLeft - 1 && v.right <= width - paddingRight + 1
}
