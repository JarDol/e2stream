package pl.jardol.e2stream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.Normalizer

/** Proste połączenie FTP do pobierania wielu plików jednym logowaniem. */
class FtpSession(private val host: String, port: Int, user: String, pass: String) : Closeable {
    private val s = Socket()
    private val r: BufferedReader
    private val w: java.io.Writer

    init {
        s.connect(InetSocketAddress(host, port), 5000)
        s.soTimeout = 15000
        r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        w = s.getOutputStream().writer(Charsets.UTF_8)
        expect(read(), "220")
        var resp = cmd("USER ${user.ifBlank { "root" }}")
        if (resp.startsWith("331")) resp = cmd("PASS $pass")
        expect(resp, "230")
        expect(cmd("TYPE I"), "200")
    }

    private fun read(): String {
        var line = r.readLine() ?: throw IOException("FTP: połączenie zamknięte")
        if (line.length >= 4 && line[3] == '-') {
            val code = line.substring(0, 3)
            while (true) {
                val l = r.readLine() ?: throw IOException("FTP: połączenie zamknięte")
                if (l.startsWith("$code ")) { line = l; break }
            }
        }
        return line
    }

    private fun cmd(c: String): String { w.write(c + "\r\n"); w.flush(); return read() }

    private fun expect(resp: String, vararg ok: String) {
        if (ok.none { resp.startsWith(it) }) throw IOException("FTP: $resp")
    }

    /** Lista nazw plików w katalogu (NLST) albo null. */
    fun list(dir: String): List<String>? = transfer("NLST $dir")?.toString(Charsets.UTF_8)
        ?.lines()?.map { it.trim().substringAfterLast('/') }?.filter { it.isNotEmpty() }

    /** Zawartość pliku albo null, gdy nie istnieje. */
    fun retr(path: String): ByteArray? = transfer("RETR $path")

    private fun transfer(command: String): ByteArray? {
        val pasv = cmd("PASV")
        if (!pasv.startsWith("227")) return null
        val g = Regex("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").find(pasv)?.groupValues ?: return null
        val port = g[5].toInt() * 256 + g[6].toInt()
        Socket().use { d ->
            d.connect(InetSocketAddress(host, port), 5000)
            d.soTimeout = 15000
            val resp = cmd(command)
            if (!resp.startsWith("150") && !resp.startsWith("125")) return null
            val out = ByteArrayOutputStream()
            d.getInputStream().use { it.copyTo(out) }
            d.close()
            read() // 226
            return out.toByteArray()
        }
    }

    override fun close() {
        try { cmd("QUIT") } catch (_: Exception) { }
        try { s.close() } catch (_: Exception) { }
    }
}

/**
 * Ikony kanałów (picony) z dekodera: najpierw przez OpenWebif (/picon/), potem przez FTP
 * z typowych katalogów. Zapisywane w pliki/picons/, tylko dla kanałów z widocznych bukietów.
 */
object Picons {
    private val mem = LruCache<String, Bitmap>(300)
    private val missing = HashSet<String>()
    private val FTP_DIRS = listOf(
        "/usr/share/enigma2/picon", "/media/hdd/picon", "/media/usb/picon",
        "/media/mmc/picon", "/picon", "/data/picon",
    )

    private fun dir(ctx: Context) = File(ctx.filesDir, "picons").also { it.mkdirs() }

    private fun localName(ref: String) = OpenWebif.key(ref).replace(':', '_') + ".png"

    /** Nazwy plików picon do wypróbowania: referencja, referencja z typem 1, nazwa (SNP). */
    fun candidates(c: Channel): List<String> {
        val f = c.ref.trimEnd(':').split(':')
        if (f.size < 10) return listOf(snp(c.name) + ".png")
        val base = f.take(10).map { it.uppercase() }
        val out = LinkedHashSet<String>()
        out.add(base.joinToString("_") + ".png")
        out.add(base.toMutableList().also { it[2] = "1" }.joinToString("_") + ".png")
        out.add(base.joinToString("_").lowercase() + ".png")
        out.add(snp(c.name) + ".png")
        return out.toList()
    }

    private fun snp(name: String): String {
        val n = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        return n.replace("&", "and").replace("+", "plus").replace("*", "star").replace(Regex("[^a-z0-9]"), "")
    }

    fun bitmap(ctx: Context, ref: String): Bitmap? {
        val k = localName(ref)
        mem.get(k)?.let { return it }
        synchronized(missing) { if (k in missing) return null }
        val f = File(dir(ctx), k)
        val b = if (f.exists()) try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null } else null
        if (b != null) mem.put(k, b) else synchronized(missing) { missing.add(k) }
        return b
    }

    private fun httpGet(url: String, auth: String?): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 5000
        auth?.let { c.setRequestProperty("Authorization", it) }
        try {
            if (c.responseCode == 200 && (c.contentType ?: "").contains("image")) c.inputStream.use { it.readBytes() } else null
        } finally { c.disconnect() }
    } catch (_: Exception) { null }

    /** Katalog picon z ustawień Enigmy2 (/etc/enigma2/settings), np. config.usage.picon_dir=/media/hdd/picon. */
    fun detectDir(prefs: Prefs): String? = try {
        FtpSession(prefs.host, 21, prefs.ftpUser, prefs.ftpPass).use { ftp ->
            val st = ftp.retr("/etc/enigma2/settings")?.toString(Charsets.UTF_8) ?: return null
            st.lines().map { it.trim() }
                .filter { it.contains("picon", true) && it.contains('=') }
                .map { it.substringAfter('=').trim().trimEnd('/') }
                .firstOrNull { it.startsWith("/") && it.length > 1 }
        }
    } catch (e: Exception) {
        AppLog.i("ikony: nie udało się odczytać ustawień Enigmy2 przez FTP: $e"); null
    }

    /** Usuwa zapisane ikony (do pobrania od nowa). */
    fun clear(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
        synchronized(missing) { missing.clear() }
        mem.evictAll()
    }

    /** FTP: lista katalogu i dopasowanie po referencji (dokładne, potem luźne SID_TSID_ONID_NS). */
    private fun ftpStage(prefs: Prefs, d: File, chans: List<Channel>, dirs: List<String>, progress: (String) -> Unit): List<Channel> {
        if (chans.isEmpty()) return chans
        val left = ArrayList<Channel>()
        try {
            FtpSession(prefs.host, 21, prefs.ftpUser, prefs.ftpPass).use { ftp ->
                var found: Pair<String, Set<String>>? = null
                for (pd in dirs.distinct()) {
                    val names = ftp.list(pd)?.toSet() ?: continue
                    if (names.any { it.endsWith(".png", true) }) { found = pd to names; break }
                }
                if (found == null) {
                    AppLog.i("ikony: brak plików .png w katalogach: ${dirs.distinct().joinToString()}")
                    return chans
                }
                val (pd, names) = found
                AppLog.i("ikony: katalog picon na dekoderze: $pd (${names.size} plików)")
                val bySvc = HashMap<String, String>()
                val byName = HashMap<String, String>()
                val upper = HashMap<String, String>()
                for (n in names) {
                    if (!n.endsWith(".png", true)) continue
                    val base = n.substring(0, n.length - 4)
                    upper.putIfAbsent(n.uppercase(), n)
                    val f = base.split('_')
                    if (f.size >= 7) bySvc.putIfAbsent(f.subList(3, 7).joinToString("_").uppercase(), n)
                    byName.putIfAbsent(base.lowercase(), n)
                }
                for ((i, c) in chans.withIndex()) {
                    val rf = c.ref.trimEnd(':').split(':')
                    val svc = if (rf.size >= 7) rf.subList(3, 7).joinToString("_").uppercase() else ""
                    val sn = snp(c.name)
                    val n = candidates(c).firstNotNullOfOrNull { upper[it.uppercase()] }
                        ?: bySvc[svc]
                        ?: byName[sn] ?: byName[sn.removeSuffix("hd")] ?: byName[sn + "hd"]
                    val data = n?.let { ftp.retr("$pd/$it") }
                    if (data != null && data.isNotEmpty()) File(d, localName(c.ref)).writeBytes(data) else left.add(c)
                    if (i % 20 == 0) progress("Ikony (FTP): $i/${chans.size}")
                }
            }
        } catch (e: Exception) {
            AppLog.i("ikony: FTP nieudane: $e")
            return chans.filter { !File(d, localName(it.ref)).exists() }
        }
        return left
    }

    /** OpenWebif /picon/<plik>. */
    private fun httpStage(prefs: Prefs, d: File, chans: List<Channel>, progress: (String) -> Unit): List<Channel> {
        if (chans.isEmpty()) return chans
        val auth = OpenWebif(prefs).authHeader()
        val left = ArrayList<Channel>()
        var fails = 0
        for ((i, c) in chans.withIndex()) {
            if (fails >= 5 && left.size == i) { left.addAll(chans.subList(i, chans.size)); break } // OpenWebif nie podaje ikon
            var got: ByteArray? = null
            for (n in candidates(c)) {
                got = httpGet("http://${prefs.host}:${prefs.httpPort}/picon/$n", auth)
                if (got != null) break
            }
            if (got != null) { File(d, localName(c.ref)).writeBytes(got); fails = -1000 } else { left.add(c); fails++ }
            if (i % 20 == 0) progress("Ikony: $i/${chans.size}")
        }
        return left
    }

    /** Pobiera brakujące ikony; zwraca liczbę ikon dostępnych po pobraniu. */
    fun fetch(ctx: Context, prefs: Prefs, chans: List<Channel>, progress: (String) -> Unit): Int {
        val d = dir(ctx)
        var left: List<Channel> = chans.filter { !File(d, localName(it.ref)).exists() }
        if (left.isNotEmpty()) {
            val cfg = detectDir(prefs)
            if (cfg != null) {
                AppLog.i("ikony: katalog picon z ustawień Enigmy2: $cfg")
                if (prefs.piconDir.isBlank()) prefs.piconDir = cfg
            }
            val preferred = listOfNotNull(prefs.piconDir.trimEnd('/').takeIf { it.isNotBlank() }, cfg)
            // 1) katalog z ustawień (Enigma2 / ręczny), 2) OpenWebif, 3) typowe katalogi
            if (preferred.isNotEmpty()) left = ftpStage(prefs, d, left, preferred, progress)
            left = httpStage(prefs, d, left, progress)
            if (left.isNotEmpty()) left = ftpStage(prefs, d, left, FTP_DIRS, progress)
        }
        val none = chans.filter { !File(d, localName(it.ref)).exists() }
        if (none.isNotEmpty()) {
            AppLog.i("ikony: brak dla ${none.size} kanałów: " +
                none.take(40).joinToString("; ") { "${it.name} (${candidates(it).first()})" })
        }
        synchronized(missing) { missing.clear() }
        mem.evictAll()
        return chans.size - none.size
    }
}
