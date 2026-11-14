package com.marrdev.mgbawear

/** Posiciones en fracciones del ancho (pantalla redonda 450x450 del Watch 5 Pro). */
data class Btn(val label: String, val bit: Int, val cx: Float, val cy: Float, val r: Float)

object Layout {
    // Ids de botón de libretro (RETRO_DEVICE_ID_JOYPAD_*)
    const val B = 0; const val SELECT = 2; const val START = 3
    const val UP = 4; const val DOWN = 5; const val LEFT = 6; const val RIGHT = 7
    const val A = 8; const val L = 10; const val R = 11
    /** Botón virtual (no existe en libretro): abre el menú de pausa. */
    const val PAUSE = 16

    const val GAME_CY = 0.45f

    val buttons = listOf(
        Btn("▲", UP, 0.26f, 0.655f, 0.065f),
        Btn("▼", DOWN, 0.26f, 0.825f, 0.065f),
        Btn("◀", LEFT, 0.18f, 0.74f, 0.065f),
        Btn("▶", RIGHT, 0.34f, 0.74f, 0.065f),
        Btn("B", B, 0.68f, 0.79f, 0.07f),
        Btn("A", A, 0.84f, 0.71f, 0.07f),
        Btn("L", L, 0.22f, 0.21f, 0.065f),
        Btn("R", R, 0.78f, 0.21f, 0.065f),
        Btn("SEL", SELECT, 0.40f, 0.90f, 0.045f),
        Btn("STA", START, 0.60f, 0.90f, 0.045f),
        Btn("II", PAUSE, 0.50f, 0.075f, 0.045f),
    )

    /** Máscara de botones tocados por un dedo en (x, y); zona de toque 1.5x el radio visual. */
    fun hit(x: Float, y: Float, w: Float, scale: Float): Int {
        var mask = 0
        for (b in buttons) {
            val dx = x - b.cx * w
            val dy = y - b.cy * w
            val r = b.r * w * scale * 1.5f
            if (dx * dx + dy * dy <= r * r) mask = mask or (1 shl b.bit)
        }
        return mask
    }
}
