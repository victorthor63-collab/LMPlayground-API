package com.druk.lmplayground.settings

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.druk.lmplayground.apiserver.ApiServerController
import com.druk.lmplayground.apiserver.ApiServerService
import com.druk.lmplayground.apiserver.LoadedModelHolder
import com.druk.lmplayground.apiserver.WifiInterface
import com.druk.lmplayground.storage.StoragePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ApiServerViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = StoragePreferences(app)

    val status: StateFlow<ApiServerController.Status> = ApiServerController.status
    val loadedModel: StateFlow<LoadedModelHolder.LoadedModel?> = LoadedModelHolder.current

    private val _port = MutableStateFlow(prefs.apiServerPort)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _apiKey = MutableStateFlow(prefs.apiServerKey)
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    // Current WiFi IPv4, shown on the setup screen so the host is visible even
    // before the server is enabled. Kept fresh as the WiFi connection changes.
    private val _wifiIp = MutableStateFlow(WifiInterface.currentWifiIpv4(app)?.hostAddress)
    val wifiIp: StateFlow<String?> = _wifiIp.asStateFlow()

    private val wifiRegistration: WifiInterface.Registration =
        WifiInterface.watchWifi(app) {
            _wifiIp.value = WifiInterface.currentWifiIpv4(app)?.hostAddress
        }

    fun setEnabled(enabled: Boolean) {
        ApiServerController.setEnabled(app, enabled)
    }

    fun setPort(port: Int) {
        if (port !in 1024..65535) return
        _port.value = port
        prefs.apiServerPort = port
        // Rebind on the new port if the server is currently enabled.
        if (prefs.apiServerEnabled) {
            ContextCompat.startForegroundService(app, ApiServerService.startIntent(app))
        }
    }

    fun regenerateKey() {
        _apiKey.value = prefs.regenerateApiServerKey()
    }

    override fun onCleared() {
        wifiRegistration.unregister()
        super.onCleared()
    }
}
