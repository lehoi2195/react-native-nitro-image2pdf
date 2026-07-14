let mockCreatePdfNative: jest.Mock

jest.mock('react-native-nitro-modules', () => {
  mockCreatePdfNative = jest.fn().mockResolvedValue({ numberOfPages: 1, pages: [], filePath: '/x.pdf' })
  return {
    NitroModules: {
      createHybridObject: jest.fn(() => ({
        createPdf: mockCreatePdfNative,
      })),
    },
  }
})

import { createPdf } from '../index'

beforeEach(() => {
  mockCreatePdfNative.mockClear()
})

it('validates then forwards normalized options to native', async () => {
  await createPdf({ images: [{ type: 'path', value: '/a.jpg' }] })
  const passed = mockCreatePdfNative.mock.calls[0][0]
  expect(passed.output.format).toBe('file')   // default applied before native
  expect(passed.page.fitMode).toBe('contain')
})

it('rejects invalid options without calling native', async () => {
  const promise = createPdf({ images: [] })
  await expect(promise).rejects.toThrow()
  expect(mockCreatePdfNative).not.toHaveBeenCalled()
})
