package com.margelo.nitro.nitroimage2pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.margelo.nitro.nitroimage2pdf.ImageSourceType
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

// isJpeg picks JPEGFactory (reuse compression) vs LosslessFactory in PdfBuilder.
data class LoadedImage(val bitmap: Bitmap, val isJpeg: Boolean)

// Loads a Bitmap from path/content/base64/uri, baking EXIF and downsampling.
object ImageLoader {
  private const val DOWNLOAD_TIMEOUT_MS = 30_000

  private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

  // Loads and normalizes the image, honoring maxWidth/maxHeight.
  fun load(context: Context, input: com.margelo.nitro.nitroimage2pdf.ImageInput, maxWidth: Int?, maxHeight: Int?): LoadedImage {
    val (bytes, exif) = when (input.type) {
      ImageSourceType.PATH -> loadPathBytes(context, input.value)
      ImageSourceType.BASE64 -> loadBase64Bytes(input.value)
      ImageSourceType.URI -> loadRemoteBytes(input.value)
    }

    // Decode near the target resolution to avoid a full-size allocation.
    val decoded = decodeBitmap(bytes, exif, maxWidth, maxHeight)
    val oriented = applyExif(decoded, exif)
    // Final precise pass; cheap since input is already downsampled.
    val resized = downsample(oriented, maxWidth, maxHeight)
    return LoadedImage(bitmap = resized, isJpeg = isJpeg(bytes))
  }

  private fun isJpeg(bytes: ByteArray): Boolean {
    if (bytes.size < JPEG_MAGIC.size) return false
    for (i in JPEG_MAGIC.indices) {
      if (bytes[i] != JPEG_MAGIC[i]) return false
    }
    return true
  }

  // region source loading

  private fun loadPathBytes(context: Context, value: String): Pair<ByteArray, ExifInterface?> {
    return try {
      if (value.startsWith("content://")) {
        val uri = Uri.parse(value)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
          ?: throw PdfException.InvalidImage("Could not open content URI: $value")
        val exif = runCatching {
          context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        }.getOrNull()
        bytes to exif
      } else {
        val path = value.removePrefix("file://")
        val file = File(path)
        if (!file.exists() || !file.isFile) {
          throw PdfException.InvalidImage("File does not exist: $path")
        }
        val bytes = file.readBytes()
        val exif = runCatching { ExifInterface(path) }.getOrNull()
        bytes to exif
      }
    } catch (e: PdfException) {
      throw e
    } catch (e: Exception) {
      throw PdfException.InvalidImage("Failed to read path $value: ${e.message}")
    }
  }

  private fun loadBase64Bytes(value: String): Pair<ByteArray, ExifInterface?> {
    val bytes = try {
      Base64.decode(value, Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
      throw PdfException.InvalidImage("Invalid base64 payload: ${e.message}")
    }
    if (bytes.isEmpty()) {
      throw PdfException.InvalidImage("Empty base64 payload")
    }
    val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
    return bytes to exif
  }

  private fun loadRemoteBytes(value: String): Pair<ByteArray, ExifInterface?> {
    val url = try {
      URL(value)
    } catch (e: Exception) {
      throw PdfException.InvalidImage("Invalid URI: $value")
    }

    var connection: HttpURLConnection? = null
    try {
      connection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = DOWNLOAD_TIMEOUT_MS
        readTimeout = DOWNLOAD_TIMEOUT_MS
        requestMethod = "GET"
        doInput = true
      }
      connection.connect()

      val responseCode = connection.responseCode
      if (responseCode !in 200..299) {
        throw PdfException.DownloadFailed("HTTP $responseCode for $value")
      }

      val bytes = connection.inputStream.use { it.readBytes() }
      val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
      return bytes to exif
    } catch (e: SocketTimeoutException) {
      throw PdfException.DownloadTimeout("Timed out downloading $value")
    } catch (e: PdfException) {
      throw e
    } catch (e: Exception) {
      throw PdfException.DownloadFailed("Failed to download $value: ${e.message}")
    } finally {
      connection?.disconnect()
    }
  }

  // endregion

  // region decode

  // Decodes near the target resolution via inSampleSize, avoiding full-size allocation.
  private fun decodeBitmap(bytes: ByteArray, exif: ExifInterface?, maxWidth: Int?, maxHeight: Int?): Bitmap {
    val widthLimit = maxWidth?.takeIf { it > 0 }
    val heightLimit = maxHeight?.takeIf { it > 0 }

    val sampleSize = if (widthLimit == null && heightLimit == null) {
      1
    } else {
      val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
      if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
        throw PdfException.InvalidImage("Could not decode image bounds")
      }

      // 90/270° EXIF rotation swaps effective width/height vs decoded bounds.
      val swapsDimensions = exifSwapsDimensions(exif)
      val effectiveWidth = if (swapsDimensions) boundsOptions.outHeight else boundsOptions.outWidth
      val effectiveHeight = if (swapsDimensions) boundsOptions.outWidth else boundsOptions.outHeight

      calculateInSampleSize(
        width = effectiveWidth,
        height = effectiveHeight,
        reqWidth = widthLimit,
        reqHeight = heightLimit
      )
    }

    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return try {
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        ?: throw PdfException.InvalidImage("Could not decode image data")
    } catch (e: OutOfMemoryError) {
      // Surface as a distinguishable error, don't silently swallow.
      throw PdfException.OutOfMemory("Out of memory decoding image (sampleSize=$sampleSize): ${e.message}")
    }
  }

  // True if EXIF orientation rotates 90 or 270 degrees.
  private fun exifSwapsDimensions(exif: ExifInterface?): Boolean {
    return when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
      ExifInterface.ORIENTATION_ROTATE_90,
      ExifInterface.ORIENTATION_ROTATE_270,
      ExifInterface.ORIENTATION_TRANSPOSE,
      ExifInterface.ORIENTATION_TRANSVERSE -> true
      else -> false
    }
  }

  // Largest power-of-2 sample size satisfying only the provided (non-null) bound(s).
  private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int?, reqHeight: Int?): Int {
    if (reqWidth == null && reqHeight == null) return 1

    var inSampleSize = 1
    val exceedsWidth = reqWidth != null && width > reqWidth
    val exceedsHeight = reqHeight != null && height > reqHeight
    if (exceedsWidth || exceedsHeight) {
      val halfHeight = height / 2
      val halfWidth = width / 2
      while (
        (reqHeight == null || (halfHeight / inSampleSize) >= reqHeight) &&
        (reqWidth == null || (halfWidth / inSampleSize) >= reqWidth)
      ) {
        inSampleSize *= 2
      }
    }
    return inSampleSize
  }

  // endregion

  // region transforms

  // Bakes EXIF orientation in; PdfBox has no notion of EXIF.
  private fun applyExif(bitmap: Bitmap, exif: ExifInterface?): Bitmap {
    val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
      ?: ExifInterface.ORIENTATION_NORMAL

    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      else -> return bitmap // ORIENTATION_NORMAL / ORIENTATION_UNDEFINED: nothing to do.
    }

    return try {
      val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (rotated !== bitmap) bitmap.recycle()
      rotated
    } catch (e: OutOfMemoryError) {
      // Don't fall back un-rotated — that renders sideways silently.
      throw PdfException.OutOfMemory("Out of memory applying EXIF rotation: ${e.message}")
    }
  }

  // Aspect-preserving downsample if bitmap exceeds maxWidth/maxHeight.
  private fun downsample(bitmap: Bitmap, maxWidth: Int?, maxHeight: Int?): Bitmap {
    val widthLimit = maxWidth?.takeIf { it > 0 }
    val heightLimit = maxHeight?.takeIf { it > 0 }
    if (widthLimit == null && heightLimit == null) return bitmap

    val effectiveWidthLimit = widthLimit ?: Int.MAX_VALUE
    val effectiveHeightLimit = heightLimit ?: Int.MAX_VALUE
    if (bitmap.width <= effectiveWidthLimit && bitmap.height <= effectiveHeightLimit) return bitmap

    val scale = minOf(
      effectiveWidthLimit.toDouble() / bitmap.width,
      effectiveHeightLimit.toDouble() / bitmap.height
    )
    val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)

    return try {
      val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
      if (scaled !== bitmap) bitmap.recycle()
      scaled
    } catch (e: OutOfMemoryError) {
      // Don't fall back unscaled — defeats the point of downsampling.
      throw PdfException.OutOfMemory("Out of memory downsampling image to ${newWidth}x$newHeight: ${e.message}")
    }
  }

  // endregion
}
