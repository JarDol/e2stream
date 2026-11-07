package pl.jardol.e2stream

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Bouquet(val name: String, val ref: String)

class Channel(val num: Int, val name: String, val ref: String) {
    @Volatile
    var now: String = ""
}

/** Minimalny klient API OpenWebif (OpenPLi). Wywoływać poza wątkiem UI. */
class OpenWebif(private val p: Prefs) {

    companion object {
        /** Klucz do porównywania referencji serwisów (różne formy końcówki ':'). */
        fun key(ref: String) = ref.trim().trimEnd(':').uppercase()
    }

    fun authHeader(): String? {
        if (p.user.isBlank()) return null
        val raw = "${p.user}:${p.pass}".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun get(path: String): JSONObject {
        val c = URL("http://${p.host}:${p.httpPort}$path").openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 20000
        authHeader()?.let { c.setRequestProperty("Authorization", it) }
        try {
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(body)
        } finally {
            c.disconnect()
        }
    }

    /** Bukiety TV. */
    fun bouquets(): List<Bouquet> {
        val arr = get("/api/getservices").optJSONArray("services") ?: return emptyList()
        val out = ArrayList<Bouquet>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ref = o.optString("servicereference")
            if (ref.isEmpty()) continue
            out.add(Bouquet(o.optString("servicename", ref).trim(), ref))
        }
        return out
    }

    /** Kanały bukietu (bez markerów i podkatalogów). */
    fun channels(b: Bouquet): List<Channel> {
        val arr = get("/api/getservices?sRef=" + enc(b.ref)).optJSONArray("services")
            ?: return emptyList()
        val out = ArrayList<Channel>()
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ref = o.optString("servicereference")
            if (ref.isEmpty()) continue
            val flags = ref.split(":").getOrNull(1)?.toIntOrNull() ?: 0
            if ((flags and 64) != 0) continue // marker
            if ((flags and 1) != 0) continue  // katalog / podbukiet
            n++
            val pos = o.optInt("pos", 0)
            out.add(Channel(if (pos > 0) pos else n, o.optString("servicename", ref).trim(), ref))
        }
        return out
    }

    /** Bieżące audycje dla całego bukietu: klucz(sref) -> tytuł. */
    fun epgNow(b: Bouquet): Map<String, String> {
        val arr = get("/api/epgnow?bRef=" + enc(b.ref)).optJSONArray("events") ?: return emptyMap()
        val m = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("title")
            val s = o.optString("sref")
            if (t.isNotBlank() && s.isNotBlank()) m[key(s)] = t
        }
        return m
    }

    /** Bieżąca audycja jednego kanału. */
    fun serviceNow(ref: String): String? {
        val arr = get("/api/epgservicenow?sRef=" + enc(ref)).optJSONArray("events") ?: return null
        val o = arr.optJSONObject(0) ?: return null
        return o.optString("title").takeIf { it.isNotBlank() }
    }

    fun streamUrl(c: Channel): String =
        "http://${p.host}:${p.streamPort}/" + c.ref.replace(" ", "%20")
}
