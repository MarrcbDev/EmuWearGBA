package com.marrdev.mgbawear

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/** Botón en pantalla. Coordenadas en fracciones del ancho (pantalla redonda 450x450). */
data class Btn(val label: String, val bit: Int, val cx: Float, val cy: Float, val r: Float, val color: Color)

object Layout {
    // Ids de botón de libretro (RETRO_DEVICE_ID_JOYPAD_*)
    const val B = 0; const val SELECT = 2; const val START = 3
    const val UP = 4; const val DOWN = 5; const val LEFT = 6; const val RIGHT = 7
    const val A = 8; const val L = 10; const val R = 11
    /** Botón virtual (no existe en libretro): abre el menú de pausa. */
    const val PAUSE = 16

    const val GAME_CY = 0.45f
    const val STICK_CX = 0.22f
    const val STICK_CY = 0.74f
    const val STICK_R = 0.11f

    private val pad = Color(0xFFE2E8F0)
    val all: List<Btn> = listOf(
        Btn("▲", UP, 0.26f, 0.655f, 0.065f, pad),
        Btn("▼", DOWN, 0.26f, 0.825f, 0.065f, pad),
        Btn("◀", LEFT, 0.18f, 0.74f, 0.065f, pad),
        Btn("▶", RIGHT, 0.34f, 0.74f, 0.065f, pad),
        Btn("B", B, 0.68f, 0.79f, 0.075f, Color(0xFFFFC857)),
        Btn("A", A, 0.84f, 0.71f, 0.075f, Color(0xFFFF6AC1)),
        Btn("L", L, 0.22f, 0.21f, 0.06f, Color(0xFF5EEAD4)),
        Btn("R", R, 0.78f, 0.21f, 0.06f, Color(0xFF5EEAD4)),
        Btn("SEL", SELECT, 0.40f, 0.90f, 0.045f, Color(0xFF94A3B8)),
        Btn("STA", START, 0.60f, 0.90f, 0.045f, Color(0xFF94A3B8)),
        Btn("II", PAUSE, 0.50f, 0.075f, 0.045f, Color(0xFF9D8CFF)),
    )

    /** Sector de 45° (0 = derecha, en sentido horario porque y crece hacia abajo) → direcciones. */
    private val SECTORS = intArrayOf(
        1 shl RIGHT,
        (1 shl RIGHT) or (1 shl DOWN),
        1 shl DOWN,
        (1 shl DOWN) or (1 shl LEFT),
        1 shl LEFT,
        (1 shl LEFT) or (1 shl UP),
        1 shl UP,
        (1 shl UP) or (1 shl RIGHT),
    )

    /** Botones visibles según el control elegido y si Back está mapeado a A o B. */
    fun visible(control: Int, hw: Int): List<Btn> = all.filter { b ->
        val isDir = b.bit == UP || b.bit == DOWN || b.bit == LEFT || b.bit == RIGHT
        !(control == 1 && isDir) && !(hw == 1 && b.bit == A) && !(hw == 2 && b.bit == B)
    }

    /** Direcciones del joystick según el desplazamiento del pulgar desde el centro. */
    fun stickBits(dx: Float, dy: Float, r: Float): Int {
        if (sqrt(dx * dx + dy * dy) < r * 0.25f) return 0
        val angle = atan2(dy, dx)
        val sector = Math.floorMod(Math.round(angle / (PI.toFloat() / 4f)), 8)
        return SECTORS[sector]
    }

    /** Máscara de botones tocados por un dedo en (x, y); zona de toque 1.5x el radio visual. */
    fun hit(x: Float, y: Float, w: Float, scale: Float, list: List<Btn>): Int {
        var mask = 0
        for (b in list) {
            val dx = x - b.cx * w
            val dy = y - b.cy * w
            val r = b.r * w * scale * 1.5f
            if (dx * dx + dy * dy <= r * r) mask = mask or (1 shl b.bit)
        }
        return mask
    }
}
