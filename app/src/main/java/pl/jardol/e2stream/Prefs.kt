package pl.jardol.e2stream

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.getSharedPreferences("e2stream", Context.MODE_PRIVATE)

    private fun s(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun putS(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun putI(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun putB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    /** Adres IP / nazwa dekodera Enigma2. */
    var host: String
        get() = s("host")
        set(v) = putS("host", v)

    /** Port OpenWebif (HTTP). */
    var httpPort: Int
        get() = sp.getInt("httpPort", 80)
        set(v) = putI("httpPort", v)

    /** Port streamingu Enigma2 (domyślnie 8001). */
    var streamPort: Int
        get() = sp.getInt("streamPort", 8001)
        set(v) = putI("streamPort", v)

    var user: String
        get() = s("user")
        set(v) = putS("user", v)

    var pass: String
        get() = s("pass")
        set(v) = putS("pass", v)

    /** Tunelowanie wideo (jak w DreamTV/ExoPlayer). */
    var tunneling: Boolean
        get() = sp.getBoolean("tunneling", true)
        set(v) = putB("tunneling", v)

    /** Automatyczne wznawianie zawieszonego strumienia. */
    var watchdog: Boolean
        get() = sp.getBoolean("watchdog", true)
        set(v) = putB("watchdog", v)

    /** Po ilu sekundach bez nowych klatek uznać obraz za zawieszony. */
    var stallSec: Int
        get() = sp.getInt("stallSec", 3)
        set(v) = putI("stallSec", v)

    /** Bufor startowy w ms. */
    var bufferMs: Int
        get() = sp.getInt("bufferMs", 1500)
        set(v) = putI("bufferMs", v)

    /** Wymuś tryb 50 Hz ekranu podczas oglądania. */
    var force50Hz: Boolean
        get() = sp.getBoolean("force50Hz", false)
        set(v) = putB("force50Hz", v)

    var lastBouquet: String
        get() = s("lastBouquet")
        set(v) = putS("lastBouquet", v)

    var lastChannel: String
        get() = s("lastChannel")
        set(v) = putS("lastChannel", v)

    /** Zwiększane przy każdym zapisie ustawień — odtwarzacz wie, że ma się przeładować. */
    val version: Int
        get() = sp.getInt("version", 0)

    fun bump() = putI("version", version + 1)
}
