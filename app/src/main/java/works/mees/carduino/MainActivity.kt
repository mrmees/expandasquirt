package works.mees.carduino

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import works.mees.carduino.ui.PermissionsGate

/**
 * App entry. Wraps content in a runtime-permission gate per V4X-DESIGN.md
 * §4.3 / IMPLEMENTATION-PLAN.md Task 63 step 4 — BLE scan/connect and
 * NearbyWifiDevices need user-granted runtime permissions on Android 12+.
 *
 * Phase N tasks 64-69 fill in the real screens (DevicePicker, Dashboard,
 * Diagnostics). For now this is just a placeholder so the project builds
 * and installs cleanly.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PermissionsGate {
                        Placeholder()
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Placeholder() {
    Text(
        text = "Carduino — scaffolding online.\n\nPhase N screens land in upcoming tasks (BLE central, dashboard, device picker, diagnostics, OTA wizard).",
        modifier = Modifier.padding(16.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
}
