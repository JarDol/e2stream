package pl.jardol.e2stream

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** EPG kanału, multi-EPG bukietu, wyszukiwanie w EPG i nagrywanie na dekoderze. */
class EpgUi(
    private val a: Activity,
    private val api: OpenWebif,
    private val io: ExecutorService,
    private val store: EpgStore,
    private val host: Host,
) {
    interface Host {
        fun current(): Channel?
        fun bouquetChannels(): List<Channel>
        fun bouquetName(): String
        fun zapTo(ref: String)
        fun message(t: String)
    }

    private val pl = Locale("pl", "PL")
    private val hm = SimpleDateFormat("HH:mm", pl)
    private val day = SimpleDateFormat("EEE dd.MM", pl)
    private val cache = HashMap<String, Pair<Long, List<EpgEvent>>>()

    private fun now() = System.currentTimeMillis() / 1000
    private fun t(sec: Long) = hm.format(Date(sec * 1000))
    private fun d(sec: Long) = day.format(Date(sec * 1000))

    private fun load(ref: String): List<EpgEvent> {
        val k = OpenWebif.key(ref)
        synchronized(cache) {
            cache[k]?.let { if (System.currentTimeMillis() - it.first < 10 * 60_000) return it.second }
        }
        // zapisane EPG (z harmonogramu), jeśli świeże i sięga w przyszłość
        store.read(ref)?.let { (at, ev) ->
            if (System.currentTimeMillis() - at < 26 * 3600_000L && ev.any { it.end > now() }) {
                synchronized(cache) { cache[k] = System.currentTimeMillis() to ev }
                return ev
            }
        }
        val ev = api.epgService(ref)
        synchronized(cache) { cache[k] = System.currentTimeMillis() to ev }
        store.write(ref, ev)
        return ev
    }

    private fun cached(ref: String): List<EpgEvent> =
        synchronized(cache) { cache[OpenWebif.key(ref)]?.second } ?: emptyList()

    // ------------------------------------------------------------ lista

    private class Row(val left: String, val title: String, val sub: String, val ev: EpgEvent?, val ch: Channel?)

    private inner class RowAdapter(var rows: List<Row>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: a.layoutInflater.inflate(R.layout.item_epg, parent, false)
            val r = rows[position]
            v.findViewById<TextView>(R.id.left).text = r.left
            val title = v.findViewById<TextView>(R.id.title)
            title.text = r.title
            val running = r.ev != null && r.ev.begin <= now() && r.ev.end > now()
            title.setTextColor(if (running) 0xFF4FC3F7.toInt() else 0xFFFFFFFF.toInt())
            v.findViewById<TextView>(R.id.sub).text = r.sub
            return v
        }
    }

    private fun bigList(title: String, adapter: RowAdapter, onPick: (Int) -> Unit): AlertDialog {
        val dlg = AlertDialog.Builder(a).setTitle(title).setAdapter(adapter, null).create()
        dlg.setOnShowListener {
            dlg.listView.setOnItemClickListener { _, _, w, _ -> onPick(w) }
        }
        dlg.show()
        val dm = a.resources.displayMetrics
        dlg.window?.setLayout((dm.widthPixels * 0.85).toInt(), (dm.heightPixels * 0.9).toInt())
        return dlg
    }

    private fun progress(ev: EpgEvent): String {
        val n = now()
        if (ev.begin > n || ev.end <= n || ev.duration <= 0) return ""
        val pct = ((n - ev.begin) * 100 / ev.duration).toInt()
        return "  •  trwa ($pct%, zostało ${(ev.end - n) / 60} min)"
    }

    // ------------------------------------------------------------ audycja

    fun showEvent(ev: EpgEvent, ch: Channel?) {
        val ref = ev.sref.ifBlank { ch?.ref ?: "" }
        val name = ev.sname.ifBlank { ch?.name ?: "" }
        val running = ev.begin <= now() && ev.end > now()
        val head = buildString {
            append("${d(ev.begin)}  ${t(ev.begin)}–${t(ev.end)}  (${ev.duration / 60} min)")
            append(progress(ev))
            if (name.isNotBlank()) append("\n$name")
        }
        val body = buildString {
            if (ev.shortDesc.isNotBlank()) append(ev.shortDesc)
            if (ev.longDesc.isNotBlank() && ev.longDesc != ev.shortDesc) {
                if (isNotEmpty()) append("\n\n")
                append(ev.longDesc)
            }
            if (isEmpty()) append("Brak opisu")
        }
        val dp = a.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        // własny układ: przewijany opis + rząd przycisków zawsze widoczny w całości
        val root = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(24), px(8), px(24), px(16))
        }
        root.addView(TextView(a).apply {
            text = head
            textSize = 19f
            setTextColor(0xFFB0BEC5.toInt())
        })
        val scroll = android.widget.ScrollView(a).apply { isFocusable = true; isFocusableInTouchMode = false }
        scroll.addView(TextView(a).apply {
            text = body
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setLineSpacing(px(3).toFloat(), 1f)
            setPadding(0, px(12), 0, px(12))
        })
        root.addView(scroll, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(0, px(12), 0, 0)
        }
        root.addView(bar, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))

        val dlg = AlertDialog.Builder(a).setTitle(ev.title).setView(root).create()
        fun button(label: String, onClick: () -> Unit): android.widget.Button {
            val btn = android.widget.Button(a)
            btn.text = label
            btn.textSize = 18f
            btn.setPadding(px(24), px(10), px(24), px(10))
            btn.setOnClickListener { dlg.dismiss(); onClick() }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = px(12)
            bar.addView(btn, lp)
            return btn
        }
        var first: android.widget.Button? = null
        if (ref.isNotBlank()) {
            first = button(if (running) "Oglądaj" else "Przełącz na kanał") { host.zapTo(ref) }
            if (ev.end > now()) button("Nagraj na dekoderze") { record(ref, ev) }
        }
        val close = button("Zamknij") { }
        dlg.show()
        val dm = a.resources.displayMetrics
        dlg.window?.setLayout((dm.widthPixels * 0.8).toInt(), (dm.heightPixels * 0.85).toInt())
        (first ?: close).requestFocus()
    }

    private fun record(ref: String, ev: EpgEvent) {
        io.execute {
            val r = try { api.timerAdd(ref, ev.id) } catch (e: Exception) { false to (e.message ?: e.toString()) }
            AppLog.i("nagranie: ${ev.title} ${d(ev.begin)} ${t(ev.begin)} -> ${r.first} ${r.second}")
            a.runOnUiThread {
                host.message(if (r.first) "Nagranie ustawione: ${ev.title}" else "Nie udało się ustawić nagrania: ${r.second}")
            }
        }
    }

    fun clearMemory() = synchronized(cache) { cache.clear() }

    /** Bieżąca i następna audycja kanału (z pamięci/zapisu, w razie potrzeby z dekodera); wynik na wątku UI. */
    fun nowNext(ch: Channel, cb: (EpgEvent?, EpgEvent?) -> Unit) {
        io.execute {
            val ev = try { load(ch.ref) } catch (_: Exception) { emptyList() }
            val n = now()
            val cur = ev.firstOrNull { it.begin <= n && it.end > n }
            val next = ev.firstOrNull { it.begin >= (cur?.end ?: n) }
            a.runOnUiThread { cb(cur, next) }
        }
    }

    // ------------------------------------------------------------ EPG wydarzenia

    /** Od razu opis bieżącej audycji kanału. */
    fun showCurrentEvent(ch: Channel? = host.current()) {
        if (ch == null) return
        io.execute {
            val ev = try { load(ch.ref) } catch (e: Exception) {
                a.runOnUiThread { host.message("Błąd EPG: ${e.message}") }
                return@execute
            }
            val n = now()
            val cur = ev.firstOrNull { it.begin <= n && it.end > n } ?: ev.firstOrNull { it.begin > n }
            a.runOnUiThread {
                if (cur == null) host.message("Brak EPG dla ${ch.name}") else showEvent(cur, ch)
            }
        }
    }

    // ------------------------------------------------------------ EPG kanału

    fun showChannelEpg(ch: Channel? = host.current()) {
        if (ch == null) return
        host.message("Pobieram EPG: ${ch.name}…")
        io.execute {
            val ev = try { load(ch.ref) } catch (e: Exception) {
                a.runOnUiThread { host.message("Błąd EPG: ${e.message}") }
                return@execute
            }
            a.runOnUiThread {
                val n = now()
                val list = ev.filter { it.end > n }
                if (list.isEmpty()) { host.message("Brak EPG dla ${ch.name}"); return@runOnUiThread }
                var lastDay = ""
                val rows = list.map {
                    val dd = d(it.begin)
                    val left = if (dd != lastDay) "$dd  ${t(it.begin)}" else t(it.begin)
                    lastDay = dd
                    Row(left, it.title, it.shortDesc + progress(it), it, ch)
                }
                val adapter = RowAdapter(rows)
                bigList("EPG: ${ch.num} ${ch.name}", adapter) { w -> showEvent(rows[w].ev!!, ch) }
            }
        }
    }

    // ------------------------------------------------------------ multi-EPG

    private var multiT = 0L

    fun showMultiEpg() {
        val chans = host.bouquetChannels()
        if (chans.isEmpty()) return
        host.message("Pobieram EPG bukietu (${chans.size} kanałów)…")
        io.execute {
            val pool = Executors.newFixedThreadPool(4)
            for (c in chans) pool.execute { try { load(c.ref) } catch (_: Exception) { } }
            pool.shutdown()
            pool.awaitTermination(60, TimeUnit.SECONDS)
            a.runOnUiThread { multiT = now(); openMulti(chans) }
        }
    }

    private fun multiRows(chans: List<Channel>, at: Long): List<Row> = chans.map { c ->
        val ev = cached(c.ref)
        val cur = ev.firstOrNull { it.begin <= at && it.end > at }
        val next = ev.firstOrNull { it.begin >= (cur?.end ?: at) }
        Row(
            "${c.num}  ${c.name}".take(18),
            cur?.let { "${t(it.begin)}  ${it.title}" } ?: "—",
            next?.let { "następnie ${t(it.begin)}  ${it.title}" } ?: "",
            cur ?: next,
            c,
        )
    }

    private fun openMulti(chans: List<Channel>) {
        val adapter = RowAdapter(multiRows(chans, multiT))
        fun title() = "Multi-EPG: ${host.bouquetName()} — ${d(multiT)} ${t(multiT)}   (◀ ▶ zmiana czasu)"
        val dlg = bigList(title(), adapter) { w ->
            val r = adapter.rows[w]
            if (r.ev != null) showEvent(r.ev, r.ch) else r.ch?.let { host.zapTo(it.ref) }
        }
        dlg.setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val step = 30 * 60L
            val shift = when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> step
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> -step
                else -> 0L
            }
            if (shift == 0L) return@setOnKeyListener false
            // pierwsze przesunięcie do pełnego pół-godzinnego znacznika
            val base = if (shift > 0) (multiT / step + 1) * step else ((multiT - 1) / step) * step
            multiT = if (base < now() && shift < 0) now() else base
            adapter.rows = multiRows(chans, multiT)
            adapter.notifyDataSetChanged()
            dlg.setTitle(title())
            true
        }
    }

    // ------------------------------------------------------------ wyszukiwanie

    fun showSearch() {
        val et = EditText(a)
        et.inputType = InputType.TYPE_CLASS_TEXT
        et.hint = "tytuł audycji"
        AlertDialog.Builder(a)
            .setTitle("Szukaj w EPG")
            .setView(et)
            .setPositiveButton("Szukaj") { _, _ -> search(et.text.toString().trim()) }
            .setNegativeButton("Anuluj", null)
            .show()
        et.requestFocus()
    }

    private fun search(q: String) {
        if (q.length < 2) { host.message("Wpisz co najmniej 2 znaki"); return }
        host.message("Szukam „$q”…")
        io.execute {
            val res = try { api.epgSearch(q) } catch (e: Exception) {
                a.runOnUiThread { host.message("Błąd wyszukiwania: ${e.message}") }
                return@execute
            }
            a.runOnUiThread {
                val n = now()
                val list = res.filter { it.end > n }
                if (list.isEmpty()) { host.message("Nic nie znaleziono: „$q”"); return@runOnUiThread }
                val rows = list.map {
                    Row("${d(it.begin)}  ${t(it.begin)}", it.title, "${it.sname}   ${it.shortDesc}${progress(it)}", it, null)
                }
                bigList("Wyniki: „$q” (${rows.size})", RowAdapter(rows)) { w -> showEvent(rows[w].ev!!, null) }
            }
        }
    }
}
