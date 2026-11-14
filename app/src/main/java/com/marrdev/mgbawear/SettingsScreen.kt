package com.marrdev.mgbawear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

val Accent = Color(0xFF9D8CFF)

@Composable
fun MenuChip(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(label) },
        colors = if (primary) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun OptionChip(label: String, value: String, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(label) },
        secondaryLabel = { Text(value) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Pantalla de ajustes. Si [session] no es null (en pleno juego), aplica los cambios al emulador al instante. */
@Composable
fun SettingsList(session: EmuSession?, onBack: () -> Unit) {
    fun changed() { session?.applySettings(); session?.redraw() }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 32.dp, bottom = 40.dp, start = 10.dp, end = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { Text("Ajustes", style = MaterialTheme.typography.title3, color = Accent) }
        item { OptionChip("Volumen", "${Settings.volumeOptions[Settings.volumeIdx]}%") { Settings.cycleVolume() } }
        item {
            OptionChip(
                "Salto de frames",
                if (Settings.frameskip == 0) "Desactivado" else "Saltar ${Settings.frameskip}"
            ) { Settings.cycleFrameskip(); changed() }
        }
        item { OptionChip("Mostrar FPS", if (Settings.showFps) "Sí" else "No") { Settings.toggleFps() } }
        item { OptionChip("Diagnóstico", if (Settings.showDiag) "Sí" else "No") { Settings.toggleDiag() } }
        item {
            OptionChip("Colores GBA", if (Settings.colorFix) "Activados" else "Desactivados") {
                Settings.toggleColor(); changed()
            }
        }
        item {
            OptionChip("Tamaño de pantalla", Settings.screenLabels[Settings.screenIdx]) {
                Settings.cycleScreen(); changed()
            }
        }
        item { OptionChip("Tamaño de botones", Settings.btnSizeLabels[Settings.btnSizeIdx]) { Settings.cycleBtnSize() } }
        item {
            OptionChip("Opacidad de botones", "${Settings.opacityOptions[Settings.opacityIdx]}%") { Settings.cycleOpacity() }
        }
        item { MenuChip("Volver", onClick = onBack) }
    }
}
