package works.mees.carduino.ui

import android.content.Context
import android.net.Network
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import works.mees.carduino.ble.BleState
import works.mees.carduino.ble.CarduinoBleClient
import works.mees.carduino.ota.OtaResult
import works.mees.carduino.ota.SizeCheck
import works.mees.carduino.ota.discoverCarduino
import works.mees.carduino.ota.pushOta
import works.mees.carduino.ota.validateSketchSize
import works.mees.carduino.persistence.DeviceStore
import works.mees.carduino.persistence.HotspotCreds

sealed class OtaStep {
    data object PickFile : OtaStep()
    data class PreFlight(val uri: Uri, val sizeBytes: Long, val sizeCheck: SizeCheck) : OtaStep()
    data class HotspotSetup(val uri: Uri, val sizeBytes: Long) : OtaStep()
    data class EnteringMaintenance(val message: String) : OtaStep()
    data class FindingDevice(val message: String) : OtaStep()
    data class Uploading(val sent: Long, val total: Long) : OtaStep()
    data object Applying : OtaStep()
    data object Verifying : OtaStep()
    data class Done(val newVersion: String?) : OtaStep()
    data class Failed(val reason: String, val canRetry: Boolean, val showUsbRescue: Boolean) : OtaStep()
}

class OtaViewModel(
    private val ble: CarduinoBleClient,
    @Suppress("unused") private val store: DeviceStore,
) : ViewModel() {
    private val _step = MutableStateFlow<OtaStep>(OtaStep.PickFile)
    val step: StateFlow<OtaStep> = _step.asStateFlow()

    private var workJob: Job? = null

    fun onFilePicked(uri: Uri, sizeBytes: Long) {
        val check = validateSketchSize(sizeBytes)
        _step.value = OtaStep.PreFlight(uri, sizeBytes, check)
    }

    fun confirmPreFlight() {
        val s = _step.value as? OtaStep.PreFlight ?: return
        if (s.sizeCheck !is SizeCheck.Ok) return
        _step.value = OtaStep.HotspotSetup(s.uri, s.sizeBytes)
    }

    fun onHotspotConfirmed(ctx: Context, creds: HotspotCreds, network: Network) {
        val s = _step.value as? OtaStep.HotspotSetup ?: return
        startTransfer(ctx, s.uri, creds, network)
    }

    fun cancel() {
        workJob?.cancel()
        workJob = null
        runCatching { ble.resumeReconnect() }
        _step.value = OtaStep.PickFile
    }

    fun retry() {
        val failed = _step.value as? OtaStep.Failed ?: return
        if (!failed.canRetry) return
        _step.value = OtaStep.PickFile
    }

    fun reset() {
        _step.value = OtaStep.PickFile
    }

    override fun onCleared() {
        workJob?.cancel()
        runCatching { ble.resumeReconnect() }
        super.onCleared()
    }

    private fun startTransfer(ctx: Context, uri: Uri, creds: HotspotCreds, network: Network) {
        workJob?.cancel()
        workJob = viewModelScope.launch {
            try {
                runOtaFlow(ctx, uri, creds, network)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                runCatching { ble.resumeReconnect() }
                _step.value = OtaStep.Failed(
                    "internal error: ${t.message}",
                    canRetry = true,
                    showUsbRescue = false,
                )
            }
        }
    }

    private suspend fun runOtaFlow(
        ctx: Context,
        uri: Uri,
        creds: HotspotCreds,
        network: Network,
    ) {
        val otaPassword = generateOtaPassword()

        _step.value = OtaStep.EnteringMaintenance("Pausing BLE autoreconnect")
        ble.pauseReconnect()

        val cmd = "maintenance " +
            "ssid=${percentEncode(creds.ssid)} " +
            "psk=${percentEncode(creds.password)} " +
            "pwd=${percentEncode(otaPassword)}"

        _step.value = OtaStep.EnteringMaintenance("Sending maintenance command")
        val replyWait = awaitBleLine(5_000) { line ->
            line.startsWith("OK maintenance armed") || line.startsWith("ERR maintenance")
        }

        val writeOk = ble.writeLine(cmd)
        if (!writeOk) {
            replyWait.cancel()
            failWithReconnect("BLE write failed (link dropped?)", canRetry = true, showUsbRescue = false)
            return
        }

        val reply = replyWait.await()
        if (reply == null) {
            failWithReconnect(
                "Carduino didn't respond to maintenance command within 5 sec",
                canRetry = true,
                showUsbRescue = false,
            )
            return
        }
        if (reply.startsWith("ERR maintenance")) {
            failWithReconnect("Firmware rejected maintenance: $reply", canRetry = true, showUsbRescue = false)
            return
        }

        _step.value = OtaStep.EnteringMaintenance("Waiting for Carduino to disconnect BLE")
        val dropOk = withTimeoutOrNull(8_000) {
            ble.state.first { it !is BleState.Connected }
            true
        }
        if (dropOk == null) {
            failWithReconnect("Carduino didn't drop BLE in time", canRetry = true, showUsbRescue = false)
            return
        }

        _step.value = OtaStep.EnteringMaintenance("Waiting for Carduino to join hotspot")
        delay(5_000)

        _step.value = OtaStep.FindingDevice("Looking for carduino-v4 on hotspot")
        val endpoint = discoverCarduino(ctx, network, "carduino-v4", 15_000)
        if (endpoint == null) {
            failWithReconnect("Device didn't appear on hotspot within 15 sec", canRetry = true, showUsbRescue = true)
            return
        }

        _step.value = OtaStep.Uploading(sent = 0, total = 0)
        when (
            val result = pushOta(
                ctx = ctx,
                endpoint = endpoint,
                sketchUri = uri,
                otaPassword = otaPassword,
                network = network,
                onProgress = { sent, total -> _step.value = OtaStep.Uploading(sent, total) },
            )
        ) {
            is OtaResult.HttpError -> {
                failWithReconnect("HTTP ${result.code}: ${result.body}", canRetry = true, showUsbRescue = true)
                return
            }
            is OtaResult.NetworkError -> {
                failWithReconnect(
                    "Network error: ${result.cause.message}",
                    canRetry = true,
                    showUsbRescue = true,
                )
                return
            }
            OtaResult.Success -> Unit
        }

        _step.value = OtaStep.Applying
        val versionWait = awaitBleLine(40_000) { line ->
            Regex("""version=(\S+)""").containsMatchIn(line)
        }
        ble.resumeReconnect()
        val reconnected = withTimeoutOrNull(30_000) {
            ble.state.first { it is BleState.Connected }
            true
        }
        if (reconnected == null) {
            versionWait.cancel()
            _step.value = OtaStep.Failed(
                "Carduino didn't come back on BLE within 30 sec",
                canRetry = false,
                showUsbRescue = true,
            )
            return
        }

        _step.value = OtaStep.Verifying
        val versionLine = withTimeoutOrNull(5_000) { versionWait.await() }
        versionWait.cancel()
        val version = versionLine?.let { line ->
            Regex("""version=(\S+)""").find(line)?.groupValues?.get(1)
        }

        _step.value = OtaStep.Done(newVersion = version)
    }

    private fun failWithReconnect(reason: String, canRetry: Boolean, showUsbRescue: Boolean) {
        ble.resumeReconnect()
        _step.value = OtaStep.Failed(reason, canRetry, showUsbRescue)
    }

    private fun awaitBleLine(timeoutMs: Long, predicate: (String) -> Boolean): CompletableDeferred<String?> {
        val deferred = CompletableDeferred<String?>()
        val ownerJob = workJob
        val job = viewModelScope.launch {
            ble.lines.collect { line ->
                if (predicate(line) && !deferred.isCompleted) {
                    deferred.complete(line)
                }
            }
        }

        val timeoutJob = viewModelScope.launch {
            try {
                deferred.complete(withTimeoutOrNull(timeoutMs) { deferred.await() })
            } finally {
                job.cancel()
            }
        }

        deferred.invokeOnCompletion {
            job.cancel()
            timeoutJob.cancel()
        }
        ownerJob?.invokeOnCompletion {
            if (!deferred.isCompleted) {
                deferred.cancel()
            }
        }

        return deferred
    }
}

private val UNRESERVED =
    ('A'..'Z').toSet() + ('a'..'z').toSet() + ('0'..'9').toSet() + setOf('-', '.', '_', '~')

internal fun percentEncode(s: String): String {
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val ch = b.toInt() and 0xFF
        if (ch.toChar() in UNRESERVED) {
            sb.append(ch.toChar())
        } else {
            sb.append('%').append("%02X".format(ch))
        }
    }
    return sb.toString()
}

private val OTA_PW_CHARS = (('A'..'Z') + ('a'..'z') + ('0'..'9')).toCharArray()

internal fun generateOtaPassword(): String {
    val rng = SecureRandom()
    return CharArray(16) { OTA_PW_CHARS[rng.nextInt(OTA_PW_CHARS.size)] }.concatToString()
}
