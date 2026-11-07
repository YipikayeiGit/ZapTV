package com.zap.iptv

import android.util.JsonReader
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
            if (method == "POST") {
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
 * Lee un array JSON enorme de objetos sin cargarlo entero en memoria,
 * quedándose solo con los campos indicados (como texto).
 */
fun readFlatObjects(file: File, fields: Set<String>): List<Map<String, String>> {
    if (!file.exists()) return emptyList()
    return try {
        JsonReader(InputStreamReader(BufferedInputStream(FileInputStream(file)), Charsets.UTF_8)).use { r ->
            r.isLenient = true
            val out = ArrayList<Map<String, String>>()
            if (r.peek() != JsonToken.BEGIN_ARRAY) {
                r.skipValue()
                return@use out
            }
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue()
                    continue
                }
                r.beginObject()
                val m = HashMap<String, String>(8)
                while (r.hasNext()) {
                    val name = r.nextName()
                    if (name in fields) {
                        when (r.peek()) {
                            JsonToken.STRING, JsonToken.NUMBER -> m[name] = r.nextString()
                            JsonToken.BOOLEAN -> m[name] = r.nextBoolean().toString()
                            else -> r.skipValue()
                        }
                    } else {
                        r.skipValue()
                    }
                }
                r.endObject()
                out += m
            }
            r.endArray()
            out
        }
    } catch (e: Exception) {
        emptyList()
    }
}
