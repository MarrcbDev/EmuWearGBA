package com.marrdev.mgbawear

import android.graphics.Bitmap

object Native {
    init { System.loadLibrary("mgbajni") }

    @JvmStatic external fun loadCore(dir: String): Boolean
    @JvmStatic external fun loadRom(path: String): Boolean
    /** [fps, sampleRate, ancho, alto] */
    @JvmStatic external fun avInfo(): DoubleArray
    /** Ejecuta un frame, dibuja en [bmp] y devuelve cuántos shorts de audio escribió en [audio]. */
    @JvmStatic external fun runFrame(bmp: Bitmap, audio: ShortArray, keys: Int): Int
    @JvmStatic external fun loadSave(path: String)
    @JvmStatic external fun writeSave(path: String)
    @JvmStatic external fun unload()
}
