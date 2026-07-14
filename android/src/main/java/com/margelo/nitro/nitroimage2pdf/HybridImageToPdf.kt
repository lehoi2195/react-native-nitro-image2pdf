package com.margelo.nitro.nitroimage2pdf

import android.content.Context
import android.util.Base64
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import com.margelo.nitro.nitroimage2pdf.CreatePdfOptions
import com.margelo.nitro.nitroimage2pdf.CreatePdfResult
import com.margelo.nitro.nitroimage2pdf.HybridImageToPdfSpec
import com.margelo.nitro.nitroimage2pdf.OutputConfig
import com.margelo.nitro.nitroimage2pdf.OutputDirectory
import com.margelo.nitro.nitroimage2pdf.OutputFormat
import com.margelo.nitro.nitroimage2pdf.PdfPageInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.UUID

// Bridges HybridImageToPdfSpec to ImageLoader/PdfBuilder; handles output writing.
class HybridImageToPdf : HybridImageToPdfSpec() {
  private val context: Context
    get() = NitroModules.applicationContext
      ?: throw PdfException.WriteFailed("No application Context available from NitroModules")

  companion object {
    // IO dispatcher avoids starving Promise.async's default pool.
    private val ioScope = CoroutineScope(Dispatchers.IO)
  }

  override fun createPdf(options: CreatePdfOptions): Promise<CreatePdfResult> = Promise.async(ioScope) {
    if (options.images.isEmpty()) {
      throw PdfException.InvalidImage("images must be a non-empty array")
    }

    val appContext = context

    // Load every source image, downsampling per processing.maxWidth/maxHeight.
    val maxWidth = options.processing?.maxWidth?.toInt()
    val maxHeight = options.processing?.maxHeight?.toInt()
    val loadedImages = options.images.map { input ->
      ImageLoader.load(appContext, input, maxWidth, maxHeight)
    }

    // Build the PDF (page sizing, fit, margin, metadata, password).
    val (pdfBytes, pageSizes) = PdfBuilder.build(
      context = appContext,
      images = loadedImages,
      page = options.page,
      metadata = options.metadata,
      quality = options.processing?.quality,
      userPassword = options.security?.password,
      ownerPassword = options.security?.ownerPassword
    )

    // Write output per output.format/directory/absolutePath.
    val outputFormat = options.output?.format ?: OutputFormat.FILE
    val writeFile = outputFormat == OutputFormat.FILE || outputFormat == OutputFormat.BOTH
    val writeBase64 = outputFormat == OutputFormat.BASE64 || outputFormat == OutputFormat.BOTH

    var filePath: String? = null
    var fileSize: Double? = null

    if (writeFile) {
      val path = resolveOutputPath(appContext, options.output)
      try {
        File(path).writeBytes(pdfBytes)
      } catch (e: Exception) {
        throw PdfException.WriteFailed("Failed to write PDF to $path: ${e.message}")
      }
      filePath = path
      fileSize = pdfBytes.size.toDouble()
    }

    val base64: String? = if (writeBase64) Base64.encodeToString(pdfBytes, Base64.NO_WRAP) else null

    val pages = pageSizes.map { PdfPageInfo(width = it.width.toDouble(), height = it.height.toDouble()) }
    CreatePdfResult(
      filePath = filePath,
      base64 = base64,
      numberOfPages = loadedImages.size.toDouble(),
      fileSize = fileSize,
      pages = pages.toTypedArray()
    )
  }

  // Resolves absolutePath, or directory + fileName (UUID default).
  private fun resolveOutputPath(context: Context, output: OutputConfig?): String {
    val absolutePath = output?.absolutePath
    if (!absolutePath.isNullOrEmpty()) {
      val parent = File(absolutePath).parentFile
      if (parent == null || !parent.exists() || !parent.isDirectory) {
        throw PdfException.WriteFailed("Parent directory does not exist: ${parent?.path}")
      }
      if (!parent.canWrite()) {
        throw PdfException.PermissionDenied("Parent directory is not writable: ${parent.path}")
      }
      return absolutePath
    }

    val directory = output?.directory ?: OutputDirectory.CACHE
    val directoryFile = when (directory) {
      OutputDirectory.CACHE -> context.cacheDir
      OutputDirectory.DOCUMENTS -> context.filesDir
      OutputDirectory.TEMP -> context.cacheDir // same as cache on Android, unlike iOS
    } ?: throw PdfException.WriteFailed("Could not resolve output directory for $directory")

    if (!directoryFile.exists() && !directoryFile.mkdirs()) {
      throw PdfException.WriteFailed("Failed to create output directory ${directoryFile.path}")
    }

    val fileName = output?.fileName?.takeIf { it.isNotEmpty() } ?: "${UUID.randomUUID()}.pdf"

    // Overwrite-on-collision: `File.writeBytes` truncates + overwrites existing files.
    return File(directoryFile, fileName).path
  }
}
