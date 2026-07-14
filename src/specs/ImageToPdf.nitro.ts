// src/specs/ImageToPdf.nitro.ts
import type { HybridObject } from 'react-native-nitro-modules'

export type ImageSourceType = 'path' | 'uri' | 'base64'
export interface ImageInput { type: ImageSourceType; value: string }

export type PageSizePreset = 'fit' | 'A4' | 'A5' | 'Letter' | 'Legal' | 'custom'
export type FitMode = 'contain' | 'cover' | 'stretch'
export interface PageConfig {
  size: PageSizePreset
  customWidth?: number
  customHeight?: number
  fitMode: FitMode
  margin?: number
  backgroundColor?: string
}

export interface ProcessingConfig {
  maxWidth?: number
  maxHeight?: number
  quality?: number
}

export type OutputDirectory = 'cache' | 'documents' | 'temp'
export type OutputFormat = 'file' | 'base64' | 'both'
export interface OutputConfig {
  directory: OutputDirectory
  fileName?: string
  absolutePath?: string
  format: OutputFormat
}

export interface PdfMetadata {
  title?: string; author?: string; subject?: string
  keywords?: string; creator?: string
}
export interface SecurityConfig { password?: string; ownerPassword?: string }

export interface CreatePdfOptions {
  images: ImageInput[]
  page?: PageConfig
  processing?: ProcessingConfig
  output?: OutputConfig
  metadata?: PdfMetadata
  security?: SecurityConfig
}

export interface PdfPageInfo { width: number; height: number }
export interface CreatePdfResult {
  filePath?: string
  base64?: string
  numberOfPages: number
  fileSize?: number
  pages: PdfPageInfo[]
}

export interface ImageToPdf
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  createPdf(options: CreatePdfOptions): Promise<CreatePdfResult>
}
