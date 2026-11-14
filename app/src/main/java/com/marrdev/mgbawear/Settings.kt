package com.marrdev.mgbawear

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ajustes globales, guardados en SharedPreferences. Cada opción se cicla con un toque. */
object Settings {
    private var prefs: SharedPreferences? = null

    val volumeOptions = listOf(20, 40, 60, 80, 100)
    val frameskipOptions = listOf(0, 1, 2, 3)
    val opacityOptions = listOf(15, 25, 40, 60)
    val btnSizeLabels = listOf("Pequeños", "Normales", "Grandes")
    val btnSizeValues = listOf(0.85f, 1f, 1.15f)
    val screenLabels = listOf("Normal", "Grande")
    val screenValues = listOf(0.693f, 0.77f)

    var volumeIdx by mutableIntStateOf(2); private set
    var frameskipIdx by mutableIntStateOf(0); private set
    var opacityIdx by mutableIntStateOf(1); private set
    var btnSizeIdx by mutableIntStateOf(1); private set
    var screenIdx by mutableIntStateOf(0); private set
    var showFps by mutableStateOf(true); private set
    var colorFix by mutableStateOf(false); private set

    val volume: Float get() = volumeOptions[volumeIdx] / 100f
    val frameskip: Int get() = frameskipOptions[frameskipIdx]
    val opacity: Float get() = opacityOptions[opacityIdx] / 100f
    val btnScale: Float get() = btnSizeValues[btnSizeIdx]
    val screenW: Float get() = screenValues[screenIdx]

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("emuwear", Context.MODE_PRIVATE)
        prefs = p
        volumeIdx = p.getInt("volume", 2).coerceIn(0, volumeOptions.lastIndex)
        frameskipIdx = p.getInt("frameskip", 0).coerceIn(0, frameskipOptions.lastIndex)
        opacityIdx = p.getInt("opacity", 1).coerceIn(0, opacityOptions.lastIndex)
        btnSizeIdx = p.getInt("btnsize", 1).coerceIn(0, btnSizeValues.lastIndex)
        screenIdx = p.getInt("screen", 0).coerceIn(0, screenValues.lastIndex)
        showFps = p.getBoolean("fps", true)
        colorFix = p.getBoolean("color", false)
    }

    private fun save() {
        prefs?.edit()
            ?.putInt("volume", volumeIdx)?.putInt("frameskip", frameskipIdx)
            ?.putInt("opacity", opacityIdx)?.putInt("btnsize", btnSizeIdx)
            ?.putInt("screen", screenIdx)?.putBoolean("fps", showFps)
            ?.putBoolean("color", colorFix)?.apply()
    }

    fun cycleVolume() { volumeIdx = (volumeIdx + 1) % volumeOptions.size; save() }
    fun cycleFrameskip() { frameskipIdx = (frameskipIdx + 1) % frameskipOptions.size; save() }
    fun cycleOpacity() { opacityIdx = (opacityIdx + 1) % opacityOptions.size; save() }
    fun cycleBtnSize() { btnSizeIdx = (btnSizeIdx + 1) % btnSizeValues.size; save() }
    fun cycleScreen() { screenIdx = (screenIdx + 1) % screenValues.size; save() }
    fun toggleFps() { showFps = !showFps; save() }
    fun toggleColor() { colorFix = !colorFix; save() }

    /** Opciones del core de mGBA (nombres de las variables libretro). */
    fun pushToCore() {
        Native.setOption("mgba_frameskip", if (frameskip > 0) "fixed_interval" else "disabled")
        Native.setOption("mgba_frameskip_interval", frameskip.toString())
        Native.setOption("mgba_color_correction", if (colorFix) "GBA" else "OFF")
    }
}
