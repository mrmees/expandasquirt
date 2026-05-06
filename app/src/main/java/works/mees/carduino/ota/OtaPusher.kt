package works.mees.carduino.ota

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

sealed class OtaResult {
    data object Success : OtaResult()
    data class HttpError(val code: Int, val body: String) : OtaResult()
    data class NetworkError(val cause: Throwable) : OtaResult()
}

suspend fun pushOta(
    ctx: Context,
    endpoint: CarduinoEndpoint,
    sketchUri: Uri,
    otaPassword: String,
    onProgress: (sent: Long, total: Long) -> Unit,
): OtaResult = withContext(Dispatchers.IO) {
    try {
        val totalBytes = ctx.contentResolver.openFileDescriptor(sketchUri, "r")?.use {
            it.statSize
        }

        if (totalBytes == null || totalBytes <= 0L) {
            return@withContext OtaResult.NetworkError(
                IllegalStateException("can't determine .bin size")
            )
        }

        val client = OkHttpClient.Builder()
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("http://${endpoint.host.hostAddress}:${endpoint.port}/sketch")
            .header("Authorization", Credentials.basic("arduino", otaPassword))
            .post(SketchRequestBody(ctx, sketchUri, totalBytes, onProgress))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                OtaResult.Success
            } else {
                OtaResult.HttpError(
                    code = response.code,
                    body = response.body?.string()?.take(500) ?: "",
                )
            }
        }
    } catch (e: IOException) {
        OtaResult.NetworkError(e)
    } catch (e: Exception) {
        OtaResult.NetworkError(e)
    }
}

private class SketchRequestBody(
    private val ctx: Context,
    private val sketchUri: Uri,
    private val totalBytes: Long,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()

    override fun contentLength() = totalBytes

    override fun writeTo(sink: BufferedSink) {
        ctx.contentResolver.openInputStream(sketchUri).use { input ->
            if (input == null) {
                throw IOException("can't open .bin input stream")
            }

            val buffer = ByteArray(4096)
            var sent = 0L

            while (true) {
                val read = input.read(buffer)
                if (read == -1) break

                sink.write(buffer, 0, read)
                sent += read
                onProgress(sent, totalBytes)
            }
        }
    }
}
