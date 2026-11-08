package pl.jardol.e2stream

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private var asking = false

    /** Po upływie minuty od odblokowania ustawienia blokują się ponownie. */
    private val relock = object : Runnable {
        override fun run() {
            if (!isFinishing && !asking && !PinLock.isUnlocked(prefs)) {
                asking = true
                PinLock.ask(this@SettingsActivity, prefs, cancelable = false,
                    onCancel = { asking = false; finish() }) { asking = false }
            }
            ui.postDelayed(this, 2000)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(relock)
        ui.postDelayed(relock, 2000)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(relock)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------ mapowanie klawiszy

    private fun showKeyMap() {
        val entries = KeyMap.load(prefs).entries.toList()
        val labels = (listOf("+ Dodaj / zmień klawisz (naciśnij go na pilocie)") +
            entries.map { "${KeyMap.keyName(it.key)}   →   ${it.value.label}" } +
            listOf("Przywróć domyślne")).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Mapowanie klawiszy (podczas oglądania)")
            .setItems(labels) { _, w ->
                when (w) {
                    0 -> captureKey()
                    labels.size - 1 -> { prefs.keyMap = ""; toast("Przywrócono domyślne"); showKeyMap() }
                    else -> chooseAct(entries[w - 1].key)
                }
            }
            .setNegativeButton("Zamknij", null)
            .show()
    }

    private fun captureKey() {
        val dlg = AlertDialog.Builder(this)
            .setTitle("Naciśnij klawisz na pilocie…")
            .setMessage("WSTECZ — anuluj")
            .create()
        dlg.setOnKeyListener { d, code, e ->
            if (e.action != android.view.KeyEvent.ACTION_UP) return@setOnKeyListener true
            if (code == android.view.KeyEvent.KEYCODE_BACK) { d.dismiss(); return@setOnKeyListener true }
            if (code in KeyMap.fixed) { toast("Klawisza ${KeyMap.keyName(code)} nie można przypisać"); return@setOnKeyListener true }
            d.dismiss()
            chooseAct(code)
            true
        }
        dlg.show()
    }

    private fun chooseAct(code: Int) {
        val m = KeyMap.load(prefs)
        val acts = Act.values()
        val cur = acts.indexOf(m[code] ?: Act.NONE)
        AlertDialog.Builder(this)
            .setTitle("Klawisz ${KeyMap.keyName(code)}")
            .setSingleChoiceItems(acts.map { it.label }.toTypedArray(), cur) { d, w ->
                m[code] = acts[w]
                KeyMap.save(prefs, m)
                AppLog.i("mapowanie: ${KeyMap.keyName(code)} → ${acts[w].name}")
                d.dismiss()
                showKeyMap()
            }
            .setNegativeButton("Anuluj") { _, _ -> showKeyMap() }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val p = Prefs(this)
        prefs = p
        p.migrate()
        AppLog.init(this, Storage.dir(this, p))
        var storageDir = p.storageDir
        var hidden = p.hiddenBouquets

        val host = findViewById<EditText>(R.id.host)
        val httpPort = findViewById<EditText>(R.id.httpPort)
        val streamPort = findViewById<EditText>(R.id.streamPort)
        val user = findViewById<EditText>(R.id.user)
        val pass = findViewById<EditText>(R.id.pass)
        val tunneling = findViewById<CheckBox>(R.id.tunneling)
        val watchdog = findViewById<CheckBox>(R.id.watchdog)
        val stallSec = findViewById<EditText>(R.id.stallSec)
        val bufferMs = findViewById<EditText>(R.id.bufferMs)
        val afr = findViewById<RadioGroup>(R.id.afr)
        val audioLang = findViewById<EditText>(R.id.audioLang)
        val preferAc3 = findViewById<CheckBox>(R.id.preferAc3)
        val logFtp = findViewById<CheckBox>(R.id.logFtp)
        val ftpUser = findViewById<EditText>(R.id.ftpUser)
        val ftpPass = findViewById<EditText>(R.id.ftpPass)
        val ftpDir = findViewById<EditText>(R.id.ftpDir)
        val discover = findViewById<Button>(R.id.discover)
        val resumeLast = findViewById<CheckBox>(R.id.resumeLast)
        val invertUpDown = findViewById<CheckBox>(R.id.invertUpDown)
        val bannerSec = findViewById<EditText>(R.id.bannerSec)
        val checkUpdates = findViewById<CheckBox>(R.id.checkUpdates)
        val pin = findViewById<EditText>(R.id.pin)
        val saveTs = findViewById<CheckBox>(R.id.saveTs)
        val tsRingMb = findViewById<EditText>(R.id.tsRingMb)
        val tsFtp = findViewById<CheckBox>(R.id.tsFtp)
        val cleanTs = findViewById<CheckBox>(R.id.cleanTs)
        val dropRestart = findViewById<CheckBox>(R.id.dropRestart)
        val showStats = findViewById<CheckBox>(R.id.showStats)
        val epgDaily = findViewById<CheckBox>(R.id.epgDaily)
        val epgHour = findViewById<EditText>(R.id.epgHour)
        val epgDays = findViewById<EditText>(R.id.epgDays)
        val picons = findViewById<CheckBox>(R.id.picons)
        val piconDir = findViewById<EditText>(R.id.piconDir)
        val epgInfo = findViewById<TextView>(R.id.epgInfo)
        findViewById<Button>(R.id.keymap).setOnClickListener { showKeyMap() }
        val store = EpgStore(this)
        fun epgStatus() {
            val at = p.lastEpgFetch
            epgInfo.text = (if (at > 0) "Ostatnie pobranie: " + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale("pl")).format(java.util.Date(at)) else "EPG jeszcze nie pobierane") +
                " • zapisanych kanałów: ${store.count()}"
        }
        epgStatus()
        fun sync() {
            EpgSync.run(applicationContext, p, store, { msg -> runOnUiThread { epgInfo.text = msg } }) { r ->
                runOnUiThread {
                    toast(r); epgStatus()
                    if (piconDir.text.isBlank()) piconDir.setText(p.piconDir) // katalog wykryty z ustawień Enigmy2
                }
            }
        }
        findViewById<Button>(R.id.epgNow).setOnClickListener { sync() }
        findViewById<Button>(R.id.piconsReload).setOnClickListener {
            Picons.clear(this)
            p.picons = true
            picons.isChecked = true
            sync()
        }
        findViewById<Button>(R.id.sendLog).setOnClickListener { Tools.uploadLog(p, true) { toast(it) } }
        findViewById<Button>(R.id.sendDumps).setOnClickListener { Tools.uploadDumps(this, p) { toast(it) } }
        findViewById<Button>(R.id.checkUpdate).setOnClickListener { Tools.checkUpdate(this, true) { toast(it) } }
        val storageInfo = findViewById<TextView>(R.id.storageInfo)
        val bouquetsInfo = findViewById<TextView>(R.id.bouquetsInfo)
        val bouquetsBtn = findViewById<Button>(R.id.bouquets)

        host.setText(p.host)
        httpPort.setText(p.httpPort.toString())
        streamPort.setText(p.streamPort.toString())
        user.setText(p.user)
        pass.setText(p.pass)
        tunneling.isChecked = p.tunneling
        watchdog.isChecked = p.watchdog
        stallSec.setText(p.stallSec.toString())
        bufferMs.setText(p.bufferMs.toString())
        afr.check(
            when (p.afrMode) {
                1 -> R.id.afr50
                2 -> R.id.afrAuto
                else -> R.id.afrOff
            }
        )
        audioLang.setText(p.audioLang)
        preferAc3.isChecked = p.preferAc3
        logFtp.isChecked = p.logFtp
        ftpUser.setText(p.ftpUser)
        ftpPass.setText(p.ftpPass)
        ftpDir.setText(p.ftpDir)
        resumeLast.isChecked = p.resumeLast
        invertUpDown.isChecked = p.invertUpDown
        bannerSec.setText(p.bannerSec.toString())
        checkUpdates.isChecked = p.checkUpdates
        pin.setText(p.pin)
        saveTs.isChecked = p.saveTs
        tsRingMb.setText(p.tsRingMb.toString())
        tsFtp.isChecked = p.tsFtp
        cleanTs.isChecked = p.cleanTs
        dropRestart.isChecked = p.dropRestart
        showStats.isChecked = p.showStats
        epgDaily.isChecked = p.epgDaily
        epgHour.setText(p.epgHour)
        epgDays.setText(p.epgDays.toString())
        picons.isChecked = p.picons
        piconDir.setText(p.piconDir)
        storageInfo.text = "Zapis: " + (storageDir.ifBlank { Storage.dir(this, p).absolutePath })
        bouquetsInfo.text = if (hidden.isEmpty()) "Widoczne: wszystkie" else "Ukryte bukiety: ${hidden.size}"

        findViewById<Button>(R.id.storage).setOnClickListener {
            val opts = Storage.options(this)
            AlertDialog.Builder(this)
                .setTitle("Miejsce zapisu logów i zrzutów TS")
                .setItems(opts.map { it.label }.toTypedArray()) { _, which ->
                    storageDir = opts[which].path
                    storageInfo.text = "Zapis: $storageDir"
                }
                .show()
        }

        bouquetsBtn.setOnClickListener {
            // zapisz dane połączenia, żeby lista bukietów pobrała się z wpisanego dekodera
            p.host = host.text.toString().trim()
            p.httpPort = httpPort.text.toString().toIntOrNull() ?: 80
            p.user = user.text.toString().trim()
            p.pass = pass.text.toString()
            bouquetsBtn.isEnabled = false
            Thread {
                val list = try { OpenWebif(p).bouquets() } catch (e: Exception) { null }
                runOnUiThread {
                    bouquetsBtn.isEnabled = true
                    if (isFinishing) return@runOnUiThread
                    if (list.isNullOrEmpty()) {
                        bouquetsInfo.text = "Nie udało się pobrać bukietów z dekodera"
                        return@runOnUiThread
                    }
                    val names = list.map { it.name }.toTypedArray()
                    val checked = BooleanArray(list.size) { list[it].ref !in hidden }
                    AlertDialog.Builder(this)
                        .setTitle("Widoczne bukiety")
                        .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                        .setPositiveButton("OK") { _, _ ->
                            hidden = list.indices.filter { !checked[it] }.map { list[it].ref }.toSet()
                            bouquetsInfo.text = if (hidden.isEmpty()) "Widoczne: wszystkie" else "Ukryte bukiety: ${hidden.size} z ${list.size}"
                        }
                        .setNegativeButton("Anuluj", null)
                        .show()
                }
            }.start()
        }

        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) { "" }
        findViewById<TextView>(R.id.version).text = "E2Stream $ver"

        discover.setOnClickListener {
            discover.isEnabled = false
            val port = httpPort.text.toString().toIntOrNull() ?: 80
            Thread {
                val found = Discovery.scan(port) { done, total ->
                    if (done % 16 == 0 || done == total) runOnUiThread {
                        discover.text = "Szukam… $done/$total"
                    }
                }
                runOnUiThread {
                    discover.isEnabled = true
                    discover.text = "Szukaj dekoderów w sieci"
                    if (isFinishing) return@runOnUiThread
                    if (found.isEmpty()) {
                        AlertDialog.Builder(this)
                            .setTitle("Nie znaleziono dekodera")
                            .setMessage("Brak odpowiedzi OpenWebif na porcie $port w sieci ${Discovery.localPrefixes().joinToString { it + "x" }}")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        val labels = found.map { "${it.ip}   ${it.label}" }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("Znalezione dekodery")
                            .setItems(labels) { _, which ->
                                host.setText(found[which].ip)
                                AppLog.i("wybrano dekoder z wyszukiwania: ${found[which].ip} ${found[which].label}")
                            }
                            .show()
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.save).setOnClickListener {
            p.host = host.text.toString().trim()
            p.httpPort = httpPort.text.toString().toIntOrNull() ?: 80
            p.streamPort = streamPort.text.toString().toIntOrNull() ?: 8001
            p.user = user.text.toString().trim()
            p.pass = pass.text.toString()
            p.tunneling = tunneling.isChecked
            p.watchdog = watchdog.isChecked
            p.stallSec = (stallSec.text.toString().toIntOrNull() ?: 3).coerceIn(1, 30)
            p.bufferMs = (bufferMs.text.toString().toIntOrNull() ?: 700).coerceIn(250, 10_000)
            p.afrMode = when (afr.checkedRadioButtonId) {
                R.id.afr50 -> 1
                R.id.afrAuto -> 2
                else -> 0
            }
            p.audioLang = audioLang.text.toString().trim().ifEmpty { "pol" }
            p.preferAc3 = preferAc3.isChecked
            p.logFtp = logFtp.isChecked
            p.ftpUser = ftpUser.text.toString().trim().ifEmpty { "root" }
            p.ftpPass = ftpPass.text.toString()
            p.ftpDir = ftpDir.text.toString().trim().ifEmpty { Prefs.DEFAULT_FTP_DIR }
            p.resumeLast = resumeLast.isChecked
            p.invertUpDown = invertUpDown.isChecked
            p.bannerSec = (bannerSec.text.toString().toIntOrNull() ?: 4).coerceIn(1, 30)
            p.checkUpdates = checkUpdates.isChecked
            p.pin = pin.text.toString().trim()
            p.saveTs = saveTs.isChecked
            p.tsRingMb = (tsRingMb.text.toString().toIntOrNull() ?: 16).coerceIn(2, 64)
            p.tsFtp = tsFtp.isChecked
            p.cleanTs = cleanTs.isChecked
            p.dropRestart = dropRestart.isChecked
            p.showStats = showStats.isChecked
            p.epgDaily = epgDaily.isChecked
            p.epgHour = epgHour.text.toString().trim().let { if (Regex("^\\d{1,2}:\\d{2}$").matches(it)) it else "05:00" }
            p.epgDays = (epgDays.text.toString().toIntOrNull() ?: 3).coerceIn(1, 14)
            p.picons = picons.isChecked
            p.piconDir = piconDir.text.toString().trim()
            p.storageDir = storageDir
            p.hiddenBouquets = hidden
            p.bump()
            AppLog.i("zapisano ustawienia: host=${p.host} tunel=${p.tunneling} watchdog=${p.watchdog}/${p.stallSec}s bufor=${p.bufferMs} afr=${p.afrMode} audio=${p.audioLang} ac3=${p.preferAc3}")
            finish()
        }
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.sendLog).requestFocus()
    }
}
