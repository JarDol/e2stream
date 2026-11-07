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
                        FoundReceiver(ip, "$brand $model  ($distro $ver)".trim())
                    }
                    401 -> FoundReceiver(ip, "OpenWebif (wymaga logowania)")
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
