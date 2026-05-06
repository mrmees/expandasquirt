package works.mees.expandasquirt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbRescueScreen(onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val uploadCommand = """
        arduino-cli upload \
          --fqbn arduino:renesas_uno:unor4wifi \
          --port <COM_PORT> \
          /path/to/expandasquirt-v4/
    """.trimIndent()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("USB rescue") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Wireless update failed?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Recover via USB. The Expandasquirt's active sketch is intact — wireless OTA " +
                    "only ever applies a new sketch atomically, so if you're here the new " +
                    "firmware never replaced the running one. Steps:",
            )

            BoldLeadText("1. ", "Plug in USB-C", " to a laptop with `arduino-cli` installed.")

            BoldLeadText("2. ", "If the device isn't detected, force the bootloader:", "")
            IndentedText(
                "- Press the reset button twice in quick succession (about 100-200 ms apart).",
            )
            IndentedText(
                "- The on-board yellow LED should pulse — that means BOSSA bootloader is active.",
            )
            IndentedText(
                "- On Windows, the COM port may change number after entering bootloader mode; " +
                    "check Device Manager.",
            )

            BoldLeadText("3. ", "Re-flash the last known-good firmware:", "")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            uploadCommand,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                                .padding(end = 44.dp),
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(uploadCommand)) },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy command")
                    }
                }
            }
            IndentedText(
                "- Replace `<COM_PORT>` with whatever appeared in step 2 (e.g. `COM7`).",
            )

            BoldLeadText("4. ", "Verify recovery:", "")
            IndentedText("- Re-open this app.")
            IndentedText(
                "- The Expandasquirt should advertise on BLE within ~5 sec of upload completion.",
            )
            IndentedText(
                "- Open the dashboard and confirm the banner shows the expected version.",
            )

            Text(
                "If the bootloader doesn't activate after 5 attempts at the double-tap, " +
                    "hold the BOOT button (if present) while pressing reset once. R4 clones " +
                    "differ in their bootloader trigger circuit — `docs/bench-test-procedures.md` " +
                    "will have any clone-specific quirks once verified at bench.",
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BoldLeadText(prefix: String, bold: String, suffix: String) {
    Text(
        buildAnnotatedString {
            append(prefix)
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(bold)
            }
            append(suffix)
        },
    )
}

@Composable
private fun IndentedText(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 24.dp),
    )
}
