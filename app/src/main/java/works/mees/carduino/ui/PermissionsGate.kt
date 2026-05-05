package works.mees.carduino.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Runtime-permission gate for the BLE + WiFi APIs the app needs. Manifest
 * declarations alone aren't enough on modern Android (BLUETOOTH_SCAN/CONNECT
 * + NEARBY_WIFI_DEVICES + ACCESS_FINE_LOCATION all need runtime grant).
 *
 * If all required permissions are granted, [content] renders. Otherwise the
 * user sees a simple "Grant permissions" prompt and clicks through the
 * standard Android grant dialogs.
 *
 * Per IMPLEMENTATION-PLAN.md Task 63 step 4.
 */
@Composable
fun PermissionsGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current

    val requiredPerms = remember {
        buildList {
            // BLE scan/connect runtime perms — Android 12 (S, API 31) onward.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            // NEARBY_WIFI_DEVICES needed for some Wi-Fi APIs on Android 13+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            // ACCESS_FINE_LOCATION required for BLE scan on Android 11 and
            // below. Harmless to request on newer too.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()
    }

    var granted by remember {
        mutableStateOf(
            requiredPerms.all {
                ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.all { it }
    }

    if (granted) {
        content()
    } else {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Carduino needs permissions",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Bluetooth (to talk to the device), nearby Wi-Fi devices (for the wireless OTA flow), and location (BLE scanning). No data leaves the phone.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { launcher.launch(requiredPerms) }) {
                    Text("Grant permissions")
                }
            }
        }
    }
}
