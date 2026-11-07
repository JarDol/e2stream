package pl.jardol.e2stream

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/** Minimalny klient FTP (tryb pasywny, binarny) do wysyłania logu na dekoder. */
object FtpUpload {

    fun upload(
        host: String,
        port: Int,
        user: String,
        pass: String,
        remoteDir: String,
        remoteName: String,
        data: ByteArray,
    ) {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 5000)
            s.soTimeout = 15000
            val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))

            fun read(): String {
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

            fun cmd(c: String): String {
                w.write(c + "\r\n")
                w.flush()
                return read()
            }

            fun expect(resp: String, vararg ok: String) {
                if (ok.none { resp.startsWith(it) }) throw IOException("FTP: $resp")
            }

            expect(read(), "220")
            var resp = cmd("USER ${user.ifBlank { "root" }}")
            if (resp.startsWith("331")) resp = cmd("PASS $pass")
            expect(resp, "230")
            expect(cmd("TYPE I"), "200")
            val pwd = cmd("PWD")
            val startDir = Regex("\"(.*)\"").find(pwd)?.groupValues?.get(1)
            fun ok(c: String) = cmd(c).startsWith("250")
            if (remoteDir.isNotBlank() && !ok("CWD $remoteDir")) {
                val segs = remoteDir.trim('/').split('/').filter { it.isNotEmpty() }
                // 1) ścieżka bezwzględna, tworzona segment po segmencie
                fun absolute(): Boolean {
                    var path = ""
                    for (seg in segs) {
                        path += "/$seg"
                        if (!ok("CWD $path")) {
                            cmd("MKD $path")
                            if (!ok("CWD $path")) return false
                        }
                    }
                    return true
                }
                // 2) serwer z „chroot” — ta sama ścieżka względem katalogu startowego
                fun relative(): Boolean {
                    if (startDir != null) cmd("CWD $startDir")
                    for (seg in segs) {
                        if (!ok("CWD $seg")) {
                            cmd("MKD $seg")
                            if (!ok("CWD $seg")) return false
                        }
                    }
                    return true
                }
                if (!absolute() && !relative()) {
                    throw IOException("FTP: nie można wejść do $remoteDir (katalog startowy serwera: ${startDir ?: pwd})")
                }
            }
            val pasv = cmd("PASV")
            expect(pasv, "227")
            val nums = Regex("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").find(pasv)
                ?: throw IOException("FTP: zła odpowiedź PASV: $pasv")
            val g = nums.groupValues
            val dataPort = g[5].toInt() * 256 + g[6].toInt()

            Socket().use { d ->
                // Adres z PASV bywa nieprawidłowy (NAT) — łączymy się z tym samym hostem.
                d.connect(InetSocketAddress(host, dataPort), 5000)
                expect(cmd("STOR $remoteName"), "150", "125")
                d.getOutputStream().use { it.write(data) }
            }
            expect(read(), "226", "250")
            try { cmd("QUIT") } catch (_: Exception) { }
        }
    }
}
