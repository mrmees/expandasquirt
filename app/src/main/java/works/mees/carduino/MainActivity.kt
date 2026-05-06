package works.mees.carduino

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import works.mees.carduino.ble.CarduinoBleClient
import works.mees.carduino.persistence.DeviceStore
import works.mees.carduino.ui.DashboardScreen
import works.mees.carduino.ui.DashboardViewModel
import works.mees.carduino.ui.DevicePickerScreen
import works.mees.carduino.ui.DiagnosticsScreen
import works.mees.carduino.ui.DiagnosticsViewModel
import works.mees.carduino.ui.HotspotSetupViewModel
import works.mees.carduino.ui.OtaViewModel
import works.mees.carduino.ui.OtaWizardScreen
import works.mees.carduino.ui.PermissionsGate

/**
 * App entry. Wraps content in a runtime-permission gate before hosting the
 * picker/dashboard navigation graph.
 */
class MainActivity : ComponentActivity() {
    private val store by lazy { DeviceStore(applicationContext) }
    private val ble by lazy { CarduinoBleClient(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PermissionsGate {
                        val nav = rememberNavController()
                        val currentMac by store.currentMac.collectAsStateWithLifecycle(
                            initialValue = null,
                        )
                        val backStackEntry by nav.currentBackStackEntryAsState()
                        val currentRoute = backStackEntry?.destination?.route
                        var handledStartupRedirect by remember { mutableStateOf(false) }

                        LaunchedEffect(currentMac, currentRoute) {
                            if (
                                !handledStartupRedirect &&
                                currentMac != null &&
                                currentRoute == "picker"
                            ) {
                                handledStartupRedirect = true
                                nav.navigate("dashboard") {
                                    popUpTo("picker") { inclusive = true }
                                }
                            }
                        }

                        val lifecycleOwner = LocalLifecycleOwner.current
                        DisposableEffect(lifecycleOwner) {
                            val observer = LifecycleEventObserver { _, event ->
                                when (event) {
                                    Lifecycle.Event.ON_PAUSE -> ble.pauseReconnect()
                                    Lifecycle.Event.ON_RESUME -> ble.resumeReconnect()
                                    else -> {}
                                }
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose {
                                lifecycleOwner.lifecycle.removeObserver(observer)
                            }
                        }

                        NavHost(navController = nav, startDestination = "picker") {
                            composable("picker") {
                                DevicePickerScreen(
                                    store = store,
                                    onSelect = {
                                        nav.navigate("dashboard") {
                                            popUpTo("picker") { inclusive = true }
                                        }
                                    },
                                )
                            }
                            composable("dashboard") {
                                val dashboardViewModel: DashboardViewModel = viewModel(
                                    factory = object : ViewModelProvider.Factory {
                                        @Suppress("UNCHECKED_CAST")
                                        override fun <T : ViewModel> create(
                                            modelClass: Class<T>,
                                        ): T {
                                            if (
                                                modelClass.isAssignableFrom(
                                                    DashboardViewModel::class.java,
                                                )
                                            ) {
                                                return DashboardViewModel(ble, store) as T
                                            }
                                            throw IllegalArgumentException(
                                                "Unknown ViewModel class: ${modelClass.name}",
                                            )
                                        }
                                    },
                                )
                                DashboardScreen(
                                    vm = dashboardViewModel,
                                    onMenuFirmwareUpdate = { nav.navigate("ota") },
                                    onMenuDiagnostics = { nav.navigate("diag") },
                                    onForget = {
                                        dashboardViewModel.forgetCurrent()
                                        nav.navigate("picker") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    },
                                )
                            }
                            composable("diag") {
                                val diagVm: DiagnosticsViewModel = viewModel(
                                    factory = object : ViewModelProvider.Factory {
                                        @Suppress("UNCHECKED_CAST")
                                        override fun <T : ViewModel> create(
                                            modelClass: Class<T>,
                                        ): T {
                                            if (
                                                modelClass.isAssignableFrom(
                                                    DiagnosticsViewModel::class.java,
                                                )
                                            ) {
                                                return DiagnosticsViewModel(ble) as T
                                            }
                                            throw IllegalArgumentException(
                                                "Unknown ViewModel class: ${modelClass.name}",
                                            )
                                        }
                                    },
                                )
                                DiagnosticsScreen(
                                    vm = diagVm,
                                    onBack = { nav.popBackStack() },
                                )
                            }
                            composable("ota") {
                                val otaVm: OtaViewModel = viewModel(
                                    factory = object : ViewModelProvider.Factory {
                                        @Suppress("UNCHECKED_CAST")
                                        override fun <T : ViewModel> create(
                                            modelClass: Class<T>,
                                        ): T {
                                            if (
                                                modelClass.isAssignableFrom(
                                                    OtaViewModel::class.java,
                                                )
                                            ) {
                                                return OtaViewModel(ble, store) as T
                                            }
                                            throw IllegalArgumentException(
                                                "Unknown ViewModel class: ${modelClass.name}",
                                            )
                                        }
                                    },
                                )
                                val hotspotVm: HotspotSetupViewModel = viewModel(
                                    factory = object : ViewModelProvider.Factory {
                                        @Suppress("UNCHECKED_CAST")
                                        override fun <T : ViewModel> create(
                                            modelClass: Class<T>,
                                        ): T {
                                            if (
                                                modelClass.isAssignableFrom(
                                                    HotspotSetupViewModel::class.java,
                                                )
                                            ) {
                                                return HotspotSetupViewModel(store) as T
                                            }
                                            throw IllegalArgumentException(
                                                "Unknown ViewModel class: ${modelClass.name}",
                                            )
                                        }
                                    },
                                )
                                OtaWizardScreen(
                                    otaVm = otaVm,
                                    hotspotVm = hotspotVm,
                                    onExit = { nav.popBackStack() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
