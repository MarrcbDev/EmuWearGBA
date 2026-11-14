package com.marrdev.mgbawear

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import java.io.File
import java.net.Inet4Address

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val romsDir = File(filesDir, "roms").apply { mkdirs() }
        setContent { MaterialTheme { App(romsDir) } }
    }
}

private fun localIp(ctx: Context): String? {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
    return lp.linkAddresses.map { it.address }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
}

@Composable
fun App(romsDir: File) {
    val ctx = LocalContext.current
    var playing by remember { mutableStateOf<File?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val server = RomServer(romsDir)
        runCatching { server.start(RomServer.SOCKET_READ_TIMEOUT, false) }
        onDispose { server.stop() }
    }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }

    val roms = remember(tick, playing) {
        romsDir.listFiles()
            ?.filter { it.extension.lowercase() in setOf("gba", "gb", "gbc") }
            ?.sortedBy { it.name } ?: emptyList()
    }
    val ip = remember(tick) { localIp(ctx) }

    val p = playing
    if (p == null) RomListScreen(ip, roms) { playing = it } else GameScreen(p) { playing = null }
}

@Composable
fun RomListScreen(ip: String?, roms: List<File>, onPlay: (File) -> Unit) {
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { Text("mGBA", style = MaterialTheme.typography.title3) }
        item {
            Text(
                if (ip != null) "Sube ROMs desde el teléfono (mismo Wi-Fi):\nhttp://$ip:8080"
                else "Conecta el reloj a Wi-Fi para subir ROMs",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.caption2,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        items(roms) { f ->
            Chip(
                onClick = { onPlay(f) },
                label = { Text(f.nameWithoutExtension, maxLines = 2) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun GameScreen(rom: File, onExit: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val session = remember { EmuSession(ctx, rom) }
    DisposableEffect(Unit) { session.start(); onDispose { session.stop() } }
    BackHandler { onExit() }

    val img = remember { session.bmp.asImageBitmap() }
    val measurer = rememberTextMeasurer()
    val labels = remember {
        Layout.buttons.associate {
            it.bit to measurer.measure(it.label, TextStyle(color = Color.White, fontSize = 13.sp))
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                // Multitouch: en cada evento se recalcula la máscara con todos los dedos apoyados
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        var mask = 0
                        for (c in ev.changes) {
                            if (c.pressed) mask = mask or Layout.hit(c.position.x, c.position.y, size.width.toFloat())
                            c.consume()
                        }
                        session.keys = mask
                    }
                }
            }
        ) {
            session.frame.intValue // suscribe el redibujo a cada frame
            val w = size.width
            val gw = w * Layout.GAME_W
            val gh = gw * session.srcH / session.srcW
            drawImage(
                img,
                srcSize = IntSize(session.srcW, session.srcH),
                dstOffset = IntOffset(((w - gw) / 2).toInt(), (w * Layout.GAME_CY - gh / 2).toInt()),
                dstSize = IntSize(gw.toInt(), gh.toInt()),
                filterQuality = FilterQuality.None
            )
            val keys = session.keys
            for (b in Layout.buttons) {
                val down = (keys shr b.bit) and 1 == 1
                val c = Offset(b.cx * w, b.cy * w)
                drawCircle(Color.White.copy(alpha = if (down) 0.55f else 0.22f), radius = b.r * w, center = c)
                labels[b.bit]?.let {
                    drawText(it, topLeft = Offset(c.x - it.size.width / 2f, c.y - it.size.height / 2f))
                }
            }
        }
        if (session.status.isNotEmpty()) Text(session.status, textAlign = TextAlign.Center)
    }
}
