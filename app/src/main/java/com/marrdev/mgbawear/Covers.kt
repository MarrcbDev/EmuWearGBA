package com.marrdev.mgbawear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Carátulas: se guardan en filesDir/covers/<nombre>.cover (bytes de imagen tal cual). */
object Covers {
    /** Sube cada vez que cambia alguna carátula para refrescar la interfaz. */
    var rev by mutableIntStateOf(0); private set
    fun bump() { rev++ }

    private val cache = LruCache<String, Bitmap>(24)

    fun dir(ctx: Context) = File(ctx.filesDir, "covers").apply { mkdirs() }
    fun file(ctx: Context, base: String) = File(dir(ctx), "$base.cover")
    private fun marker(ctx: Context, base: String) = File(dir(ctx), "$base.none")

    fun load(ctx: Context, base: String, px: Int): Bitmap? {
        val f = file(ctx, base)
        if (!f.exists()) return null
        val key = "$base#${f.lastModified()}#$px"
        cache.get(key)?.let { return it }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        cache.put(key, bmp)
        return bmp
    }

    fun clearMarkers(ctx: Context) {
        dir(ctx).listFiles()?.filter { it.extension == "none" }?.forEach { it.delete() }
    }

    fun setCover(ctx: Context, base: String) {
        marker(ctx, base).delete()
        bump()
    }

    fun deleteRom(ctx: Context, rom: File) {
        rom.delete()
        file(ctx, rom.nameWithoutExtension).delete()
        marker(ctx, rom.nameWithoutExtension).delete()
        bump()
    }

    /** Intenta bajar las carátulas que falten desde thumbnails.libretro.com (necesita Wi-Fi). */
    fun fetchMissing(ctx: Context, roms: List<File>): Boolean {
        var any = false
        for (rom in roms) {
            val base = rom.nameWithoutExtension
            if (file(ctx, base).exists() || marker(ctx, base).exists()) continue
            if (fetch(ctx, rom)) any = true else runCatching { marker(ctx, base).createNewFile() }
        }
        if (any) bump()
        return any
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun fetch(ctx: Context, rom: File): Boolean {
        val system = when (rom.extension.lowercase()) {
            "gb" -> "Nintendo - Game Boy"
            "gbc" -> "Nintendo - Game Boy Color"
            else -> "Nintendo - Game Boy Advance"
        }
        val clean = rom.nameWithoutExtension.replace(Regex("[&*/:`<>?\\\\|]"), "_")
        val tmp = File(dir(ctx), "${rom.nameWithoutExtension}.part")
        return try {
            val url = URL("https://thumbnails.libretro.com/${enc(system)}/Named_Boxarts/${enc(clean)}.png")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            if (c.responseCode != 200) return false
            c.inputStream.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, o)
            if (o.outWidth <= 0) { tmp.delete(); return false }
            val dest = file(ctx, rom.nameWithoutExtension)
            dest.delete()
            tmp.renameTo(dest)
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }
}
