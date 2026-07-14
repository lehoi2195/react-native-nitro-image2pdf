package com.margelo.nitro.nitroimage2pdf

// toString/no-stack-trace keeps the [CODE] prefix clean when bridged to JS.
sealed class PdfException(val code: String, detail: String) :
  Exception("[$code] $detail", /* cause = */ null, /* enableSuppression = */ true, /* writableStackTrace = */ false) {
  override fun toString(): String = message ?: super.toString()

  class InvalidImage(detail: String) : PdfException("INVALID_IMAGE", detail)
  class DownloadFailed(detail: String) : PdfException("DOWNLOAD_FAILED", detail)
  class DownloadTimeout(detail: String) : PdfException("DOWNLOAD_TIMEOUT", detail)
  class WriteFailed(detail: String) : PdfException("WRITE_FAILED", detail)
  class PermissionDenied(detail: String) : PdfException("PERMISSION_DENIED", detail)
  class EncryptionFailed(detail: String) : PdfException("ENCRYPTION_FAILED", detail)

  // Raised when ImageLoader's decode/rotate/scale steps throw OutOfMemoryError.
  class OutOfMemory(detail: String) : PdfException("OUT_OF_MEMORY", detail)
}
