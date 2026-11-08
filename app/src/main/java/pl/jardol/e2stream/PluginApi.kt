package pl.jardol.e2stream

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * API wtyczki EPG Scan na dekoderze (moduł „serwer dla klientów”, domyślnie port 8770).
 * Gdy wtyczka jest — bukiety, kanały z PID-ami i synchronizacja idą przez nią, bez loginów do OpenWebif/FTP.
 */
class PluginApi(private val p: Prefs) {

    private fun url(path: String) = "http://${p.host}:${p.pluginPort}$path"

    private fun open(path: String, timeout: Int): HttpURLConnection {
        val c = URL(url(path)).openConnection() as HttpURLConnection
        c.connectTimeout = minOf(timeout, 4000)
        c.readTimeout = timeout
        if (p.pluginToken.isNotBlank()) c.setRequestProperty("X-E2S-Token", p.pluginToken)
        return c
    }

    private fun get(path: String, timeout: Int = 15000): JSONObject {
        val c = open(path, timeout)
        try {
            if (c.responseCode != 200) throw IOException("EPG Scan HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val c = open(path, 20000)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) throw IOException("EPG Scan HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Informacja o wtyczce albo null, gdy jej nie ma. */
    fun info(): JSONObject? = try {
        get("/api/info", 3000).takeIf { it.optInt("api") >= 1 }
    } catch (_: Exception) { null }

    fun bouquets(): List<Bouquet> {
        val arr = get("/api/bouquets").optJSONArray("bouquets") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Bouquet(OpenWebif.unescape(o.optString("name")), o.optString("ref"))
        }
    }

    /** Kanały bukietu; PID-y z lamedb zapisuje w ustawieniach (do budowy PMT). */
    fun channels(b: Bouquet): List<Channel> {
        val arr = get("/api/services?bRef=" + enc(b.ref), 30000).optJSONArray("services") ?: JSONArray()
        val out = ArrayList<Channel>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val play = o.optString("play").ifBlank { o.optString("ref") }
            if (play.isBlank()) continue
            o.optJSONObject("pids")?.let { pids ->
                OpenWebif.sidOf(play)?.let { sid -> p.setPidsFor(sid, pids.toString()) }
            }
            out.add(Channel(o.optInt("num", i + 1), OpenWebif.unescape(o.optString("name")).trim(), play))
        }
        return out
    }

    fun syncState(id: String): JSONObject = get("/api/sync/state?id=" + enc(id))

    fun syncPush(id: String, body: JSONObject): JSONObject = post("/api/sync/push?id=" + enc(id), body)

    fun devices(): JSONArray = get("/api/sync/devices").optJSONArray("devices") ?: JSONArray()

    companion object {
        /** Sprawdza wtyczkę i zapamiętuje wynik w ustawieniach. */
        fun check(p: Prefs): Boolean {
            if (p.host.isBlank()) return false
            val info = PluginApi(p).info()
            val ok = info != null
            if (ok != p.pluginOk || (info?.optString("version") ?: "") != p.pluginVersion) {
                AppLog.i(if (ok) "EPG Scan na dekoderze: ${info?.optString("version")} (API ${info?.optInt("api")})"
                else "EPG Scan na dekoderze: brak (port ${p.pluginPort})")
            }
            p.pluginOk = ok
            p.pluginVersion = info?.optString("version") ?: ""
            return ok
        }
    }
}

/** Buduje sekcję PMT z PID-ów zapamiętanych przez Enigma2 (lamedb), gdy dekoder nie wysyła PMT. */
object PmtBuilder {
    fun build(sid: Int, json: String?): ByteArray? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val es = java.io.ByteArrayOutputStream()
            fun entry(type: Int, pid: Int, desc: ByteArray = ByteArray(0)) {
                es.write(type)
                es.write(0xe0 or ((pid shr 8) and 0x1f)); es.write(pid and 0xff)
                es.write(0xf0 or ((desc.size shr 8) and 0x0f)); es.write(desc.size and 0xff)
                es.write(desc)
            }
            val video = o.optInt("video", -1)
            if (video > 0) {
                val st = when (o.optInt("vtype", 0)) {
                    0 -> 0x02; 1 -> 0x1b; 3 -> 0x10; 4 -> 0xea; 6 -> 0x01; 7 -> 0x24; else -> 0x1b
                }
                entry(st, video)
            }
            val audio = o.optJSONArray("audio")
            if (audio != null) for (i in 0 until audio.length()) {
                val a = audio.optJSONObject(i) ?: continue
                val pid = a.optInt("pid", -1)
                if (pid <= 0) continue
                when (a.optString("type")) {
                    "mpeg" -> entry(0x03, pid)
                    "ac3" -> entry(0x06, pid, byteArrayOf(0x6a, 1, 0))
                    "eac3" -> entry(0x06, pid, byteArrayOf(0x7a, 1, 0))
                    "aac" -> entry(0x0f, pid)
                    "latm" -> entry(0x11, pid)
                }
            }
            val esb = es.toByteArray()
            if (esb.isEmpty()) return null
            val pcr = o.optInt("pcr", if (video > 0) video else 0x1fff)
            val sl = 9 + esb.size + 4
            val sec = java.io.ByteArrayOutputStream()
            sec.write(0x02)
            sec.write(0xb0 or ((sl shr 8) and 0x0f)); sec.write(sl and 0xff)
            sec.write((sid shr 8) and 0xff); sec.write(sid and 0xff)
            sec.write(0xc1)            // wersja 0, current_next = 1
            sec.write(0); sec.write(0) // section_number, last_section_number
            sec.write(0xe0 or ((pcr shr 8) and 0x1f)); sec.write(pcr and 0xff)
            sec.write(0xf0); sec.write(0)  // program_info_length = 0
            sec.write(esb)
            sec.write(byteArrayOf(0, 0, 0, 0)) // CRC liczone przy wstawianiu
            sec.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
}
