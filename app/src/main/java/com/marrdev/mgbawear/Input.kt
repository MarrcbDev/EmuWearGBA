package com.marrdev.mgbawear

import android.view.KeyEvent

/** Botones físicos del reloj usados como A/B mientras se juega. */
object Input {
    /** Bits de botón de los físicos ahora pulsados. */
    @Volatile var hwMask = 0
    /** True solo dentro del juego y sin menú de pausa. */
    @Volatile var gameActive = false

    /** Máscara del botón libretro que corresponde a esta tecla, o 0 si no se usa. */
    fun maskFor(keyCode: Int): Int {
        if (!gameActive || keyCode != KeyEvent.KEYCODE_BACK) return 0
        return when (Settings.hwIdx) {
            1 -> 1 shl Layout.A
            2 -> 1 shl Layout.B
            else -> 0
        }
    }
}
