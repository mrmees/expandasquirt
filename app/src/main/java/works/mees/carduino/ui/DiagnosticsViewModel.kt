package works.mees.carduino.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import works.mees.carduino.ble.CarduinoBleClient

/**
 * Sends one-shot BLE shell commands to the Carduino and captures the next
 * ~3 seconds of non-dump-frame BLE notifications as the response. Periodic
 * sensor dumps are filtered out so the output isn't drowned by them.
 */
class DiagnosticsViewModel(
    private val ble: CarduinoBleClient,
) : ViewModel() {
    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private var activeJob: Job? = null

    fun run(cmd: String) {
        // Cancel any previous in-flight capture so a quick double-tap doesn't
        // interleave responses.
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _output.value = "> $cmd\n"
            ble.writeLine(cmd)
            val collector = launch {
                ble.lines.collect { line ->
                    if (isDumpFrame(line)) return@collect
                    _output.update { current -> current + line + "\n" }
                }
            }
            delay(3000)
            collector.cancel()
        }
    }

    private fun isDumpFrame(line: String): Boolean =
        DUMP_HEADER.matches(line) || DUMP_BODY.matches(line)

    companion object {
        private val DUMP_HEADER =
            Regex("""^\[seq=\d+\s+ready=[01]\s+health=0x[0-9A-Fa-f]+\]$""")
        private val DUMP_BODY =
            Regex("""^\s*(oilT|oilP|fuelP|preP|postT)\s*=\s*-?\d+\.?\d*\s+\S+\s+\S+\s*$""")
    }
}
