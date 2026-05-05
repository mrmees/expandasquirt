/*
 * MainActivity.kt — LOH + OkHttp routing and R4 OTA bench prototype for Tasks 53/54.
 *
 * Verifies that on Samsung S25+ / Android 16 (One UI 8) we can:
 *   1. Start a LocalOnlyHotspot and read its auto-generated SSID + passphrase
 *   2. Capture the underlying android.net.Network via ConnectivityManager
 *   3. Bind an OkHttp client to that Network so the HTTP request actually
 *      goes through the LOH AP (rather than cellular/default network)
 *   4. Successfully POST to a test echo server running on a laptop joined
 *      to the LOH hotspot
 *
 * A second path pushes a selected .bin directly to the R4 ArduinoOTA listener
 * on the phone hotspot/default network, without LOH, for Task 53 verification.
 *
 * If both paths pass, the production V4X-DESIGN.md OTA approach is unblocked.
 *
 * Setup: paste this into a fresh Android Studio "Empty Activity (Compose)"
 * project at min SDK 26, target SDK 35. Add the manifest permissions from
 * AndroidManifest-snippet.xml and the gradle deps from
 * build.gradle.kts-deps.txt. See README.md for the full bench procedure.
 *
 * NOT production code — see V4X-DESIGN.md and IMPLEMENTATION-PLAN.md
 * Phase O for the production OTA wizard that integrates this.
 */

package works.mees.carduino.protoloh

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit

// EDIT THIS after starting the laptop's echo server and reading its DHCP IP:
private const val LAPTOP_IP = "192.168.49.2"
private const val LAPTOP_PORT = 8080

// EDIT THIS after reading the R4's DHCP IP from USB serial on boot:
private const val R4_IP = "192.168.43.7"
private const val R4_PORT = 65280
private const val R4_OTA_PASSWORD = "testpw"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.CHANGE_WIFI_STATE,
            ),
            1,
        )

        setContent {
            MaterialTheme {
                ProtoScreen(applicationContext)
            }
        }
    }
}

@Composable
fun ProtoScreen(appCtx: Context) {
    var status by remember { mutableStateOf("idle - choose a test") }
    val scope = rememberCoroutineScope()
    val binPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            status = (status + "\nR4 push canceled - no file selected").takeLast(4000)
            return@rememberLauncherForActivityResult
        }

        status = "R4 push selected: $uri"
        scope.launch {
            pushBinToR4(appCtx, uri) { line ->
                status = (status + "\n" + line).takeLast(4000)
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
            Text("LOH + OkHttp prototype", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Target: http://$LAPTOP_IP:$LAPTOP_PORT/echo",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Text(
                "R4 OTA target: http://$R4_IP:$R4_PORT/sketch",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(16.dp))

            Button(onClick = {
                status = "[1/4] Starting LocalOnlyHotspot..."
                scope.launch {
                    runTest(appCtx) { line ->
                        status = (status + "\n" + line).takeLast(2000)
                    }
                }
            }) {
                Text("Run LOH + echo test")
            }

            Spacer(Modifier.height(8.dp))

            Button(onClick = {
                status = "Opening file picker for R4 .bin..."
                binPicker.launch(arrayOf("*/*"))
            }) {
                Text("Push .bin to R4")
            }

            Spacer(Modifier.height(16.dp))

            Surface(modifier = Modifier.fillMaxSize(), tonalElevation = 1.dp) {
                Text(
                    status,
                    modifier = Modifier
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * Reads the selected firmware binary and POSTs it to the R4 ArduinoOTA
 * listener on the phone hotspot/default network. LOH is not involved.
 */
private suspend fun pushBinToR4(ctx: Context, uri: Uri, log: (String) -> Unit) =
    withContext(Dispatchers.IO) {
        val bytes = try {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            log("ERROR reading file: ${e.javaClass.simpleName} - ${e.message}")
            return@withContext
        }

        if (bytes == null) {
            log("ERROR reading file: ContentResolver.openInputStream returned null")
            return@withContext
        }
        if (bytes.isEmpty()) {
            log("ERROR reading file: selected file is empty")
            return@withContext
        }

        log("File selected: ${bytes.size} bytes")
        log("Starting POST to http://$R4_IP:$R4_PORT/sketch")

        val client = OkHttpClient.Builder()
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val body = ProgressByteArrayRequestBody(
            bytes = bytes,
            contentType = "application/octet-stream".toMediaType(),
        ) { sent, total ->
            log("Progress: $sent / $total bytes")
        }
        val request = Request.Builder()
            .url("http://$R4_IP:$R4_PORT/sketch")
            .header("Authorization", Credentials.basic("arduino", R4_OTA_PASSWORD))
            .post(body)
            .build()

        val result = try {
            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                "HTTP ${resp.code}: $respBody"
            }
        } catch (e: Exception) {
            "ERROR: ${e.javaClass.simpleName} - ${e.message}"
        }
        log(result)
    }

/**
 * RequestBody with a fixed Content-Length and simple chunk progress callbacks.
 */
private class ProgressByteArrayRequestBody(
    private val bytes: ByteArray,
    private val contentType: okhttp3.MediaType,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {
    override fun contentType() = contentType

    override fun contentLength() = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        var offset = 0
        val total = bytes.size
        onProgress(0, total.toLong())
        while (offset < total) {
            val byteCount = minOf(8192, total - offset)
            sink.write(bytes, offset, byteCount)
            offset += byteCount
            onProgress(offset.toLong(), total.toLong())
        }
    }
}

/**
 * Runs all four phases of the LOH + OkHttp routing test. Reports each milestone
 * via the [log] callback. Returns when the test is done (success or failure).
 */
@SuppressLint("MissingPermission")
private suspend fun runTest(ctx: Context, log: (String) -> Unit) = withContext(Dispatchers.IO) {
    val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // ─── Phase 1: register NetworkCallback BEFORE starting LOH ───
    log("[1a/4] Registering NetworkCallback (WIFI, !INTERNET)…")
    val networkDeferred = CompletableDeferred<Network>()
    val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            log("       NetworkCallback.onAvailable → $network")
            if (!networkDeferred.isCompleted) networkDeferred.complete(network)
        }
    }
    val req = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    cm.registerNetworkCallback(req, networkCallback)

    // ─── Phase 1b: start LOH ───
    log("[1b/4] Calling startLocalOnlyHotspot()…")
    val reservationDeferred = CompletableDeferred<WifiManager.LocalOnlyHotspotReservation?>()
    val lohCb = object : WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
            log("       LOH onStarted")
            reservationDeferred.complete(r)
        }
        override fun onStopped() {
            log("       LOH onStopped")
        }
        override fun onFailed(reason: Int) {
            log("       LOH onFailed(reason=$reason)")
            reservationDeferred.complete(null)
        }
    }
    wifi.startLocalOnlyHotspot(lohCb, null)

    val reservation = withTimeoutOrNull(15_000) { reservationDeferred.await() }
    if (reservation == null) {
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
        log("FAIL — LOH did not start within 15s")
        return@withContext
    }

    // ─── Phase 2: read SSID + passphrase ───
    log("[2/4] Reading SoftAp config…")
    val cfg = reservation.softApConfiguration
    val ssid = cfg.ssid ?: ""
    val psk = cfg.passphrase ?: ""
    log("       SSID = \"$ssid\"")
    log("       PSK  = \"$psk\"  ← connect laptop to this hotspot now")

    // ─── Phase 3: wait for the LOH Network to surface ───
    log("[3/4] Waiting for LOH Network via NetworkCallback (max 10s)…")
    val network = withTimeoutOrNull(10_000) { networkDeferred.await() }
    if (network == null) {
        runCatching { reservation.close() }
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
        log("FAIL — NetworkCallback did not fire within 10s")
        log("       This means OkHttp can't be scoped to LOH; design needs revision.")
        return@withContext
    }
    log("       Got Network: $network")
    val caps = cm.getNetworkCapabilities(network)
    log("       caps: $caps")

    // Give the laptop a moment to actually join the LOH and the echo-server to be reachable.
    log("[3b/4] Waiting 15s for laptop to join LOH and echo-server to be reachable…")
    log("       (connect laptop to \"$ssid\" with passphrase \"$psk\" now)")
    kotlinx.coroutines.delay(15_000)

    // ─── Phase 4: POST through OkHttp bound to the LOH Network ───
    log("[4/4] POSTing to http://$LAPTOP_IP:$LAPTOP_PORT/echo via LOH socketFactory…")
    val client = OkHttpClient.Builder()
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .socketFactory(network.socketFactory)
        .build()

    val payload = "PROTO_TEST_BYTES_FROM_S25+_${System.currentTimeMillis()}"
    val body = payload.toByteArray().toRequestBody("application/octet-stream".toMediaType())
    val request = Request.Builder()
        .url("http://$LAPTOP_IP:$LAPTOP_PORT/echo")
        .post(body)
        .build()

    val result = try {
        client.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string() ?: ""
            "HTTP ${resp.code}: $respBody"
        }
    } catch (e: Exception) {
        "ERROR: ${e.javaClass.simpleName} — ${e.message}"
    }
    log("       → $result")

    // ─── Cleanup ───
    log("Cleaning up: closing LOH and unregistering callback…")
    runCatching { reservation.close() }
    runCatching { cm.unregisterNetworkCallback(networkCallback) }
    log("Done.")
}
