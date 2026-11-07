package com.zap.iptv

import android.content.Context
import android.net.Uri
import java.io.IOException

object Source {
    const val UA = Http.UA

    /** Lee una lista desde una URL http(s) o un archivo del dispositivo. */
    fun read(ctx: Context, src: String): String {
        if (src.startsWith("content:") || src.startsWith("file:")) {
            return ctx.contentResolver.openInputStream(Uri.parse(src))
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IOException("No se pudo abrir el archivo")
        }
        return Http.get(src, timeout = 60_000)
    }
}
