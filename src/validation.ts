import type {
  CreatePdfOptions, ImageInput, PageConfig, ProcessingConfig, OutputConfig,
} from './specs/ImageToPdf.nitro'

export class ValidationError extends Error {
  code = 'VALIDATION_ERROR' as const
  constructor(message: string) { super(message); this.name = 'ValidationError' }
}

const HEX = /^#[0-9a-fA-F]{6}$/
const SOURCE_TYPES = ['path', 'uri', 'base64']
const PRESETS = ['fit', 'A4', 'A5', 'Letter', 'Legal', 'custom']
const FITS = ['contain', 'cover', 'stretch']
const DIRS = ['cache', 'documents', 'temp']
const FORMATS = ['file', 'base64', 'both']

function fail(msg: string): never { throw new ValidationError(msg) }

function normImages(images: ImageInput[]): ImageInput[] {
  if (!Array.isArray(images) || images.length === 0) fail('images must be a non-empty array')
  return images.map((img, i) => {
    if (!img || !SOURCE_TYPES.includes(img.type)) fail(`images[${i}].type must be one of ${SOURCE_TYPES.join(', ')}`)
    if (typeof img.value !== 'string' || img.value.length === 0) fail(`images[${i}].value must be a non-empty string`)
    return { type: img.type, value: img.value }
  })
}

function normPage(page?: PageConfig): PageConfig {
  const p: PageConfig = {
    size: page?.size ?? 'fit',
    fitMode: page?.fitMode ?? 'contain',
    customWidth: page?.customWidth,
    customHeight: page?.customHeight,
    margin: page?.margin ?? 0,
    backgroundColor: page?.backgroundColor,
  }
  if (!PRESETS.includes(p.size)) fail(`page.size must be one of ${PRESETS.join(', ')}`)
  if (!FITS.includes(p.fitMode)) fail(`page.fitMode must be one of ${FITS.join(', ')}`)
  if (p.size === 'custom') {
    if (!(typeof p.customWidth === 'number' && p.customWidth > 0 &&
          typeof p.customHeight === 'number' && p.customHeight > 0))
      fail('page.customWidth and customHeight must be > 0 when size is "custom"')
  }
  if ((p.margin ?? 0) < 0) fail('page.margin must be >= 0')
  if (p.backgroundColor !== undefined && !HEX.test(p.backgroundColor))
    fail('page.backgroundColor must be a #RRGGBB hex string')
  return p
}

function normProcessing(pr?: ProcessingConfig): ProcessingConfig {
  const r: ProcessingConfig = { maxWidth: pr?.maxWidth, maxHeight: pr?.maxHeight, quality: pr?.quality ?? 1 }
  if (r.maxWidth !== undefined && !(r.maxWidth > 0)) fail('processing.maxWidth must be > 0')
  if (r.maxHeight !== undefined && !(r.maxHeight > 0)) fail('processing.maxHeight must be > 0')
  if (!(typeof r.quality === 'number' && r.quality >= 0 && r.quality <= 1)) fail('processing.quality must be in 0..1')
  return r
}

function normOutput(out?: OutputConfig): OutputConfig {
  const o: OutputConfig = {
    directory: out?.directory ?? 'cache',
    format: out?.format ?? 'file',
    fileName: out?.fileName,
    absolutePath: out?.absolutePath,
  }
  if (!DIRS.includes(o.directory)) fail(`output.directory must be one of ${DIRS.join(', ')}`)
  if (!FORMATS.includes(o.format)) fail(`output.format must be one of ${FORMATS.join(', ')}`)
  return o
}

export function validateAndNormalize(options: CreatePdfOptions): CreatePdfOptions {
  if (!options || typeof options !== 'object') fail('options must be an object')
  const security = options.security
  if (security) {
    if (security.password !== undefined) {
      if (typeof security.password !== 'string') fail('security.password must be a string')
      if (security.password.length === 0) fail('security.password must not be empty')
    }
    if (security.ownerPassword !== undefined) {
      if (typeof security.ownerPassword !== 'string') fail('security.ownerPassword must be a string')
      if (security.ownerPassword.length === 0) fail('security.ownerPassword must not be empty')
    }
  }
  return {
    images: normImages(options.images),
    page: normPage(options.page),
    processing: normProcessing(options.processing),
    output: normOutput(options.output),
    metadata: options.metadata ? { ...options.metadata } : undefined,
    security: security ? { ...security } : undefined,
  }
}
