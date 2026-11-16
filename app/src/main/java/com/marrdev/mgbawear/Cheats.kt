package com.marrdev.mgbawear

import android.content.Context
import java.io.File

/** Trucos por juego en filesDir/cheats/<juego>.txt, una línea por truco: estado TAB nombre TAB código. */
object Cheats {
    data class Cheat(val id: Int, val name: String, val code: String, val on: Boolean)

    private fun file(ctx: Context, base: String): File =
        File(File(ctx.filesDir, "cheats").apply { mkdirs() }, "$base.txt")

    fun load(ctx: Context, base: String): List<Cheat> {
        val f = file(ctx, base)
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.mapIndexedNotNull { i, line ->
            val p = line.split('\t')
            if (p.size < 3) null else Cheat(i, p[1], p[2], p[0] == "1")
        }
    }

    fun save(ctx: Context, base: String, list: List<Cheat>) {
        file(ctx, base).writeText(
            list.joinToString("\n") { c ->
                (if (c.on) "1" else "0") + "\t" + clean(c.name) + "\t" + clean(c.code)
            }
        )
    }

    /** Un solo renglón por truco: las líneas del código se unen con '+' (formato de libretro). */
    private fun clean(s: String): String =
        s.replace("\r", "").split('\n').map { it.trim().replace('\t', ' ') }
            .filter { it.isNotEmpty() }.joinToString("+")
}
