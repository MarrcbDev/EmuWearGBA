package com.marrdev.mgbawear

import fi.iki.elonen.NanoHTTPD
import java.io.File

/** Servidor HTTP local: GET / muestra la página de subida, POST /upload?name=x.gba guarda la ROM. */
class RomServer(private val dir: File, port: Int = 8080) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.POST && session.uri == "/upload") return upload(session)
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE)
    }

    private fun upload(session: IHTTPSession): Response {
        val name = File(session.parms["name"] ?: "").name
        val ext = name.substringAfterLast('.', "").lowercase()
        if (name.isEmpty() || ext !in setOf("gba", "gb", "gbc"))
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Solo .gba, .gb o .gbc")
        val len = session.headers["content-length"]?.toLongOrNull()
            ?: return newFixedLengthResponse(Response.Status.LENGTH_REQUIRED, "text/plain", "Falta content-length")
        if (len > 64L * 1024 * 1024)
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Archivo demasiado grande")

        dir.mkdirs()
        val tmp = File(dir, "$name.part")
        var left = len
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            val ins = session.inputStream
            while (left > 0) {
                val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        if (left != 0L) {
            tmp.delete()
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Subida incompleta")
        }
        File(dir, name).delete()
        tmp.renameTo(File(dir, name))
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
    }

    companion object {
        private const val PAGE = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<title>mGBA Watch</title>
<body style="font-family:sans-serif;max-width:480px;margin:2em auto;padding:0 1em">
<h2>Enviar ROMs al reloj</h2>
<input type=file id=f accept=".gba,.gb,.gbc" multiple>
<button onclick=up()>Subir</button><pre id=log></pre>
<script>
async function up(){
  const log=document.getElementById('log');log.textContent='';
  for(const f of document.getElementById('f').files){
    await new Promise(res=>{
      const x=new XMLHttpRequest();
      x.open('POST','/upload?name='+encodeURIComponent(f.name));
      x.upload.onprogress=e=>log.textContent=f.name+': '+Math.round(e.loaded*100/e.total)+'%';
      x.onload=()=>{log.textContent+='\n'+f.name+': '+(x.status==200?'listo':'error '+x.responseText);res()};
      x.onerror=()=>{log.textContent+='\n'+f.name+': error de red';res()};
      x.send(f);
    });
  }
}
</script>"""
    }
}
