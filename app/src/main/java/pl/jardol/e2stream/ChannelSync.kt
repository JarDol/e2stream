package pl.jardol.e2stream

import org.json.JSONObject

/**
 * Dane kanałów (zapamiętane PMT, wybrane ścieżki dźwięku) we wspólnym pliku na dekoderze:
 * <katalog FTP>/e2stream-channels.json — współdzielone przez wszystkie urządzenia.
 */
object ChannelSync {
    const val FILE = "e2stream-channels.json"

    private fun path(prefs: Prefs) = prefs.ftpDir.trimEnd('/') + "/" + FILE

    private fun toJson(data: Map<String, String>): JSONObject {
        val js = JSONObject().put("version", 1).put("updated", System.currentTimeMillis())
        val d = JSONObject()
        for ((k, v) in data) d.put(k, v)
        return js.put("data", d)
    }

    private fun fromJson(text: String): Map<String, String> {
        val d = JSONObject(text).optJSONObject("data") ?: return emptyMap()
        val out = HashMap<String, String>()
        for (k in d.keys()) if (Prefs.isChannelKey(k)) out[k] = d.optString(k)
        return out
    }

    /** Pobiera plik z dekodera (null = brak pliku). */
    fun fetchRemote(prefs: Prefs): Map<String, String>? {
        FtpSession(prefs.host, 21, prefs.ftpUser, prefs.ftpPass).use { ftp ->
            val p = path(prefs)
            val bytes = ftp.retr(p) ?: ftp.retr(p.trimStart('/')) ?: return null
            return fromJson(bytes.toString(Charsets.UTF_8))
        }
    }

    /** Podsumowanie danych: "PMT: n, dźwięk: m". */
    fun describe(m: Map<String, String>): String {
        val pmt = m.keys.count { it.startsWith("pmts_") }
        val aud = m.keys.count { it.startsWith("audio_") }
        return "PMT kanałów: $pmt, ścieżek dźwięku: $aud"
    }

    /** Scala z danymi z dekodera (lokalne wygrywają) i wysyła. Zwraca opis. */
    fun upload(prefs: Prefs): String {
        val remote = try { fetchRemote(prefs) } catch (_: Exception) { null } ?: emptyMap()
        val merged = HashMap(remote)
        merged.putAll(prefs.channelData())
        val bytes = toJson(merged).toString().toByteArray(Charsets.UTF_8)
        FtpUpload.upload(prefs.host, 21, prefs.ftpUser, prefs.ftpPass, prefs.ftpDir, FILE, bytes)
        prefs.chanDirty = false
        return describe(merged)
    }

    /** Pobiera z dekodera i zapisuje lokalnie (bez nadpisywania lokalnych wartości, chyba że [overwrite]). */
    fun download(prefs: Prefs, overwrite: Boolean = false): String? {
        val remote = fetchRemote(prefs) ?: return null
        val local = prefs.channelData()
        var n = 0
        for ((k, v) in remote) if (overwrite || k !in local) { prefs.putChannelData(k, v); n++ }
        return "pobrano $n wpisów (${describe(remote)})"
    }
}
