package works.mees.carduino.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import works.mees.carduino.ota.QrDecodeResult
import works.mees.carduino.ota.WifiQrResult
import works.mees.carduino.ota.decodeQrFromImage
import works.mees.carduino.ota.parseWifiQr
import works.mees.carduino.persistence.DeviceStore
import works.mees.carduino.persistence.HotspotCreds

class HotspotSetupViewModel(private val store: DeviceStore) : ViewModel() {
    private val _ssid = MutableStateFlow("")
    val ssid: StateFlow<String> = _ssid.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _status = MutableStateFlow("Enable your phone hotspot, fill in the credentials, then tap Continue.")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _hasSaved = MutableStateFlow(false)
    val hasSaved: StateFlow<Boolean> = _hasSaved.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = store.hotspot.first()
            _hasSaved.value = saved != null
            if (saved != null) {
                _ssid.value = saved.ssid
                _password.value = saved.password
            }
        }
    }

    fun setSsid(s: String) {
        _ssid.value = s
    }

    fun setPassword(p: String) {
        _password.value = p
    }

    fun loadSaved() {
        viewModelScope.launch {
            val saved = store.hotspot.first() ?: return@launch
            _ssid.value = saved.ssid
            _password.value = saved.password
        }
    }

    fun importQr(ctx: Context, uri: Uri) {
        viewModelScope.launch {
            when (val r = decodeQrFromImage(ctx, uri)) {
                is QrDecodeResult.Err -> _status.value = "QR import failed: ${r.reason}"
                is QrDecodeResult.Ok -> {
                    when (val p = parseWifiQr(r.payload)) {
                        is WifiQrResult.Err -> _status.value = "QR import failed: ${p.reason}"
                        is WifiQrResult.Ok -> {
                            _ssid.value = p.creds.ssid
                            _password.value = p.creds.password ?: ""
                            _status.value = "QR imported. Enable your phone hotspot if it's not on, then tap Continue."
                        }
                    }
                }
            }
        }
    }

    suspend fun persist(): HotspotCreds {
        val creds = HotspotCreds(_ssid.value, _password.value)
        store.saveHotspot(creds)
        _hasSaved.value = true
        return creds
    }
}
