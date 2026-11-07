package pl.jardol.e2stream

import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Przepuszcza dane z sieci do ExoPlayera, po drodze podając je analizatorowi TS. */
class AnalyzingDataSource(
    private val upstream: DataSource,
    private val analyzer: () -> TsAnalyzer?,
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long = upstream.open(dataSpec)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val t0 = SystemClock.elapsedRealtime()
        val n = upstream.read(buffer, offset, length)
        val a = analyzer()
        if (a != null) {
            a.readTime(SystemClock.elapsedRealtime() - t0)
            if (n > 0) a.feed(buffer, offset, n)
        }
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}
