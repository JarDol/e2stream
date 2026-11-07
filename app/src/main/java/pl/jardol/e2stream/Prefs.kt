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
        get() = sp.getInt("bufferMs", 700)
        set(v) = putI("bufferMs", v)

    /** Odświeżanie ekranu: 0 = bez zmian, 1 = zawsze 50 Hz, 2 = automatycznie wg treści. */
    var afrMode: Int
        get() = sp.getInt("afrMode", if (sp.getBoolean("force50Hz", false)) 1 else 0)
        set(v) = putI("afrMode", v)

    /** Preferowany język dźwięku (kody ISO 639-2 oddzielone przecinkami). */
    var audioLang: String
        get() = s("audioLang", "pol")
        set(v) = putS("audioLang", v)

    /** Preferuj AC3 / E-AC3 (Dolby) przed MPEG stereo. */
    var preferAc3: Boolean
        get() = sp.getBoolean("preferAc3", false)
        set(v) = putB("preferAc3", v)

    /** Ścieżka dźwięku zapamiętana dla kanału: "id|język|mime". */
    fun audioFor(ref: String): String = s("audio_" + OpenWebif.key(ref))
    fun setAudioFor(ref: String, v: String) = putS("audio_" + OpenWebif.key(ref), v)

    /** Wysyłanie logu na dekoder przez FTP. */
    var logFtp: Boolean
        get() = sp.getBoolean("logFtp", true)
        set(v) = putB("logFtp", v)

    var ftpUser: String
        get() = s("ftpUser", "root")
        set(v) = putS("ftpUser", v)

    var ftpPass: String
        get() = s("ftpPass")
        set(v) = putS("ftpPass", v)

    var ftpDir: String
        get() = s("ftpDir", DEFAULT_FTP_DIR)
        set(v) = putS("ftpDir", v)

    /** Katalog zapisu logów i zrzutów TS (pusty = domyślny katalog aplikacji). */
    var storageDir: String
        get() = s("storageDir")
        set(v) = putS("storageDir", v)

    /** Zapis ostatnich sekund strumienia TS przy zawieszeniu. */
    var saveTs: Boolean
        get() = sp.getBoolean("saveTs", true)
        set(v) = putB("saveTs", v)

    /** Rozmiar bufora zrzutu TS w MB. */
    var tsRingMb: Int
        get() = sp.getInt("tsRingMb", 16)
        set(v) = putI("tsRingMb", v)

    /** Wysyłanie zrzutów TS także przez FTP na dekoder. */
    var tsFtp: Boolean
        get() = sp.getBoolean("tsFtp", false)
        set(v) = putB("tsFtp", v)

    /** Czyszczenie strumienia przed odtwarzaczem (pakiety uszkodzone / kodowane / obce PMT). */
    var cleanTs: Boolean
        get() = sp.getBoolean("cleanTs", true)
        set(v) = putB("cleanTs", v)

    /** Zapamiętany PMT kanału (surowe pakiety TS, Base64). */
    fun pmtFor(sid: Int): ByteArray? = s("pmt_$sid").takeIf { it.isNotEmpty() }?.let {
        try { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) } catch (_: Exception) { null }
    }
    fun setPmtFor(sid: Int, b: ByteArray) =
        putS("pmt_$sid", android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP))

    /** Ukryte bukiety (referencje). */
    var hiddenBouquets: Set<String>
        get() = HashSet(sp.getStringSet("hiddenBouquets", emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet("hiddenBouquets", HashSet(v)).apply()

    /** PIN do ustawień (pusty = bez blokady). */
    var pin: String
        get() = s("pin")
        set(v) = putS("pin", v)

    var invertUpDown: Boolean
        get() = sp.getBoolean("invertUpDown", false)
        set(v) = putB("invertUpDown", v)

    var bannerSec: Int
        get() = sp.getInt("bannerSec", 4)
        set(v) = putI("bannerSec", v)

    var resumeLast: Boolean
        get() = sp.getBoolean("resumeLast", true)
        set(v) = putB("resumeLast", v)

    var checkUpdates: Boolean
        get() = sp.getBoolean("checkUpdates", true)
        set(v) = putB("checkUpdates", v)

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

    /** Jednorazowe przejście ze starych wartości domyślnych na nowe. */
    fun migrate() {
        val v = sp.getInt("prefsVer", 1)
        if (v < 2) {
            if (sp.getString("ftpDir", null) == "/tmp") putS("ftpDir", DEFAULT_FTP_DIR)
            if (sp.getInt("bufferMs", -1) == 1500) putI("bufferMs", 700)
            putI("prefsVer", 2)
        }
    }

    companion object {
        const val DEFAULT_FTP_DIR = "/media/hdd/e2stream"
    }
}
