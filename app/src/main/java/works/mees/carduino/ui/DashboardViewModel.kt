package works.mees.carduino.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import works.mees.carduino.ble.BleState
import works.mees.carduino.ble.CarduinoBleClient
import works.mees.carduino.ble.DumpFrame
import works.mees.carduino.ble.DumpParser

/** View-model for the live Layout B dashboard. */
data class DashboardState(
    val deviceName: String = "—",
    val connected: Boolean = false,
    val frame: DumpFrame? = null,
    val firmwareVersion: String? = null,
)

class DashboardViewModel(
    private val ble: CarduinoBleClient,
) : ViewModel() {
    private val parser = DumpParser()
    private val _state = MutableStateFlow(DashboardState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            ble.state.collect { s ->
                _state.update { it.copy(connected = s is BleState.Connected) }
            }
        }
        viewModelScope.launch {
            ble.lines.collect { line ->
                if (line.startsWith("CARDUINO-v4 version=")) {
                    val ver = Regex("""version=(\S+)""").find(line)?.groupValues?.get(1)
                    _state.update { it.copy(firmwareVersion = ver) }
                }

                val frame = parser.feed(line)
                if (frame != null) {
                    _state.update { it.copy(frame = frame) }
                }
            }
        }
    }
}
