package works.mees.carduino.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.max
import works.mees.carduino.ota.CARDUINO_R4_MAX_SKETCH_BYTES
import works.mees.carduino.ota.SizeCheck
import works.mees.carduino.ota.rememberBinFilePicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtaWizardScreen(
    otaVm: OtaViewModel,
    hotspotVm: HotspotSetupViewModel,
    onExit: () -> Unit,
    onUsbRescue: () -> Unit,
) {
    val step by otaVm.step.collectAsState()
    val ctx = LocalContext.current

    if (step is OtaStep.HotspotSetup) {
        HotspotSetupScreen(
            vm = hotspotVm,
            onCancel = {
                otaVm.cancel()
                onExit()
            },
            onProceed = { creds, network ->
                otaVm.onHotspotConfirmed(ctx, creds, network)
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Firmware Update") },
                navigationIcon = {
                    TextButton(
                        onClick = {
                            otaVm.cancel()
                            onExit()
                        },
                    ) {
                        Text("<")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            when (val currentStep = step) {
                OtaStep.PickFile -> PickFileStep(otaVm)
                is OtaStep.PreFlight -> PreFlightStep(currentStep, otaVm, onExit)
                is OtaStep.EnteringMaintenance -> BusyStep(currentStep.message)
                is OtaStep.FindingDevice -> BusyStep(currentStep.message)
                is OtaStep.Uploading -> UploadingStep(currentStep, otaVm, onExit)
                OtaStep.Applying -> BusyStep("Carduino is flashing the firmware. Don't disturb.")
                OtaStep.Verifying -> BusyStep("Verifying new firmware version...")
                is OtaStep.Done -> DoneStep(currentStep, otaVm, onExit)
                is OtaStep.Failed -> FailedStep(currentStep, otaVm, onExit, onUsbRescue)
                is OtaStep.HotspotSetup -> Unit
            }
        }
    }
}

@Composable
private fun PickFileStep(otaVm: OtaViewModel) {
    val pickBin = rememberBinFilePicker { uri, size ->
        otaVm.onFilePicked(uri, size)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pick a `.bin` to flash to the Carduino. Engine off; stable power.")
        Button(onClick = pickBin) {
            Text("Pick .bin")
        }
    }
}

@Composable
private fun PreFlightStep(
    step: OtaStep.PreFlight,
    otaVm: OtaViewModel,
    onExit: () -> Unit,
) {
    var powerConfirmed by remember(step.uri) { mutableStateOf(false) }
    val sizeOk = step.sizeCheck is SizeCheck.Ok
    val pickBin = rememberBinFilePicker { uri, size ->
        otaVm.onFilePicked(uri, size)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("File size: %.1f KB".format(step.sizeBytes / 1024.0))
        when (val check = step.sizeCheck) {
            SizeCheck.Ok -> Text(
                "Size OK (${step.sizeBytes} / $CARDUINO_R4_MAX_SKETCH_BYTES bytes)",
                color = Color(0xFF16833A),
            )
            is SizeCheck.TooLarge -> Text(
                "Too large: ${check.bytes} bytes (max ${check.max}). Pick a smaller .bin.",
                color = MaterialTheme.colorScheme.error,
            )
            SizeCheck.Empty -> Text(
                "Empty file. Pick a different .bin.",
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = powerConfirmed,
                onCheckedChange = { powerConfirmed = it },
            )
            Text("Engine off and stable power confirmed")
        }

        Text(
            "If power drops mid-upload, wireless recovery may not work - use USB rescue.",
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.weight(1f))

        if (!sizeOk) {
            OutlinedButton(onClick = pickBin, modifier = Modifier.fillMaxWidth()) {
                Text("Pick another")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = {
                    otaVm.cancel()
                    onExit()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Cancel")
            }
            Button(
                enabled = sizeOk && powerConfirmed,
                onClick = otaVm::confirmPreFlight,
                modifier = Modifier.weight(1f),
            ) {
                Text("Continue")
            }
        }
    }
}

@Composable
private fun BusyStep(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            message,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun UploadingStep(
    step: OtaStep.Uploading,
    otaVm: OtaViewModel,
    onExit: () -> Unit,
) {
    val progress = if (step.total > 0L) {
        (step.sent.toFloat() / step.total).coerceIn(0f, 1f)
    } else {
        0f
    }
    val percent = step.sent * 100L / max(step.total, 1L)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("${step.sent} / ${step.total} bytes ($percent%)")
        Spacer(modifier = Modifier.weight(1f))
        TextButton(
            onClick = {
                otaVm.cancel()
                onExit()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun DoneStep(
    step: OtaStep.Done,
    otaVm: OtaViewModel,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Update complete",
            fontWeight = FontWeight.Bold,
            color = Color(0xFF16833A),
        )
        step.newVersion?.let { version ->
            Text("Now running version=$version")
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = {
                otaVm.reset()
                onExit()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Back to dashboard")
        }
    }
}

@Composable
private fun FailedStep(
    step: OtaStep.Failed,
    otaVm: OtaViewModel,
    onExit: () -> Unit,
    onUsbRescue: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Update failed",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
        Text(step.reason)
        Spacer(modifier = Modifier.weight(1f))

        if (step.canRetry) {
            Button(
                onClick = otaVm::retry,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Retry")
            }
        }
        OutlinedButton(
            onClick = {
                otaVm.cancel()
                onExit()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Back to dashboard")
        }
        if (step.showUsbRescue) {
            TextButton(
                onClick = onUsbRescue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("USB rescue")
            }
        }
    }
}
