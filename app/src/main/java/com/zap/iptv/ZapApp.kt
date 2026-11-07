package com.zap.iptv

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Si la app se cierra por un error, guarda el motivo para enseñarlo al volver a abrirla. */
class ZapApp : Application() {
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
