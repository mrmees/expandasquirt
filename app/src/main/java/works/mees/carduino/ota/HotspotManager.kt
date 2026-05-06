package works.mees.carduino.ota

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class HotspotSession(val network: Network)

suspend fun awaitHotspotNetwork(ctx: Context, timeoutMillis: Long): Network? {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val deferred = CompletableDeferred<Network?>()
    val requestBuilder = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)

    if (Build.VERSION.SDK_INT >= 33) {
        requestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
    } else {
        // TODO: verify which capability filter matches the phone's own AP on Android 12; fall back to enumerating networks via cm.allNetworks if needed.
    }

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            deferred.complete(network)
        }

        override fun onUnavailable() {
            deferred.complete(null)
        }
    }

    return try {
        cm.registerNetworkCallback(requestBuilder.build(), callback)
        withTimeoutOrNull(timeoutMillis) { deferred.await() }
    } finally {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }
}
