package works.mees.carduino.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Diagnostics actions for querying status, boot metadata, help, and reboot over BLE. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    vm: DiagnosticsViewModel,
    onBack: () -> Unit,
    onUsbRescue: () -> Unit,
) {
    val out by vm.output.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.run("status") }) { Text("Status dump") }
                FilledTonalButton(onClick = { vm.run("boot") }) { Text("Boot info") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.run("help") }) { Text("Help") }
                FilledTonalButton(onClick = { vm.run("reboot") }) { Text("Reboot") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onUsbRescue) { Text("USB rescue") }
            }
            Spacer(Modifier.height(16.dp))
            Surface(modifier = Modifier.fillMaxSize(), tonalElevation = 1.dp) {
                Text(
                    out,
                    modifier = Modifier
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
