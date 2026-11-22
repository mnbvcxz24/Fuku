package com.moldyhub.fuku

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

object CloneIconProcessor {
    private const val ICON_SIZE = 1024

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

        val output = File(context.cacheDir, "fuku_clone_custom_icon.webp")
        FileOutputStream(output).use {
            check(compressWebp(scaled, it)) {
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

    fun createDensityIconBytes(context: Context, customIconFile: File?, hueDegrees: Float): Map<String, ByteArray> {
        val source = previewBitmap(context, customIconFile?.absolutePath, hueDegrees)
        return try {
            listOf(
                "mdpi" to 48,
                "hdpi" to 72,
                "xhdpi" to 96,
                "xxhdpi" to 144,
                "xxxhdpi" to 192,
                "nodpi" to 192
            ).associate { (density, size) ->
                val scaled = Bitmap.createScaledBitmap(source, size, size, true)
                try {
                    val output = ByteArrayOutputStream()
                    check(compressWebp(scaled, output)) { "Could not encode the $density clone icon." }
                    density to output.toByteArray()
                } finally {
                    if (scaled !== source) scaled.recycle()
                }
            }
        } finally {
            source.recycle()
        }
    }

    fun createWebpBytes(context: Context, customIconFile: File?, hueDegrees: Float): ByteArray {
        val bitmap = previewBitmap(context, customIconFile?.absolutePath, hueDegrees)
        return try {
            ByteArrayOutputStream().use { output ->
                check(compressWebp(bitmap, output)) {
                    "Could not encode the clone icon."
                }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun compressWebp(bitmap: Bitmap, output: java.io.OutputStream): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output)
        } else {
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, 100, output)
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
