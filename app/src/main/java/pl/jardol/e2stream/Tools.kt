package pl.jardol.e2stream

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.widget.EditText

/** Blokada ustawień PIN-em: po poprawnym PIN-ie odblokowane na 1 minutę. */
object PinLock {
    private const val UNLOCK_MS = 60_000L
    private var unlockedUntil = 0L

    fun isUnlocked(prefs: Prefs) = prefs.pin.isEmpty() || SystemClock.elapsedRealtime() < unlockedUntil

    fun remainingMs() = (unlockedUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0)

    fun lock() { unlockedUntil = 0 }

    /** Wywołuje [onOk], gdy ustawienia są odblokowane lub po poprawnym PIN-ie; [onCancel] przy anulowaniu. */
    fun ask(a: Activity, prefs: Prefs, cancelable: Boolean = true, onCancel: () -> Unit = {}, onOk: () -> Unit) {
        if (isUnlocked(prefs)) { onOk(); return }
        val et = EditText(a)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        et.hint = "PIN"
        val dlg = AlertDialog.Builder(a)
            .setTitle("Ustawienia zablokowane — podaj PIN")
            .setView(et)
            .setCancelable(cancelable)
            .setPositiveButton("OK") { _, _ ->
                if (et.text.toString() == prefs.pin) {
                    unlockedUntil = SystemClock.elapsedRealtime() + UNLOCK_MS
                    onOk()
                } else {
                    android.widget.Toast.makeText(a, "Błędny PIN", android.widget.Toast.LENGTH_SHORT).show()
                    onCancel()
                }
            }
            .setNegativeButton("Anuluj") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
        dlg.setCanceledOnTouchOutside(false)
        et.requestFocus()
    }
}

/** Wspólne operacje serwisowe: log i zrzuty na dekoder, aktualizacja aplikacji. */
object Tools {
    private val ui = Handler(Looper.getMainLooper())

    fun logName() = "e2stream-" + Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".log"

    fun uploadLog(prefs: Prefs, manual: Boolean, msg: (String) -> Unit) {
        if (!prefs.logFtp && !manual) return
        val host = prefs.host
        if (host.isBlank()) return
        val user = prefs.ftpUser
        val pass = prefs.ftpPass
        val dir = prefs.ftpDir
        val name = logName()
        val data = AppLog.snapshot()
        Thread {
            try {
                FtpUpload.upload(host, 21, user, pass, dir, name, data)
                if (manual) ui.post { msg("Log wysłany: $dir/$name") }
            } catch (e: Exception) {
                AppLog.markDirty()
                if (manual) ui.post { msg("Nie udało się wysłać logu: ${e.message}") }
            }
        }.start()
    }

    /** Wysyła wszystkie zrzuty stall-*.ts przez FTP. */
    fun uploadDumps(ctx: Context, prefs: Prefs, msg: (String) -> Unit) {
        val dirs = listOfNotNull(Storage.dir(ctx, prefs), ctx.getExternalFilesDir(null), ctx.filesDir).distinct()
        val files = dirs.flatMap { d ->
            d.listFiles { f -> f.name.startsWith("stall-") && f.name.endsWith(".ts") }?.toList() ?: emptyList()
        }.distinctBy { it.name }.sortedBy { it.name }
        if (files.isEmpty()) { msg("Brak zrzutów TS"); return }
        val host = prefs.host
        val user = prefs.ftpUser
        val pass = prefs.ftpPass
        val dir = prefs.ftpDir
        msg("Wysyłam ${files.size} zrzut(ów) TS…")
        Thread {
            var ok = 0
            var err = ""
            for (f in files) {
                try {
                    FtpUpload.upload(host, 21, user, pass, dir, f.name, f.readBytes())
                    ok++
                    AppLog.i("zrzut TS wysłany na dekoder: $dir/${f.name}")
                } catch (e: Exception) {
                    err = e.message ?: e.toString()
                    AppLog.i("wysyłanie ${f.name} nieudane: $e")
                }
            }
            ui.post {
                msg(if (err.isEmpty()) "Wysłano $ok zrzut(ów) do $dir" else "Wysłano $ok/${files.size}. Błąd: $err")
            }
        }.start()
    }

    fun appVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    fun checkUpdate(a: Activity, manual: Boolean, msg: (String) -> Unit) {
        if (manual) msg("Sprawdzam aktualizację…")
        Thread {
            val info = try { Updater.latest() } catch (e: Exception) {
                AppLog.i("aktualizacja: błąd $e"); null
            }
            ui.post {
                if (a.isFinishing) return@post
                val cur = appVersion(a)
                when {
                    info == null -> if (manual) msg("Nie udało się sprawdzić aktualizacji")
                    Updater.newer(info.version, cur) ->
                        if (manual) askUpdate(a, info, msg)
                        else msg("Dostępna wersja ${info.version} — Ustawienia → Sprawdź aktualizację")
                    else -> if (manual) msg("Masz najnowszą wersję ($cur)")
                }
            }
        }.start()
    }

    private fun askUpdate(a: Activity, info: Updater.Info, msg: (String) -> Unit) {
        AlertDialog.Builder(a)
            .setTitle("Aktualizacja E2Stream")
            .setMessage("Dostępna wersja ${info.version} (masz ${appVersion(a)}). Pobrać i zainstalować?")
            .setPositiveButton("Tak") { _, _ ->
                AppLog.i("aktualizacja: pobieram ${info.url}")
                Thread {
                    try {
                        var last = -1
                        val apk = Updater.download(a, info) { pct ->
                            if (pct / 10 != last) { last = pct / 10; ui.post { msg("Pobieranie $pct%") } }
                        }
                        ui.post {
                            if (!Updater.install(a, apk)) {
                                msg("Zezwól E2Stream na instalowanie aplikacji i spróbuj ponownie")
                            }
                        }
                    } catch (e: Exception) {
                        AppLog.i("aktualizacja: błąd pobierania $e")
                        ui.post { msg("Błąd pobierania: ${e.message}") }
                    }
                }.start()
            }
            .setNegativeButton("Nie", null)
            .show()
    }
}
