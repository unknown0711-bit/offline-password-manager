package com.example.offlinevault.login

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

public sealed interface QrPayloadResult {
    public data class Success(public val payload: TotpUriPayload) : QrPayloadResult
    public data class Invalid(public val message: String) : QrPayloadResult
}

/**
 * Bundled ZXing-core QR adapter. Camera frames are passed in as a Bitmap and
 * are never written to disk. The caller owns and must recycle the camera frame.
 */
public class ZxingQrPayloadParser {
    public fun parsePayload(text: String): QrPayloadResult = try {
        QrPayloadResult.Success(TotpUriParser.parse(text))
    } catch (error: IllegalArgumentException) {
        QrPayloadResult.Invalid(error.message ?: "Invalid TOTP payload")
    }

    public fun decode(bitmap: Bitmap): QrPayloadResult {
        require(!bitmap.isRecycled) { "The QR frame is already recycled" }
        require(bitmap.width > 0 && bitmap.height > 0) { "The QR frame is empty" }
        val pixels = IntArray(bitmap.width * bitmap.height)
        return try {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))
            parsePayload(result.text)
        } catch (error: Exception) {
            QrPayloadResult.Invalid(error.message ?: "Unable to decode QR payload")
        } finally {
            pixels.fill(0)
        }
    }
}
