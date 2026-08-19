package com.msp1974.vacompanion.gideon

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Gideon voice meter — a READ-ONLY tap on the audio that is already flowing.
 *
 * WHY: the house dashboard draws a live bar meter across the top edge of the
 * tablet screens while Gideon listens and speaks. That meter has to be driven by
 * the real audio, in real time — not by a reconstruction, not by a precomputed
 * envelope scrolled against a clock.
 *
 * WHAT IT IS NOT: this object never touches a sample. Both taps hand it a buffer
 * that is already on its way to the recognizer or to the speaker; it reads,
 * accumulates, and returns. The audio path is bit-identical with this class
 * present or absent. That is the whole contract — if a change here could ever
 * alter what the mic hears or what the speaker plays, it is the wrong change.
 *
 * THE TWO SOURCES
 *   mic — MicrophoneInput.readShort(), tapped AFTER downmix and the mic_gain dB
 *         trim, so the meter shows what the recognizer actually hears.
 *   tts — VoicePlayerService.writeAudio(), the PCM about to enter the AudioTrack.
 *
 * UNIFORM 50 Hz OUTPUT: the two sources arrive in different chunk sizes at
 * different sample rates (16 kHz mic, usually 22.05 kHz TTS). A scrolling bar
 * meter needs every bar to be an equal slice of TIME, so this class accumulates
 * and emits exactly one value per 20 ms of audio regardless of who is feeding it.
 * One renderer, one cadence, no branching downstream.
 *
 * TTS IS SYNCED TO PLAYBACK, NOT TO WRITES. AudioTrack MODE_STREAM buffers, so
 * a chunk handed to write() is heard 100-200 ms later. Emitting on write would
 * run the meter ahead of Gideon's voice by about a syllable — visible as "off"
 * without being obviously wrong. Instead each TTS slice is tagged with the frame
 * position it will be heard at, parked in a pending queue, and released only once
 * the AudioTrack's playback head reaches it. Between writes the head is
 * extrapolated from wall time, which is exact because playback runs at a fixed
 * rate. The result is a meter that moves with the sound, not with the buffer.
 *
 * THREADING: the accumulators are touched only by their own producer thread (the
 * mic read loop, the voice player). Only the shared rings are synchronized, so
 * the audio threads never block on the WebView's drain() call.
 *
 * Values on the wire are integer thousandths (0..1000) to keep the JSON small;
 * gideon-voice.js is the public contract and hands out 0..1 floats.
 */
object GideonVoiceMeter {

    /** One emitted value per this many milliseconds of audio, from either source. */
    const val SLICE_MS = 20

    /** Emit rate in Hz. Every bar is an equal 20 ms slice of time. */
    const val HZ = 1000 / SLICE_MS

    /** Bumped when the wire format changes so the JS can refuse a stale pairing. */
    const val PROTOCOL = 4

    /**
     * Spectrum bands per slice.
     *
     * WHY THIS EXISTS: RMS alone is an ENVELOPE — one number for loudness. A
     * renderer fed only that can scale a fixed shape up and down, but the shape
     * never changes, which reads as a pulsing blob rather than a voice. Bands
     * give the drawing something to be ABOUT: vowels light the low mids,
     * sibilants light the top, silence lights nothing.
     *
     * 32 log-spaced bands over 60 Hz..8 kHz. Log spacing because pitch is
     * logarithmic — linear bins would spend most of the screen on the 4-8 kHz
     * octave where speech has almost nothing to say.
     */
    const val BANDS = 96
    private const val FFT_N = 1024         // transform size; zero-padded past WINDOW
    /**
     * Real audio fed to each transform, in samples (~32 ms at 16 kHz).
     *
     * A 20 ms slice gives ~50 Hz resolution, which cannot separate the harmonics
     * of a voice — they sit 100-200 Hz apart and smear into one smooth hump.
     * That is why the spectrum looked lifeless. 512 samples resolves them, and
     * the window OVERLAPS successive slices so the update rate stays 50 Hz.
     */
    private const val WINDOW = 512
    /**
     * Bands ride a finer fixed-point scale than rms/peak. A single-bin peak in
     * one of 96 narrow bands is roughly a tenth of a 32-band average, and at
     * integer thousandths a quiet room quantised straight to zero.
     */
    private const val BAND_SCALE = 10000f
    private const val BAND_LO_HZ = 60.0
    private const val BAND_HI_HZ = 8000.0

    private const val CAPACITY = 256        // ~5.1 s of released slices
    private const val PENDING = 256         // ~5.1 s of TTS awaiting playback
    private const val MIC_IDLE_NANOS = 1_000_000_000L

    const val SRC_NONE = 0
    const val SRC_MIC = 1
    const val SRC_TTS = 2

    private val lock = Any()

    // ---- released ring (drained by the WebView) ----------------------------
    private val outRms = FloatArray(CAPACITY)
    private val outPeak = FloatArray(CAPACITY)
    private val outSrc = ByteArray(CAPACITY)
    private var outHead = 0                 // next write slot
    private var outCount = 0                // unread slices

    // ---- TTS pending queue (awaiting the playback head) --------------------
    private val penRms = FloatArray(PENDING)
    private val penPeak = FloatArray(PENDING)
    private val penFrame = LongArray(PENDING)
    private var penHead = 0
    private var penTail = 0
    private var penCount = 0

    // ---- playback head tracking -------------------------------------------
    @Volatile private var ttsOpen = false
    @Volatile private var ttsRate = 22050
    @Volatile private var ttsWidth = 2
    @Volatile private var ttsChannels = 1
    private var framesWritten = 0L
    private var headFrames = 0L
    private var headAtNanos = 0L
    private var headWrapBase = 0L
    private var lastRawHead = 0

    @Volatile private var lastMicNanos = 0L

    // ---- accumulators (thread-confined to their producer) ------------------
    private var micSumSq = 0.0
    private var micPeak = 0f
    private var micN = 0
    private var micPerSlice = 320           // 16 kHz

    private var ttsSumSq = 0.0
    private var ttsPeak = 0f
    private var ttsN = 0
    private var ttsPerSlice = 441           // 22.05 kHz

    /** True while TTS owns the meter — either playing or still queued to be heard. */
    private val ttsActive: Boolean
        get() = ttsOpen || synchronized(lock) { penCount } > 0

    // ========================================================================
    // MIC TAP — called from MicrophoneInput.readShort on the mic read thread.
    // Reads `frame`; never writes to it.
    // ========================================================================
    fun feedMic(frame: ShortArray, length: Int, sampleRate: Int) {
        if (length <= 0) return
        lastMicNanos = System.nanoTime()

        // Gideon's own voice is in the room while he speaks; the TTS tap is the
        // honest source for that. Drop mic slices rather than meter the echo.
        if (ttsActive) {
            micSumSq = 0.0; micPeak = 0f; micN = 0
            return
        }

        val perSlice = sampleRate * SLICE_MS / 1000
        if (perSlice != micPerSlice) {
            micPerSlice = if (perSlice > 0) perSlice else 320
            micSumSq = 0.0; micPeak = 0f; micN = 0
        }

        val n = if (length <= frame.size) length else frame.size
        for (i in 0 until n) {
            val f = frame[i] / 32768f
            micSumSq += (f * f).toDouble()
            val a = abs(f)
            if (a > micPeak) micPeak = a
            // winBuf is shared with the TTS tap, which is safe because mic
            // slices are dropped while TTS owns the meter — the two never
            // accumulate at once.
            pushHist(f)
            micN++
            if (micN >= micPerSlice) {
                analyse(sampleRate)
                emit(sqrt(micSumSq / micN).toFloat(), micPeak, SRC_MIC)
                micSumSq = 0.0; micPeak = 0f; micN = 0
            }
        }
    }

    // ========================================================================
    // TTS TAP — called from VoicePlayerService on the voice player thread.
    // ========================================================================

    /** A new utterance opened its AudioTrack. Frame counters restart with it. */
    fun ttsStarted(rate: Int, width: Int, channels: Int) {
        synchronized(lock) {
            penHead = 0; penTail = 0; penCount = 0
        }
        ttsRate = if (rate > 0) rate else 22050
        ttsPerSlice = (ttsRate * SLICE_MS / 1000).coerceAtLeast(1)
        ttsSumSq = 0.0; ttsPeak = 0f; ttsN = 0
        framesWritten = 0L
        headFrames = 0L
        headWrapBase = 0L
        lastRawHead = 0
        headAtNanos = System.nanoTime()
        ttsWidth = if (width > 0) width else 2
        ttsChannels = if (channels > 0) channels else 1
        ttsOpen = true
    }

    /**
     * @param buffer PCM exactly as handed to AudioTrack.write — read only.
     * @param rawHead AudioTrack.getPlaybackHeadPosition() sampled around the write.
     */
    fun feedTts(buffer: ByteArray, size: Int, rawHead: Int) {
        if (!ttsOpen || size <= 0) return

        val width = ttsWidth
        val channels = ttsChannels
        val bytesPerFrame = width * channels
        if (bytesPerFrame <= 0) return
        val usable = (if (size <= buffer.size) size else buffer.size) / bytesPerFrame * bytesPerFrame

        var i = 0
        while (i + bytesPerFrame <= usable) {
            // Average the channels down to one value per frame; in this house TTS
            // is mono, but a stereo voice must not double-count.
            var acc = 0f
            for (c in 0 until channels) {
                val o = i + c * width
                acc += if (width == 2) {
                    (((buffer[o + 1].toInt() shl 8) or (buffer[o].toInt() and 0xFF)).toShort()) / 32768f
                } else {
                    ((buffer[o].toInt() and 0xFF) - 128) / 128f
                }
            }
            val f = acc / channels

            ttsSumSq += (f * f).toDouble()
            val a = abs(f)
            if (a > ttsPeak) ttsPeak = a
            pushHist(f)
            ttsN++
            framesWritten++

            if (ttsN >= ttsPerSlice) {
                // Note: unlike rms/peak, the spectrum is NOT parked against the
                // playback head — it is published immediately. It describes
                // "what Gideon's voice sounds like now" at meter resolution, and
                // delaying it by the AudioTrack buffer would cost more in
                // complexity than the few tens of ms it would buy.
                analyse(ttsRate)
                park(sqrt(ttsSumSq / ttsN).toFloat(), ttsPeak, framesWritten)
                ttsSumSq = 0.0; ttsPeak = 0f; ttsN = 0
            }
            i += bytesPerFrame
        }

        noteHead(rawHead)
    }

    /** Utterance finished. `flushed` means the queued audio was discarded, not heard. */
    fun ttsStopped(flushed: Boolean) {
        ttsOpen = false
        ttsSumSq = 0.0; ttsPeak = 0f; ttsN = 0
        if (flushed) {
            synchronized(lock) { penHead = 0; penTail = 0; penCount = 0 }
        }
    }

    /**
     * Record where playback actually is. getPlaybackHeadPosition returns a 32-bit
     * frame count; unwrap it so a long utterance can't fold the timeline back on
     * itself.
     */
    private fun noteHead(rawHead: Int) {
        if (rawHead < lastRawHead) headWrapBase += 0x1_0000_0000L
        lastRawHead = rawHead
        synchronized(lock) {
            headFrames = headWrapBase + (rawHead.toLong() and 0xFFFF_FFFFL)
            headAtNanos = System.nanoTime()
        }
    }

    private fun park(rms: Float, peak: Float, atFrame: Long) {
        synchronized(lock) {
            if (penCount == PENDING) {                 // never block the player
                penTail = (penTail + 1) % PENDING
                penCount--
            }
            penRms[penHead] = rms
            penPeak[penHead] = peak
            penFrame[penHead] = atFrame
            penHead = (penHead + 1) % PENDING
            penCount++
        }
    }

    /** Move every TTS slice the speaker has now reached into the released ring. */
    private fun releaseHeard() {
        synchronized(lock) {
            if (penCount == 0) return
            val elapsed = (System.nanoTime() - headAtNanos).coerceAtLeast(0L)
            val projected = headFrames + elapsed * ttsRate / 1_000_000_000L
            val heard = if (projected > framesWritten) framesWritten else projected
            while (penCount > 0 && penFrame[penTail] <= heard) {
                emitLocked(penRms[penTail], penPeak[penTail], SRC_TTS)
                penTail = (penTail + 1) % PENDING
                penCount--
            }
        }
    }

    // ---- spectrum ----------------------------------------------------------
    // Scratch buffers, allocated once. feedMic/feedTts run on audio threads at
    // 50 Hz; allocating 512-float arrays per slice would hand the GC 100
    // objects a second on an armv7 device for no reason.
    // Rolling history so each transform sees WINDOW samples of real audio even
    // though slices arrive 320 (mic) or 441 (TTS) at a time. Shared between the
    // taps, which is safe because mic slices are dropped while TTS owns the
    // meter — the two never accumulate at once.
    private val hist = FloatArray(WINDOW)
    private var histPos = 0
    private var histFill = 0
    private val winBuf = FloatArray(FFT_N)
    private val fftRe = FloatArray(FFT_N)
    private val fftIm = FloatArray(FFT_N)
    private val bandsScratch = FloatArray(BANDS)
    private val latestBands = FloatArray(BANDS)
    private var hann: FloatArray? = null
    private var bandEdges: FloatArray? = null
    private var bandEdgeRate = 0

    private fun pushHist(f: Float) {
        hist[histPos] = f
        histPos = (histPos + 1) % WINDOW
        if (histFill < WINDOW) histFill++
    }

    /** Hann window: without it, chopping audio into 20 ms slices smears every
     *  tone across the whole spectrum and the bands all move together. */
    private fun hannFor(n: Int): FloatArray {
        var h = hann
        if (h == null || h.size != n) {
            h = FloatArray(n) { i -> (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (n - 1))).toFloat() }
            hann = h
        }
        return h
    }

    /**
     * Log-spaced bin boundaries, as FLOATS.
     *
     * These were ints with a "every band must own one more bin than the last"
     * rule, and that rule propagated: with 96 bands starting at bin 4 it forced
     * 4,5,6,7... so the top band landed near 1.5 kHz instead of 8 kHz and the
     * whole log spread collapsed into a linear ramp over the bottom octaves.
     * Keeping them as floats preserves the true spacing; bands that are
     * narrower than one bin simply share that bin, which is the honest answer
     * at the low end where resolution genuinely runs out.
     */
    private fun edgesFor(rate: Int): FloatArray {
        val cached = bandEdges
        if (cached != null && bandEdgeRate == rate) return cached
        val hi = minOf(BAND_HI_HZ, rate / 2.0 - 1.0)
        val edges = FloatArray(BANDS + 1)
        val ratio = hi / BAND_LO_HZ
        val maxBin = (FFT_N / 2 - 1).toFloat()
        for (b in 0..BANDS) {
            val hz = BAND_LO_HZ * Math.pow(ratio, b.toDouble() / BANDS)
            var bin = (hz * FFT_N / rate).toFloat()
            if (bin < 1f) bin = 1f
            if (bin > maxBin) bin = maxBin
            edges[b] = bin
        }
        bandEdges = edges
        bandEdgeRate = rate
        return edges
    }

    /** In-place iterative radix-2 Cooley-Tukey. Real input, so only the lower
     *  half of the output is meaningful. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = kotlin.math.cos(ang).toFloat()
            val wIm = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]; val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe; im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe; im[i + k + len / 2] = uIm - vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Bands for the most recent WINDOW samples of real audio. */
    private fun analyse(rate: Int) {
        if (histFill < WINDOW / 2) return          // not enough audio yet
        val n = if (histFill < WINDOW) histFill else WINDOW
        val w = hannFor(n)
        // Unroll the ring oldest-first so the window is contiguous in time.
        val start = (histPos - n + WINDOW) % WINDOW
        for (i in 0 until n) winBuf[i] = hist[(start + i) % WINDOW]
        for (i in 0 until FFT_N) {
            fftRe[i] = if (i < n) winBuf[i] * w[i] else 0f
            fftIm[i] = 0f
        }
        fft(fftRe, fftIm)

        val edges = edgesFor(rate)
        // Normalising by count (not FFT_N) keeps magnitudes comparable between
        // the 16 kHz mic slice and the 22.05 kHz TTS slice.
        val norm = 2f / n
        val topBin = FFT_N / 2 - 1
        for (b in 0 until BANDS) {
            // PEAK, not mean. Averaging the bins inside a band is a second
            // smoothing pass that erases the harmonic peaks the eye reads as
            // life — it turns a voice into a hump.
            var loBin = edges[b].toInt()
            var hiBin = edges[b + 1].toInt()
            if (hiBin <= loBin) hiBin = loBin + 1        // local only, never propagated
            if (hiBin > topBin) hiBin = topBin
            if (loBin >= hiBin) loBin = hiBin - 1
            var peak = 0f
            for (bin in loBin until hiBin) {
                val re = fftRe[bin]; val im = fftIm[bin]
                val m = sqrt(re * re + im * im)
                if (m > peak) peak = m
            }
            bandsScratch[b] = peak * norm
        }
        synchronized(lock) {
            System.arraycopy(bandsScratch, 0, latestBands, 0, BANDS)
        }
    }

    private fun emit(rms: Float, peak: Float, src: Int) {
        synchronized(lock) { emitLocked(rms, peak, src) }
    }

    private fun emitLocked(rms: Float, peak: Float, src: Int) {
        if (outCount == CAPACITY) outCount--        // drop oldest unread
        outRms[outHead] = rms
        outPeak[outHead] = peak
        outSrc[outHead] = src.toByte()
        outHead = (outHead + 1) % CAPACITY
        outCount++
    }

    /**
     * Hand everything accumulated since the last call to the WebView and clear it.
     * Called from the JS thread ~60×/s; typically returns 1-2 values.
     */
    fun drain(): String {
        releaseHeard()
        val sb = StringBuilder(160)
        synchronized(lock) {
            var src = SRC_NONE
            sb.append("{\"v\":[")
            val start = (outHead - outCount + CAPACITY) % CAPACITY
            for (k in 0 until outCount) {
                val idx = (start + k) % CAPACITY
                if (k > 0) sb.append(',')
                sb.append((outRms[idx] * 1000f).toInt())
                src = outSrc[idx].toInt()
            }
            sb.append("],\"p\":[")
            for (k in 0 until outCount) {
                val idx = (start + k) % CAPACITY
                if (k > 0) sb.append(',')
                sb.append((outPeak[idx] * 1000f).toInt())
            }
            sb.append("],\"s\":")
            if (outCount == 0) {
                val idle = System.nanoTime() - lastMicNanos > MIC_IDLE_NANOS
                src = if (ttsOpen) SRC_TTS else if (idle) SRC_NONE else SRC_MIC
            }
            sb.append(src)
            sb.append(",\"hz\":").append(HZ)
            // Spectrum of the most recent completed slice only. The renderer
            // draws "now", so shipping one band set per drain instead of one
            // per slice cuts the payload by the drain rate with no visible
            // difference.
            sb.append(",\"b\":[")
            for (b in 0 until BANDS) {
                if (b > 0) sb.append(',')
                sb.append((latestBands[b] * BAND_SCALE).toInt())
            }
            sb.append(']')
            sb.append('}')
            outCount = 0
        }
        return sb.toString()
    }

    fun info(): String =
        "{\"protocol\":$PROTOCOL,\"hz\":$HZ,\"sliceMs\":$SLICE_MS," +
            "\"capacity\":$CAPACITY,\"bands\":$BANDS,\"bandScale\":${BAND_SCALE.toInt()}," +
            "\"bandLoHz\":$BAND_LO_HZ,\"bandHiHz\":$BAND_HI_HZ}"
}
