package works.mees.carduino.ota

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

data class CarduinoEndpoint(
    val host: InetAddress,
    val port: Int,
    val serviceName: String,
)

suspend fun discoverCarduino(
    ctx: Context,
    expectedName: String = "carduino-v4",
    timeoutMillis: Long = 15_000,
): CarduinoEndpoint? {
    val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    val deferred = CompletableDeferred<CarduinoEndpoint>()
    var resolvedAlready = false

    val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            deferred.completeExceptionally(IllegalStateException("mDNS resolve failed: $errorCode"))
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            deferred.complete(
                CarduinoEndpoint(
                    host = serviceInfo.host,
                    port = serviceInfo.port,
                    serviceName = serviceInfo.serviceName,
                )
            )
        }
    }

    val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (resolvedAlready || serviceInfo.serviceName != expectedName) return
            resolvedAlready = true

            runCatching {
                // TODO: switch to registerServiceInfoCallback when minSdk reaches 34
                @Suppress("DEPRECATION")
                nsd.resolveService(serviceInfo, resolveListener)
            }.onFailure { deferred.completeExceptionally(it) }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            deferred.completeExceptionally(IllegalStateException("mDNS discovery failed: $errorCode"))
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    return try {
        withTimeoutOrNull(timeoutMillis) {
            @Suppress("DEPRECATION")
            nsd.discoverServices(
                CARDUINO_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
            deferred.await()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    } finally {
        runCatching { nsd.stopServiceDiscovery(discoveryListener) }
    }
}

private const val CARDUINO_SERVICE_TYPE = "_arduino._tcp."
