// Bridges HybridImageToPdfSpec to ImageLoader/PdfBuilder; handles output writing.

import Foundation
import NitroModules
import UIKit

// NitroModules bridges errors to JS via `description`, not `errorDescription`.
enum PdfError: Error {
  case invalidImage(String)
  case downloadFailed(String)
  case downloadTimeout(String)
  case writeFailed(String)
  case permissionDenied(String)
  case encryptionFailed(String)
}

extension PdfError: CustomStringConvertible {
  var description: String {
    switch self {
    case .invalidImage(let detail):
      return "[INVALID_IMAGE] \(detail)"
    case .downloadFailed(let detail):
      return "[DOWNLOAD_FAILED] \(detail)"
    case .downloadTimeout(let detail):
      return "[DOWNLOAD_TIMEOUT] \(detail)"
    case .writeFailed(let detail):
      return "[WRITE_FAILED] \(detail)"
    case .permissionDenied(let detail):
      return "[PERMISSION_DENIED] \(detail)"
    case .encryptionFailed(let detail):
      return "[ENCRYPTION_FAILED] \(detail)"
    }
  }
}

extension PdfError: LocalizedError {
  var errorDescription: String? { description }
}

class HybridImageToPdf: HybridImageToPdfSpec {
  func createPdf(options: CreatePdfOptions) throws -> Promise<CreatePdfResult> {
    return Promise.async {
      guard !options.images.isEmpty else {
        throw PdfError.invalidImage("images must be a non-empty array")
      }

      // Load images, downsampling via maxWidth/maxHeight if set.
      let uiImages: [UIImage] = try options.images.map { input in
        do {
          return try ImageLoader.load(
            type: input.type,
            value: input.value,
            maxWidth: options.processing?.maxWidth,
            maxHeight: options.processing?.maxHeight
          )
        } catch let error as ImageLoadError {
          throw Self.mapLoadError(error)
        }
      }

      // Build the PDF (page sizing, fit, margin, metadata, password).
      let built: (data: Data, pageSizes: [CGSize])
      do {
        built = try PdfBuilder.build(
          images: uiImages,
          page: options.page,
          processing: options.processing,
          metadata: options.metadata,
          userPassword: options.security?.password,
          ownerPassword: options.security?.ownerPassword
        )
      } catch let error as PdfBuildError {
        throw PdfError.invalidImage(error.localizedDescription)
      }

      // Write output per output.format/directory/absolutePath.
      let outputFormat = options.output?.format ?? .file
      var writeFile = false
      var writeBase64 = false
      switch outputFormat {
      case .file:
        writeFile = true
      case .base64:
        writeBase64 = true
      case .both:
        writeFile = true
        writeBase64 = true
      }

      var filePath: String?
      var fileSize: Double?

      if writeFile {
        let path = try Self.resolveOutputPath(output: options.output)
        do {
          try built.data.write(to: URL(fileURLWithPath: path), options: .atomic)
        } catch {
          throw PdfError.writeFailed("Failed to write PDF to \(path): \(error.localizedDescription)")
        }
        filePath = path
        fileSize = Double(built.data.count)
      }

      let base64: String? = writeBase64 ? built.data.base64EncodedString() : nil

      let pages = built.pageSizes.map { PdfPageInfo(width: Double($0.width), height: Double($0.height)) }
      return CreatePdfResult(
        filePath: filePath,
        base64: base64,
        numberOfPages: Double(uiImages.count),
        fileSize: fileSize,
        pages: pages
      )
    }
  }

  private static func mapLoadError(_ error: ImageLoadError) -> PdfError {
    switch error {
    case .invalidImage(let detail):
      return .invalidImage(detail)
    case .downloadFailed(let detail):
      return .downloadFailed(detail)
    case .downloadTimeout(let detail):
      return .downloadTimeout(detail)
    }
  }

  // Resolves absolutePath, or directory + fileName (UUID default).
  private static func resolveOutputPath(output: OutputConfig?) throws -> String {
    if let absolutePath = output?.absolutePath, !absolutePath.isEmpty {
      let parentDirectory = (absolutePath as NSString).deletingLastPathComponent
      var isDirectory: ObjCBool = false
      guard FileManager.default.fileExists(atPath: parentDirectory, isDirectory: &isDirectory), isDirectory.boolValue else {
        throw PdfError.writeFailed("Parent directory does not exist: \(parentDirectory)")
      }
      guard FileManager.default.isWritableFile(atPath: parentDirectory) else {
        throw PdfError.permissionDenied("Parent directory is not writable: \(parentDirectory)")
      }
      return absolutePath
    }

    let directory = output?.directory ?? .cache
    let directoryURL: URL
    switch directory {
    case .cache:
      guard let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else {
        throw PdfError.writeFailed("Could not resolve the caches directory")
      }
      directoryURL = url
    case .documents:
      guard let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
        throw PdfError.writeFailed("Could not resolve the documents directory")
      }
      directoryURL = url
    case .temp:
      directoryURL = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
    }

    if !FileManager.default.fileExists(atPath: directoryURL.path) {
      do {
        try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
      } catch {
        throw PdfError.writeFailed("Failed to create output directory \(directoryURL.path): \(error.localizedDescription)")
      }
    }

    let fileName: String
    if let providedName = output?.fileName, !providedName.isEmpty {
      fileName = providedName
    } else {
      fileName = "\(UUID().uuidString).pdf"
    }

    // .atomic write overwrites an existing file at this path.
    return directoryURL.appendingPathComponent(fileName).path
  }
}
