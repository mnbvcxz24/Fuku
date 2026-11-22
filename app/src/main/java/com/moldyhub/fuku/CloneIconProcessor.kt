package com.moldyhub.fuku

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.floor
import kotlin.math.min

object CloneIconProcessor {
    private const val ICON_SIZE = 887

    fun importIcon(context: Context, uri: Uri): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(uri)
            ?: error("Could not open the selected image.")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected file is not a supported image." }

        var sampleSize = 1
        while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > 2048) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("Could not decode the selected image.")

        val square = centerCrop(decoded)
        if (square !== decoded) decoded.recycle()
        val scaled = Bitmap.createScaledBitmap(square, ICON_SIZE, ICON_SIZE, true)
        if (scaled !== square) square.recycle()

        val output = File(context.cacheDir, "fuku_clone_custom_icon.png")
        FileOutputStream(output).use {
            check(scaled.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                "Could not save the selected icon image."
            }
            it.fd.sync()
        }
        scaled.recycle()
        return output
    }

    fun previewBitmap(context: Context, customIconPath: String?, hueDegrees: Float): Bitmap {
        val source = if (customIconPath.isNullOrBlank()) {
            BitmapFactory.decodeResource(context.resources, R.drawable.fuku_clone_default_icon)
        } else {
            BitmapFactory.decodeFile(File(customIconPath).absolutePath)
        } ?: error("Could not load the selected clone icon.")

        val square = centerCrop(source)
        if (square !== source) source.recycle()
        val scaled = Bitmap.createScaledBitmap(square, ICON_SIZE, ICON_SIZE, true)
        if (scaled !== square) square.recycle()
        val adjusted = applyHue(scaled, hueDegrees.coerceIn(0f, 360f))
        scaled.recycle()
        return adjusted
    }

    fun createPngBytes(context: Context, customIconFile: File?, hueDegrees: Float): ByteArray {
        val bitmap = previewBitmap(context, customIconFile?.absolutePath, hueDegrees)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not encode the clone icon."
                }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun centerCrop(source: Bitmap): Bitmap {
        val side = min(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        return Bitmap.createBitmap(source, left, top, side, side)
    }

    private fun applyHue(source: Bitmap, degrees: Float): Bitmap {
        // Rotate hue in HSV space so saturation, brightness, and alpha remain intact.
        // This avoids the channel mixing and color shifts of an RGB color-matrix rotation.
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val hsv = FloatArray(3)
        for (index in pixels.indices) {
            val color = pixels[index]
            if ((color ushr 24) == 0) continue
            android.graphics.Color.colorToHSV(color, hsv)
            hsv[0] = (hsv[0] + degrees) % 360f
            pixels[index] = android.graphics.Color.HSVToColor(android.graphics.Color.alpha(color), hsv)
        }
        return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
    }
}
