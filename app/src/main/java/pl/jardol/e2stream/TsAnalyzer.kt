package pl.jardol.e2stream

import android.os.SystemClock

/**
 * Analiza strumienia MPEG-TS „w locie” (wątek ładowania ExoPlayera):
 * błędy ciągłości CC, TEI, pakiety kodowane, utrata synchronizacji, skoki PCR,
 * zmiany PMT, przerwy w napływie danych. Trzyma też bufor pierścieniowy
 * ostatnich bajtów strumienia do zrzutu przy zawieszeniu obrazu.
 */
class TsAnalyzer(ringBytes: Int, private val onEvent: (String) -> Unit) {

    /** Czyść strumień: pakiety TEI / kodowane / PMT innych programów zamieniane na puste (PID 0x1FFF). */
    @Volatile var clean = true

    /** Pamięć PMT kanałów: odczyt/zapis surowych pakietów PMT (wielokrotność 188 B). */
    var pmtCacheGet: ((Int) -> ByteArray?)? = null
    var pmtCachePut: ((Int, ByteArray) -> Unit)? = null

    private var expectedSid = -1
    @Volatile var patSeenAt = 0L
        private set
    @Volatile var pmtSeen = false
        private set
    @Volatile var pmtInjected = 0
        private set
    private var injectPkts: ByteArray? = null
    private var injectIdx = 0
    private var injectCc = 0
    private var lastInjectAt = 0L
    // --- znaczniki czasu: PTS/DTS względem PCR (ms)
    private var lastPcrAny = -1L
    private var lastPcrTicks = -1L
    private var pcrPid = -1
    private val pidSeen = IntArray(8192)
    private val lastDts = LongArray(8192) { -1 }
    private val lastStep = LongArray(8192)
    private val offEma = LongArray(8192) { NONE }
    private val badRun = IntArray(8192)
    @Volatile var tsFixed = 0L
        private set
    private var cachedSection: ByteArray? = null
    private var recentPk = 0L
    private var recentScr = 0L

    /** (pakiety danych, pakiety kodowane) od ostatniego wywołania — do wykrywania kanałów nierozkodowanych. */
    @Synchronized
    fun takeScramble(): Pair<Long, Long> {
        val r = recentPk to recentScr
        recentPk = 0; recentScr = 0
        return r
    }
    private val videoPids = HashSet<Int>()
    private val audioPids = HashSet<Int>()
    private var mainAudioPid = -1
    private var ivVMin = Long.MAX_VALUE
    private var ivVMax = Long.MIN_VALUE
    private var ivAMin = Long.MAX_VALUE
    private var ivAMax = Long.MIN_VALUE
    private var lastAvDiff = Long.MIN_VALUE
    @Volatile var avDiffMs = 0L
        private set
    @Volatile var videoLeadMs = 0L
        private set
    private val secBuf = ByteArray(1100)
    private var secLen = 0
    private var secPkts = java.io.ByteArrayOutputStream()
    private var startAt = SystemClock.elapsedRealtime()
    private var startSkipped = 0
    private var startReported = false
    private val otherPmtPids = HashSet<Int>()
    @Volatile var cleaned = 0L
        private set
    @Volatile var burstAvgMs = 0L
        private set
    private var ivBurstN = 0
    private var ivBurstSum = 0L

    // --- liczniki sesji (od startu strumienia)
    @Volatile var bytes = 0L
        private set
    @Volatile var packets = 0L
        private set
    @Volatile var ccErrors = 0L
        private set
    @Volatile var teiErrors = 0L
        private set
    @Volatile var scrambled = 0L
        private set
    @Volatile var syncLoss = 0L
        private set
    @Volatile var pcrJumps = 0L
        private set
    @Volatile var pmtChanges = 0L
        private set
    @Volatile var maxGapMs = 0L
        private set
    @Volatile var bitrateKbps = 0L
        private set
    @Volatile var lastErrorAt = 0L
        private set

    private val pidCc = IntArray(8192) { -1 }
    private val pidCcErr = IntArray(8192)
    private val lastPcrMs = LongArray(8192) { -1 }
    private val pidType = HashMap<Int, String>()
    private var pmtPid = -1
    private var pmtVersion = -1

    private val carry = ByteArray(188)
    private var carryLen = 0

    // --- przedział 10 s
    private var ivStart = SystemClock.elapsedRealtime()
    private var ivBytes = 0L
    private var ivCc = 0L
    private var ivTei = 0L
    private var ivScr = 0L
    private var ivSync = 0L
    private var ivPcr = 0L
    private var ivGap = 0L
    private var lastHeartbeat = 0L

    // --- limit zdarzeń w logu
    private var evWindow = 0L
    private var evCount = 0
    private var evSuppressed = 0

    // --- bufor pierścieniowy
    private val ring: ByteArray? = if (ringBytes > 0) ByteArray(ringBytes) else null
    private var ringPos = 0
    private var ringFull = false

    /** Nowa sesja strumienia; [sid] = identyfikator serwisu z referencji (lub -1). */
    @Synchronized
    fun reset(sid: Int = -1) {
        expectedSid = sid
        patSeenAt = 0
        pmtSeen = false
        pmtInjected = 0
        cachedSection = if (sid >= 0) pmtCacheGet?.invoke(sid) else null
        injectPkts = null
        pidSeen.fill(0); lastDts.fill(-1); lastStep.fill(0); offEma.fill(NONE); badRun.fill(0)
        tsFixed = 0
        lastPcrTicks = -1
        pcrPid = -1
        injectIdx = 0
        injectCc = 0
        lastInjectAt = 0
        secLen = 0
        secPkts = java.io.ByteArrayOutputStream()
        lastPcrAny = -1
        videoPids.clear(); audioPids.clear(); mainAudioPid = -1
        ivVMin = Long.MAX_VALUE; ivVMax = Long.MIN_VALUE; ivAMin = Long.MAX_VALUE; ivAMax = Long.MIN_VALUE
        lastAvDiff = Long.MIN_VALUE
        avDiffMs = 0; videoLeadMs = 0
        startAt = SystemClock.elapsedRealtime()
        startSkipped = 0
        startReported = false
        otherPmtPids.clear()
        cleaned = 0
        burstAvgMs = 0
        ivBurstN = 0
        ivBurstSum = 0
        bytes = 0; packets = 0; ccErrors = 0; teiErrors = 0; scrambled = 0
        syncLoss = 0; pcrJumps = 0; pmtChanges = 0; maxGapMs = 0; bitrateKbps = 0
        lastErrorAt = 0
        pidCc.fill(-1); pidCcErr.fill(0); lastPcrMs.fill(-1)
        pidType.clear(); pmtPid = -1; pmtVersion = -1; pmtCandidates.clear()
        carryLen = 0
        ivStart = SystemClock.elapsedRealtime()
        ivBytes = 0; ivCc = 0; ivTei = 0; ivScr = 0; ivSync = 0; ivPcr = 0; ivGap = 0
        ringPos = 0; ringFull = false
    }

    /** Pierwsze 2 s po przełączeniu: dane sprzed zmiany kanału / dekodowanie startowe — nie liczymy jako błędów. */
    private fun inStartGrace(): Boolean {
        val g = SystemClock.elapsedRealtime() - startAt < 2000
        if (!g && !startReported && startSkipped > 0) {
            startReported = true
            onEvent("TS: start — pominięto $startSkipped zdarzeń z pierwszych 2 s")
        }
        return g
    }

    private fun event(msg: String) {
        lastErrorAt = SystemClock.elapsedRealtime()
        val now = lastErrorAt
        if (now - evWindow > 10_000) {
            if (evSuppressed > 0) onEvent("TS: … pominięto $evSuppressed zdarzeń")
            evWindow = now; evCount = 0; evSuppressed = 0
        }
        if (evCount < 15) { evCount++; onEvent("TS: $msg") } else evSuppressed++
    }

    private fun pidName(pid: Int) = pidType[pid]?.let { "$pid/$it" } ?: pid.toString()

    /** Czas trwania jednego odczytu z sieci (ms). */
    @Synchronized
    fun readTime(ms: Long) {
        if (ms > ivGap) ivGap = ms
        if (ms > maxGapMs) maxGapMs = ms
        if (ms >= 300) { ivBurstN++; ivBurstSum += ms }
        if (ms >= 2500 && !inStartGrace()) event("dane z dekodera nie przychodziły przez $ms ms")
    }

    @Synchronized
    fun feed(buf: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        bytes += len
        ivBytes += len
        ring?.let { r ->
            var o = off
            var left = len
            while (left > 0) {
                val n = minOf(left, r.size - ringPos)
                System.arraycopy(buf, o, r, ringPos, n)
                ringPos += n; o += n; left -= n
                if (ringPos == r.size) { ringPos = 0; ringFull = true }
            }
        }

        var i = off
        val end = off + len
        if (carryLen > 0) {
            val n = minOf(188 - carryLen, len)
            System.arraycopy(buf, off, carry, carryLen, n)
            carryLen += n
            i += n
            if (carryLen < 188) return
            carryLen = 0
            if (carry[0] == SYNC) packet(carry, 0, false) else lostSync()
        }
        while (end - i >= 188) {
            if (buf[i] != SYNC) {
                lostSync()
                var j = i + 1
                while (j < end && buf[j] != SYNC) j++
                i = j
                continue
            }
            packet(buf, i, true)
            i += 188
        }
        if (i < end && buf[i] == SYNC) {
            carryLen = end - i
            System.arraycopy(buf, i, carry, 0, carryLen)
        }
    }

    private fun lostSync() {
        if (inStartGrace()) { startSkipped++; return }
        syncLoss++; ivSync++
        event("utrata synchronizacji TS")
    }

    private fun nullPacket(p: ByteArray, o: Int) {
        if (!clean) return
        p[o + 1] = 0x1f
        p[o + 2] = 0xff.toByte()
        cleaned++
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xff

    private fun packet(p: ByteArray, o: Int, mutable: Boolean) {
        packets++
        val grace = inStartGrace()
        val b1 = u(p, o + 1)
        val pid = ((b1 and 0x1f) shl 8) or u(p, o + 2)
        if ((b1 and 0x80) != 0) {
            if (grace) startSkipped++ else {
                teiErrors++; ivTei++
                event("TEI (pakiet uszkodzony na wejściu tunera) pid ${pidName(pid)}")
            }
            if (mutable) nullPacket(p, o)
            return
        }
        val slot = pid == 0x1fff || pid in otherPmtPids || pid == 0x11 || pid == 0x12
        if (slot && mutable && maybeInject(p, o)) return
        if (pid == 0x1fff) return
        pidSeen[pid]++
        if (pid in otherPmtPids) {
            if (mutable) nullPacket(p, o) // PMT innego programu z PAT — ExoPlayer mógłby się na nim „zaczepić”
            return
        }
        val b3 = u(p, o + 3)
        val scr = b3 shr 6
        if (pid != 0 && pid != pmtPid && pid > 0x1f) { recentPk++; if (scr != 0) recentScr++ }
        val afc = (b3 shr 4) and 3
        val cc = b3 and 0x0f
        if (scr != 0) {
            if (grace) startSkipped++ else {
                scrambled++; ivScr++
                if (scrambled == 1L || scrambled % 500 == 0L) event("pakiety kodowane (niezdekodowane) pid ${pidName(pid)}, razem $scrambled")
            }
            if (mutable) nullPacket(p, o)
            return
        }

        var disc = false
        var payload = o + 4
        if ((afc and 2) != 0) {
            val afl = u(p, o + 4)
            payload = o + 5 + afl
            if (afl > 0 && afl <= 183) {
                val flags = u(p, o + 5)
                disc = (flags and 0x80) != 0
                if ((flags and 0x10) != 0 && afl >= 7) pcr(pid, p, o + 6, disc)
            }
        }
        if ((afc and 1) != 0) {
            val last = pidCc[pid]
            if (last >= 0 && !disc) {
                val exp = (last + 1) and 0x0f
                if (cc != exp && cc != last && grace) {
                    startSkipped++
                } else if (cc != exp && cc != last) {
                    ccErrors++; ivCc++; pidCcErr[pid]++
                    val lost = (cc - exp + 16) and 0x0f
                    event("błąd CC pid ${pidName(pid)}: oczekiwano $exp, jest $cc (~$lost pakietów brak)")
                }
            }
            pidCc[pid] = cc
            if ((b1 and 0x40) != 0 && (pid in videoPids || pid in audioPids)) pes(p, payload, o + 188, pid, mutable)
            if (pid == pmtPid && pmtPid >= 0 && payload < o + 188) {
                assemblePmt(p, o, payload, (b1 and 0x40) != 0)
            } else if ((b1 and 0x40) != 0 && payload < o + 188) {
                if (pid == 0) parsePat(p, payload, o + 188)
                else if (pid == pmtPid || (pmtPid < 0 && pmtCandidates.contains(pid))) parsePmt(p, payload, o + 188, pid)
            }
        }
    }

    private fun pcr(pid: Int, p: ByteArray, i: Int, disc: Boolean) {
        val base = (u(p, i).toLong() shl 25) or (u(p, i + 1).toLong() shl 17) or
            (u(p, i + 2).toLong() shl 9) or (u(p, i + 3).toLong() shl 1) or (u(p, i + 4).toLong() shr 7)
        val ms = base / 90
        val last = lastPcrMs[pid]
        lastPcrMs[pid] = ms
        if (pid == pcrPid) {
            lastPcrAny = ms
            lastPcrTicks = base
            if (disc || (last >= 0 && (ms - last < 0 || ms - last > 700))) {
                // nieciągłość zegara programu — znaczniki liczymy od nowa
                offEma.fill(NONE); lastDts.fill(-1); badRun.fill(0)
            }
        }
        if (last >= 0 && !disc) {
            val d = ms - last
            if (d < 0 || d > 700) {
                pcrJumps++; ivPcr++
                event("skok PCR pid ${pidName(pid)}: $d ms")
            }
        }
    }

    private val pmtCandidates = HashSet<Int>()

    private fun readTs(p: ByteArray, i: Int): Long =
        (((u(p, i).toLong() shr 1) and 7L) shl 30) or (u(p, i + 1).toLong() shl 22) or
            ((u(p, i + 2).toLong() shr 1) shl 15) or (u(p, i + 3).toLong() shl 7) or (u(p, i + 4).toLong() shr 1)

    private fun writeTs(p: ByteArray, i: Int, ts: Long) {
        val t = ts and 0x1ffffffffL
        p[i] = ((u(p, i) and 0xf0) or (((t shr 30) and 7L).toInt() shl 1) or 1).toByte()
        p[i + 1] = ((t shr 22) and 0xffL).toInt().toByte()
        p[i + 2] = ((((t shr 15) and 0x7fL).toInt() shl 1) or 1).toByte()
        p[i + 3] = ((t shr 7) and 0xffL).toInt().toByte()
        p[i + 4] = (((t and 0x7fL).toInt() shl 1) or 1).toByte()
    }

    /** Różnica a−b w taktach 90 kHz z uwzględnieniem zawinięcia 33 bitów. */
    private fun wrapDiff(a: Long, b: Long): Long {
        var d = (a - b) % TS_MOD
        if (d > TS_MOD / 2) d -= TS_MOD
        if (d < -TS_MOD / 2) d += TS_MOD
        return d
    }

    /**
     * Nagłówek PES: DTS (lub PTS) względem PCR. Znacznik odbiegający od PCR o ponad 3 s
     * (np. wstawka nadawcy z innym zegarem — TTV) jest poprawiany na oczekiwany, żeby
     * zegar odtwarzacza się nie rozjechał.
     */
    private fun pes(p: ByteArray, s: Int, end: Int, pid: Int, mutable: Boolean) {
        if (s + 14 > end || lastPcrTicks < 0) return
        if (u(p, s) != 0 || u(p, s + 1) != 0 || u(p, s + 2) != 1) return
        val flags = u(p, s + 7) shr 6
        if ((flags and 2) == 0) return
        val ptsI = s + 9
        val dtsI = if (flags == 3 && s + 19 <= end) s + 14 else -1
        val dts = readTs(p, if (dtsI >= 0) dtsI else ptsI)
        val off = wrapDiff(dts, lastPcrTicks)
        val ema = offEma[pid]
        val absurd = kotlin.math.abs(off) > 5 * 90_000L
        if (ema != NONE && absurd && kotlin.math.abs(off - ema) > 3 * 90_000L && badRun[pid] < 40) {
            badRun[pid]++
            tsFixed++
            val devMs = (off - ema) / 90
            if (clean && mutable) {
                val expected = if (lastDts[pid] >= 0 && lastStep[pid] > 0) lastDts[pid] + lastStep[pid]
                else lastPcrTicks + ema
                val delta = expected - dts
                writeTs(p, ptsI, readTs(p, ptsI) + delta)
                if (dtsI >= 0) writeTs(p, dtsI, dts + delta)
                lastDts[pid] = expected and 0x1ffffffffL
                event("błędny znacznik czasu pid ${pidName(pid)}: odchyłka $devMs ms od PCR — poprawiony")
            } else {
                event("błędny znacznik czasu pid ${pidName(pid)}: odchyłka $devMs ms od PCR")
            }
            return
        }
        if (badRun[pid] >= 40) {
            // długo utrzymująca się zmiana — to nowa, prawidłowa podstawa czasu
            event("nowa podstawa czasu pid ${pidName(pid)}")
            offEma[pid] = off
        }
        badRun[pid] = 0
        if (lastDts[pid] >= 0) {
            val st = wrapDiff(dts, lastDts[pid])
            if (st in 1L..90_000L) lastStep[pid] = st
        }
        lastDts[pid] = dts
        if (!absurd) offEma[pid] = if (ema == NONE) off else ema + (off - ema) / 8
        val offMs = off / 90
        if (pid in videoPids) {
            if (offMs < ivVMin) ivVMin = offMs
            if (offMs > ivVMax) ivVMax = offMs
        } else if (pid == mainAudioPid) {
            if (offMs < ivAMin) ivAMin = offMs
            if (offMs > ivAMax) ivAMax = offMs
        }
    }

    /** CRC32 MPEG-2 (sekcje PSI). */
    private fun crc32(b: ByteArray): Int {
        var crc = -1
        for (x in b) {
            crc = crc xor (x.toInt() shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 }
        }
        return crc
    }

    /**
     * Buduje pakiety PMT do wstawienia z zapamiętanej sekcji, zostawiając tylko te
     * ścieżki, które dekoder faktycznie przysyła (inaczej ExoPlayer czeka na brakujące).
     */
    private fun buildInjection(): ByteArray? {
        val sec = cachedSection ?: return null
        if (sec.size < 16 || u(sec, 0) != 0x02 || pmtPid < 0) return null
        val sl = ((u(sec, 1) and 0x0f) shl 8) or u(sec, 2)
        val end = minOf(3 + sl - 4, sec.size - 4)
        val pil = ((u(sec, 10) and 0x0f) shl 8) or u(sec, 11)
        if (12 + pil > end) return null
        val es = java.io.ByteArrayOutputStream()
        var k = 12 + pil
        var kept = 0
        val missing = ArrayList<Int>()
        while (k + 5 <= end) {
            val epid = ((u(sec, k + 1) and 0x1f) shl 8) or u(sec, k + 2)
            val len = 5 + (((u(sec, k + 3) and 0x0f) shl 8) or u(sec, k + 4))
            if (k + len > end) break
            if (pidSeen[epid] > 0) { es.write(sec, k, len); kept++ } else missing.add(epid)
            k += len
        }
        val esBytes = if (kept > 0) es.toByteArray() else sec.copyOfRange(12 + pil, end)
        val body = sec.copyOfRange(0, 12 + pil) + esBytes
        val newSl = body.size - 3 + 4
        body[1] = ((u(body, 1) and 0xf0) or ((newSl shr 8) and 0x0f)).toByte()
        body[2] = (newSl and 0xff).toByte()
        val crc = crc32(body)
        val section = body + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
        val out = java.io.ByteArrayOutputStream()
        var pos = 0
        var first = true
        while (first || pos < section.size) {
            val pk = ByteArray(188) { 0xff.toByte() }
            pk[0] = SYNC
            pk[1] = ((if (first) 0x40 else 0) or ((pmtPid shr 8) and 0x1f)).toByte()
            pk[2] = (pmtPid and 0xff).toByte()
            pk[3] = 0x10
            var w = 4
            if (first) { pk[4] = 0; w = 5 }
            val n = minOf(188 - w, section.size - pos)
            System.arraycopy(section, pos, pk, w, n)
            pos += n
            first = false
            out.write(pk)
        }
        onEvent("TS: PMT do wstawienia: ścieżek $kept" + if (missing.isNotEmpty()) ", pominięte (brak w strumieniu): ${missing.joinToString(",")}" else "")
        return out.toByteArray()
    }

    /** Zbiera sekcję PMT z jednego lub kilku pakietów; po skompletowaniu parsuje i zapamiętuje pakiety. */
    private fun assemblePmt(p: ByteArray, o: Int, payload: Int, pusi: Boolean) {
        if (pusi) {
            val t = payload + 1 + u(p, payload)
            if (t >= o + 188) return
            secLen = 0
            secPkts = java.io.ByteArrayOutputStream()
            System.arraycopy(p, t, secBuf, 0, o + 188 - t)
            secLen = o + 188 - t
        } else {
            if (secLen == 0) return
            val n = minOf(o + 188 - payload, secBuf.size - secLen)
            if (n <= 0) { secLen = 0; return }
            System.arraycopy(p, payload, secBuf, secLen, n)
            secLen += n
        }
        secPkts.write(p, o, 188)
        if (secLen < 3) return
        val need = 3 + (((secBuf[1].toInt() and 0x0f) shl 8) or (secBuf[2].toInt() and 0xff))
        if (need > secBuf.size) { secLen = 0; return }
        if (secLen < need) return
        val pkts = secPkts.toByteArray()
        secLen = 0
        if (parsePmtSection(secBuf, 0, need)) {
            if (!pmtSeen) {
                pmtSeen = true
                if (expectedSid >= 0 && need in 16..1024) {
                    val sec = secBuf.copyOf(need)
                    cachedSection = sec
                    pmtCachePut?.invoke(expectedSid, sec)
                }
            }
        }
    }

    /** Gdy po PAT przez 700 ms nie ma PMT, wstawia zapamiętany PMT kanału w miejsce mało ważnego pakietu. */
    private fun maybeInject(p: ByteArray, o: Int): Boolean {
        if (pmtSeen || patSeenAt == 0L || pmtPid < 0 || cachedSection == null) return false
        val now = SystemClock.elapsedRealtime()
        if (now - patSeenAt < 700) return false
        if (injectIdx == 0 && now - lastInjectAt < 300) return false
        val pk = injectPkts ?: buildInjection()?.also { injectPkts = it } ?: return false
        val src = injectIdx * 188
        if (src + 188 > pk.size) { injectIdx = 0; return false }
        System.arraycopy(pk, src, p, o, 188)
        // PID naszego PMT i kolejny licznik ciągłości
        p[o + 1] = ((p[o + 1].toInt() and 0xe0) or ((pmtPid shr 8) and 0x1f)).toByte()
        p[o + 2] = (pmtPid and 0xff).toByte()
        p[o + 3] = ((p[o + 3].toInt() and 0xf0) or (injectCc and 0x0f)).toByte()
        injectCc = (injectCc + 1) and 0x0f
        injectIdx++
        if (injectIdx * 188 >= pk.size) {
            injectIdx = 0
            lastInjectAt = now
            pmtInjected++
            if (pmtInjected == 1 || pmtInjected % 20 == 0) {
                onEvent("TS: brak PMT od dekodera ${now - patSeenAt} ms po PAT — wstawiam zapamiętany PMT (×$pmtInjected)")
            }
        }
        return true
    }

    /**
     * PAT ze strumienia Enigma2 to oryginalny PAT transpondera — zawiera wszystkie programy.
     * Nasz PMT wybieramy po SID z referencji serwisu; PMT pozostałych programów oznaczamy do usunięcia.
     */
    private fun parsePat(p: ByteArray, s: Int, end: Int) {
        val t = s + 1 + u(p, s)
        if (t + 8 > end || u(p, t) != 0x00) return
        val sl = ((u(p, t + 1) and 0x0f) shl 8) or u(p, t + 2)
        var k = t + 8
        val last = minOf(t + 3 + sl - 4, end)
        var mine = -1
        val all = ArrayList<Pair<Int, Int>>()
        while (k + 4 <= last) {
            val prog = (u(p, k) shl 8) or u(p, k + 1)
            val ppid = ((u(p, k + 2) and 0x1f) shl 8) or u(p, k + 3)
            if (prog != 0) {
                all.add(prog to ppid)
                if (prog == expectedSid) mine = ppid
            }
            k += 4
        }
        if (patSeenAt == 0L) patSeenAt = SystemClock.elapsedRealtime()
        if (mine >= 0) {
            if (mine != pmtPid) {
                pmtPid = mine
                pmtVersion = -1
                otherPmtPids.clear()
                all.filter { it.second != mine }.forEach { otherPmtPids.add(it.second) }
                otherPmtPids.remove(mine)
                onEvent("TS: PAT ${all.size} programów, nasz SID $expectedSid → PMT pid $mine" +
                    if (otherPmtPids.isNotEmpty()) ", usuwam PMT innych: ${otherPmtPids.size}" else "")
            }
        } else if (pmtPid < 0) {
            // SID nieznany lub brak w PAT — weź pierwszy PMT, który faktycznie przyjdzie
            pmtCandidates.clear()
            all.forEach { pmtCandidates.add(it.second) }
        }
    }

    private fun streamTypeName(t: Int) = when (t) {
        0x01, 0x02 -> "MPEG2-V"
        0x1b -> "H264"
        0x24 -> "HEVC"
        0x03, 0x04 -> "MPEG-A"
        0x0f -> "AAC"
        0x11 -> "LATM"
        0x06 -> "PES-priv"
        0x81 -> "AC3"
        0x87 -> "EAC3"
        0x05 -> "sekcje"
        else -> "0x%02x".format(t)
    }

    private fun parsePmt(p: ByteArray, s: Int, end: Int, pid: Int) {
        val t = s + 1 + u(p, s)
        if (t + 12 > end || u(p, t) != 0x02) return
        val sl = ((u(p, t + 1) and 0x0f) shl 8) or u(p, t + 2)
        if (t + 3 + sl > end) return // dłuższa sekcja — tylko gdy SID znany (assemblePmt)
        val prog = (u(p, t + 3) shl 8) or u(p, t + 4)
        if (expectedSid >= 0 && prog != expectedSid) return
        if (pmtPid < 0) {
            // pierwszy PMT z kandydatów — to nasz; PMT pozostałych programów usuwamy
            pmtPid = pid
            otherPmtPids.clear()
            pmtCandidates.filter { it != pid }.forEach { otherPmtPids.add(it) }
        }
        if (parsePmtSection(p, t, t + 3 + sl)) pmtSeen = true
    }

    /** Parsuje kompletną sekcję PMT od [t] (table_id) do [end] (z CRC). */
    private fun parsePmtSection(p: ByteArray, t: Int, end: Int): Boolean {
        if (t + 12 > end || u(p, t) != 0x02) return false
        val prog = (u(p, t + 3) shl 8) or u(p, t + 4)
        if (expectedSid >= 0 && prog != expectedSid) return false
        val sectionEnd = end - 4
        val version = (u(p, t + 5) shr 1) and 0x1f
        val pp = ((u(p, t + 8) and 0x1f) shl 8) or u(p, t + 9)
        if (pp != pcrPid) { pcrPid = pp; lastPcrTicks = -1; offEma.fill(NONE); lastDts.fill(-1) }
        if (version == pmtVersion) return true
        val pil = ((u(p, t + 10) and 0x0f) shl 8) or u(p, t + 11)
        var k = t + 12 + pil
        val list = ArrayList<String>()
        pidType.clear()
        while (k + 5 <= sectionEnd) {
            val st = u(p, k)
            val epid = ((u(p, k + 1) and 0x1f) shl 8) or u(p, k + 2)
            val esil = ((u(p, k + 3) and 0x0f) shl 8) or u(p, k + 4)
            var name = streamTypeName(st)
            if (st == 0x06) {
                var d = k + 5
                val de = minOf(k + 5 + esil, sectionEnd)
                while (d + 2 <= de) {
                    when (u(p, d)) {
                        0x6a -> name = "AC3"
                        0x7a -> name = "EAC3"
                        0x7c -> name = "AAC"
                        0x56 -> name = "teletekst"
                        0x59 -> name = "napisy"
                    }
                    d += 2 + u(p, d + 1)
                }
            }
            pidType[epid] = name
            list.add("$epid:$name")
            k += 5 + esil
        }
        videoPids.clear(); audioPids.clear()
        for ((pid, n) in pidType) {
            if (n == "H264" || n == "HEVC" || n == "MPEG2-V") videoPids.add(pid)
            if (n == "MPEG-A" || n == "AC3" || n == "EAC3" || n == "AAC" || n == "LATM") audioPids.add(pid)
        }
        mainAudioPid = audioPids.minOrNull() ?: -1
        if (pmtVersion >= 0) {
            pmtChanges++
            event("zmiana PMT wersja $pmtVersion → $version: ${list.joinToString(" ")}")
        } else {
            val delay = if (patSeenAt > 0) " (${SystemClock.elapsedRealtime() - patSeenAt} ms po PAT)" else ""
            onEvent("TS: PMT program $prog wersja $version$delay: ${list.joinToString(" ")}")
        }
        pmtVersion = version
        return true
    }

    /** Ile ms od PAT czekamy na PMT (0 = PMT już jest albo nie było jeszcze PAT). */
    fun pmtMissingMs(): Long =
        if (pmtSeen || patSeenAt == 0L) 0 else SystemClock.elapsedRealtime() - patSeenAt

    /** Podsumowanie ostatnich ~10 s; zwraca tekst do logu albo null (gdy nic ciekawego). */
    @Synchronized
    fun interval(): String? {
        val now = SystemClock.elapsedRealtime()
        val dt = (now - ivStart).coerceAtLeast(1)
        bitrateKbps = ivBytes * 8 / dt
        val errors = ivCc + ivTei + ivSync + ivPcr + ivScr
        if (ivBurstN > 0) burstAvgMs = ivBurstSum / ivBurstN
        val text = "TS ${dt / 1000}s: %.2f Mb/s, CC +$ivCc, TEI +$ivTei, kodowane +$ivScr, sync +$ivSync, PCR +$ivPcr, maks. odczyt $ivGap ms, paczki danych: $ivBurstN × ~$burstAvgMs ms przerwy"
            .format(bitrateKbps / 1000.0)
        ivBurstN = 0
        ivBurstSum = 0
        var avText = ""
        var avAlert = false
        if (ivVMin != Long.MAX_VALUE && ivAMin != Long.MAX_VALUE) {
            val v = (ivVMin + ivVMax) / 2
            val a = (ivAMin + ivAMax) / 2
            avDiffMs = v - a
            videoLeadMs = ivVMin
            avText = ", PTS−PCR: wideo $ivVMin…$ivVMax ms, audio $ivAMin…$ivAMax ms, wideo−audio ${v - a} ms"
            if (lastAvDiff != Long.MIN_VALUE && kotlin.math.abs((v - a) - lastAvDiff) > 200) {
                avAlert = true
                event("przesunięcie obrazu względem dźwięku zmieniło się z $lastAvDiff na ${v - a} ms")
            }
            if (ivVMax - ivVMin > 1500) {
                avAlert = true
                event("znaczniki czasu obrazu skaczą: rozrzut ${ivVMax - ivVMin} ms")
            }
            lastAvDiff = v - a
        }
        ivVMin = Long.MAX_VALUE; ivVMax = Long.MIN_VALUE; ivAMin = Long.MAX_VALUE; ivAMax = Long.MIN_VALUE
        ivStart = now
        ivBytes = 0; ivCc = 0; ivTei = 0; ivScr = 0; ivSync = 0; ivPcr = 0; ivGap = 0
        if (errors > 0 || avAlert || now - lastHeartbeat > 60_000 || forceNext) {
            forceNext = false
            lastHeartbeat = now
            return text + avText
        }
        return null
    }

    /** Wymusza pełną linię podsumowania przy najbliższym interwale. */
    @Volatile var forceNext = false

    @Synchronized
    fun summary(): String {
        val worst = (0 until 8192).filter { pidCcErr[it] > 0 }
            .sortedByDescending { pidCcErr[it] }.take(5)
            .joinToString(" ") { "${pidName(it)}=${pidCcErr[it]}" }
        return "TS razem: ${bytes / 1_000_000} MB, PMT pid $pmtPid ${if (pmtSeen) "jest" else "BRAK"}, wstawiony ×$pmtInjected, poprawione znaczniki $tsFixed, wyczyszczone $cleaned, CC $ccErrors, TEI $teiErrors, kodowane $scrambled, sync $syncLoss, PCR $pcrJumps, PMT $pmtChanges, maks. odczyt $maxGapMs ms" +
            (if (worst.isNotEmpty()) " | CC wg PID: $worst" else "") +
            " | ostatnio wideo−audio $avDiffMs ms, wideo PTS−PCR min $videoLeadMs ms"
    }

    /** Kopia bufora pierścieniowego w kolejności chronologicznej, wyrównana do pakietów. */
    @Synchronized
    fun ringSnapshot(): ByteArray {
        val r = ring ?: return ByteArray(0)
        val raw = if (!ringFull) r.copyOfRange(0, ringPos)
        else r.copyOfRange(ringPos, r.size) + r.copyOfRange(0, ringPos)
        var s = 0
        while (s + 188 < raw.size && !(raw[s] == SYNC && raw[s + 188] == SYNC)) s++
        return if (s == 0) raw else raw.copyOfRange(s, raw.size)
    }

    companion object {
        private const val SYNC: Byte = 0x47
        private const val NONE = Long.MIN_VALUE
        private const val TS_MOD = 1L shl 33
    }
}
