package com.marrdev.mgbawear

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.view.SurfaceHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Corre el emulador en su propio hilo. El core pinta en un framebuffer RGB565 y la capa nativa lo
 * copia directo al Surface del SurfaceView (el sistema lo escala). El audio va por un anillo a otro hilo.
 */
class EmuSession(private val ctx: Context, private val rom: File) {
    /** Solo para miniaturas de estados (se llena con Native.copyFrame). */
    val bmp: Bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.RGB_565)
    var keys by mutableIntStateOf(0)
    var status by mutableStateOf("Cargando…")
    var toast by mutableStateOf("")
    var fps by mutableIntStateOf(0)
    /** Línea 1 del diagnóstico: tiempos medios por frame (ms) de emulación / presentación / espera. */
    var perf by mutableStateOf("")
    /** Línea 2 del diagnóstico: audio (tasa usada, muestras por frame, descartadas). */
    var diag by mutableStateOf("")
    var srcW by mutableIntStateOf(240)
    var srcH by mutableIntStateOf(160)
    @Volatile var holder: SurfaceHolder? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var dirty = true
    private val queue = ConcurrentLinkedQueue<() -> Unit>()
    private var thread: Thread? = null

    private val base = rom.nameWithoutExtension
    private val sav = File(File(ctx.filesDir, "saves").apply { mkdirs() }, "$base.sav").path
    private val statesDir = File(ctx.filesDir, "states").apply { mkdirs() }

    fun statePath(slot: Int) = File(statesDir, "$base.$slot.state")
    fun thumbPath(slot: Int) = File(statesDir, "$base.$slot.jpg")
    fun slotTime(slot: Int): String? {
        val f = statePath(slot)
        return if (f.exists()) SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(f.lastModified())) else null
    }

    // ---- control desde la interfaz (todo se ejecuta en el hilo del emulador) ----
    fun post(r: () -> Unit) { queue.add(r) }
    fun setPaused(p: Boolean) { paused = p; if (!p) dirty = true }
    fun redraw() { dirty = true }
    fun applySettings() = post { Settings.pushToCore() }
    fun reset() = post { Native.reset(); toast = "Reiniciado" }

    fun saveState(slot: Int) = post {
        val ok = Native.saveState(statePath(slot).path)
        if (ok) saveThumb(slot)
        toast = if (ok) "Estado $slot guardado" else "No se pudo guardar"
    }

    fun loadState(slot: Int) = post {
        toast = when {
            !statePath(slot).exists() -> "Ranura vacía"
            Native.loadState(statePath(slot).path) -> "Estado $slot cargado"
            else -> "No se pudo cargar"
        }
    }

    private fun saveThumb(slot: Int) {
        runCatching {
            Native.copyFrame(bmp)
            val b = Bitmap.createBitmap(bmp, 0, 0, srcW, srcH)
            thumbPath(slot).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 75, it) }
        }
    }

    fun start() {
        running = true
        thread = Thread({ loop() }, "emu").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(2000)
    }

    /** Anillo de audio: el hilo del emulador escribe aquí y un hilo aparte lo vuelca al AudioTrack. */
    private class AudioPump(val track: AudioTrack) : Thread("audio") {
        private val cap = 1 shl 16
        private val ring = ShortArray(cap)
        private var rd = 0
        private var wr = 0
        private val lock = Object()
        @Volatile var active = true
        @Volatile var quit = false
        @Volatile var dropped = 0

        fun clear() = synchronized(lock) { rd = wr }

        fun push(src: ShortArray, n: Int, maxFill: Int) = synchronized(lock) {
            if (wr - rd + n > maxFill) { dropped += n; return@synchronized }
            for (i in 0 until n) ring[(wr + i) and (cap - 1)] = src[i]
            wr += n
        }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val chunk = ShortArray(1024) // 512 frames ≈ 15 ms
            while (!quit) {
                if (!active) { sleep(15); continue }
                val got = synchronized(lock) {
                    val a = minOf(wr - rd, chunk.size)
                    for (i in 0 until a) chunk[i] = ring[(rd + i) and (cap - 1)]
                    rd += a
                    a
                }
                if (got < chunk.size) chunk.fill(0, got, chunk.size) // subdesborde: silencio, no tirones de ritmo
                track.write(chunk, 0, chunk.size)
            }
        }
    }

    private fun snapRate(measured: Double, declared: Int): Int {
        if (measured < 8000) return declared
        if (Math.abs(measured / declared - 1) < 0.08) return declared
        val common = listOf(32768, 44100, 48000, 65536, 96000)
        val near = common.minByOrNull { Math.abs(it - measured) }!!
        return if (Math.abs(near / measured - 1) < 0.08) near else measured.toInt().coerceIn(8000, 192000)
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        if (!Native.loadCore(ctx.filesDir.path)) { status = "No se pudo cargar el core"; return }
        Settings.pushToCore()
        if (!Native.loadRom(rom.path)) { status = "No se pudo cargar la ROM"; return }
        Native.loadSave(sav)
        val av = Native.avInfo()
        val declared = av[1].toInt().coerceAtLeast(8000)
        srcW = av[2].toInt().coerceIn(1, 240)
        srcH = av[3].toInt().coerceIn(1, 160)
        val coreFps = av[0].coerceIn(30.0, 120.0)
        val frameNs = (1_000_000_000.0 / coreFps).toLong()
        val buf = ShortArray(16384)

        // El Surface se entrega a la capa nativa desde este mismo hilo
        var attached: SurfaceHolder? = null
        fun syncSurface() {
            val h = holder
            if (h !== attached) {
                Native.setSurface(h?.surface?.takeIf { it.isValid })
                attached = h
                dirty = true
            }
        }

        var next = System.nanoTime()
        var tSleep = 0L
        fun pace() {
            next += frameNs
            val wait = next - System.nanoTime()
            if (wait > 0) {
                tSleep += wait
                Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            } else if (wait < -100_000_000L) {
                next = System.nanoTime()
            }
        }

        // Calentamiento (~0.75 s): medimos cuánto audio produce de verdad el core por frame
        // y usamos esa tasa real para el AudioTrack (el core a veces no coincide con lo que declara).
        var warmFrames = 0L
        val warmN = 45
        for (i in 0 until warmN) {
            if (!running) break
            syncSurface()
            val r = Native.runFrame(buf, 0)
            if (r and 0x10000 != 0) Native.present()
            warmFrames += (r and 0xFFFF) / 2
            pace()
        }
        val rate = snapRate(warmFrames.toDouble() / warmN * coreFps, declared)
        val audioInfo = "audio $rate Hz (core dice $declared)"

        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 1024 * 2 * 4))
            .build()
        val pump = AudioPump(track)
        track.play()
        pump.start()
        status = ""

        val maxFill = rate / 10 * 2 // ~100 ms de audio como máximo en el anillo (en shorts estéreo)
        var n = 0
        var frames = 0
        var t0 = SystemClock.uptimeMillis()
        var lastVol = -1f
        var wasPaused = false
        var tRun = 0L; var tDraw = 0L; var shorts = 0L
        var lastDrop = 0
        next = System.nanoTime()
        tSleep = 0
        try {
            while (running) {
                while (true) { (queue.poll() ?: break).invoke() }

                if (paused) {
                    if (!wasPaused) {
                        pump.active = false
                        track.pause(); track.flush(); pump.clear()
                        wasPaused = true
                        Native.writeSave(sav)
                    }
                    syncSurface()
                    if (dirty) { Native.present(); dirty = false }
                    Thread.sleep(30)
                    continue
                }
                if (wasPaused) {
                    pump.clear(); track.play(); pump.active = true
                    wasPaused = false
                    frames = 0
                    t0 = SystemClock.uptimeMillis()
                    next = System.nanoTime()
                }

                val vol = Settings.volume
                if (vol != lastVol) { track.setVolume(vol); lastVol = vol }

                syncSurface()
                val a0 = System.nanoTime()
                val r = Native.runFrame(buf, keys and 0xFFFF)
                val a1 = System.nanoTime()
                if (r and 0x10000 != 0) Native.present()
                val a2 = System.nanoTime()
                val s = r and 0xFFFF
                if (s > 0) pump.push(buf, s, maxFill)
                shorts += s
                tRun += a1 - a0; tDraw += a2 - a1

                pace() // ritmo por reloj (fps del core), independiente del audio

                frames++
                val now = SystemClock.uptimeMillis()
                if (now - t0 >= 1000) {
                    fps = (frames * 1000L / (now - t0)).toInt()
                    val d = pump.dropped
                    perf = "emu ${tRun / frames / 1_000_000} · dib ${tDraw / frames / 1_000_000} · " +
                        "esp ${tSleep / frames / 1_000_000} ms"
                    diag = "$audioInfo · ${shorts / 2 / frames}/f · perd ${(d - lastDrop) / 2}"
                    lastDrop = d
                    tRun = 0; tDraw = 0; tSleep = 0; shorts = 0
                    frames = 0
                    t0 = now
                }
                if (++n % 1800 == 0) Native.writeSave(sav) // ~cada 30 s
            }
        } finally {
            Native.writeSave(sav)
            Native.setSurface(null)
            Native.unload()
            pump.quit = true
            pump.active = false
            track.pause(); track.flush()
            pump.join(500)
            track.release()
        }
    }
}
