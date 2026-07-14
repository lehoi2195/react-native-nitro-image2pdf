import { validateAndNormalize, ValidationError } from '../validation'
import type { CreatePdfOptions } from '../specs/ImageToPdf.nitro'

const base = (o: Partial<CreatePdfOptions> = {}): CreatePdfOptions => ({
  images: [{ type: 'path', value: '/tmp/a.jpg' }],
  ...o,
})

describe('validateAndNormalize', () => {
  it('rejects empty images', () => {
    expect(() => validateAndNormalize(base({ images: [] })))
      .toThrow(ValidationError)
  })

  it('rejects invalid image source type', () => {
    // @ts-expect-error testing runtime guard
    expect(() => validateAndNormalize(base({ images: [{ type: 'ftp', value: 'x' }] })))
      .toThrow(/type/)
  })

  it('rejects empty base64/path value', () => {
    expect(() => validateAndNormalize(base({ images: [{ type: 'base64', value: '' }] })))
      .toThrow(/empty/)
  })

  it('applies default page/output', () => {
    const r = validateAndNormalize(base())
    expect(r.page?.fitMode).toBe('contain')
    expect(r.output?.directory).toBe('cache')
    expect(r.output?.format).toBe('file')
    expect(r.page?.size).toBe('fit')
  })

  it('requires customWidth/Height > 0 when size=custom', () => {
    expect(() => validateAndNormalize(base({ page: { size: 'custom', fitMode: 'contain' } })))
      .toThrow(/custom/)
    expect(() => validateAndNormalize(base({ page: { size: 'custom', fitMode: 'contain', customWidth: 0, customHeight: 10 } })))
      .toThrow(/custom/)
  })

  it('rejects quality out of 0..1', () => {
    expect(() => validateAndNormalize(base({ processing: { quality: 1.5 } }))).toThrow(/quality/)
    expect(() => validateAndNormalize(base({ processing: { quality: -0.1 } }))).toThrow(/quality/)
  })

  it('rejects negative margin and non-positive dims', () => {
    expect(() => validateAndNormalize(base({ page: { size: 'A4', fitMode: 'contain', margin: -1 } }))).toThrow(/margin/)
    expect(() => validateAndNormalize(base({ processing: { maxWidth: 0 } }))).toThrow(/maxWidth/)
  })

  it('rejects bad hex backgroundColor', () => {
    expect(() => validateAndNormalize(base({ page: { size: 'A4', fitMode: 'contain', backgroundColor: 'red' } })))
      .toThrow(/backgroundColor/)
  })

  it('rejects empty passwords', () => {
    expect(() => validateAndNormalize(base({ security: { password: '' } }))).toThrow(/password/)
  })

  it('rejects non-string password/ownerPassword with a ValidationError instead of throwing a raw TypeError', () => {
    // @ts-expect-error testing runtime guard against non-string values from untyped JS callers
    expect(() => validateAndNormalize(base({ security: { password: 12345 } })))
      .toThrow(ValidationError)
    // @ts-expect-error testing runtime guard against non-string values from untyped JS callers
    expect(() => validateAndNormalize(base({ security: { password: 12345 } })))
      .toThrow(/security\.password must be a string/)
    // @ts-expect-error testing runtime guard against non-string values from untyped JS callers
    expect(() => validateAndNormalize(base({ security: { ownerPassword: 12345 } })))
      .toThrow(ValidationError)
    // @ts-expect-error testing runtime guard against non-string values from untyped JS callers
    expect(() => validateAndNormalize(base({ security: { ownerPassword: 12345 } })))
      .toThrow(/security\.ownerPassword must be a string/)
  })

  it('does not mutate the input', () => {
    const input = base()
    const snapshot = JSON.stringify(input)
    validateAndNormalize(input)
    expect(JSON.stringify(input)).toBe(snapshot)
  })
})
