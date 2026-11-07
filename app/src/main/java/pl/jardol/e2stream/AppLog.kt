package pl.jardol.e2stream

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Prosty log do pliku: <pamięć zewnętrzna aplikacji>/e2stream.log
 * (Android/data/pl.jardol.e2stream/files/e2stream.log), rotacja przy 1 MB.
 */
object AppLog {
    private const val TAG = "E2Stream"
    private const val MAX = 1_000_000L
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null
    private val lock = Any()

    @Volatile
    var dirty = false
        private set

    fun init(ctx: Context, dir: File? = null) {
        val d = dir ?: ctx.getExternalFilesDir(null) ?: ctx.filesDir
        synchronized(lock) { file = File(d, "e2stream.log") }
    }

    fun dir(): File? = file?.parentFile

    fun path(): String = file?.absolutePath ?: "-"

    fun i(msg: String) {
        Log.i(TAG, msg)
        val f = file ?: return
        synchronized(lock) {
            try {
                if (f.length() > MAX) {
                    val old = File(f.parentFile, "e2stream.1.log")
                    old.delete()
                    f.renameTo(old)
                }
                FileWriter(f, true).use { it.write("${fmt.format(Date())} $msg\n") }
                dirty = true
            } catch (e: Exception) {
                Log.w(TAG, "log write failed", e)
            }
        }
    }

    /** Zawartość logu (poprzedni + bieżący plik). */
    fun snapshot(): ByteArray {
        val f = file ?: return ByteArray(0)
        synchronized(lock) {
            dirty = false
            val old = File(f.parentFile, "e2stream.1.log")
            val a = if (old.exists()) old.readBytes() else ByteArray(0)
            val b = if (f.exists()) f.readBytes() else ByteArray(0)
            return a + b
        }
    }

    fun markDirty() {
        dirty = true
    }
}
