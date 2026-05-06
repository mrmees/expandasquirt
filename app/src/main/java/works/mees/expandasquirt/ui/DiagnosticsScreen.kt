package works.mees.expandasquirt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    vm: DiagnosticsViewModel,
    onBack: () -> Unit,
    onUsbRescue: () -> Unit,
) {
    val out by vm.output.collectAsState()
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { vm.run("status") }) {
                    Icon(Icons.Default.Memory, contentDescription = null)
                    Text("Status dump", modifier = Modifier.padding(start = 8.dp))
                }
                FilledTonalButton(onClick = { vm.run("boot") }) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Text("Boot info", modifier = Modifier.padding(start = 8.dp))
                }
                FilledTonalButton(onClick = { vm.run("help") }) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
                    Text("Help", modifier = Modifier.padding(start = 8.dp))
                }
                FilledTonalButton(onClick = { vm.run("reboot") }) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null)
                    Text("Reboot", modifier = Modifier.padding(start = 8.dp))
                }
                FilledTonalButton(onClick = onUsbRescue) {
                    Icon(Icons.Default.Usb, contentDescription = null)
                    Text("USB rescue", modifier = Modifier.padding(start = 8.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Box(Modifier.fillMaxSize()) {
                    Text(
                        out,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .padding(end = 44.dp)
                            .verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(out)) },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy output")
                    }
                }
            }
        }
    }
}
