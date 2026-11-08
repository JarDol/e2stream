package pl.jardol.e2stream

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Trwały zapis EPG: jeden plik JSON na kanał (pliki/epg/). */
class EpgStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "epg").also { it.mkdirs() }

    private fun file(ref: String) =
        File(dir, OpenWebif.key(ref).replace(Regex("[^A-Z0-9]"), "_").trim('_') + ".json")

    /** (czas pobrania ms, audycje) albo null. */
    fun read(ref: String): Pair<Long, List<EpgEvent>>? {
        val f = file(ref)
        if (!f.exists()) return null
        return try {
            val js = JSONObject(f.readText())
            val arr = js.optJSONArray("e") ?: JSONArray()
            val out = ArrayList<EpgEvent>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    EpgEvent(
                        o.optInt("i"), o.optLong("b"), o.optLong("d"), OpenWebif.unescape(o.optString("t")),
                        OpenWebif.unescape(o.optString("s")), OpenWebif.unescape(o.optString("l")),
                        o.optString("r"), OpenWebif.unescape(o.optString("n")),
                    )
                )
            }
            js.optLong("at") to out
        } catch (_: Exception) {
            null
        }
    }

    fun write(ref: String, events: List<EpgEvent>) {
        val arr = JSONArray()
        for (e in events) {
            arr.put(
                JSONObject().put("i", e.id).put("b", e.begin).put("d", e.duration).put("t", e.title)
                    .put("s", e.shortDesc).put("l", e.longDesc).put("r", e.sref).put("n", e.sname)
            )
        }
        try {
            file(ref).writeText(JSONObject().put("at", System.currentTimeMillis()).put("e", arr).toString())
        } catch (_: Exception) { }
    }

    fun count() = dir.listFiles()?.size ?: 0
}

/** Pobieranie EPG (i ikon) wszystkich kanałów z widocznych bukietów — ręcznie lub wg harmonogramu. */
object EpgSync {
    @Volatile var running = false
        private set

    /** Najbliższy minął termin z harmonogramu (ms) dla godziny "HH:MM". */
    private fun lastScheduled(hhmm: String): Long {
        val p = hhmm.split(":")
        val h = p.getOrNull(0)?.trim()?.toIntOrNull()?.coerceIn(0, 23) ?: 5
        val m = p.getOrNull(1)?.trim()?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, m); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis > System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, -1)
        return c.timeInMillis
    }

    /** true, gdy od ostatniego terminu harmonogramu EPG nie było pobierane. */
    fun due(prefs: Prefs): Boolean {
        if (running || prefs.host.isBlank()) return false
        // z wtyczką EPG Scan: pierwsze uruchomienie w danym dniu pobiera wszystko (bez harmonogramu w aplikacji)
        if (prefs.pluginOk) return prefs.lastEpgFetch < startOfToday()
        return prefs.epgDaily && prefs.lastEpgFetch < lastScheduled(prefs.epgHour)
    }

    private fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun run(ctx: Context, prefs: Prefs, store: EpgStore, progress: (String) -> Unit, done: (String) -> Unit) {
        if (running) { done("Pobieranie EPG już trwa"); return }
        running = true
        Thread {
            val t0 = SystemClock.elapsedRealtime()
            var result: String
            try {
                val api = OpenWebif(prefs)
                val hidden = prefs.hiddenBouquets
                val all = api.bouquets()
                val bq = all.filter { it.ref !in hidden }.ifEmpty { all }
                val seen = HashSet<String>()
                val chans = ArrayList<Channel>()
                for (b in bq) {
                    for (c in try { api.channels(b) } catch (_: Exception) { emptyList() }) {
                        if (seen.add(OpenWebif.key(c.ref))) chans.add(c)
                    }
                }
                val until = System.currentTimeMillis() / 1000 + prefs.epgDays.coerceIn(1, 14) * 86_400L
                val now = System.currentTimeMillis() / 1000
                val done1 = AtomicInteger(0)
                val events = AtomicInteger(0)
                val pool = Executors.newFixedThreadPool(2)
                for (c in chans) pool.execute {
                    try {
                        val ev = api.epgService(c.ref).filter { it.end > now && it.begin < until }
                        store.write(c.ref, ev)
                        events.addAndGet(ev.size)
                    } catch (_: Exception) { }
                    val n = done1.incrementAndGet()
                    if (n % 10 == 0) progress("EPG: $n/${chans.size} kanałów")
                }
                pool.shutdown()
                pool.awaitTermination(10, TimeUnit.MINUTES)
                var icons = ""
                if (prefs.picons) {
                    val got = Picons.fetch(ctx, prefs, chans) { progress(it) }
                    icons = ", ikony: $got"
                }
                prefs.lastEpgFetch = System.currentTimeMillis()
                result = "EPG pobrane: ${chans.size} kanałów, ${events.get()} audycji$icons (${(SystemClock.elapsedRealtime() - t0) / 1000} s)"
            } catch (e: Exception) {
                result = "Błąd pobierania EPG: ${e.message}"
            }
            AppLog.i(result)
            running = false
            done(result)
        }.start()
    }
}
