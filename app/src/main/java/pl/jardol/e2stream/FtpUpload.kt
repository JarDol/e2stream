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
            if (remoteDir.isNotBlank() && !cmd("CWD $remoteDir").startsWith("250")) {
                // katalog nie istnieje — tworzymy go po kolei, segment po segmencie
                var path = ""
                for (seg in remoteDir.trim('/').split('/').filter { it.isNotEmpty() }) {
                    path += "/$seg"
                    if (!cmd("CWD $path").startsWith("250")) {
                        val m = cmd("MKD $path")
                        if (!m.startsWith("257") && !cmd("CWD $path").startsWith("250")) throw IOException("FTP: nie można utworzyć $path: $m")
                    }
                }
                expect(cmd("CWD $remoteDir"), "250")
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
