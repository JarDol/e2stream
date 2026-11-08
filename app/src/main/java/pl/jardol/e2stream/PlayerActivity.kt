package pl.jardol.e2stream

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
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
import androidx.media3.datasource.DataSource
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class PlayerActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var api: OpenWebif
    private lateinit var epg: EpgUi
    private lateinit var epgStore: EpgStore
    private var keyMap: MutableMap<Int, Act> = LinkedHashMap(KeyMap.defaults)
    private lateinit var bannerIcon: android.widget.ImageView

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
    private var safeMode = false
    private var numberBuffer = ""
    private var suppressOkUp = false
    private var lastBackAt = 0L
    private var streamGen = 0
    private var analyzer: TsAnalyzer? = null
    private var analyzerRing = -1
    private var lastDumpAt = 0L
    private var updateChecked = false
    private var streamStartAt = 0L
    private var readyLogged = false
    private var lastStartMs = -1L
    private var audioAppliedGen = -1

    // ---------------------------------------------------------------- cykl życia

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        prefs = Prefs(this)
        api = OpenWebif(prefs)
        prefs.migrate()
        AppLog.init(this, Storage.dir(this, prefs))
        epgStore = EpgStore(this)
        epg = EpgUi(this, api, io, epgStore, object : EpgUi.Host {
            override fun current(): Channel? = playList.getOrNull(playIndex)
            override fun bouquetChannels(): List<Channel> = playList
            override fun bouquetName(): String = bouquets.getOrNull(playBouquet)?.name ?: ""
            override fun zapTo(ref: String) = this@PlayerActivity.zapTo(ref)
            override fun message(t: String) = showMessage(t, 4000)
        })
        AppLog.i("=== Start E2Stream ${appVersion()} | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ekran: ${displayInfo()}")

        playerView = findViewById(R.id.playerView)
        panel = findViewById(R.id.panel)
        panelTitle = findViewById(R.id.panelTitle)
        list = findViewById(R.id.list)
        banner = findViewById(R.id.banner)
        bannerName = findViewById(R.id.bannerName)
        bannerNow = findViewById(R.id.bannerNow)
        bannerIcon = findViewById(R.id.bannerIcon)
        numEntry = findViewById(R.id.numEntry)
        stats = findViewById(R.id.stats)
        message = findViewById(R.id.message)

        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> onPanelPick(pos) }

        val crash = prefs.lastCrash
        if (crash.isNotEmpty()) {
            prefs.lastCrash = ""
            safeMode = true
            AppLog.i("start w trybie bezpiecznym po awarii")
            Tools.uploadLog(prefs, true) { }
            AlertDialog.Builder(this)
                .setTitle("Poprzednie uruchomienie zakończyło się błędem")
                .setMessage(crash.lines().take(14).joinToString("\n") +
                    "\n\nLog z pełnym opisem wysłano na dekoder (${prefs.ftpDir}/${Tools.logName()}).")
                .setPositiveButton("Kontynuuj") { _, _ -> safeMode = false; settingsVersion = -1; startUp() }
                .setNeutralButton("Ustawienia") { _, _ -> openSettings() }
                .setNegativeButton("Zamknij") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        if (safeMode) return
        startUp()
    }

    private fun startUp() {
        AppLog.i("start: host=${prefs.host} ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}")
        if (prefs.host.isBlank()) {
            if (settingsShown) { finish(); return }
            settingsShown = true
            openSettings()
            return
        }
        AppLog.init(this, Storage.dir(this, prefs))
        stats.visibility = if (prefs.showStats) View.VISIBLE else View.GONE
        keyMap = KeyMap.load(prefs)
        ui.removeCallbacks(epgTick)
        ui.postDelayed(epgTick, 30_000)
        ensureAnalyzer()
        buildPlayer()
        applyDisplayMode()
        if (prefs.checkUpdates && !updateChecked) {
            updateChecked = true
            ui.postDelayed({ checkUpdate(false) }, 8000)
        }
        ui.removeCallbacks(logTick)
        ui.postDelayed(logTick, 120_000)
        if (settingsVersion != prefs.version || bouquets.isEmpty()) {
            settingsVersion = prefs.version
            loadBouquets()
        } else {
            currentUrl?.let { startStream(it) }
        }
    }

    /** Co minutę: czy przypada pobranie EPG z harmonogramu. */
    private val epgTick = object : Runnable {
        override fun run() {
            if (EpgSync.due(prefs)) {
                AppLog.i("EPG: pobieranie wg harmonogramu (${prefs.epgHour})")
                EpgSync.run(applicationContext, prefs, epgStore, { }) { r ->
                    runOnUiThread { epg.clearMemory(); adapter.notifyDataSetChanged(); AppLog.i(r) }
                }
            }
            ui.postDelayed(this, 60_000)
        }
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(epgTick)
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

        val start = prefs.bufferMs.coerceIn(250, 10_000)
        val rebuffer = maxOf(start, 4000)
        // ExoPlayer wymaga: minBuffer ≥ bufor startowy i ≥ bufor po niedoborze
        val minBuf = maxOf(start * 2, rebuffer, 2000)
        val load = DefaultLoadControl.Builder()
            // Enigma2 wysyła dane paczkami (~0,5 MB): na kanałach SD przerwy między paczkami
            // to nawet 3–4 s, więc po niedoborze bufora czekamy na zapas, który je pokryje.
            .setBufferDurationsMs(minBuf, maxOf(minBuf, 15_000), start, rebuffer)
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
                if (playbackState == Player.STATE_READY && !readyLogged) {
                    readyLogged = true
                    AppLog.i("start: READY po ${SystemClock.elapsedRealtime() - streamStartAt} ms")
                }
                if (playbackState == Player.STATE_ENDED) scheduleRestart("koniec strumienia")
            }

            override fun onRenderedFirstFrame() {
                lastStartMs = SystemClock.elapsedRealtime() - streamStartAt
                AppLog.i("start: pierwszy obraz po $lastStartMs ms")
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
                dropRestart = prefs.dropRestart,
            ).also { it.start() }
        }
        ui.removeCallbacks(statsTick)
        ui.post(statsTick)
        ui.removeCallbacks(tsTick)
        ui.postDelayed(tsTick, 10_000)
        ui.removeCallbacks(pmtCheck)
        ui.postDelayed(pmtCheck, 500)
    }

    private fun ensureAnalyzer() {
        val ring = if (prefs.saveTs) prefs.tsRingMb.coerceIn(2, 64) * 1_000_000 else 0
        if (analyzer == null || ring != analyzerRing) {
            analyzer = TsAnalyzer(ring) { msg -> AppLog.i(msg) }.also { a ->
                a.pmtCacheGet = { sid -> prefs.pmtFor(sid) }
                a.pmtCachePut = { sid, b -> prefs.setPmtFor(sid, b) }
            }
            analyzerRing = ring
        }
        analyzer?.clean = prefs.cleanTs
    }

    /** Szybki restart, gdy dekoder nie przysłał PMT, a odtwarzacz wciąż buforuje. */
    private val pmtCheck = object : Runnable {
        override fun run() {
            val a = analyzer
            val p = player
            if (a != null && p != null && p.playbackState == Player.STATE_BUFFERING &&
                p.videoFormat == null && a.pmtMissingMs() > 3000
            ) {
                scheduleRestart("brak PMT w strumieniu ${a.pmtMissingMs()} ms")
            }
            ui.postDelayed(this, 500)
        }
    }

    private var lastRendered = -1L
    private var lastDropped = -1L

    private val tsTick = object : Runnable {
        override fun run() {
            val c = player?.videoDecoderCounters
            if (c != null) {
                c.ensureUpdated()
                val r = c.renderedOutputBufferCount.toLong()
                val d = c.droppedBufferCount.toLong()
                if (lastRendered in 0..r && lastDropped in 0..d) {
                    val dd = d - lastDropped
                    if (dd > 0) {
                        AppLog.i("dekoder 10 s: pokazane +${r - lastRendered}, utracone +$dd")
                        analyzer?.forceNext = true
                    }
                }
                lastRendered = r
                lastDropped = d
            } else {
                lastRendered = -1; lastDropped = -1
            }
            analyzer?.interval()?.let { AppLog.i(it) }
            ui.postDelayed(this, 10_000)
        }
    }

    private fun releasePlayer() {
        watchdog?.stop()
        watchdog = null
        ui.removeCallbacks(restartRunnable)
        ui.removeCallbacks(statsTick)
        ui.removeCallbacks(tsTick)
        ui.removeCallbacks(pmtCheck)
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
        val analyzing = DataSource.Factory {
            AnalyzingDataSource(http.createDataSource()) { analyzer }
        }
        return ProgressiveMediaSource.Factory(analyzing, extractors)
            .createMediaSource(MediaItem.fromUri(url))
    }

    private fun startStream(url: String) {
        val p = player ?: return
        currentUrl = url
        streamGen++
        streamStartAt = SystemClock.elapsedRealtime()
        readyLogged = false
        lastStartMs = -1
        // SID serwisu = 4. pole referencji (hex), np. 1:0:19:3DD2:640:13E:820000:0:0:0:
        val sid = url.substringAfterLast('/').split(":").getOrNull(3)?.toIntOrNull(16) ?: -1
        analyzer?.reset(sid)
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
        dumpStall(reason)
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
                val all = api.bouquets()
                val hidden = prefs.hiddenBouquets
                val bq = all.filter { it.ref !in hidden }.ifEmpty { all }
                AppLog.i("bukiety: ${bq.size} z ${all.size} widocznych")
                val bi = if (!prefs.resumeLast) 0
                else bq.indexOfFirst { it.ref == prefs.lastBouquet }.let { if (it < 0) 0 else it }
                val ch = if (bq.isEmpty()) emptyList() else api.channels(bq[bi])
                runOnUiThread {
                    bouquets = bq
                    if (bq.isEmpty()) {
                        showMessage("Brak bukietów na dekoderze", 8000)
                        return@runOnUiThread
                    }
                    message.visibility = View.GONE
                    val ci = if (!prefs.resumeLast) 0
                    else ch.indexOfFirst { it.ref == prefs.lastChannel }.let { if (it < 0) 0 else it }
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
            val iv = v.findViewById<android.widget.ImageView>(R.id.icon)
            val bmp = if (prefs.picons) Picons.bitmap(this@PlayerActivity, c.ref) else null
            iv.setImageBitmap(bmp)
            iv.visibility = if (bmp != null) View.VISIBLE else View.INVISIBLE
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
        val icon = if (prefs.picons) Picons.bitmap(this, ch.ref) else null
        bannerIcon.setImageBitmap(icon)
        bannerIcon.visibility = if (icon != null) View.VISIBLE else View.GONE
        bannerNow.text = ch.now
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, prefs.bannerSec.coerceIn(1, 30) * 1000L)
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
        prefs.showStats = stats.visibility != View.VISIBLE
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
            appendLine("Bufor: ${p.totalBufferedDuration} ms   start obrazu: " + (if (lastStartMs >= 0) "$lastStartMs ms" else "-"))
            analyzer?.let { a ->
                appendLine("TS: %.2f Mb/s  odczyt maks. ${a.maxGapMs} ms".format(a.bitrateKbps / 1000.0))
                appendLine("TS paczki: przerwy ~${a.burstAvgMs} ms   wyczyszczone: ${a.cleaned}   poprawione PTS: ${a.tsFixed}")
                appendLine("TS błędy: CC ${a.ccErrors}  TEI ${a.teiErrors}  kod. ${a.scrambled}  sync ${a.syncLoss}  PCR ${a.pcrJumps}  PMT ${a.pmtChanges}")
            }
            appendLine("Ekran: ${displayInfo()}")
            appendLine("Odświeżanie: ${afrName(prefs.afrMode)}")
            if (wd != null) {
                appendLine("Klatki: ${wd.frames}   utracone: ${wd.dropped}" + if (prefs.tunneling) " (w tunelu niewidoczne)" else "")
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
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> {
                    panelList.getOrNull(list.selectedItemPosition)?.let { epg.showChannelEpg(it) }
                    return true
                }
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

        keyMap[keyCode]?.let { act ->
            if (act != Act.NONE) { doAct(act, keyCode); return true }
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
            KeyEvent.KEYCODE_BACK -> {
                if (stats.visibility == View.VISIBLE) { stats.visibility = View.GONE; prefs.showStats = false; return true }
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

    private fun doAct(act: Act, keyCode: Int) {
        val inv = prefs.invertUpDown && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        when (act) {
            Act.NONE -> {}
            Act.CHANNEL_LIST -> showPanel()
            Act.EPG_EVENT -> epg.showCurrentEvent()
            Act.EPG_CHANNEL -> epg.showChannelEpg()
            Act.MULTI_EPG -> epg.showMultiEpg()
            Act.EPG_SEARCH -> epg.showSearch()
            Act.AUDIO -> showAudioDialog()
            Act.STATS -> toggleStats()
            Act.MENU -> showQuickMenu()
            Act.SETTINGS -> openSettings()
            Act.RECALL -> recall()
            Act.CH_UP -> play(playIndex + if (inv) -1 else 1)
            Act.CH_DOWN -> play(playIndex + if (inv) 1 else -1)
        }
    }

    private fun openSettings() {
        PinLock.ask(this, prefs) { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    // ---------------------------------------------------------------- zrzut przy zawieszeniu

    private fun decoderInfo(): String {
        val c = player?.videoDecoderCounters ?: return "dekoder: -"
        c.ensureUpdated()
        return "dekoder: wyrenderowane ${c.renderedOutputBufferCount}, utracone ${c.droppedBufferCount}, pominięte ${c.skippedOutputBufferCount}, maks. kolejno utracone ${c.maxConsecutiveDroppedBufferCount}"
    }

    private fun dumpStall(reason: String) {
        val a = analyzer ?: return
        // Problemy „startowe” (brak PMT, brak danych) to nie awaria strumienia — tylko wpis w logu,
        // bez zrzutu TS. Zrzut ma sens, gdy strumień był kompletny i odtwarzany.
        val startIssue = !a.pmtSeen || a.bytes < 2_000_000
        if (startIssue) {
            val why = when {
                a.bytes == 0L -> "dekoder nie wysyła danych"
                !a.pmtSeen && a.pmtInjected == 0 -> "brak PMT, jeszcze nie zapamiętany dla kanału"
                !a.pmtSeen -> "brak PMT mimo wstawienia zapamiętanego"
                else -> "za mało danych"
            }
            AppLog.i("start: restart ($reason) — $why; ${a.summary()}")
            if (a.bytes == 0L && failStreak >= 2) showMessage("Dekoder nie wysyła danych dla tego kanału", 6000)
            return
        }
        AppLog.i("ZAWIESZENIE ($reason) | ${a.summary()} | ${decoderInfo()}")
        if (!prefs.saveTs) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDumpAt < 60_000) {
            AppLog.i("zrzut TS pominięty (poprzedni < 60 s temu)"); return
        }
        lastDumpAt = now
        val data = a.ringSnapshot()
        if (data.isEmpty()) return
        val ch = playList.getOrNull(playIndex)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = (ch?.name ?: "kanal").replace(Regex("[^A-Za-z0-9_-]"), "_").take(30)
        val name = "stall-$stamp-$safe.ts"
        val dir = Storage.dir(this, prefs)
        val ftp = prefs.tsFtp
        val host = prefs.host
        val user = prefs.ftpUser
        val pass = prefs.ftpPass
        val ftpDir = prefs.ftpDir
        Thread {
            try {
                File(dir, name).writeBytes(data)
                Storage.prune(dir, "stall-", 10)
                AppLog.i("zrzut TS: ${dir.absolutePath}/$name (${data.size / 1024} kB)")
            } catch (e: Exception) {
                AppLog.i("zrzut TS nieudany: $e")
            }
            if (ftp) {
                try {
                    FtpUpload.upload(host, 21, user, pass, ftpDir, name, data)
                    AppLog.i("zrzut TS wysłany na dekoder: $ftpDir/$name")
                } catch (e: Exception) {
                    AppLog.i("wysyłanie zrzutu TS nieudane: $e")
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- aktualizacja

    private fun checkUpdate(manual: Boolean) = Tools.checkUpdate(this, manual) { showMessage(it, 8000) }



    // ---------------------------------------------------------------- menu podręczne

    private fun showQuickMenu() {
        val items = listOf<Pair<String, () -> Unit>>(
            "EPG wydarzenia" to { epg.showCurrentEvent() },
            "EPG kanału" to { epg.showChannelEpg() },
            "Multi EPG" to { epg.showMultiEpg() },
            "Szukaj w EPG" to { epg.showSearch() },
            "Ścieżka dźwięku…" to { showAudioDialog() },
            "Ustawienia" to { openSettings() },
        )
        AlertDialog.Builder(this)
            .setTitle("E2Stream ${appVersion()}")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    /** Przełącza na kanał o danej referencji (z bieżącego lub innego widocznego bukietu). */
    private fun zapTo(ref: String) {
        val k = OpenWebif.key(ref)
        val i = playList.indexOfFirst { OpenWebif.key(it.ref) == k }
        if (i >= 0) { hidePanel(); play(i); return }
        val bq = bouquets
        io.execute {
            for ((bi, b) in bq.withIndex()) {
                val ch = try { api.channels(b) } catch (e: Exception) { continue }
                val j = ch.indexOfFirst { OpenWebif.key(it.ref) == k }
                if (j >= 0) {
                    runOnUiThread { hidePanel(); playIn(bi, ch, j) }
                    return@execute
                }
            }
            runOnUiThread { showMessage("Tego kanału nie ma w widocznych bukietach") }
        }
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

    private fun uploadLog(manual: Boolean) = Tools.uploadLog(prefs, manual) { showMessage(it, 6000) }



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
