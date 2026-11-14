package com.marrdev.mgbawear

import android.graphics.Bitmap

object Native {
    init { System.loadLibrary("mgbajni") }

    @JvmStatic external fun loadCore(dir: String): Boolean
    @JvmStatic external fun loadRom(path: String): Boolean
    /** [fps, sampleRate, ancho, alto] */
    @JvmStatic external fun avInfo(): DoubleArray
    /**
     * Ejecuta un frame. Devuelve (shorts de audio) | 0x10000 si se dibujó un frame nuevo en [bmp].
     */
    @JvmStatic external fun runFrame(bmp: Bitmap, audio: ShortArray, keys: Int): Int
    @JvmStatic external fun loadSave(path: String)
    @JvmStatic external fun writeSave(path: String)
    @JvmStatic external fun saveState(path: String): Boolean
    @JvmStatic external fun loadState(path: String): Boolean
    @JvmStatic external fun reset()
    @JvmStatic external fun setOption(key: String, value: String)
    @JvmStatic external fun unload()
}
