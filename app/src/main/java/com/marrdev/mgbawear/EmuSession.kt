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
import android.util.Log
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
 * Corre el emulador en su propio hilo. El video se dibuja directo a un SurfaceView desde ese hilo
 * (sin pasar por Compose) y el audio bloqueante marca el ritmo (~60 fps).
 */
class EmuSession(private val ctx: Context, private val rom: File) {
    val bmp: Bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.RGB_565)
    var keys by mutableIntStateOf(0)
    var status by mutableStateOf("Cargando…")
    var toast by mutableStateOf("")
    var fps by mutableIntStateOf(0)
    /** Tiempo medio por frame en ms: emulación / dibujo / audio (para diagnosticar lentitud). */
    var perf by mutableStateOf("")
    /** Tiempos medios por frame (ms): emulación / dibujo / espera de audio. */
    var diag by mutableStateOf("")
    @Volatile var srcW = 240
    @Volatile var srcH = 160
    @Volatile var holder: SurfaceHolder? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var dirty = true
    private val queue = ConcurrentLinkedQueue<() -> Unit>()
    private var thread: Thread? = null

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
    }

    private fun draw() {
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
        }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
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
            .setBufferSizeInBytes(maxOf(minBuf, rate / 60 * 4 * 6)) // ~6 frames de colchón contra cortes
            .build()
        track.play()
        status = ""

        val buf = ShortArray(16384)
        var n = 0
        var frames = 0
        var t0 = SystemClock.uptimeMillis()
        var lastVol = -1f
        var wasPaused = false
        var tRun = 0L
        var tDraw = 0L
        var tAud = 0L
        try {
            while (running) {
                while (true) { (queue.poll() ?: break).invoke() }

                if (paused) {
                    if (!wasPaused) {
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

                val a = System.nanoTime()
                val r = Native.runFrame(bmp, buf, keys and 0xFFFF)
                val b = System.nanoTime()
                if (r and 0x10000 != 0) draw()
                val c = System.nanoTime()
                val s = r and 0xFFFF
                if (s > 0) track.write(buf, 0, s)
                val d = System.nanoTime()
                tRun += b - a; tDraw += c - b; tAud += d - c

                frames++
                val now = SystemClock.uptimeMillis()
                if (now - t0 >= 1000) {
                    fps = (frames * 1000L / (now - t0)).toInt()
                    val f = maxOf(frames, 1)
                    diag = "emu ${tRun / f / 100000 / 10.0} · dib ${tDraw / f / 100000 / 10.0} · aud ${tAud / f / 100000 / 10.0} ms"
                    Log.i("mgbawear", "fps=$fps $diag")
                    tRun = 0; tDraw = 0; tAud = 0
                    frames = 0
                    t0 = now
                }
                if (++n % 1800 == 0) Native.writeSave(sav) // ~cada 30 s
            }
        } finally {
            Native.writeSave(sav)
            Native.unload()
            track.stop()
            track.release()
        }
    }
}
