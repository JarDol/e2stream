package pl.jardol.e2stream

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cicha synchronizacja ustawień i danych kanałów z dekoderem (bez „mastera” — dekoder jest wspólnym miejscem).
 *
 * Na dekoderze (<katalog FTP>/sync/):
 *  - common.json          — wartości domyślne dla nowych odbiorników + wspólne PMT kanałów,
 *  - device-<id>.json     — pełne ustawienia każdego odbiornika (nazwa, model, wartości ze znacznikami czasu).
 *
 * Zasady:
 *  - nowy odbiornik przy pierwszym uruchomieniu bierze swój plik (reinstalacja) albo domyślne z common.json,
 *  - każda zmiana trafia do pliku odbiornika; jeśli w common.json nie ma takiego klucza — dopisuje go jako domyślny,
 *  - PMT kanałów zawsze wspólne (wygrywa nowszy wpis),
 *  - ścieżki dźwięku: brakujące lokalnie są przejmowane z części wspólnej (nowe kanały wybrane na innym TV),
 *    a wybór na tym odbiorniku zapisuje się w jego pliku.
 */
object SyncManager {
    private const val SUBDIR = "sync"
    private const val COMMON = "common.json"
    private const val LEGACY = "e2stream-channels.json"

    /** Ustawienia synchronizowane (bez adresu dekodera, ścieżek lokalnych i stanu). */
    private val SETTINGS = setOf(
        "httpPort", "streamPort", "user", "pass",
        "tunneling", "watchdog", "stallSec", "bufferMs", "rebufferMs", "afrMode", "dropRestart",
        "audioLang", "preferAc3",
        "logFtp", "ftpUser", "ftpPass", "ftpDir", "saveTs", "tsRingMb", "tsFtp", "cleanTs",
        "hiddenBouquets", "pin", "invertUpDown", "bannerSec", "resumeLast",
        "startBouquet", "startChannel", "startChannelName", "checkUpdates", "keyMap",
        "epgDaily", "epgHour", "epgDays", "picons", "piconDir", "showStats",
    )

    /** Przy pierwszym uruchomieniu nie nadpisujemy tego, co użytkownik właśnie wpisał. */
    private val KEEP_ON_FIRST = setOf("ftpUser", "ftpPass", "ftpDir")

    fun isSetting(k: String) = k in SETTINGS
    fun isAudio(k: String) = k.startsWith("audio_")
    fun isPmt(k: String) = k.startsWith("pmts_")
    private fun isDeviceKey(k: String) = isSetting(k) || isAudio(k)

    private lateinit var app: Context
    private lateinit var sp: SharedPreferences
    private lateinit var meta: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var applying = false
    @Volatile var running = false
        private set
    @Volatile var lastResult = ""
        private set

    /** Wywoływane (na wątku UI), gdy synchronizacja zmieniła ustawienia odtwarzania. */
    var onSettingsApplied: (() -> Unit)? = null

    private val soon = Runnable { syncAsync() }

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || applying) return@OnSharedPreferenceChangeListener
        if (isDeviceKey(key) || isPmt(key)) {
            meta.edit().putLong("t_$key", System.currentTimeMillis()).putBoolean("dirty", true).apply()
            ui.removeCallbacks(soon)
            ui.postDelayed(soon, 5000)
        }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        sp = app.getSharedPreferences("e2stream", Context.MODE_PRIVATE)
        meta = app.getSharedPreferences("e2sync", Context.MODE_PRIVATE)
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    @SuppressLint("HardwareIds")
    fun deviceId(): String =
        (Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: Build.MODEL)
            .replace(Regex("[^A-Za-z0-9_-]"), "_")

    val dirty: Boolean get() = meta.getBoolean("dirty", false)
    val lastSyncAt: Long get() = meta.getLong("lastSync", 0)

    fun syncAsync(done: ((String) -> Unit)? = null) {
        if (!::app.isInitialized || running) return
        val prefs = Prefs(app)
        if (!prefs.channelSync || prefs.host.isBlank()) return
        running = true
        Thread {
            val r = try { sync(prefs) } catch (e: Exception) { "błąd: ${e.message ?: e}" }
            lastResult = r
            AppLog.i("synchronizacja: $r")
            running = false
            done?.let { cb -> ui.post { cb(r) } }
        }.start()
    }

    // ------------------------------------------------------------ wartości

    private fun toJson(v: Any?): Any? = when (v) {
        is Set<*> -> JSONArray(v.map { it.toString() })
        is Boolean, is Int, is Long, is String -> v
        else -> null
    }

    private fun put(e: SharedPreferences.Editor, k: String, v: Any?) {
        when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putInt(k, v.toInt())
            is Double -> e.putInt(k, v.toInt())
            is String -> e.putString(k, v)
            is JSONArray -> e.putStringSet(k, (0 until v.length()).map { v.optString(it) }.toSet())
        }
    }

    private fun entry(v: Any?, t: Long) = JSONObject().put("v", toJson(v)).put("t", t)

    private fun dirPath(prefs: Prefs) = prefs.ftpDir.trimEnd('/') + "/" + SUBDIR

    private fun read(ftp: FtpSession, path: String): JSONObject? {
        val b = ftp.retr(path) ?: ftp.retr(path.trimStart('/')) ?: return null
        return try { JSONObject(b.toString(Charsets.UTF_8)) } catch (_: Exception) { null }
    }

    private fun upload(prefs: Prefs, name: String, js: JSONObject) {
        FtpUpload.upload(prefs.host, 21, prefs.ftpUser, prefs.ftpPass, dirPath(prefs), name,
            js.toString(1).toByteArray(Charsets.UTF_8))
    }

    // ------------------------------------------------------------ synchronizacja

    @Synchronized
    private fun sync(prefs: Prefs): String {
        val dir = dirPath(prefs)
        val devName = "device-${deviceId()}.json"
        var common: JSONObject? = null
        var dev: JSONObject? = null
        var legacy: JSONObject? = null
        FtpSession(prefs.host, 21, prefs.ftpUser, prefs.ftpPass).use { ftp ->
            common = read(ftp, "$dir/$COMMON")
            dev = read(ftp, "$dir/$devName")
            if (common == null) legacy = read(ftp, prefs.ftpDir.trimEnd('/') + "/" + LEGACY)
        }
        val now = System.currentTimeMillis()
        val c = common ?: JSONObject()
        val cData = c.optJSONObject("data") ?: JSONObject().also { c.put("data", it) }
        // dane z 0.6.3 (wspólny plik kanałów) — przejmij do części wspólnej
        legacy?.optJSONObject("data")?.let { ld ->
            for (k in ld.keys()) if ((isPmt(k) || isAudio(k)) && !cData.has(k)) cData.put(k, entry(ld.optString(k), now))
        }
        val dData = dev?.optJSONObject("data")

        val first = !meta.getBoolean("initialized", false)
        val local = sp.all
        var applied = 0
        var settingsApplied = false
        val metaEd = meta.edit()
        applying = true
        try {
            val ed = sp.edit()
            if (first) {
                val configured = local.keys.count { isSetting(it) && it !in KEEP_ON_FIRST } >= 3
                if (configured && dData == null) {
                    // istniejąca instalacja sprzed synchronizacji: lokalne ustawienia są ważne
                    for (k in local.keys) if (isDeviceKey(k) || isPmt(k)) metaEd.putLong("t_$k", now)
                } else {
                    // nowy odbiornik: własny plik (reinstalacja) albo domyślne z części wspólnej
                    val src = dData ?: cData
                    for (k in src.keys()) {
                        if (!isDeviceKey(k) || k in KEEP_ON_FIRST) continue
                        val e = src.optJSONObject(k) ?: continue
                        put(ed, k, e.opt("v")); metaEd.putLong("t_$k", e.optLong("t")); applied++
                        if (isSetting(k)) settingsApplied = true
                    }
                }
            } else if (dData != null) {
                // własny plik na dekoderze nowszy (np. zmieniony z innej instalacji tego odbiornika)
                for (k in dData.keys()) {
                    if (!isDeviceKey(k)) continue
                    val e = dData.optJSONObject(k) ?: continue
                    if (e.optLong("t") > meta.getLong("t_$k", 0)) {
                        put(ed, k, e.opt("v")); metaEd.putLong("t_$k", e.optLong("t")); applied++
                        if (isSetting(k)) settingsApplied = true
                    }
                }
            }
            // wspólne: PMT (nowszy wygrywa) i ścieżki dźwięku brakujące lokalnie
            for (k in cData.keys()) {
                val e = cData.optJSONObject(k) ?: continue
                val lt = meta.getLong("t_$k", 0)
                if ((isPmt(k) && (k !in local || e.optLong("t") > lt)) || (isAudio(k) && k !in local)) {
                    put(ed, k, e.opt("v")); metaEd.putLong("t_$k", e.optLong("t")); applied++
                }
            }
            ed.commit()
        } finally {
            applying = false
        }
        metaEd.putBoolean("initialized", true).commit()

        // plik odbiornika: wszystkie jego ustawienia i ścieżki
        val all = sp.all
        val myData = JSONObject()
        for ((k, v) in all) if (isDeviceKey(k)) {
            val j = toJson(v) ?: continue
            myData.put(k, JSONObject().put("v", j).put("t", meta.getLong("t_$k", 0)))
        }
        val myFile = JSONObject()
            .put("device", JSONObject().put("id", deviceId()).put("name", prefs.deviceName)
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}").put("app", Tools.appVersion(app)).put("updated", now))
            .put("data", myData)

        // część wspólna: PMT (nowszy wygrywa), pozostałe tylko gdy brak klucza (domyślne dla nowych)
        var commonChanged = common == null || legacy != null
        for ((k, v) in all) {
            val t = meta.getLong("t_$k", 0)
            val j = toJson(v) ?: continue
            val has = cData.optJSONObject(k)
            val write = when {
                isPmt(k) -> has == null || t > has.optLong("t")
                isDeviceKey(k) -> has == null
                else -> false
            }
            if (write) { cData.put(k, entry(v, t)); commonChanged = true }
        }

        if (first || dirty || dev == null || applied > 0) upload(prefs, devName, myFile)
        if (commonChanged) {
            c.put("updated", now)
            upload(prefs, COMMON, c)
        }
        meta.edit().putBoolean("dirty", false).putLong("lastSync", now).apply()

        if (settingsApplied) ui.post { prefs.bump(); onSettingsApplied?.invoke() }
        return "pobrano $applied, wspólnych ${cData.length()}" + (if (first) " (pierwsza synchronizacja)" else "")
    }

    // ------------------------------------------------------------ profile innych odbiorników

    data class Profile(val file: String, val name: String, val model: String, val updated: Long, val data: JSONObject)

    fun profiles(prefs: Prefs): List<Profile> {
        val out = ArrayList<Profile>()
        FtpSession(prefs.host, 21, prefs.ftpUser, prefs.ftpPass).use { ftp ->
            val dir = dirPath(prefs)
            val names = ftp.list(dir) ?: ftp.list(dir.trimStart('/')) ?: return out
            for (n in names.filter { it.startsWith("device-") && it.endsWith(".json") }) {
                val js = read(ftp, "$dir/$n") ?: continue
                val d = js.optJSONObject("device") ?: JSONObject()
                out.add(Profile(n, d.optString("name"), d.optString("model"), d.optLong("updated"),
                    js.optJSONObject("data") ?: JSONObject()))
            }
        }
        return out.sortedByDescending { it.updated }
    }

    /** Przenosi ustawienia i ścieżki z profilu innego odbiornika na ten (jako nowe zmiany). */
    fun restore(p: Profile): Int {
        val ed = sp.edit()
        var n = 0
        for (k in p.data.keys()) {
            if (!isDeviceKey(k)) continue
            put(ed, k, p.data.optJSONObject(k)?.opt("v")); n++
        }
        ed.commit() // listener oznaczy zmiany jako nowe — trafią do pliku tego odbiornika
        return n
    }
}
