package pl.jardol.e2stream

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Aktualizacja z GitHub Releases (JarDol/e2stream). Metody sieciowe poza wątkiem UI. */
object Updater {

    private const val API = "https://api.github.com/repos/JarDol/e2stream/releases/latest"

    data class Info(val version: String, val url: String, val name: String)

    fun latest(): Info? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "E2Stream")
        try {
            if (c.responseCode != 200) throw IOException("GitHub HTTP ${c.responseCode}")
            val js = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = js.optString("tag_name").removePrefix("v")
            val assets = js.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.endsWith(".apk")) return Info(tag, a.optString("browser_download_url"), name)
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    /** true, jeśli [a] jest nowsza niż [b] (porównanie X.Y.Z). */
    fun newer(a: String, b: String): Boolean {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    fun download(ctx: Context, info: Info, progress: (Int) -> Unit): File {
        val dir = ctx.externalCacheDir ?: ctx.cacheDir
        val out = File(dir, info.name)
        var url = URL(info.url)
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "E2Stream")
            val code = c.responseCode
            if (code in 300..399 && hops < 5) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Brak przekierowania")
                c.disconnect()
                url = URL(url, loc)
                hops++
                continue
            }
            if (code != 200) throw IOException("HTTP $code")
            break
        }
        try {
            val total = c.contentLength.toLong()
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var sum = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        sum += n
                        if (total > 0) {
                            val pct = (sum * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; progress(pct) }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return out
    }

    /** Uruchamia instalator. Zwraca false, gdy trzeba najpierw zezwolić na instalację z nieznanych źródeł. */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) { }
            return false
        }
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        } else {
            Uri.fromFile(apk)
        }
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return true
    }
}
