package com.zap.iptv

import android.util.JsonReader
import coil.request.ImageRequest
import android.util.JsonToken
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class HttpException(val code: Int) : IOException("HTTP $code")

object Http {
    const val UA = "Mozilla/5.0 (Linux; Android) ZapIPTV/1.2"

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = 20_000): String =
        open("GET", url, headers, timeout) { it.readBytes() }.toString(Charsets.UTF_8)

    fun post(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = 20_000): String =
        open("POST", url, headers, timeout) { it.readBytes() }.toString(Charsets.UTF_8)

    /** Lee la respuesta sin cargarla entera en memoria. */
    fun <T> stream(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = 60_000, body: (InputStream) -> T): T =
        open("GET", url, headers, timeout, body)

    fun put(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = 20_000): String =
        open("PUT", url, headers, timeout) { it.readBytes() }.toString(Charsets.UTF_8)

    fun download(url: String, file: File, headers: Map<String, String> = emptyMap(), timeout: Int = 90_000) {
        open("GET", url, headers, timeout) { input -> file.outputStream().use { input.copyTo(it) } }
    }

    private fun <T> open(
        method: String,
        url: String,
        headers: Map<String, String>,
        timeout: Int,
        body: (InputStream) -> T,
    ): T {
        var u = URL(url)
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = timeout.coerceAtMost(15_000)
            c.readTimeout = timeout
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (method == "POST" || method == "PUT") {
                c.doOutput = true
                c.setFixedLengthStreamingMode(0)
                c.outputStream.close()
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("Redirección sin destino")
                    u = URL(u, loc)
                    return@repeat
                }
                if (code !in 200..299) throw HttpException(code)
                return c.inputStream.use(body)
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Demasiadas redirecciones")
    }
}

/** Valor de texto o null (también cuando el JSON trae null o ""). */
fun JSONObject.str(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

/**
 * Recorre un array JSON enorme de objetos sin cargarlo en memoria, entregando
 * solo los campos indicados (como texto). El mapa se reutiliza: no guardarlo.
 */
fun forEachFlatObject(file: File, fields: Set<String>, onItem: (Map<String, String>) -> Unit) {
    if (!file.exists()) return
    try {
        JsonReader(InputStreamReader(BufferedInputStream(FileInputStream(file), 64 * 1024), Charsets.UTF_8)).use { r ->
            r.isLenient = true
            if (r.peek() != JsonToken.BEGIN_ARRAY) return
            r.beginArray()
            val m = HashMap<String, String>(16)
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue()
                    continue
                }
                m.clear()
                r.beginObject()
                while (r.hasNext()) {
                    val name = r.nextName()
                    if (name in fields) {
                        when (r.peek()) {
                            JsonToken.STRING, JsonToken.NUMBER -> m[name] = r.nextString()
                            JsonToken.BOOLEAN -> m[name] = r.nextBoolean().toString()
                            // Listas de valores simples (p. ej. category_ids: [12, 45]) → "12,45"
                            JsonToken.BEGIN_ARRAY -> {
                                val parts = ArrayList<String>(4)
                                r.beginArray()
                                while (r.hasNext()) {
                                    when (r.peek()) {
                                        JsonToken.STRING, JsonToken.NUMBER -> parts += r.nextString()
                                        else -> r.skipValue()
                                    }
                                }
                                r.endArray()
                                m[name] = parts.joinToString(",")
                            }
                            else -> r.skipValue()
                        }
                    } else {
                        r.skipValue()
                    }
                }
                r.endObject()
                onItem(m)
            }
            r.endArray()
        }
    } catch (e: Exception) {
        // JSON roto o cortado: nos quedamos con lo leído hasta ahí
    }
}

private val HOST = Regex("^https?://[^/]+")
private val TOKEN = Regex("[&?]X-Plex-Token=[^&]*")

/**
 * Clave estable para la caché de imágenes: en Plex la dirección del servidor y el token
 * pueden cambiar, pero la imagen es la misma. Así no se vuelve a descargar.
 */
fun imageKey(url: String): String =
    if ("X-Plex-Token" in url) url.replace(HOST, "plex").replace(TOKEN, "") else url

fun ImageRequest.Builder.stableCache(url: String?) {
    if (url == null) return
    val k = imageKey(url)
    memoryCacheKey(k)
    diskCacheKey(k)
}
