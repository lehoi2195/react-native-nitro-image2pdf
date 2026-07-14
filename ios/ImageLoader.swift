// Loads a UIImage from an ImageInput (path/base64/uri), downsampling if needed.

import ImageIO
import UIKit

// Remapped to PdfError before reaching the bridge; conforms for consistency.
enum ImageLoadError: Error {
  case invalidImage(String)
  case downloadFailed(String)
  case downloadTimeout(String)
}

extension ImageLoadError: CustomStringConvertible {
  var description: String {
    switch self {
    case .invalidImage(let detail):
      return "[INVALID_IMAGE] Could not load image: \(detail)"
    case .downloadFailed(let detail):
      return "[DOWNLOAD_FAILED] Could not download image: \(detail)"
    case .downloadTimeout(let detail):
      return "[DOWNLOAD_TIMEOUT] Timed out downloading image: \(detail)"
    }
  }
}

extension ImageLoadError: LocalizedError {
  var errorDescription: String? { description }
}

enum ImageLoader {
  /// Default timeout for remote `uri` downloads, in seconds.
  private static let downloadTimeoutInterval: TimeInterval = 30

  // Loads from path/base64/uri, downsampling via Image I/O if bounds given.
  static func load(type: ImageSourceType, value: String, maxWidth: Double? = nil, maxHeight: Double? = nil) throws -> UIImage {
    switch type {
    case .path:
      return try loadFromPath(value, maxWidth: maxWidth, maxHeight: maxHeight)
    case .base64:
      return try loadFromBase64(value, maxWidth: maxWidth, maxHeight: maxHeight)
    case .uri:
      return try loadFromRemoteURL(value, maxWidth: maxWidth, maxHeight: maxHeight)
    }
  }

  private static func loadFromPath(_ value: String, maxWidth: Double?, maxHeight: Double?) throws -> UIImage {
    let path = value.hasPrefix("file://") ? String(value.dropFirst("file://".count)) : value

    guard needsDownsampling(maxWidth: maxWidth, maxHeight: maxHeight) else {
      guard let image = UIImage(contentsOfFile: path) else {
        throw ImageLoadError.invalidImage(value)
      }
      return image
    }

    guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil) else {
      throw ImageLoadError.invalidImage(value)
    }
    return try downsampledImage(from: source, maxWidth: maxWidth, maxHeight: maxHeight, detail: value)
  }

  private static func loadFromBase64(_ value: String, maxWidth: Double?, maxHeight: Double?) throws -> UIImage {
    guard let data = Data(base64Encoded: value, options: .ignoreUnknownCharacters) else {
      throw ImageLoadError.invalidImage("base64 payload")
    }

    guard needsDownsampling(maxWidth: maxWidth, maxHeight: maxHeight) else {
      guard let image = UIImage(data: data) else {
        throw ImageLoadError.invalidImage("base64 payload")
      }
      return image
    }

    guard let source = CGImageSourceCreateWithData(data as CFData, nil) else {
      throw ImageLoadError.invalidImage("base64 payload")
    }
    return try downsampledImage(from: source, maxWidth: maxWidth, maxHeight: maxHeight, detail: "base64 payload")
  }

  private static func loadFromRemoteURL(_ value: String, maxWidth: Double?, maxHeight: Double?) throws -> UIImage {
    guard let url = URL(string: value) else {
      throw ImageLoadError.invalidImage(value)
    }

    var request = URLRequest(url: url)
    request.timeoutInterval = downloadTimeoutInterval

    let semaphore = DispatchSemaphore(value: 0)
    var loadedData: Data?
    var loadError: Error?

    let task = URLSession.shared.dataTask(with: request) { data, _, error in
      defer { semaphore.signal() }
      if let data {
        loadedData = data
        return
      }
      if let nsError = error as NSError?, nsError.code == NSURLErrorTimedOut {
        loadError = ImageLoadError.downloadTimeout(value)
      } else {
        loadError = ImageLoadError.downloadFailed(value)
      }
    }
    task.resume()
    semaphore.wait()

    guard let loadedData else {
      throw loadError ?? ImageLoadError.downloadFailed(value)
    }

    guard needsDownsampling(maxWidth: maxWidth, maxHeight: maxHeight) else {
      guard let image = UIImage(data: loadedData) else {
        throw ImageLoadError.invalidImage(value)
      }
      return image
    }

    guard let source = CGImageSourceCreateWithData(loadedData as CFData, nil) else {
      throw ImageLoadError.invalidImage(value)
    }
    return try downsampledImage(from: source, maxWidth: maxWidth, maxHeight: maxHeight, detail: value)
  }

  // True if either bound is a real positive limit.
  private static func needsDownsampling(maxWidth: Double?, maxHeight: Double?) -> Bool {
    (maxWidth.map { $0 > 0 } ?? false) || (maxHeight.map { $0 > 0 } ?? false)
  }

  // Thumbnail-decodes at the target resolution to avoid a full-size allocation.
  private static func downsampledImage(
    from source: CGImageSource,
    maxWidth: Double?,
    maxHeight: Double?,
    detail: String
  ) throws -> UIImage {
    guard CGImageSourceGetCount(source) > 0,
          let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
    else {
      throw ImageLoadError.invalidImage(detail)
    }

    guard let pixelWidth = (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.doubleValue, pixelWidth > 0,
          let pixelHeight = (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.doubleValue, pixelHeight > 0
    else {
      throw ImageLoadError.invalidImage(detail)
    }

    // EXIF orientations 5-8 rotate 90/270°, swapping effective width/height.
    let orientation = (properties[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
    let swapsDimensions = [5, 6, 7, 8].contains(orientation)
    let effectiveWidth = swapsDimensions ? pixelHeight : pixelWidth
    let effectiveHeight = swapsDimensions ? pixelWidth : pixelHeight

    var scale = 1.0
    if let maxWidth, maxWidth > 0 {
      scale = min(scale, maxWidth / effectiveWidth)
    }
    if let maxHeight, maxHeight > 0 {
      scale = min(scale, maxHeight / effectiveHeight)
    }

    let maxPixelSize = max(effectiveWidth, effectiveHeight) * scale

    // WithTransform bakes EXIF rotation into the thumbnail pixels.
    let thumbnailOptions: [CFString: Any] = [
      kCGImageSourceCreateThumbnailFromImageAlways: true,
      kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
      kCGImageSourceCreateThumbnailWithTransform: true,
    ]

    guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions as CFDictionary) else {
      throw ImageLoadError.invalidImage(detail)
    }

    return UIImage(cgImage: thumbnail, scale: 1.0, orientation: .up)
  }
}
