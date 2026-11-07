package com.zap.iptv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Busca la última APK publicada en GitHub y la instala. */
object Updater {
    private const val API = "https://api.github.com/repos/YipikayeiGit/ZapTV/releases/latest"

    data class Release(val build: Long, val url: String, val size: Long)

    fun latest(): Release? {
        val j = JSONObject(Http.get(API, mapOf("Accept" to "application/vnd.github+json")))
        val build = j.optString("tag_name").substringAfter("build-").toLongOrNull() ?: return null
        val assets = j.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name").endsWith(".apk")) {
                return Release(build, a.getString("browser_download_url"), a.optLong("size"))
            }
        }
        return null
    }

    fun currentBuild(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    fun versionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    /** Descarga la APK informando del progreso (0–100). */
    fun download(ctx: Context, r: Release, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "Zap.apk")
        val c = URL(r.url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", Http.UA)
        try {
            if (c.responseCode !in 200..299) throw HttpException(c.responseCode)
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) {
                                last = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        if (file.length() == 0L) throw IOException("descarga vacía")
        return file
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Abre el ajuste "Instalar apps desconocidas" para Zap. Devuelve false si el sistema no lo tiene. */
    fun openInstallPermission(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        return try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    fun install(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
