package pl.jardol.e2stream

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors
import kotlin.math.abs

class PlayerActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var api: OpenWebif

    private lateinit var playerView: PlayerView
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var list: ListView
    private lateinit var banner: LinearLayout
    private lateinit var bannerName: TextView
    private lateinit var bannerNow: TextView
    private lateinit var numEntry: TextView
    private lateinit var stats: TextView
    private lateinit var message: TextView

    private var player: ExoPlayer? = null
    private var watchdog: StreamWatchdog? = null
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var bouquets: List<Bouquet> = emptyList()
    private var playBouquet = 0
    private var playList: List<Channel> = emptyList()
    private var playIndex = -1
    private var prevBouquet = -1
    private var prevRef: String? = null

    private var panelBouquet = 0
    private var panelList: List<Channel> = emptyList()
    private val adapter = ChannelAdapter()

    private var currentUrl: String? = null
    private var restarts = 0
    private var lastRestartReason = "-"
    private var failStreak = 0
    private var restartPending = false
    private var pendingReason = ""
    private var settingsVersion = -1
    private var settingsShown = false
    private var numberBuffer = ""
    private var suppressOkUp = false
    private var lastBackAt = 0L
    private var streamGen = 0
    private var audioAppliedGen = -1

    // ---------------------------------------------------------------- cykl życia

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        prefs = Prefs(this)
        api = OpenWebif(prefs)
        AppLog.init(this)
        AppLog.i("=== Start E2Stream ${appVersion()} | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ekran: ${displayInfo()}")

        playerView = findViewById(R.id.playerView)
        panel = findViewById(R.id.panel)
        panelTitle = findViewById(R.id.panelTitle)
        list = findViewById(R.id.list)
        banner = findViewById(R.id.banner)
        bannerName = findViewById(R.id.bannerName)
        bannerNow = findViewById(R.id.bannerNow)
        numEntry = findViewById(R.id.numEntry)
        stats = findViewById(R.id.stats)
        message = findViewById(R.id.message)

        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> onPanelPick(pos) }
    }

    override fun onStart() {
        super.onStart()
        if (prefs.host.isBlank()) {
            if (settingsShown) { finish(); return }
            settingsShown = true
            openSettings()
            return
        }
        buildPlayer()
        applyDisplayMode()
        ui.removeCallbacks(logTick)
        ui.postDelayed(logTick, 120_000)
        if (settingsVersion != prefs.version || bouquets.isEmpty()) {
            settingsVersion = prefs.version
            loadBouquets()
        } else {
            currentUrl?.let { startStream(it) }
        }
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
        ui.removeCallbacks(logTick)
        if (AppLog.dirty) uploadLog(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    // ---------------------------------------------------------------- odtwarzacz

    private fun buildPlayer() {
        if (player != null) return

        val trackSelector = DefaultTrackSelector(this)
        val langs = prefs.audioLang.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val params = trackSelector.buildUponParameters()
            .setTunnelingEnabled(prefs.tunneling)
            .setPreferredAudioLanguages(*langs.toTypedArray())
        if (prefs.preferAc3) {
            params.setPreferredAudioMimeTypes(MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AC3)
        }
        trackSelector.setParameters(params.build())

        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)

        val start = prefs.bufferMs.coerceIn(500, 10_000)
        val minBuf = maxOf(start * 2, 3000)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuf, maxOf(minBuf, 15_000), start, start)
            .build()

        val p = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(load)
            .build()

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                AppLog.i("BŁĄD ${error.errorCodeName}: ${error.message} / ${error.cause}")
                scheduleRestart("błąd: ${error.errorCodeName}")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val n = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    else -> "ENDED"
                }
                AppLog.i("stan: $n (bufor ${p.totalBufferedDuration} ms)")
                if (playbackState == Player.STATE_ENDED) scheduleRestart("koniec strumienia")
            }

            override fun onTracksChanged(tracks: Tracks) {
                applySavedAudio()
            }
        })

        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                AppLog.i("utracone klatki: $droppedFrames w $elapsedMs ms")
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                AppLog.i("wideo: ${format.width}x${format.height} ${format.sampleMimeType} fps=${format.frameRate} id=${format.id}")
                if (prefs.afrMode == 2) ui.post { applyDisplayMode() }
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                AppLog.i("audio: ${format.sampleMimeType} ${format.channelCount}ch ${format.sampleRate} Hz lang=${format.language} id=${format.id}")
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                AppLog.i("dekoder wideo: $decoderName (tunelowanie ${if (prefs.tunneling) "wł." else "wył."})")
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                AppLog.i("dekoder audio: $decoderName")
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                AppLog.i("audio underrun (bufor $bufferSizeMs ms, od ostatnich danych $elapsedSinceLastFeedMs ms)")
            }
        })

        playerView.player = p
        player = p

        if (prefs.watchdog) {
            val stall = prefs.stallSec.coerceIn(1, 30) * 1000L
            watchdog = StreamWatchdog(
                player = p,
                stallMs = stall,
                bufferingMs = maxOf(stall * 2, 8000L),
                onStall = { reason -> scheduleRestart(reason) },
                onHealthy = { failStreak = 0 },
            ).also { it.start() }
        }
        ui.removeCallbacks(statsTick)
        ui.post(statsTick)
    }

    private fun releasePlayer() {
        watchdog?.stop()
        watchdog = null
        ui.removeCallbacks(restartRunnable)
        ui.removeCallbacks(statsTick)
        restartPending = false
        playerView.player = null
        player?.release()
        player = null
    }

    private fun buildSource(url: String): MediaSource {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("E2Stream")
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(6000)
            .setAllowCrossProtocolRedirects(true)
        api.authHeader()?.let { http.setDefaultRequestProperties(mapOf("Authorization" to it)) }

        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        return ProgressiveMediaSource.Factory(http, extractors)
            .createMediaSource(MediaItem.fromUri(url))
    }

    private fun startStream(url: String) {
        val p = player ?: return
        currentUrl = url
        streamGen++
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
        p.setMediaSource(buildSource(url))
        p.prepare()
        p.playWhenReady = true
        watchdog?.reset()
    }

    private val restartRunnable = Runnable { doRestart() }

    private fun scheduleRestart(reason: String) {
        if (currentUrl == null || restartPending) return
        restartPending = true
        pendingReason = reason
        val delays = longArrayOf(0, 1000, 2000, 5000, 10_000)
        val d = delays[minOf(failStreak, delays.size - 1)]
        failStreak++
        AppLog.i("WZNOWIENIE za $d ms: $reason")
        ui.postDelayed(restartRunnable, d)
    }

    private fun doRestart() {
        restartPending = false
        val url = currentUrl ?: return
        restarts++
        lastRestartReason = pendingReason
        showMessage("Wznawiam strumień ($pendingReason)")
        startStream(url)
    }

    // ---------------------------------------------------------------- kanały

    private fun loadBouquets() {
        showMessage("Łączenie z ${prefs.host}…", 10_000)
        io.execute {
            try {
                val bq = api.bouquets()
                val bi = bq.indexOfFirst { it.ref == prefs.lastBouquet }.let { if (it < 0) 0 else it }
                val ch = if (bq.isEmpty()) emptyList() else api.channels(bq[bi])
                runOnUiThread {
                    bouquets = bq
                    if (bq.isEmpty()) {
                        showMessage("Brak bukietów na dekoderze", 8000)
                        return@runOnUiThread
                    }
                    message.visibility = View.GONE
                    val ci = ch.indexOfFirst { it.ref == prefs.lastChannel }.let { if (it < 0) 0 else it }
                    playIndex = -1
                    if (ch.isNotEmpty()) playIn(bi, ch, ci)
                }
            } catch (e: Exception) {
                AppLog.i("błąd połączenia z OpenWebif: $e")
                runOnUiThread {
                    showMessage("Błąd połączenia z dekoderem: ${e.message}\nPrzytrzymaj OK → menu → Ustawienia", 15_000)
                }
            }
        }
    }

    private fun playIn(bIdx: Int, chList: List<Channel>, index: Int) {
        if (chList.isEmpty()) return
        val i = ((index % chList.size) + chList.size) % chList.size
        val cur = playList.getOrNull(playIndex)
        if (cur != null && !(bIdx == playBouquet && i == playIndex)) {
            prevBouquet = playBouquet
            prevRef = cur.ref
        }
        playBouquet = bIdx
        playList = chList
        playIndex = i
        val ch = chList[i]
        prefs.lastBouquet = bouquets.getOrNull(bIdx)?.ref ?: ""
        prefs.lastChannel = ch.ref

        failStreak = 0
        ui.removeCallbacks(restartRunnable)
        restartPending = false
        AppLog.i("kanał: ${ch.num} ${ch.name} [${bouquets.getOrNull(bIdx)?.name}] ${api.streamUrl(ch)}")
        startStream(api.streamUrl(ch))
        showBanner(ch)
        fetchNow(ch)
    }

    private fun play(index: Int) = playIn(playBouquet, playList, index)

    private fun recall() {
        val ref = prevRef ?: return
        val b = prevBouquet
        if (b == playBouquet) {
            val i = playList.indexOfFirst { it.ref == ref }
            if (i >= 0) play(i)
            return
        }
        val bq = bouquets.getOrNull(b) ?: return
        io.execute {
            val ch = try { api.channels(bq) } catch (e: Exception) { return@execute }
            runOnUiThread {
                val i = ch.indexOfFirst { it.ref == ref }
                if (i >= 0) playIn(b, ch, i)
            }
        }
    }

    private fun fetchNow(ch: Channel) {
        io.execute {
            val t = try { api.serviceNow(ch.ref) } catch (e: Exception) { null }
            if (t != null) runOnUiThread {
                ch.now = t
                if (playList.getOrNull(playIndex) === ch && banner.visibility == View.VISIBLE) {
                    bannerNow.text = t
                }
            }
        }
    }

    // ---------------------------------------------------------------- panel listy kanałów

    private fun showPanel() {
        if (bouquets.isEmpty()) return
        panelBouquet = playBouquet
        panelList = playList
        refreshPanel(playIndex.coerceAtLeast(0))
        panel.visibility = View.VISIBLE
        list.requestFocus()
        loadPanelEpg()
    }

    private fun hidePanel() {
        panel.visibility = View.GONE
    }

    private fun refreshPanel(sel: Int) {
        panelTitle.text = "◀  ${bouquets.getOrNull(panelBouquet)?.name ?: ""}  ▶"
        adapter.notifyDataSetChanged()
        if (panelList.isNotEmpty()) list.setSelection(sel.coerceIn(0, panelList.size - 1))
    }

    private fun panelBouquetStep(d: Int) {
        if (bouquets.isEmpty()) return
        val b = ((panelBouquet + d) % bouquets.size + bouquets.size) % bouquets.size
        panelBouquet = b
        if (b == playBouquet) {
            panelList = playList
            refreshPanel(playIndex.coerceAtLeast(0))
            loadPanelEpg()
            return
        }
        panelList = emptyList()
        refreshPanel(0)
        val bq = bouquets[b]
        io.execute {
            val ch = try { api.channels(bq) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (panelBouquet == b) {
                    panelList = ch
                    refreshPanel(0)
                    loadPanelEpg()
                }
            }
        }
    }

    private fun loadPanelEpg() {
        val b = bouquets.getOrNull(panelBouquet) ?: return
        val target = panelList
        io.execute {
            val map = try { api.epgNow(b) } catch (e: Exception) { emptyMap() }
            runOnUiThread {
                target.forEach { c -> map[OpenWebif.key(c.ref)]?.let { c.now = it } }
                if (panelList === target) adapter.notifyDataSetChanged()
            }
        }
    }

    private fun onPanelPick(pos: Int) {
        val l = panelList
        if (pos !in l.indices) return
        hidePanel()
        playIn(panelBouquet, l, pos)
    }

    private inner class ChannelAdapter : BaseAdapter() {
        override fun getCount() = panelList.size
        override fun getItem(position: Int): Any = panelList[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_channel, parent, false)
            val c = panelList[position]
            v.findViewById<TextView>(R.id.num).text = c.num.toString()
            val name = v.findViewById<TextView>(R.id.name)
            name.text = c.name
            val playing = panelBouquet == playBouquet && position == playIndex
            name.setTextColor(if (playing) 0xFF4FC3F7.toInt() else 0xFFFFFFFF.toInt())
            v.findViewById<TextView>(R.id.now).text = c.now
            return v
        }
    }

    // ---------------------------------------------------------------- nakładki

    private val hideBanner = Runnable { banner.visibility = View.GONE }
    private val hideMessage = Runnable { message.visibility = View.GONE }

    private fun showBanner(ch: Channel) {
        bannerName.text = "${ch.num}   ${ch.name}"
        bannerNow.text = ch.now
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, 4000)
    }

    private fun showMessage(t: String, ms: Long = 3000) {
        message.text = t
        message.visibility = View.VISIBLE
        ui.removeCallbacks(hideMessage)
        ui.postDelayed(hideMessage, ms)
    }

    private val statsTick = object : Runnable {
        override fun run() {
            if (stats.visibility == View.VISIBLE) stats.text = statsText()
            ui.postDelayed(this, 1000)
        }
    }

    private fun toggleStats() {
        if (stats.visibility == View.VISIBLE) {
            stats.visibility = View.GONE
        } else {
            stats.text = statsText()
            stats.visibility = View.VISIBLE
        }
    }

    private fun statsText(): String {
        val p = player ?: return ""
        val v = p.videoFormat
        val a = p.audioFormat
        val wd = watchdog
        val ch = playList.getOrNull(playIndex)
        return buildString {
            appendLine("${ch?.num ?: ""}  ${ch?.name ?: ""}")
            appendLine(
                "Obraz: " + (v?.let {
                    "${it.width}x${it.height} ${it.sampleMimeType ?: "?"}" +
                        (if (it.frameRate > 0) " %.2f fps".format(it.frameRate) else "")
                } ?: "-")
            )
            appendLine(
                "Dźwięk: " + (a?.let {
                    "${it.sampleMimeType ?: "?"} ${it.channelCount}ch ${it.language ?: ""}"
                } ?: "-")
            )
            appendLine("Tunelowanie: " + if (prefs.tunneling) "włączone" else "wyłączone")
            appendLine("Bufor: ${p.totalBufferedDuration} ms")
            appendLine("Ekran: ${displayInfo()}")
            appendLine("Odświeżanie: ${afrName(prefs.afrMode)}")
            if (wd != null) {
                appendLine("Klatki: ${wd.frames}   utracone: ${wd.dropped}")
                appendLine("Watchdog: ${wd.mode}")
            } else {
                appendLine("Watchdog: wyłączony")
            }
            appendLine("Wznowienia: $restarts")
            append("Ostatni powód: $lastRestartReason")
        }
    }

    // ---------------------------------------------------------------- numer kanału

    private val commitNumberRunnable = Runnable { commitNumber() }

    private fun digit(d: Int) {
        if (numberBuffer.length >= 4) numberBuffer = ""
        numberBuffer += d.toString()
        numEntry.text = numberBuffer
        numEntry.visibility = View.VISIBLE
        ui.removeCallbacks(commitNumberRunnable)
        ui.postDelayed(commitNumberRunnable, 1800)
    }

    private fun commitNumber() {
        ui.removeCallbacks(commitNumberRunnable)
        val n = numberBuffer.toIntOrNull()
        numberBuffer = ""
        numEntry.visibility = View.GONE
        if (n == null) return
        val i = playList.indexOfFirst { it.num == n }
        if (i >= 0) play(i) else showMessage("Brak kanału $n w bukiecie")
    }

    // ---------------------------------------------------------------- pilot

    private fun isOk(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (panel.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { panelBouquetStep(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { panelBouquetStep(1); return true }
                KeyEvent.KEYCODE_BACK -> { hidePanel(); return true }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    if (panelList.isNotEmpty()) list.setSelection(maxOf(0, list.selectedItemPosition - 10))
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (panelList.isNotEmpty()) list.setSelection(minOf(panelList.size - 1, list.selectedItemPosition + 10))
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            digit(keyCode - KeyEvent.KEYCODE_0); return true
        }
        if (keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) {
            digit(keyCode - KeyEvent.KEYCODE_NUMPAD_0); return true
        }

        if (isOk(keyCode)) {
            if (event.repeatCount == 0) {
                suppressOkUp = false
                if (numberBuffer.isNotEmpty()) {
                    commitNumber()
                    suppressOkUp = true
                } else {
                    event.startTracking()
                }
            }
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { play(playIndex + 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { play(playIndex - 1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { showPanel(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> { toggleStats(); return true }
            KeyEvent.KEYCODE_MENU -> { showQuickMenu(); return true }
            KeyEvent.KEYCODE_SETTINGS -> { openSettings(); return true }
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { showAudioDialog(); return true }
            KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { recall(); return true }
            KeyEvent.KEYCODE_BACK -> {
                if (stats.visibility == View.VISIBLE) { stats.visibility = View.GONE; return true }
                if (banner.visibility == View.VISIBLE) { banner.visibility = View.GONE; return true }
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackAt < 2500) {
                    finish()
                } else {
                    lastBackAt = now
                    showMessage("Naciśnij ponownie WSTECZ, aby wyjść", 2500)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (panel.visibility != View.VISIBLE && isOk(keyCode)) {
            suppressOkUp = true
            showQuickMenu()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (panel.visibility != View.VISIBLE && isOk(keyCode)) {
            if (!suppressOkUp && !event.isCanceled) showPanel()
            suppressOkUp = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ---------------------------------------------------------------- menu podręczne

    private fun showQuickMenu() {
        val items = arrayOf(
            "Ścieżka dźwięku…",
            if (stats.visibility == View.VISIBLE) "Ukryj statystyki" else "Pokaż statystyki",
            "Odświeżanie ekranu: ${afrName(prefs.afrMode)}",
            "Wyślij log na dekoder",
            "Ustawienia",
        )
        AlertDialog.Builder(this)
            .setTitle("E2Stream ${appVersion()}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showAudioDialog()
                    1 -> toggleStats()
                    2 -> {
                        prefs.afrMode = (prefs.afrMode + 1) % 3
                        AppLog.i("odświeżanie: ${afrName(prefs.afrMode)}")
                        applyDisplayMode()
                        showMessage("Odświeżanie ekranu: ${afrName(prefs.afrMode)}")
                    }
                    3 -> uploadLog(true)
                    4 -> openSettings()
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- dźwięk

    private class AudioTrack(val group: Tracks.Group, val index: Int, val format: Format)

    private fun audioTracks(): List<AudioTrack> {
        val p = player ?: return emptyList()
        val out = ArrayList<AudioTrack>()
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) {
                if (g.isTrackSupported(i)) out.add(AudioTrack(g, i, g.getTrackFormat(i)))
            }
        }
        return out
    }

    private fun audioLabel(f: Format): String {
        val codec = when (f.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "AC3"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3"
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2 -> "MPEG"
            MimeTypes.AUDIO_AAC -> "AAC"
            else -> f.sampleMimeType ?: "?"
        }
        val ch = if (f.channelCount > 0) " ${f.channelCount}ch" else ""
        val label = f.label?.let { " – $it" } ?: ""
        return "${f.language ?: "?"}  $codec$ch$label"
    }

    private fun showAudioDialog() {
        val tracks = audioTracks()
        if (tracks.isEmpty()) {
            showMessage("Brak ścieżek dźwięku"); return
        }
        val labels = tracks.map { audioLabel(it.format) }.toTypedArray()
        val sel = tracks.indexOfFirst { it.group.isTrackSelected(it.index) }
        AlertDialog.Builder(this)
            .setTitle("Ścieżka dźwięku (zapamiętywana dla kanału)")
            .setSingleChoiceItems(labels, sel) { d, which ->
                selectAudio(tracks[which], remember = true)
                d.dismiss()
            }
            .show()
    }

    private fun selectAudio(t: AudioTrack, remember: Boolean) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
            .build()
        val ch = playList.getOrNull(playIndex)
        if (remember && ch != null) {
            val f = t.format
            prefs.setAudioFor(ch.ref, "${f.id ?: ""}|${f.language ?: ""}|${f.sampleMimeType ?: ""}")
            AppLog.i("wybrano audio dla ${ch.name}: ${audioLabel(f)}")
        }
    }

    /** Po starcie strumienia (także po wznowieniu) ustawia zapamiętaną ścieżkę kanału. */
    private fun applySavedAudio() {
        if (audioAppliedGen == streamGen) return
        val tracks = audioTracks()
        if (tracks.isEmpty()) return
        audioAppliedGen = streamGen
        val ch = playList.getOrNull(playIndex) ?: return
        val saved = prefs.audioFor(ch.ref)
        if (saved.isEmpty()) return
        val parts = saved.split("|")
        val id = parts.getOrElse(0) { "" }
        val lang = parts.getOrElse(1) { "" }
        val mime = parts.getOrElse(2) { "" }
        val match = tracks.firstOrNull { id.isNotEmpty() && it.format.id == id }
            ?: tracks.firstOrNull { (it.format.language ?: "") == lang && (it.format.sampleMimeType ?: "") == mime }
            ?: return
        if (!match.group.isTrackSelected(match.index)) selectAudio(match, remember = false)
    }

    // ---------------------------------------------------------------- log na dekoder

    private val logTick = object : Runnable {
        override fun run() {
            if (AppLog.dirty) uploadLog(false)
            ui.postDelayed(this, 120_000)
        }
    }

    private fun uploadLog(manual: Boolean) {
        if (!prefs.logFtp && !manual) return
        val host = prefs.host
        if (host.isBlank()) return
        val user = prefs.ftpUser
        val pass = prefs.ftpPass
        val dir = prefs.ftpDir
        val name = "e2stream-" + Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".log"
        val data = AppLog.snapshot()
        Thread {
            try {
                FtpUpload.upload(host, 21, user, pass, dir, name, data)
                if (manual) runOnUiThread { showMessage("Log wysłany: $dir/$name", 5000) }
            } catch (e: Exception) {
                AppLog.markDirty()
                if (manual) runOnUiThread { showMessage("Nie udało się wysłać logu: ${e.message}", 8000) }
            }
        }.start()
    }

    // ---------------------------------------------------------------- odświeżanie ekranu

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    private fun afrName(m: Int) = when (m) {
        1 -> "zawsze 50 Hz"
        2 -> "automatycznie"
        else -> "bez zmian"
    }

    @Suppress("DEPRECATION")
    private fun displayInfo(): String {
        if (Build.VERSION.SDK_INT < 23) return "?"
        val d = windowManager.defaultDisplay ?: return "?"
        val m = d.mode
        val rates = d.supportedModes
            .filter { it.physicalWidth == m.physicalWidth && it.physicalHeight == m.physicalHeight }
            .map { "%.2f".format(it.refreshRate) }
            .distinct()
            .joinToString(", ")
        return "${m.physicalWidth}x${m.physicalHeight} @ " + "%.2f".format(m.refreshRate) + " Hz (dostępne: $rates)"
    }

    private fun targetRate(fr: Float): Float = when {
        fr <= 0f -> 50f // nieznane — DVB w Europie to 25/50
        abs(fr - 25f) < 1f || abs(fr - 50f) < 1f -> 50f
        abs(fr - 23.976f) < 0.05f -> 23.976f
        abs(fr - 24f) < 0.5f -> 24f
        abs(fr - 29.97f) < 0.1f || abs(fr - 59.94f) < 0.2f -> 59.94f
        abs(fr - 30f) < 0.5f || abs(fr - 60f) < 0.5f -> 60f
        else -> 50f
    }

    @Suppress("DEPRECATION")
    private fun applyDisplayMode() {
        if (Build.VERSION.SDK_INT < 23) return
        val d = windowManager.defaultDisplay ?: return
        val lp = window.attributes
        val mode = prefs.afrMode
        var target = 0
        if (mode != 0) {
            val rate = if (mode == 1) 50f else targetRate(player?.videoFormat?.frameRate ?: -1f)
            val cur = d.mode
            val same = d.supportedModes.filter {
                it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight
            }
            val exact = same.minByOrNull { abs(it.refreshRate - rate) }
                ?.takeIf { abs(it.refreshRate - rate) < 0.1f }
            val multiple = same.firstOrNull {
                val k = it.refreshRate / rate
                k >= 1.9f && abs(k - Math.round(k)) < 0.01f
            }
            target = (exact ?: multiple)?.modeId ?: 0
            if (target == 0) AppLog.i("ekran: brak trybu dla $rate Hz (${displayInfo()})")
        }
        if (lp.preferredDisplayModeId != target) {
            lp.preferredDisplayModeId = target
            window.attributes = lp
            AppLog.i("ekran: preferowany tryb id=$target")
        }
    }
}
