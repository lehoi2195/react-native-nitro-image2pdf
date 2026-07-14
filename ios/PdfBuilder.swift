// Assembles a multi-page PDF from UIImages via UIGraphicsPDFRenderer.

import UIKit

enum PdfBuildError: Error {
  case emptyImages
}

// CustomStringConvertible keeps the [CODE] prefix consistent with PdfError.
extension PdfBuildError: CustomStringConvertible {
  var description: String {
    switch self {
    case .emptyImages:
      return "[INVALID_IMAGE] No images were provided to render"
    }
  }
}

extension PdfBuildError: LocalizedError {
  var errorDescription: String? { description }
}

enum PdfBuilder {
  // Standard page sizes in points (72 dpi).
  private static let a4Size = CGSize(width: 595.28, height: 841.89)
  private static let a5Size = CGSize(width: 419.53, height: 595.28)
  private static let letterSize = CGSize(width: 612, height: 792)
  private static let legalSize = CGSize(width: 612, height: 1008)

  // Resolves the page size in points for a given preset.
  static func pageSize(
    for preset: PageSizePreset,
    customWidth: Double?,
    customHeight: Double?,
    image: UIImage
  ) -> CGSize {
    switch preset {
    case .fit:
      return image.size
    case .a4:
      return a4Size
    case .a5:
      return a5Size
    case .letter:
      return letterSize
    case .legal:
      return legalSize
    case .custom:
      return CGSize(
        width: customWidth ?? image.size.width,
        height: customHeight ?? image.size.height
      )
    }
  }

  // Content rect honoring margin + fit; caller clips for cover.
  static func contentRect(pageSize: CGSize, imageSize: CGSize, fitMode: FitMode, margin: CGFloat) -> CGRect {
    let box = CGRect(
      x: margin,
      y: margin,
      width: max(pageSize.width - 2 * margin, 0),
      height: max(pageSize.height - 2 * margin, 0)
    )

    guard imageSize.width > 0, imageSize.height > 0 else { return box }

    switch fitMode {
    case .stretch:
      return box
    case .contain:
      let scale = min(box.width / imageSize.width, box.height / imageSize.height)
      let width = imageSize.width * scale
      let height = imageSize.height * scale
      return CGRect(x: box.midX - width / 2, y: box.midY - height / 2, width: width, height: height)
    case .cover:
      let scale = max(box.width / imageSize.width, box.height / imageSize.height)
      let width = imageSize.width * scale
      let height = imageSize.height * scale
      return CGRect(x: box.midX - width / 2, y: box.midY - height / 2, width: width, height: height)
    }
  }

  // Parses a #RRGGBB hex string, defaulting to white.
  static func backgroundColor(fromHex hex: String?) -> UIColor {
    guard var hexString = hex else { return .white }
    if hexString.hasPrefix("#") { hexString.removeFirst() }
    guard hexString.count == 6, let value = UInt32(hexString, radix: 16) else { return .white }

    let red = CGFloat((value >> 16) & 0xFF) / 255
    let green = CGFloat((value >> 8) & 0xFF) / 255
    let blue = CGFloat(value & 0xFF) / 255
    return UIColor(red: red, green: green, blue: blue, alpha: 1)
  }

  // jpegDataProviderSource preserves JPEG compression when embedded in the PDF.
  private static func applyQuality(_ image: UIImage, quality: Double) -> UIImage {
    let flattened: UIImage
    if image.imageOrientation == .up {
      flattened = image
    } else {
      let renderer = UIGraphicsImageRenderer(size: image.size)
      flattened = renderer.image { _ in image.draw(at: .zero) }
    }
    guard let jpegData = flattened.jpegData(compressionQuality: CGFloat(quality)),
          let dataProvider = CGDataProvider(data: jpegData as CFData),
          let cgImage = CGImage(
            jpegDataProviderSource: dataProvider,
            decode: nil,
            shouldInterpolate: true,
            intent: .defaultIntent
          )
    else {
      return image // fall back to the original if re-encoding somehow fails
    }
    return UIImage(cgImage: cgImage, scale: 1, orientation: .up)
  }

  // Builds the PDF Data plus each page's size in points.
  static func build(
    images: [UIImage],
    page: PageConfig?,
    processing: ProcessingConfig?,
    metadata: PdfMetadata?,
    userPassword: String?,
    ownerPassword: String?
  ) throws -> (data: Data, pageSizes: [CGSize]) {
    guard !images.isEmpty else { throw PdfBuildError.emptyImages }

    // quality == 1 (default) skips compression entirely.
    let quality = processing?.quality.map { min(max($0, 0), 1) }
    let images = quality.map { q -> [UIImage] in
      guard q < 1 else { return images }
      return images.map { applyQuality($0, quality: q) }
    } ?? images

    let preset = page?.size ?? .fit
    let fitMode = page?.fitMode ?? .contain
    let margin = CGFloat(page?.margin ?? 0)
    let customWidth = page?.customWidth
    let customHeight = page?.customHeight
    let background = backgroundColor(fromHex: page?.backgroundColor)

    var documentInfo: [String: Any] = [:]
    if let title = metadata?.title { documentInfo[kCGPDFContextTitle as String] = title }
    if let author = metadata?.author { documentInfo[kCGPDFContextAuthor as String] = author }
    if let subject = metadata?.subject { documentInfo[kCGPDFContextSubject as String] = subject }
    if let keywords = metadata?.keywords { documentInfo[kCGPDFContextKeywords as String] = keywords }
    if let creator = metadata?.creator { documentInfo[kCGPDFContextCreator as String] = creator }
    if let userPassword { documentInfo[kCGPDFContextUserPassword as String] = userPassword }
    if let ownerPassword { documentInfo[kCGPDFContextOwnerPassword as String] = ownerPassword }
    if userPassword != nil || ownerPassword != nil {
      // RC4 128-bit is Core Graphics' max encryption strength.
      documentInfo[kCGPDFContextEncryptionKeyLength as String] = 128
    }

    let format = UIGraphicsPDFRendererFormat()
    format.documentInfo = documentInfo

    let firstPageSize = pageSize(for: preset, customWidth: customWidth, customHeight: customHeight, image: images[0])
    let renderer = UIGraphicsPDFRenderer(bounds: CGRect(origin: .zero, size: firstPageSize), format: format)

    var pageSizes: [CGSize] = []
    let data = renderer.pdfData { context in
      for image in images {
        let size = pageSize(for: preset, customWidth: customWidth, customHeight: customHeight, image: image)
        pageSizes.append(size)

        context.beginPage(withBounds: CGRect(origin: .zero, size: size), pageInfo: [:])
        let cgContext = context.cgContext

        // Background must be filled before the image, always.
        background.setFill()
        cgContext.fill(CGRect(origin: .zero, size: size))

        let rect = contentRect(pageSize: size, imageSize: image.size, fitMode: fitMode, margin: margin)

        switch fitMode {
        case .cover:
          // cover overflows by design — clip to avoid margin bleed.
          let clipRect = CGRect(
            x: margin,
            y: margin,
            width: max(size.width - 2 * margin, 0),
            height: max(size.height - 2 * margin, 0)
          )
          cgContext.saveGState()
          cgContext.clip(to: clipRect)
          image.draw(in: rect) // UIImage.draw(in:) auto-applies EXIF orientation.
          cgContext.restoreGState()
        case .contain, .stretch:
          image.draw(in: rect)
        }
      }
    }

    return (data, pageSizes)
  }
}
