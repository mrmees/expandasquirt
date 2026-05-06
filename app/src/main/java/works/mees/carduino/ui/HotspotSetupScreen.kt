package works.mees.carduino.ui

import android.content.Intent
import android.net.Network
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import works.mees.carduino.persistence.HotspotCreds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotspotSetupScreen(
    vm: HotspotSetupViewModel,
    onCancel: () -> Unit,
    onProceed: (HotspotCreds, Network) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ssid by vm.ssid.collectAsState()
    val password by vm.password.collectAsState()
    val status by vm.status.collectAsState()
    val hotspotNetwork by vm.hotspotNetwork.collectAsState()
    val hasSaved by vm.hasSaved.collectAsState()
    var passwordVisible by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importQr(ctx, uri)
    }

    LaunchedEffect(Unit) {
        vm.startCapturing(ctx)
    }

    DisposableEffect(Unit) {
        onDispose { vm.stopCapturing() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hotspot Setup") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Text("<") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append("Must be WPA2-Personal. ")
                        }
                        append("WPA3 / Transition will not work - the Carduino's modem can't join those.")
                    },
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = ssid,
                onValueChange = vm::setSsid,
                label = { Text("Hotspot name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = password,
                onValueChange = vm::setPassword,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(if (passwordVisible) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        ctx.startActivity(Intent("android.settings.TETHER_SETTINGS"))
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Open Hotspot Settings")
                }
                FilledTonalButton(
                    onClick = { imagePicker.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Import from QR screenshot")
                }
            }

            if (hasSaved) {
                FilledTonalButton(onClick = vm::loadSaved) {
                    Text("Use saved")
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    enabled = ssid.isNotBlank() && password.isNotBlank() && hotspotNetwork != null,
                    onClick = {
                        val network = hotspotNetwork ?: return@Button
                        scope.launch {
                            val creds = vm.persist()
                            onProceed(creds, network)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Continue")
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
