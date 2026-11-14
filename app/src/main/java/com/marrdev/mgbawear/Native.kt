package com.marrdev.mgbawear

import android.graphics.Bitmap
import android.view.Surface

object Native {
    init { System.loadLibrary("mgbajni") }

    @JvmStatic external fun loadCore(dir: String): Boolean
    @JvmStatic external fun loadRom(path: String): Boolean
    /** [fps, sampleRate, ancho, alto] según el core */
    @JvmStatic external fun avInfo(): DoubleArray
    /** Ejecuta un frame. Devuelve (shorts de audio) | 0x10000 si hay un frame nuevo. */
    @JvmStatic external fun runFrame(audio: ShortArray, keys: Int): Int
    /** Surface donde se presenta el video (null = ninguna). Llamar desde el hilo del emulador. */
    @JvmStatic external fun setSurface(surface: Surface?)
    @JvmStatic external fun present()
    /** Copia el último frame a un Bitmap RGB_565 (miniaturas de estados). */
    @JvmStatic external fun copyFrame(bmp: Bitmap)
    @JvmStatic external fun loadSave(path: String)
    @JvmStatic external fun writeSave(path: String)
    @JvmStatic external fun saveState(path: String): Boolean
    @JvmStatic external fun loadState(path: String): Boolean
    @JvmStatic external fun reset()
    @JvmStatic external fun setOption(key: String, value: String)
    @JvmStatic external fun unload()
}
