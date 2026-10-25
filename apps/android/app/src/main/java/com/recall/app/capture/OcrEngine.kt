package com.recall.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

object OcrEngine {
    /** Decodes a downscaled bitmap (max 1600 px) without loading a full screenshot into memory. */
    fun loadBitmap(context: Context, uri: Uri, maxDimension: Int = 1600): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDimension || bounds.outHeight / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull() ?: return null

        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).toInt()),
            max(1, (bitmap.height * scale).toInt()),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    fun thumbnail(bitmap: Bitmap, maxDimension: Int = 256): ByteArray? = runCatching {
        val longest = max(bitmap.width, bitmap.height)
        val scale = min(1f, maxDimension.toFloat() / longest)
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).toInt()),
            max(1, (bitmap.height * scale).toInt()),
            true,
        )
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)
        if (scaled !== bitmap) scaled.recycle()
        output.toByteArray()
    }.getOrNull()

    suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                recognizer.close()
                if (continuation.isActive) continuation.resume(text.text)
            }
            .addOnFailureListener { error ->
                recognizer.close()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }
}
