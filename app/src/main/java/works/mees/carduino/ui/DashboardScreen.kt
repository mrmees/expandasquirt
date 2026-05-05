package works.mees.carduino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import works.mees.carduino.ble.SensorReading

/** Compose implementation of the compact live sensor list dashboard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    vm: DashboardViewModel,
    onMenuFirmwareUpdate: () -> Unit,
    onMenuDiagnostics: () -> Unit,
) {
    val state by vm.state.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.deviceName) },
                actions = {
                    Text(
                        if (state.connected) "● connected" else "○ disconnected",
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    IconButton(onClick = { menuOpen = true }) { Text("⋮") }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Firmware update…") },
                            onClick = {
                                menuOpen = false
                                onMenuFirmwareUpdate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Diagnostics") },
                            onClick = {
                                menuOpen = false
                                onMenuDiagnostics()
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 12.dp),
        ) {
            val frame = state.frame
            val order = listOf(
                "oilT" to "Oil Temp",
                "oilP" to "Oil Press",
                "fuelP" to "Fuel Press",
                "preP" to "Pre-IC MAP",
                "postT" to "Post-IC Temp",
            )

            order.forEach { (key, label) ->
                val reading = frame?.readings?.get(key)
                SensorRow(label = label, reading = reading)
            }

            Spacer(Modifier.height(12.dp))
            Text(
                if (frame != null) {
                    "seq ${frame.seq} · health 0x%02X".format(frame.healthBitmask)
                } else {
                    "— no data —"
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color.Gray,
            )
        }
    }
}

@Composable
private fun SensorRow(label: String, reading: SensorReading?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(
                    if (reading?.healthOk == true) Color(0xFF4ADE80) else Color(0xFFFBBF24),
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Text(
            reading?.let { "%.1f".format(it.value) } ?: "—",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(4.dp))
        Text(reading?.unit ?: "", fontSize = 10.sp, color = Color.Gray)
    }
}
