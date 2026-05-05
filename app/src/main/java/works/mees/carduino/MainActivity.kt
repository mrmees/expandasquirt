package works.mees.carduino

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import works.mees.carduino.ble.CarduinoBleClient
import works.mees.carduino.ui.DashboardScreen
import works.mees.carduino.ui.DashboardViewModel
import works.mees.carduino.ui.PermissionsGate

/**
 * App entry. Wraps content in a runtime-permission gate before hosting the
 * dashboard screen; device selection and auto-connect land in Task 67.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PermissionsGate {
                        val dashboardViewModel: DashboardViewModel = viewModel(
                            factory = object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                    if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
                                        return DashboardViewModel(
                                            CarduinoBleClient(applicationContext),
                                        ) as T
                                    }
                                    throw IllegalArgumentException(
                                        "Unknown ViewModel class: ${modelClass.name}",
                                    )
                                }
                            },
                        )
                        DashboardScreen(
                            vm = dashboardViewModel,
                            onMenuFirmwareUpdate = {},
                            onMenuDiagnostics = {},
                        )
                    }
                }
            }
        }
    }
}
