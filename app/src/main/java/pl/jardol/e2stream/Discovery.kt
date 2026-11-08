package pl.jardol.e2stream

import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class FoundReceiver(val ip: String, val label: String)

/**
 * Szuka dekoderów Enigma2 w sieci lokalnej: skanuje /24 własnych interfejsów
 * na porcie OpenWebif i pyta /api/about. Wywoływać poza wątkiem UI.
 */
object Discovery {

    fun localPrefixes(): List<String> {
        val out = LinkedHashSet<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        val parts = a.hostAddress?.split(".") ?: continue
                        if (parts.size == 4) out.add("${parts[0]}.${parts[1]}.${parts[2]}.")
                    }
                }
            }
        } catch (_: Exception) { }
        return out.toList()
    }

    fun scan(port: Int, onProgress: (Int, Int) -> Unit): List<FoundReceiver> {
        val prefixes = localPrefixes()
        val ips = prefixes.flatMap { p -> (1..254).map { p + it } }
        val found = java.util.Collections.synchronizedList(ArrayList<FoundReceiver>())
        val pool = Executors.newFixedThreadPool(48)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        for (ip in ips) {
            pool.execute {
                try {
                    probe(ip, port)?.let { found.add(it) }
                } finally {
                    onProgress(done.incrementAndGet(), ips.size)
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)
        return found.sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
    }

    /** Wersja wtyczki EPG Scan (serwer dla klientów, port 8770) albo null. */
    private fun epgScanVersion(ip: String): String? = try {
        val c = URL("http://$ip:8770/api/info").openConnection() as HttpURLConnection
        c.connectTimeout = 1200
        c.readTimeout = 2000
        try {
            if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("version").ifBlank { null } else null
        } finally { c.disconnect() }
    } catch (_: Exception) { null }

    private fun probe(ip: String, port: Int): FoundReceiver? {
        try {
            Socket().use { it.connect(InetSocketAddress(ip, port), 350) }
        } catch (_: Exception) {
            return null
        }
        return try {
            val c = URL("http://$ip:$port/api/about").openConnection() as HttpURLConnection
            c.connectTimeout = 1500
            c.readTimeout = 2500
            try {
                when (c.responseCode) {
                    200 -> {
                        val js = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                        val info = js.optJSONObject("info") ?: return null
                        val brand = info.optString("brand")
                        val model = info.optString("model")
                        val distro = info.optString("imagedistro")
                        val ver = info.optString("imagever")
                        val epgscan = epgScanVersion(ip)
                        FoundReceiver(ip, "$brand $model  ($distro $ver)".trim() + (if (epgscan != null) "  + EPG Scan $epgscan" else ""))
                    }
                    401 -> FoundReceiver(ip, "OpenWebif (wymaga logowania)" + (epgScanVersion(ip)?.let { "  + EPG Scan $it" } ?: ""))
                    else -> null
                }
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }
}
