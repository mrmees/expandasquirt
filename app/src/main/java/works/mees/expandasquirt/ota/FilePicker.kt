package works.mees.expandasquirt.ota

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * SAF file picker for OTA `.bin` payloads. Returns a launcher action; the
 * caller invokes it to open Android's document picker, and [onPicked] is
 * fired with the chosen URI and its byte size once a file is selected
 * (or never, if the user cancels).
 *
 * The mime filter is intentionally permissive (`* / *`) — `.bin` is not a
 * standard mime type and DocumentsUI providers classify it inconsistently
 * (`application/octet-stream` on local FS, `application/x-binary` on some
 * cloud providers, often plain `application/unknown`). Size validation
 * via [validateSketchSize] rejects empty/oversize files downstream.
 *
 * Persistable read permission is taken on the URI so a retry path (e.g.
 * after a failed push) can re-read the same file without re-prompting.
 */
@Composable
fun rememberBinFilePicker(onPicked: (Uri, Long) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val size = runCatching {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        }.getOrDefault(0L)
        onPicked(uri, size)
    }
    return { launcher.launch(arrayOf("*/*")) }
}
