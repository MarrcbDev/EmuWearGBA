package com.marrdev.mgbawear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Corre el emulador en su propio hilo. El video se dibuja directo a un SurfaceView desde ese hilo
 * (sin pasar por Compose) y el audio bloqueante marca el ritmo (~60 fps).
 */
class EmuSession(private val ctx: Context, private val rom: File) {
    val bmp: Bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
    var keys by mutableIntStateOf(0)
    var status by mutableStateOf("Cargando…")
    var toast by mutableStateOf("")
    var fps by mutableIntStateOf(0)
    // Diagnóstico temporal: separa coste del core, dibujo, espera de audio y underruns.
    var coreMs by mutableIntStateOf(0)
    var drawMs by mutableIntStateOf(0)
    var audioWaitMs by mutableIntStateOf(0)
    var audioUnderruns by mutableIntStateOf(0)
    var audioPeak by mutableIntStateOf(0)
    @Volatile var srcW = 240
    @Volatile var srcH = 160
    @Volatile var holder: SurfaceHolder? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var dirty = true
    private val queue = ConcurrentLinkedQueue<() -> Unit>()
    private var thread: Thread? = null
    private var audioThread: Thread? = null
    private val audioRunning = AtomicBoolean(false)
    private val audioQueue = ArrayBlockingQueue<ShortArray>(4)

    private val base = rom.nameWithoutExtension
    private val sav = File(File(ctx.filesDir, "saves").apply { mkdirs() }, "$base.sav").path
    private val statesDir = File(ctx.filesDir, "states").apply { mkdirs() }

    private val src = Rect()
    private val dst = RectF()
    private val paint = Paint().apply { isFilterBitmap = false }

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
        audioRunning.set(false)
        audioThread?.interrupt()
        audioThread?.join(1000)
    }

    private fun draw() {
        val started = System.nanoTime()
        val h = holder ?: return
        val c = try { h.lockHardwareCanvas() } catch (e: Exception) { null } ?: return
        try {
            c.drawColor(Color.BLACK)
            val w = c.width.toFloat()
            val gw = w * Settings.screenW
            val gh = gw * srcH / srcW
            val left = (w - gw) / 2f
            val top = w * Layout.GAME_CY - gh / 2f
            src.set(0, 0, srcW, srcH)
            dst.set(left, top, left + gw, top + gh)
            c.drawBitmap(bmp, src, dst, paint)
        } finally {
            try { h.unlockCanvasAndPost(c) } catch (e: Exception) { }
            drawMs = ((System.nanoTime() - started) / 1_000_000L).toInt()
        }
    }

    private fun loop() {
        // El hilo de audio tiene prioridad AUDIO; el de emulación usa DISPLAY para
        // no competir con AudioTrack ni quedar bloqueado por sus escrituras.
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        if (!Native.loadCore(ctx.filesDir.path)) { status = "No se pudo cargar el core"; return }
        Settings.pushToCore()
        if (!Native.loadRom(rom.path)) { status = "No se pudo cargar la ROM"; return }
        Native.loadSave(sav)
        val av = Native.avInfo()
        val rate = av[1].toInt().coerceAtLeast(8000)
        srcW = av[2].toInt().coerceIn(1, 240)
        srcH = av[3].toInt().coerceIn(1, 160)

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
            // Búfer moderado: suficiente para el reloj sin añadir demasiada latencia.
            .setBufferSizeInBytes(maxOf(if (minBuf > 0) minBuf else 0, rate / 10 * 4))
            .build()
        audioQueue.clear()
        track.setVolume(Settings.volume)
        audioRunning.set(true)
        track.play()
        audioThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                while (audioRunning.get() || audioQueue.isNotEmpty()) {
                    val chunk = try { audioQueue.take() } catch (_: InterruptedException) { continue }
                    var offset = 0
                    while (offset < chunk.size && audioRunning.get()) {
                        val wrote = track.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (wrote <= 0) break
                        offset += wrote
                    }
                }
            } finally {
                runCatching { track.pause(); track.flush(); track.stop() }
                track.release()
            }
        }, "emu-audio").also { it.start() }
        status = ""

        val buf = ShortArray(32768)
        var n = 0
        val frameNanos = (1_000_000_000.0 / av[0].coerceIn(30.0, 120.0)).toLong()
        var nextFrame = System.nanoTime()
        var frames = 0
        var t0 = SystemClock.uptimeMillis()
        var lastVol = Settings.volume
        var wasPaused = false
        try {
            while (running) {
                while (true) { (queue.poll() ?: break).invoke() }

                if (paused) {
                    if (!wasPaused) {
                        audioQueue.clear()
                        track.pause(); track.flush()
                        wasPaused = true
                        Native.writeSave(sav)
                    }
                    if (dirty) { draw(); dirty = false }
                    Thread.sleep(30)
                    continue
                }
                if (wasPaused) {
                    track.play()
                    wasPaused = false
                    frames = 0
                    t0 = SystemClock.uptimeMillis()
                }

                val vol = Settings.volume
                if (vol != lastVol) { track.setVolume(vol); lastVol = vol }

                val coreStarted = System.nanoTime()
                val r = Native.runFrame(bmp, buf, keys and 0xFFFF)
                coreMs = ((System.nanoTime() - coreStarted) / 1_000_000L).toInt()
                if (r and 0x10000 != 0) draw()
                val s = r and 0xFFFF
                if (s > 0) {
                    var peak = 0
                    for (i in 0 until s) { val v = kotlin.math.abs(buf[i].toInt()); if (v > peak) peak = v }
                    audioPeak = peak
                    val chunk = buf.copyOf(s)
                    // No descartar bloques: perder muestras entre frames genera clics,
                    // cortes y artefactos. La cola aplica back-pressure si AudioTrack se atrasa.
                    try {
                        val waitStarted = System.nanoTime()
                        audioQueue.put(chunk)
                        audioWaitMs = ((System.nanoTime() - waitStarted) / 1_000_000L).toInt()
                    } catch (_: InterruptedException) {
                        if (!running) break
                    }
                }

                // Ritmo del core: no dejar que la CPU genere frames ilimitados.
                nextFrame += frameNanos
                val wait = nextFrame - System.nanoTime()
                if (wait > 0) LockSupport.parkNanos(wait) else if (wait < -frameNanos * 3) nextFrame = System.nanoTime()

                runCatching { audioUnderruns = track.underrunCount }
                frames++
                val now = SystemClock.uptimeMillis()
                if (now - t0 >= 1000) {
                    fps = (frames * 1000L / (now - t0)).toInt()
                    frames = 0
                    t0 = now
                }
                if (++n % 1800 == 0) Native.writeSave(sav) // ~cada 30 s
            }
        } finally {
            Native.writeSave(sav)
            Native.unload()
            audioRunning.set(false)
            audioThread?.interrupt()
            audioThread?.join(1000)
        }
    }
}
