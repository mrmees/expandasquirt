package works.mees.carduino.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import works.mees.carduino.ble.ScannedDevice
import works.mees.carduino.ble.scanForCarduinos
import works.mees.carduino.persistence.DeviceStore
import works.mees.carduino.persistence.KnownDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerScreen(
    store: DeviceStore,
    onSelect: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val seen = remember { mutableStateMapOf<String, ScannedDevice>() }
    var promptMac by remember { mutableStateOf<String?>(null) }
    var nickname by remember { mutableStateOf("Carduino") }

    LaunchedEffect(Unit) {
        scanForCarduinos(ctx).collect { d -> seen[d.mac] = d }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Pick a Carduino") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text("Nearby devices", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(seen.values.toList()) { d ->
                    ListItem(
                        headlineContent = { Text(d.name) },
                        supportingContent = { Text("${d.mac} \u00B7 RSSI ${d.rssi}") },
                        modifier = Modifier.clickable {
                            nickname = "Carduino"
                            promptMac = d.mac
                        },
                    )
                }
            }
            if (seen.isEmpty()) {
                Text(
                    "Scanning\u2026",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    promptMac?.let { mac ->
        AlertDialog(
            onDismissRequest = { promptMac = null },
            title = { Text("Name this device") },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("Nickname") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmedOrDefault = nickname.trim().ifEmpty { "Carduino" }
                        scope.launch {
                            store.upsert(
                                KnownDevice(
                                    mac = mac,
                                    nickname = trimmedOrDefault,
                                    lastSeenEpochMs = System.currentTimeMillis(),
                                ),
                            )
                            store.setCurrent(mac)
                            promptMac = null
                            onSelect()
                        }
                    },
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { promptMac = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}
