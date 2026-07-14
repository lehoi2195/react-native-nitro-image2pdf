package com.margelo.nitro.nitroimage2pdf

import android.content.Context
import com.margelo.nitro.nitroimage2pdf.FitMode
import com.margelo.nitro.nitroimage2pdf.PageSizePreset
import com.margelo.nitro.nitroimage2pdf.PageConfig
import com.margelo.nitro.nitroimage2pdf.PdfMetadata
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream

// A single page's rendered size, in PDF points.
data class PdfPageSize(val width: Float, val height: Float)

// Bottom-left-origin box an image is drawn into.
private data class ContentBox(val x: Float, val y: Float, val width: Float, val height: Float)

// Assembles a multi-page PDF from LoadedImages via PdfBox-Android.
object PdfBuilder {
  // Standard page sizes in points (72 dpi), matching iOS.
  private const val A4_WIDTH = 595.28f
  private const val A4_HEIGHT = 841.89f
  private const val A5_WIDTH = 419.53f
  private const val A5_HEIGHT = 595.28f
  private const val LETTER_WIDTH = 612f
  private const val LETTER_HEIGHT = 792f
  private const val LEGAL_WIDTH = 612f
  private const val LEGAL_HEIGHT = 1008f

  // AES-128 is the max PdfBox-Android supports for writing.
  private const val ENCRYPTION_KEY_LENGTH = 128

  private const val DEFAULT_JPEG_QUALITY = 1.0f

  @Volatile
  private var initialized = false
  private val initLock = Any()

  // Must run once before any PDDocument work (idempotent).
  private fun ensureInit(context: Context) {
    if (initialized) return
    synchronized(initLock) {
      if (initialized) return
      PDFBoxResourceLoader.init(context.applicationContext)
      initialized = true
    }
  }

  // Resolves the page size in points for a given preset.
  private fun pageSize(
    preset: PageSizePreset,
    customWidth: Double?,
    customHeight: Double?,
    imageWidth: Int,
    imageHeight: Int
  ): PdfPageSize {
    return when (preset) {
      PageSizePreset.FIT -> PdfPageSize(imageWidth.toFloat(), imageHeight.toFloat())
      PageSizePreset.A4 -> PdfPageSize(A4_WIDTH, A4_HEIGHT)
      PageSizePreset.A5 -> PdfPageSize(A5_WIDTH, A5_HEIGHT)
      PageSizePreset.LETTER -> PdfPageSize(LETTER_WIDTH, LETTER_HEIGHT)
      PageSizePreset.LEGAL -> PdfPageSize(LEGAL_WIDTH, LEGAL_HEIGHT)
      PageSizePreset.CUSTOM -> PdfPageSize(
        (customWidth ?: imageWidth.toDouble()).toFloat(),
        (customHeight ?: imageHeight.toDouble()).toFloat()
      )
    }
  }

  // Content box honoring margin + fit; caller clips for cover.
  private fun contentBox(pageSize: PdfPageSize, imageWidth: Float, imageHeight: Float, fitMode: FitMode, margin: Float): ContentBox {
    val boxX = margin
    val boxY = margin
    val boxWidth = (pageSize.width - 2 * margin).coerceAtLeast(0f)
    val boxHeight = (pageSize.height - 2 * margin).coerceAtLeast(0f)

    if (imageWidth <= 0f || imageHeight <= 0f) {
      return ContentBox(boxX, boxY, boxWidth, boxHeight)
    }

    return when (fitMode) {
      FitMode.STRETCH -> ContentBox(boxX, boxY, boxWidth, boxHeight)
      FitMode.CONTAIN -> {
        val scale = minOf(boxWidth / imageWidth, boxHeight / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        ContentBox(boxX + (boxWidth - width) / 2, boxY + (boxHeight - height) / 2, width, height)
      }
      FitMode.COVER -> {
        val scale = maxOf(boxWidth / imageWidth, boxHeight / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        ContentBox(boxX + (boxWidth - width) / 2, boxY + (boxHeight - height) / 2, width, height)
      }
    }
  }

  // AccessPermission() defaults grant everything; deny print/modify/copy explicitly.
  private fun restrictedAccessPermission(): AccessPermission {
    return AccessPermission().apply {
      setCanPrint(false)
      setCanPrintDegraded(false)
      setCanPrintFaithful(false)
      setCanModify(false)
      setCanModifyAnnotations(false)
      setCanFillInForm(false)
      setCanAssembleDocument(false)
      setCanExtractContent(false)
    }
  }

  // Parses #RRGGBB into (r, g, b) floats 0..1, defaulting to white.
  private fun backgroundColor(hex: String?): Triple<Float, Float, Float> {
    val value = hex?.removePrefix("#")
    if (value == null || value.length != 6) return Triple(1f, 1f, 1f)
    val parsed = value.toIntOrNull(16) ?: return Triple(1f, 1f, 1f)
    val r = ((parsed shr 16) and 0xFF) / 255f
    val g = ((parsed shr 8) and 0xFF) / 255f
    val b = (parsed and 0xFF) / 255f
    return Triple(r, g, b)
  }

  // Builds the PDF bytes plus each page's size in points.
  fun build(
    context: Context,
    images: List<LoadedImage>,
    page: PageConfig?,
    metadata: PdfMetadata?,
    quality: Double?,
    userPassword: String?,
    ownerPassword: String?
  ): Pair<ByteArray, List<PdfPageSize>> {
    if (images.isEmpty()) {
      throw PdfException.InvalidImage("No images were provided to render")
    }

    ensureInit(context)

    val preset = page?.size ?: PageSizePreset.FIT
    val fitMode = page?.fitMode ?: FitMode.CONTAIN
    val margin = (page?.margin ?: 0.0).toFloat()
    val customWidth = page?.customWidth
    val customHeight = page?.customHeight
    val background = backgroundColor(page?.backgroundColor)
    val jpegQuality = (quality ?: 1.0).toFloat().coerceIn(0f, 1f)

    val document = PDDocument()
    try {
      val pageSizes = mutableListOf<PdfPageSize>()

      for (image in images) {
        val bitmap = image.bitmap
        val size = pageSize(preset, customWidth, customHeight, bitmap.width, bitmap.height)
        pageSizes.add(size)

        val pdfPage = PDPage(PDRectangle(size.width, size.height))
        document.addPage(pdfPage)

        val pdImage: PDImageXObject = try {
          if (image.isJpeg) {
            JPEGFactory.createFromImage(document, bitmap, jpegQuality)
          } else {
            LosslessFactory.createFromImage(document, bitmap)
          }
        } catch (e: Exception) {
          throw PdfException.InvalidImage("Failed to encode image for PDF: ${e.message}")
        }

        try {
          PDPageContentStream(document, pdfPage).use { contentStream ->
            // Background must be filled before the image, always.
            contentStream.setNonStrokingColor(background.first, background.second, background.third)
            contentStream.addRect(0f, 0f, size.width, size.height)
            contentStream.fill()

            val box = contentBox(size, bitmap.width.toFloat(), bitmap.height.toFloat(), fitMode, margin)

            when (fitMode) {
              FitMode.COVER -> {
                // cover overflows by design — clip to avoid margin bleed.
                contentStream.saveGraphicsState()
                val clipWidth = (size.width - 2 * margin).coerceAtLeast(0f)
                val clipHeight = (size.height - 2 * margin).coerceAtLeast(0f)
                contentStream.addRect(margin, margin, clipWidth, clipHeight)
                contentStream.clip()
                contentStream.drawImage(pdImage, box.x, box.y, box.width, box.height)
                contentStream.restoreGraphicsState()
              }
              FitMode.CONTAIN, FitMode.STRETCH -> {
                contentStream.drawImage(pdImage, box.x, box.y, box.width, box.height)
              }
            }
          }
        } catch (e: PdfException) {
          throw e
        } catch (e: Exception) {
          throw PdfException.WriteFailed("Failed to render page ${pageSizes.size}: ${e.message}")
        }
      }

      val info = PDDocumentInformation()
      metadata?.title?.let { info.title = it }
      metadata?.author?.let { info.author = it }
      metadata?.subject?.let { info.subject = it }
      metadata?.keywords?.let { info.keywords = it }
      metadata?.creator?.let { info.creator = it }
      document.documentInformation = info

      if (!userPassword.isNullOrEmpty() || !ownerPassword.isNullOrEmpty()) {
        try {
          // Restrict permissions only when there's no user/open password.
          val ownerPasswordOnly = !ownerPassword.isNullOrEmpty() && userPassword.isNullOrEmpty()
          val permissions = if (ownerPasswordOnly) restrictedAccessPermission() else AccessPermission()
          val policy = StandardProtectionPolicy(ownerPassword ?: userPassword ?: "", userPassword ?: "", permissions)
          policy.encryptionKeyLength = ENCRYPTION_KEY_LENGTH
          policy.isPreferAES = true
          document.protect(policy)
        } catch (e: Exception) {
          throw PdfException.EncryptionFailed("Failed to encrypt PDF: ${e.message}")
        }
      }

      val output = ByteArrayOutputStream()
      try {
        document.save(output)
      } catch (e: Exception) {
        throw PdfException.WriteFailed("Failed to serialize PDF: ${e.message}")
      }

      return output.toByteArray() to pageSizes
    } finally {
      document.close()
    }
  }
}
