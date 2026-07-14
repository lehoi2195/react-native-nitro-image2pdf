# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/). Starting from this release, versions are
managed by [semantic-release](https://semantic-release.gitbook.io/) based on
[Conventional Commits](https://www.conventionalcommits.org/) — run `npm run release` to cut a new
version automatically.

## [1.0.0] - 2026-07-14

Initial public release. Full rewrite of the legacy bridge-based `react-native-image-to-pdf` as a
Nitro Module (New Architecture only, JSI-backed, fully type-safe, no bridge/serialization overhead).

### ✨ Features

- `createPdf()` — convert one or more images into a single PDF in one call
- Image sources: local `path`, Android `content://` URI, remote `http(s)://` URL, or raw `base64`
- Page layout: `A4` / `A5` / `Letter` / `Legal` / `custom` size or fit-to-image, with
  `contain` / `cover` / `stretch` fit modes, margins, and background color
- Image processing: `maxWidth` / `maxHeight` downsampling and JPEG `quality` re-encoding —
  consistent behavior on **both** iOS and Android
- Document metadata: title, author, subject, keywords, creator
- Password protection: open (user) and permissions (owner) passwords — AES on Android, RC4 on iOS
- Flexible output: return a file path, base64 string, or both; write to `cache` / `documents` /
  `temp`, or an absolute path
- Runnable example app (`example/`): every image source and page/fit combination, password
  protection, an inline PDF preview via `react-native-pdf`, a device photo picker, and
  open-in-external-viewer

### 🐛 Bug Fixes

Notable issues found and fixed during development, kept here for future reference:

- Fixed an Android JNI package mismatch (`HybridImageToPdf` was outside
  `com.margelo.nitro.nitroimage2pdf`) that caused a `ClassNotFoundException` at runtime — invisible
  to any build or unit test, only reproducible by actually invoking the native method
- Fixed iOS `processing.quality` not actually reducing PDF file size — `UIGraphicsPDFRenderer`
  re-rasterizes a plain `UIImage`'s bitmap regardless of source JPEG compression;
  `CGImage(jpegDataProviderSource:)` is required to preserve the re-encoded JPEG stream
- Fixed Android-only `ownerPassword`-only encryption not applying restrictive permissions
- Fixed `react-native-blob-util` failing to link on iOS (missing direct dependency plus a stale
  `Pods.xcodeproj` that never got a target for it), causing a `NativeEventEmitter() requires a
  non-null argument` crash in the example app
- Fixed "No app associated with this mime type" when opening a generated PDF from the example app
  on Android 11+ — requires a `<queries>` manifest declaration for `PackageManager` to see
  installed PDF viewers
- Fixed missing `.pdf` file extensions on generated file names breaking mime-type detection for
  "open in default viewer"

### 📚 Documentation

- Full README: features, requirements, installation, usage, options reference, per-platform
  encryption model, output directory mapping, Android R8/ProGuard keep rules, migration guide from
  `react-native-image-to-pdf`, error code reference, and demo videos

### 🔄 Code Refactors

- Condensed verbose multi-line comments across the native (Swift/Kotlin) and TypeScript source into
  concise single-line notes

### 🛠️ Other changes

- Bundled `consumerProguardFiles` for Android R8/ProGuard (BouncyCastle + PdfBox-Android reflection
  keep rules) so consuming apps need no manual ProGuard configuration
- Migration guide and field-mapping table from the legacy `createPDFbyImages` API
