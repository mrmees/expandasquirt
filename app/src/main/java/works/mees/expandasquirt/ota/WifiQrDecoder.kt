package works.mees.expandasquirt.ota

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

sealed class QrDecodeResult {
    data class Ok(val payload: String) : QrDecodeResult()
    data class Err(val reason: String) : QrDecodeResult()
}

fun decodeQrFromImage(ctx: Context, uri: Uri): QrDecodeResult = runCatching {
    val inputStream = ctx.contentResolver.openInputStream(uri)
        ?: return@runCatching QrDecodeResult.Err("cannot open image")
    val bmp = inputStream.use { input ->
        BitmapFactory.decodeStream(input)
    } ?: return@runCatching QrDecodeResult.Err("not an image")

    val pixels = IntArray(bmp.width * bmp.height)
    bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    val source = RGBLuminanceSource(bmp.width, bmp.height, pixels)
    val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

    runCatching {
        MultiFormatReader().decode(binaryBitmap)
    }.fold(
        onSuccess = { QrDecodeResult.Ok(it.text) },
        onFailure = { t ->
            if (t is NotFoundException) {
                QrDecodeResult.Err("no QR code found in image")
            } else {
                throw t
            }
        },
    )
}.getOrElse { t ->
    QrDecodeResult.Err("qr decode failed: ${t::class.simpleName}: ${t.message}")
}
