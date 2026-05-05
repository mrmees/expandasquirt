package works.mees.carduino.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import works.mees.carduino.ble.BleState
import works.mees.carduino.ble.CarduinoBleClient
import works.mees.carduino.ble.DumpFrame
import works.mees.carduino.ble.DumpParser
import works.mees.carduino.persistence.DeviceStore

/** View-model for the live Layout B dashboard. */
data class DashboardState(
    val deviceName: String = "—",
    val connected: Boolean = false,
    val frame: DumpFrame? = null,
    val firmwareVersion: String? = null,
)

class DashboardViewModel(
    private val ble: CarduinoBleClient,
    private val store: DeviceStore,
) : ViewModel() {
    private val parser = DumpParser()
    private val _state = MutableStateFlow(DashboardState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            var previousMac: String? = null
            store.currentMac.collect { mac ->
                if (mac == null) {
                    if (previousMac != null) {
                        ble.disconnect()
                    }
                    previousMac = null
                    return@collect
                }

                previousMac = mac
                val bleState = ble.state.value
                val alreadyTarget =
                    (bleState is BleState.Connecting && bleState.mac == mac) ||
                        (bleState is BleState.Connected && bleState.mac == mac)
                if (!alreadyTarget) {
                    ble.connect(mac)
                }
            }
        }
        viewModelScope.launch {
            store.known.combine(store.currentMac) { known, currentMac ->
                known.firstOrNull { it.mac == currentMac }?.nickname
            }.collect { nickname ->
                _state.update { it.copy(deviceName = nickname ?: "\u2014") }
            }
        }
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

    fun forgetCurrent() {
        viewModelScope.launch {
            store.currentMac.first()?.let { store.forget(it) }
        }
    }
}
