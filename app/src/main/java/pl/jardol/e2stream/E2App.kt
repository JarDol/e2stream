package pl.jardol.e2stream

import android.app.Application
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

/** Przechwytuje awarie: zapisuje pełny błąd do logu i ustawień, żeby pokazać go przy następnym starcie. */
class E2App : Application() {
    override fun onCreate() {
        super.onCreate()
        try { AppLog.init(this, Storage.dir(this, Prefs(this))) } catch (_: Throwable) { AppLog.init(this) }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val text = "AWARIA (wątek ${t.name}) | ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n$sw"
                AppLog.i(text)
                getSharedPreferences("e2stream", MODE_PRIVATE).edit()
                    .putString("lastCrash", text.take(6000))
                    .commit()
            } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }
}
