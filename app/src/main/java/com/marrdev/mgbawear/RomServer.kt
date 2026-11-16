package com.marrdev.mgbawear

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Servidor HTTP local (puerto 8080):
 *  GET  /                 página web
 *  GET  /api/library      lista de juegos en JSON
 *  GET  /cover?name=X     carátula de X
 *  GET  /sav?name=X       descarga la partida guardada de X
 *  POST /upload?name=F    sube ROM (.gba/.gb/.gbc) o partida (.sav)
 *  POST /cover?rom=X      sube la carátula de X (cualquier imagen)
 *  POST /delete?file=F    borra la ROM y su carátula (conserva partidas y estados)
 *  POST /fetch-covers     busca carátulas en línea desde el reloj
 */
class RomServer(private val ctx: Context, port: Int = 8080) : NanoHTTPD(port) {
    private val romExt = setOf("gba", "gb", "gbc")
    private val romsDir get() = File(ctx.filesDir, "roms").apply { mkdirs() }
    private val savesDir get() = File(ctx.filesDir, "saves").apply { mkdirs() }

    private fun text(st: Response.IStatus, msg: String) =
        newFixedLengthResponse(st, "text/plain; charset=utf-8", msg)

    private fun ok() = text(Response.Status.OK, "ok")

    private fun roms(): List<File> =
        romsDir.listFiles()?.filter { it.extension.lowercase() in romExt }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    override fun serve(session: IHTTPSession): Response =
        try { route(session) } catch (e: Exception) {
            text(Response.Status.INTERNAL_ERROR, e.message ?: "error")
        }

    private fun route(s: IHTTPSession): Response {
        val get = s.method == Method.GET
        val post = s.method == Method.POST
        return when {
            get && s.uri == "/" -> page()
            get && s.uri == "/api/library" -> library()
            get && s.uri == "/cover" -> cover(s)
            get && s.uri == "/sav" -> sav(s)
            post && s.uri == "/upload" -> upload(s)
            post && s.uri == "/cover" -> uploadCover(s)
            post && s.uri == "/delete" -> delete(s)
            post && s.uri == "/fetch-covers" -> fetchCovers()
            get && s.uri == "/api/cheats" -> cheatsJson(s)
            post && s.uri == "/cheat-add" -> cheatAdd(s)
            post && s.uri == "/cheat-toggle" -> cheatToggle(s)
            post && s.uri == "/cheat-del" -> cheatDel(s)
            else -> text(Response.Status.NOT_FOUND, "no encontrado")
        }
    }

    private fun page(): Response {
        val html = ctx.assets.open("web/index.html").use { it.readBytes() }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", String(html, Charsets.UTF_8))
    }

    private fun library(): Response {
        val arr = JSONArray()
        for (f in roms()) {
            val base = f.nameWithoutExtension
            val cov = Covers.file(ctx, base)
            arr.put(
                JSONObject()
                    .put("file", f.name)
                    .put("name", base)
                    .put("system", f.extension.uppercase())
                    .put("size", f.length())
                    .put("cover", cov.exists())
                    .put("cover_v", cov.lastModified())
                    .put("sav", File(savesDir, "$base.sav").exists())
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", arr.toString())
    }

    private fun cover(s: IHTTPSession): Response {
        val f = Covers.file(ctx, File(s.parms["name"] ?: "").name)
        if (!f.exists()) return text(Response.Status.NOT_FOUND, "sin carátula")
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", f.inputStream(), f.length())
    }

    private fun sav(s: IHTTPSession): Response {
        val name = File(s.parms["name"] ?: "").name
        val f = File(savesDir, "$name.sav")
        if (!f.exists()) return text(Response.Status.NOT_FOUND, "sin partida")
        val r = newFixedLengthResponse(Response.Status.OK, "application/octet-stream", f.inputStream(), f.length())
        r.addHeader("Content-Disposition", "attachment; filename=\"$name.sav\"")
        return r
    }

    /** Guarda el cuerpo crudo de la petición en [target]; devuelve una respuesta de error o null si salió bien. */
    private fun readBody(s: IHTTPSession, target: File, max: Long): Response? {
        val len = s.headers["content-length"]?.toLongOrNull()
            ?: return text(Response.Status.LENGTH_REQUIRED, "Falta content-length")
        if (len > max) return text(Response.Status.BAD_REQUEST, "Archivo demasiado grande")
        val tmp = File(target.parentFile, target.name + ".part")
        var left = len
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            val ins = s.inputStream
            while (left > 0) {
                val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        if (left != 0L) {
            tmp.delete()
            return text(Response.Status.BAD_REQUEST, "Subida incompleta")
        }
        target.delete()
        tmp.renameTo(target)
        return null
    }

    private fun upload(s: IHTTPSession): Response {
        val name = File(s.parms["name"] ?: "").name
        val ext = name.substringAfterLast('.', "").lowercase()
        val target = when (ext) {
            in romExt -> File(romsDir, name)
            "sav" -> File(savesDir, name)
            else -> return text(Response.Status.BAD_REQUEST, "Solo .gba, .gb, .gbc o .sav")
        }
        readBody(s, target, 64L * 1024 * 1024)?.let { return it }
        if (ext in romExt) Thread { Covers.fetchMissing(ctx, listOf(target)) }.start()
        return ok()
    }

    private fun uploadCover(s: IHTTPSession): Response {
        val base = File(s.parms["rom"] ?: "").name
        if (base.isEmpty()) return text(Response.Status.BAD_REQUEST, "Falta el juego")
        readBody(s, Covers.file(ctx, base), 10L * 1024 * 1024)?.let { return it }
        Covers.setCover(ctx, base)
        return ok()
    }

    private fun delete(s: IHTTPSession): Response {
        val f = File(romsDir, File(s.parms["file"] ?: "").name)
        if (!f.exists()) return text(Response.Status.NOT_FOUND, "no existe")
        Covers.deleteRom(ctx, f)
        return ok()
    }

    private fun romBase(s: IHTTPSession): String = File(s.parms["rom"] ?: "").name

    private fun cheatsJson(s: IHTTPSession): Response {
        val arr = JSONArray()
        for (c in Cheats.load(ctx, romBase(s))) {
            arr.put(JSONObject().put("id", c.id).put("name", c.name).put("code", c.code).put("on", c.on).put("once", c.once))
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", arr.toString())
    }

    /** POST /cheat-add?rom=X&name=N con el código en el cuerpo (una línea por parte del código). */
    private fun cheatAdd(s: IHTTPSession): Response {
        val base = romBase(s)
        if (base.isEmpty()) return text(Response.Status.BAD_REQUEST, "Falta el juego")
        val map = HashMap<String, String>()
        s.parseBody(map)
        val code = (map["postData"] ?: "").trim()
        if (code.isEmpty()) return text(Response.Status.BAD_REQUEST, "Falta el código")
        val name = (s.parms["name"] ?: "").trim().ifEmpty { "Truco" }
        val list = Cheats.load(ctx, base).toMutableList()
        list.add(Cheats.Cheat(list.size, name, code, true, s.parms["once"] == "1"))
        Cheats.save(ctx, base, list)
        return ok()
    }

    private fun cheatToggle(s: IHTTPSession): Response {
        val base = romBase(s)
        val i = s.parms["i"]?.toIntOrNull() ?: return text(Response.Status.BAD_REQUEST, "Falta i")
        val list = Cheats.load(ctx, base).toMutableList()
        if (i !in list.indices) return text(Response.Status.NOT_FOUND, "no existe")
        list[i] = list[i].copy(on = s.parms["on"] == "1")
        Cheats.save(ctx, base, list)
        return ok()
    }

    private fun cheatDel(s: IHTTPSession): Response {
        val base = romBase(s)
        val i = s.parms["i"]?.toIntOrNull() ?: return text(Response.Status.BAD_REQUEST, "Falta i")
        val list = Cheats.load(ctx, base).toMutableList()
        if (i !in list.indices) return text(Response.Status.NOT_FOUND, "no existe")
        list.removeAt(i)
        Cheats.save(ctx, base, list.mapIndexed { idx, c -> c.copy(id = idx) })
        return ok()
    }

    private fun fetchCovers(): Response {
        Covers.clearMarkers(ctx)
        val list = roms()
        Thread { Covers.fetchMissing(ctx, list) }.start()
        return ok()
    }
}
