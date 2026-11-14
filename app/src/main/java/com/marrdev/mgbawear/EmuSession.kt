package com.marrdev.mgbawear

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Corre el emulador en su propio hilo; el audio bloqueante marca el ritmo (~60 fps). */
class EmuSession(private val ctx: Context, private val rom: File) {
    val bmp: Bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
    var keys by mutableIntStateOf(0)
    val frame = mutableIntStateOf(0)
    var status by mutableStateOf("Cargando…")
    @Volatile var srcW = 240
    @Volatile var srcH = 160
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ loop() }, "emu").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(2000)
    }

    private fun loop() {
        val savDir = File(ctx.filesDir, "saves").apply { mkdirs() }
        val sav = File(savDir, rom.nameWithoutExtension + ".sav").path
        if (!Native.loadCore(ctx.filesDir.path) || !Native.loadRom(rom.path)) {
            status = "No se pudo cargar la ROM"
            return
        }
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
            .setBufferSizeInBytes(maxOf(minBuf, rate / 60 * 4 * 3))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track.play()
        status = ""

        val buf = ShortArray(16384)
        var n = 0
        try {
            while (running) {
                val s = Native.runFrame(bmp, buf, keys)
                if (s > 0) track.write(buf, 0, s)
                frame.intValue++
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
