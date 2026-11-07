package com.zap.iptv

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Mini servidor web en la red local. Solo está encendido mientras el diálogo
 * "Añadir lista" está abierto. Sirve una página para enviar la lista desde el móvil.
 */
class LanServer(private val onPayload: (String) -> Unit) {

    @Volatile
    private var server: ServerSocket? = null
    val port: Int get() = server?.localPort ?: -1

    fun start(): Boolean {
        val s = try {
            ServerSocket(8080)
        } catch (e: IOException) {
            try { ServerSocket(0) } catch (e2: IOException) { return false }
        }
        server = s
        thread(isDaemon = true, name = "zap-lan") {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: IOException) { break }
                thread(isDaemon = true) { runCatching { handle(c) } }
            }
        }
        return true
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(socket: Socket) {
        socket.use { c ->
            c.soTimeout = 30_000
            val input = BufferedInputStream(c.getInputStream())
            val out = c.getOutputStream()
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val parts = lines.first().split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            val length = lines.drop(1)
                .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0

            when {
                method == "GET" && path == "/" ->
                    respond(out, 200, "text/html; charset=utf-8", PAGE)

                method == "POST" && path == "/send" -> {
                    if (length <= 0 || length > MAX_BYTES) {
                        respond(out, 413, TEXT, "Vacío o demasiado grande")
                        return
                    }
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(body, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                    val text = String(body, 0, read, Charsets.UTF_8)
                    if (text.isBlank()) {
                        respond(out, 400, TEXT, "Vacío")
                        return
                    }
                    respond(out, 200, TEXT, "OK")
                    onPayload(text)
                }

                else -> respond(out, 404, TEXT, "No encontrado")
            }
        }
    }

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var match = 0
        while (buf.size() < 16_384) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            match = when {
                b == '\r'.code && (match == 0 || match == 2) -> match + 1
                b == '\n'.code && (match == 1 || match == 3) -> match + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (match == 4) return buf.toString("ISO-8859-1")
        }
        return null
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 413 -> "Payload Too Large"
            else -> "Error"
        }
        val header = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    companion object {
        private const val TEXT = "text/plain; charset=utf-8"
        private const val MAX_BYTES = 60 * 1024 * 1024

        /** IP de la tele en la red local (wifi o cable). */
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()

        private val PAGE = """
<!doctype html>
<html lang="es"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Enviar lista a Zap</title>
<style>
*{box-sizing:border-box}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#0E0E10;color:#F2F2F4;font:16px system-ui,-apple-system,sans-serif;padding:20px}
.c{width:100%;max-width:420px}
h1{font-size:22px;margin:0 0 6px}
p{color:#8A8A94;margin:0 0 22px;font-size:15px}
label{display:block;font-size:13px;color:#8A8A94;margin:18px 0 8px}
textarea,input[type=file]{width:100%;background:#1A1A1F;color:#F2F2F4;border:1px solid #2A2A32;
border-radius:12px;padding:14px;font:inherit}
textarea{height:96px;resize:none}
button{width:100%;margin-top:24px;padding:16px;border:0;border-radius:12px;background:#FF5A4E;
color:#0E0E10;font:600 17px system-ui,sans-serif}
button:disabled{opacity:.5}
#m{margin-top:16px;text-align:center;min-height:22px}
</style></head><body><div class="c">
<h1>Enviar lista a la tele</h1>
<p>Pega el enlace de tu lista M3U o elige el archivo.</p>
<label for="u">Enlace</label>
<textarea id="u" placeholder="http://…" autocapitalize="off" autocorrect="off" spellcheck="false"></textarea>
<label for="f">o archivo .m3u</label>
<input id="f" type="file" accept=".m3u,.m3u8,.txt,audio/x-mpegurl,application/x-mpegurl">
<button id="b" onclick="send()">Enviar a la tele</button>
<div id="m"></div>
</div>
<script>
function msg(t){document.getElementById('m').textContent=t}
async function send(){
  var b=document.getElementById('b');
  var f=document.getElementById('f').files[0];
  var body=document.getElementById('u').value.trim();
  if(f) body=await f.text();
  if(!body){msg('Pega un enlace o elige un archivo');return}
  b.disabled=true;msg('Enviando…');
  try{
    var r=await fetch('/send',{method:'POST',headers:{'Content-Type':'text/plain;charset=utf-8'},body:body});
    msg(r.ok?'✓ Enviada. Ya puedes mirar la tele.':'Error: '+await r.text());
  }catch(e){msg('No se pudo conectar con la tele')}
  b.disabled=false;
}
</script></body></html>
""".trimIndent()
    }
}
