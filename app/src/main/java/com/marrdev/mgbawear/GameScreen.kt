package com.marrdev.mgbawear

import android.graphics.BitmapFactory
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun GameScreen(rom: File, onExit: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val session = remember { EmuSession(ctx, rom) }
    var menu by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { session.start(); onDispose { session.stop() } }
    LaunchedEffect(menu) { session.keys = 0; session.setPaused(menu) }

    // Al salir de la app o apagarse la pantalla: pausa (y guarda la partida)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) menu = true }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    BackHandler(enabled = !menu) { menu = true }
    LaunchedEffect(session.toast) {
        if (session.toast.isNotEmpty()) { delay(1500); session.toast = "" }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val gwPx = wPx * Settings.screenW
        val ghPx = gwPx * session.srcH / session.srcW
        val leftPx = (wPx - gwPx) / 2f
        val topPx = wPx * Layout.GAME_CY - ghPx / 2f
        // Superficie del tamaño exacto del juego: el compositor la escala, sin costo para la CPU
        AndroidView(
            factory = { c ->
                SurfaceView(c).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { session.holder = h; session.redraw() }
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) { session.redraw() }
                        override fun surfaceDestroyed(h: SurfaceHolder) { session.holder = null }
                    })
                }
            },
            update = { it.holder.setFixedSize(session.srcW, session.srcH) },
            modifier = Modifier
                .offset { IntOffset(leftPx.roundToInt(), topPx.roundToInt()) }
                .size(with(density) { gwPx.toDp() }, with(density) { ghPx.toDp() })
        )
        Controls(session, enabled = !menu) { menu = true }

        if (session.status.isNotEmpty()) {
            Text(session.status, Modifier.align(Alignment.Center), textAlign = TextAlign.Center)
        }
        if (session.toast.isNotEmpty() && !menu) {
            Text(
                session.toast,
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 6.dp),
                textAlign = TextAlign.Center
            )
        }
        if (menu) PauseMenu(session, rom, onResume = { menu = false }, onExit = onExit)
    }
}

@Composable
private fun Controls(session: EmuSession, enabled: Boolean, onPause: () -> Unit) {
    val measurer = rememberTextMeasurer()
    val labels = remember {
        Layout.buttons.associate {
            it.bit to measurer.measure(it.label, TextStyle(color = Color.White, fontSize = 13.sp))
        }
    }
    val fpsStyle = remember { TextStyle(color = Color(0xE6FFFFFF), fontSize = 8.sp, textAlign = TextAlign.Center) }

    Canvas(
        Modifier.fillMaxSize().pointerInput(enabled) {
            if (!enabled) return@pointerInput
            // Multitouch: en cada evento se recalcula la máscara con todos los dedos apoyados
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    var mask = 0
                    for (c in ev.changes) {
                        if (c.pressed) {
                            mask = mask or Layout.hit(c.position.x, c.position.y, size.width.toFloat(), Settings.btnScale)
                        }
                        c.consume()
                    }
                    if (mask and (1 shl Layout.PAUSE) != 0) { session.keys = 0; onPause() } else session.keys = mask
                }
            }
        }
    ) {
        val w = size.width
        val keys = session.keys
        val alpha = Settings.opacity
        val sc = Settings.btnScale
        for (b in Layout.buttons) {
            val down = (keys shr b.bit) and 1 == 1
            val c = Offset(b.cx * w, b.cy * w)
            drawCircle(Color.White.copy(alpha = if (down) alpha + 0.3f else alpha), radius = b.r * w * sc, center = c)
            labels[b.bit]?.let {
                drawText(it, topLeft = Offset(c.x - it.size.width / 2f, c.y - it.size.height / 2f))
            }
        }
        if (Settings.showFps) {
            val t = measurer.measure("${session.fps} fps · ${session.perf}", fpsStyle)
            drawText(t, topLeft = Offset((w - t.size.width) / 2f, w * 0.115f))
        }
        if (Settings.showDiag && session.diag.isNotEmpty()) {
            val t = measurer.measure(session.diag, fpsStyle)
            drawText(t, topLeft = Offset((w - t.size.width) / 2f, w * 0.175f))
        }
    }
}

@Composable
private fun PauseMenu(session: EmuSession, rom: File, onResume: () -> Unit, onExit: () -> Unit) {
    var page by remember { mutableStateOf("main") }
    BackHandler { if (page == "main") onResume() else page = "main" }

    Box(Modifier.fillMaxSize().background(Color(0xF00B1020))) {
        when (page) {
            "main" -> ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 32.dp, bottom = 40.dp, start = 10.dp, end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item { Text("Pausa", style = MaterialTheme.typography.title3, color = Accent) }
                item {
                    Text(
                        rom.nameWithoutExtension, maxLines = 2, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.caption2
                    )
                }
                item { MenuChip("Continuar", primary = true, onClick = onResume) }
                item { MenuChip("Guardar estado") { page = "save" } }
                item { MenuChip("Cargar estado") { page = "load" } }
                item { MenuChip("Reiniciar") { session.reset(); onResume() } }
                item { MenuChip("Ajustes") { page = "settings" } }
                item { MenuChip("Salir al menú", onClick = onExit) }
            }
            "save" -> SlotsPage(session, saving = true, onDone = onResume)
            "load" -> SlotsPage(session, saving = false, onDone = onResume)
            else -> SettingsList(session) { page = "main" }
        }
    }
}

@Composable
private fun SlotsPage(session: EmuSession, saving: Boolean, onDone: () -> Unit) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 32.dp, bottom = 40.dp, start = 10.dp, end = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                if (saving) "Guardar en…" else "Cargar de…",
                style = MaterialTheme.typography.title3, color = Accent
            )
        }
        items(3) { i ->
            val slot = i + 1
            val time = session.slotTime(slot)
            val thumb = remember(slot) {
                if (time != null) BitmapFactory.decodeFile(session.thumbPath(slot).path) else null
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1F2430))
                    .clickable(enabled = saving || time != null) {
                        if (saving) session.saveState(slot) else session.loadState(slot)
                        onDone()
                    }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(64.dp, 43.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A3042))) {
                    if (thumb != null) {
                        Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Ranura $slot", style = MaterialTheme.typography.button)
                    Text(time ?: "Vacía", style = MaterialTheme.typography.caption2, color = Color(0xFFB8BCCB))
                }
            }
        }
    }
}
