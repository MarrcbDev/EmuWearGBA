package com.marrdev.mgbawear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
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

sealed interface Screen {
    object Home : Screen
    object Upload : Screen
    object Options : Screen
    data class Game(val rom: File) : Screen
    data class Delete(val rom: File) : Screen
}

@Composable
fun App(romsDir: File) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var tick by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val server = RomServer(ctx.applicationContext)
        runCatching { server.start(5000, false) }
        onDispose { server.stop() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            if (screen is Screen.Home || screen is Screen.Upload) tick++
        }
    }

    val roms = remember(tick, screen) {
        romsDir.listFiles()
            ?.filter { it.extension.lowercase() in setOf("gba", "gb", "gbc") }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
    }
    val ip = remember(tick) { localIp(ctx) }

    // Busca carátulas en línea para los juegos que no tengan (si hay Wi-Fi)
    LaunchedEffect(roms.map { it.name }) {
        withContext(Dispatchers.IO) { Covers.fetchMissing(ctx.applicationContext, roms) }
    }

    BackHandler(enabled = screen !is Screen.Home && screen !is Screen.Game) { screen = Screen.Home }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            roms,
            onPlay = { screen = Screen.Game(it) },
            onDelete = { screen = Screen.Delete(it) },
            onUpload = { screen = Screen.Upload },
            onOptions = { screen = Screen.Options }
        )
        Screen.Upload -> UploadScreen(ip)
        Screen.Options -> SettingsList(null) { screen = Screen.Home }
        is Screen.Game -> GameScreen(s.rom) { screen = Screen.Home }
        is Screen.Delete -> DeleteScreen(
            s.rom,
            onConfirm = { Covers.deleteRom(ctx.applicationContext, s.rom); screen = Screen.Home },
            onCancel = { screen = Screen.Home }
        )
    }
}

private val bgBrush = Brush.verticalGradient(listOf(Color(0xFF0B1020), Color(0xFF1A1040)))

@Composable
fun HomeScreen(
    roms: List<File>,
    onPlay: (File) -> Unit,
    onDelete: (File) -> Unit,
    onUpload: () -> Unit,
    onOptions: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(bgBrush)) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 30.dp, bottom = 40.dp, start = 10.dp, end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text("EmuWearGBA", style = MaterialTheme.typography.title2, color = Accent, fontWeight = FontWeight.Bold)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactChip(
                        onClick = onUpload, label = { Text("Subir") },
                        colors = ChipDefaults.primaryChipColors()
                    )
                    CompactChip(
                        onClick = onOptions, label = { Text("Ajustes") },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
            }
            if (roms.isEmpty()) {
                item {
                    Text(
                        "Aún no hay juegos.\nToca «Subir» para enviarlos desde tu teléfono.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.body2,
                        color = Color(0xFFB8BCCB),
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            } else {
                items(roms, key = { it.name }) { f -> GameCard(f, onClick = { onPlay(f) }, onLongClick = { onDelete(f) }) }
                item {
                    Text(
                        "Mantén pulsado un juego para borrarlo",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.caption3,
                        color = Color(0xFF8A8FA3)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameCard(rom: File, onClick: () -> Unit, onLongClick: () -> Unit) {
    val size = rom.length()
    val sizeText = if (size >= 1024 * 1024) "%.1f MB".format(size / 1048576f) else "${size / 1024} KB"
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1F2430))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(rom.nameWithoutExtension, Modifier.size(56.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                rom.nameWithoutExtension.replace(Regex("\\s*\\(.*?\\)"), ""),
                maxLines = 2, style = MaterialTheme.typography.button
            )
            Text(
                "${rom.extension.uppercase()} · $sizeText",
                style = MaterialTheme.typography.caption2, color = Color(0xFFB8BCCB)
            )
        }
    }
}

@Composable
fun CoverImage(base: String, modifier: Modifier) {
    val ctx = LocalContext.current.applicationContext
    val bmp by produceState<Bitmap?>(null, base, Covers.rev) {
        value = withContext(Dispatchers.IO) { Covers.load(ctx, base, 160) }
    }
    val b = bmp
    val shape = RoundedCornerShape(12.dp)
    if (b != null) {
        Image(b.asImageBitmap(), null, modifier.clip(shape), contentScale = ContentScale.Crop)
    } else {
        val hue = ((base.hashCode() and 0x7fffffff) % 360).toFloat()
        Box(modifier.clip(shape).background(Color.hsv(hue, 0.5f, 0.55f)), contentAlignment = Alignment.Center) {
            Text(base.take(1).uppercase(), style = MaterialTheme.typography.title2, color = Color.White)
        }
    }
}

private fun makeUploadQr(url: String): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 360, 360,
        mapOf(EncodeHintType.MARGIN to 1))
    Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888).also { bmp ->
        for (y in 0 until 360) for (x in 0 until 360) {
            bmp.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
}.getOrNull()

@Composable
fun UploadScreen(ip: String?) {
    val url = ip?.let { "http://$it:8080" }
    val qr = remember(url) { url?.let(::makeUploadQr) }
    Box(Modifier.fillMaxSize().background(bgBrush)) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 24.dp, bottom = 40.dp, start = 14.dp, end = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item { Text("Subir juegos", style = MaterialTheme.typography.title3, color = Accent) }
            if (url == null) {
                item { Text("Conecta el reloj a una red Wi-Fi para generar el QR de subida.", textAlign = TextAlign.Center, style = MaterialTheme.typography.body2) }
            } else {
                if (qr != null) item {
                    Image(bitmap = qr.asImageBitmap(), contentDescription = "Código QR para abrir la página local de subida",
                        modifier = Modifier.size(174.dp))
                }
                item { Text("Escanea con la cámara del teléfono", style = MaterialTheme.typography.body2, textAlign = TextAlign.Center) }
                item { Text(url, color = Accent, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption1) }
                item { Text("Conecta el teléfono a la misma red Wi-Fi del reloj. Desde la página podrás subir ROM, partidas y carátulas.", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
            }
        }
    }
}

@Composable
fun DeleteScreen(rom: File, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(bgBrush)) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 34.dp, bottom = 40.dp, start = 14.dp, end = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item { Text("¿Borrar juego?", style = MaterialTheme.typography.title3, color = Accent) }
            item {
                Text(
                    rom.nameWithoutExtension, maxLines = 3, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.body2
                )
            }
            item {
                Text(
                    "Tus partidas y estados guardados se conservan.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2,
                    color = Color(0xFFB8BCCB)
                )
            }
            item { MenuChip("Borrar", primary = true, onClick = onConfirm) }
            item { MenuChip("Cancelar", onClick = onCancel) }
        }
    }
}
