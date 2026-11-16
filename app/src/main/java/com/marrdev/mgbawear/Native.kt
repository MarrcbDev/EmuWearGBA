package com.marrdev.mgbawear

import android.graphics.Bitmap
import android.view.Surface

object Native {
    init { System.loadLibrary("mgbajni") }

    @JvmStatic external fun loadCore(dir: String): Boolean
    @JvmStatic external fun loadRom(path: String): Boolean
    /** [fps, sampleRate, ancho, alto] */
    @JvmStatic external fun avInfo(): DoubleArray
    @JvmStatic external fun setSurface(surface: Surface?)
    /** Ejecuta un frame. Devuelve shorts de audio | 0x10000 si hay frame nuevo para present(). */
    @JvmStatic external fun runFrame(audio: ShortArray, keys: Int): Int
    @JvmStatic external fun present()
    @JvmStatic external fun copyFrame(dst: Bitmap)
    @JvmStatic external fun loadSave(path: String)
    @JvmStatic external fun writeSave(path: String)
    @JvmStatic external fun saveState(path: String): Boolean
    @JvmStatic external fun loadState(path: String): Boolean
    @JvmStatic external fun reset()
    @JvmStatic external fun setOption(key: String, value: String)
    @JvmStatic external fun cheatReset()
    @JvmStatic external fun cheatSet(index: Int, enabled: Boolean, code: String)
    @JvmStatic external fun unload()
}
