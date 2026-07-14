import { NitroModules } from 'react-native-nitro-modules'
import type { ImageToPdf, CreatePdfOptions, CreatePdfResult } from './specs/ImageToPdf.nitro'
import { validateAndNormalize } from './validation'

export * from './specs/ImageToPdf.nitro'
export { ValidationError } from './validation'

const native = NitroModules.createHybridObject<ImageToPdf>('ImageToPdf')

export async function createPdf(options: CreatePdfOptions): Promise<CreatePdfResult> {
  const normalized = validateAndNormalize(options)   // throws synchronously → rejects the promise chain
  return native.createPdf(normalized)
}
