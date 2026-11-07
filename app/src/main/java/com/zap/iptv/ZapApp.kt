package com.zap.iptv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Si la app se cierra por un error, guarda el motivo para enseñarlo al volver a abrirla. */
class ZapApp : Application(), ImageLoaderFactory {

    /**
     * Imágenes guardadas en el dispositivo (hasta 300 MB) aunque el servidor diga que no se guarden:
     * al abrir la app las carátulas salen al instante en vez de pedirlas otra vez a Plex.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("imagenes"))
                .maxSizeBytes(300L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .crossfade(false)
        .build()

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val version = runCatching { Updater.versionName(this) }.getOrDefault("?")
                crashFile(this).writeText(
                    "Zap $version · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n\n$sw"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        fun crashFile(app: Application) = File(app.filesDir, "ultimo_error.txt")

        /** Devuelve el último error (y lo borra) o null si no hubo ninguno. */
        fun takeCrash(app: Application): String? {
            val f = crashFile(app)
            if (!f.exists()) return null
            val text = runCatching { f.readText() }.getOrNull()
            f.delete()
            return text
        }
    }
}
