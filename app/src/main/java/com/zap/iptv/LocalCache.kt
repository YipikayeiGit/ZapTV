package com.zap.iptv

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/**
 * Descarga el archivo entero en segundo plano (lo más rápido que da la conexión) y se lo
 * sirve al reproductor desde el propio aparato a través de un mini servidor local.
 *
 * Los AVI antiguos obligan al reproductor a saltar constantemente de un sitio a otro del
 * archivo; por internet cada salto es una petición nueva y se queda sin datos cada pocos
 * minutos. Leyéndolo del aparato no pasa: es lo que hace en la práctica la app de Plex
 * al guardar mucho vídeo por adelantado.
 */
class LocalCache(private val remote: String, private val file: File) {
    @Volatile var total = -1L
        private set
    @Volatile var downloaded = 0L
        private set
    @Volatile private var closed = false
    @Volatile private var dlEnded = false
    private val lock = Object()
    private var server: ServerSocket? = null

    val done get() = total > 0 && downloaded >= total
    @Volatile private var note: String? = null

    fun status(): String = note ?: when {
        total <= 0 -> "guardando en el aparato…"
        else -> "desde el aparato · guardado ${downloaded * 100 / total}% (${downloaded / 1_048_576} de ${total / 1_048_576} MB)"
    }

    /** Arranca descarga y servidor; devuelve la dirección local para el reproductor. */
    fun start(): String {
        file.delete()
        thread(name = "zap-cache-dl", isDaemon = true) { download() }
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(name = "zap-cache-srv", isDaemon = true) {
            while (!closed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(c) }; runCatching { c.close() } }
            }
        }
        val ext = remote.substringBefore('?').substringAfterLast('.', "avi").take(4)
        return "http://127.0.0.1:${s.localPort}/video.$ext"
    }

    fun close() {
        closed = true
        runCatching { server?.close() }
        synchronized(lock) { lock.notifyAll() }
        thread(isDaemon = true) {
            Thread.sleep(1500)
            file.delete()
        }
    }

    private fun open(from: Long, to: Long? = null): HttpURLConnection {
        val c = URL(remote).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", Http.UA)
        c.setRequestProperty("Range", "bytes=$from-${to ?: ""}")
        return c
    }

    private fun download() {
        var errors = 0
        RandomAccessFile(file, "rw").use { out ->
            while (!closed && !done && errors < 30) {
                try {
                    val c = open(downloaded)
                    val code = c.responseCode
                    if (code != 206 && code != 200) throw java.io.IOException("HTTP $code")
                    if (total < 0) {
                        total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                            ?: c.contentLengthLong.takeIf { it > 0 }?.plus(downloaded) ?: -1L
                    }
                    if (code == 200 && downloaded > 0) downloaded = 0 // el servidor no admite trozos
                    if (total > 0 && file.parentFile!!.usableSpace < total - downloaded + 200L * 1024 * 1024) {
                        note = "directa (el archivo ocupa ${total / 1_048_576} MB y no cabe en el aparato)"
                        c.disconnect()
                        break
                    }
                    c.inputStream.use { inp ->
                        val buf = ByteArray(256 * 1024)
                        while (!closed) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.seek(downloaded)
                            out.write(buf, 0, n)
                            downloaded += n
                            errors = 0
                            synchronized(lock) { lock.notifyAll() }
                        }
                    }
                    if (total < 0) total = downloaded
                } catch (e: Exception) {
                    errors++
                    if (errors >= 30) note = "directa (la descarga falla: ${e.message ?: e.javaClass.simpleName})"
                    if (!closed) Thread.sleep(2_000)
                }
            }
        }
        dlEnded = true
        synchronized(lock) { lock.notifyAll() }
    }

    /** Espera a que lo descargado pase de [pos] (o se acabe). */
    private fun waitFor(pos: Long): Boolean {
        synchronized(lock) {
            while (!closed && !dlEnded && downloaded <= pos && !(total in 0..pos)) lock.wait(1_000)
        }
        return !closed && downloaded > pos
    }

    private fun serve(c: Socket) {
        c.soTimeout = 60_000
        val reader = BufferedReader(InputStreamReader(c.getInputStream()))
        val first = reader.readLine() ?: return
        var range: String? = null
        while (true) {
            val l = reader.readLine() ?: break
            if (l.isEmpty()) break
            if (l.startsWith("Range:", true)) range = l.substringAfter(':').trim()
        }
        val head = first.startsWith("HEAD")
        // Hasta saber el tamaño no se puede contestar
        var waited = 0
        while (total < 0 && !closed && waited < 300) {
            Thread.sleep(100)
            waited++
        }
        val size = total
        val out = c.getOutputStream()
        if (size <= 0) {
            out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        val spec = range?.substringAfter("bytes=")
        val start = spec?.substringBefore('-')?.toLongOrNull() ?: 0L
        val end = spec?.substringAfter('-')?.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
        if (start >= size) {
            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        val len = end - start + 1
        val status = if (range != null) "206 Partial Content" else "200 OK"
        val headers = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: video/x-msvideo\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $len\r\n")
            if (range != null) append("Content-Range: bytes $start-$end/$size\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray())
        if (head) return
        // Muy por delante de lo descargado (p. ej. el índice al final del AVI): se pide directo
        if (start > downloaded + 32L * 1024 * 1024) {
            passThrough(start, end, out)
            return
        }
        RandomAccessFile(file, "r").use { f ->
            val buf = ByteArray(256 * 1024)
            var pos = start
            while (pos <= end && !closed) {
                if (pos >= downloaded && !waitFor(pos)) {
                    // La descarga se ha detenido (sin espacio, errores…): el resto, directo de internet
                    if (dlEnded && !closed && pos <= end) passThrough(pos, end, out)
                    break
                }
                val avail = minOf(downloaded, end + 1) - pos
                if (avail <= 0) continue
                f.seek(pos)
                val n = f.read(buf, 0, minOf(avail, buf.size.toLong()).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                pos += n
            }
        }
        out.flush()
    }

    private fun passThrough(start: Long, end: Long, out: OutputStream) {
        val c = open(start, end)
        c.inputStream.use { inp: InputStream ->
            val buf = ByteArray(128 * 1024)
            while (!closed) {
                val n = inp.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        out.flush()
    }
}
