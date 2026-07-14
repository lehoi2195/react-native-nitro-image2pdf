import React, { useState } from 'react';
import {
  Image,
  Platform,
  Pressable,
  SafeAreaView,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import FileViewer from 'react-native-file-viewer';
import ImagePicker from 'react-native-image-crop-picker';
import { createPdf, type CreatePdfOptions } from 'react-native-nitro-image2pdf';
import Pdf from 'react-native-pdf';
StatusBar.setBarStyle('dark-content');

const landscapeUri = Image.resolveAssetSource(
  require('./assets/sample-landscape.jpg'),
).uri;
const portraitUri = Image.resolveAssetSource(
  require('./assets/sample-portrait.jpg'),
).uri;
const squareUri = Image.resolveAssetSource(
  require('./assets/sample-square.png'),
).uri;

interface Preset {
  key: string;
  title: string;
  subtitle: string;
  options: CreatePdfOptions;
}

const PRESETS: Preset[] = [
  {
    key: 'a4-secured',
    title: 'A4 · metadata · password',
    subtitle: 'Landscape photo, contain fit',
    options: {
      images: [{ type: 'uri', value: landscapeUri }],
      page: { size: 'A4', fitMode: 'contain', margin: 24 },
      metadata: { title: 'Sample Invoice', author: 'nitro-image2pdf' },
      security: { password: 'test-1234' },
      output: {
        directory: 'documents',
        fileName: 'a4-secured.pdf',
        format: 'file',
      },
    },
  },
  {
    key: 'multi-fit',
    title: 'Multi-image · fit',
    subtitle: 'Portrait + square photo, one page each',
    options: {
      images: [
        { type: 'uri', value: portraitUri },
        { type: 'uri', value: squareUri },
      ],
      output: {
        directory: 'documents',
        fileName: 'multi-fit.pdf',
        format: 'file',
      },
    },
  },
  {
    key: 'cover-legal',
    title: 'Legal · cover fit',
    subtitle: 'Square photo, cropped to fill the page',
    options: {
      images: [{ type: 'uri', value: squareUri }],
      page: { size: 'Legal', fitMode: 'cover', margin: 12 },
      output: {
        directory: 'documents',
        fileName: 'cover-legal.pdf',
        format: 'file',
      },
    },
  },
];

type Status =
  | { kind: 'idle' }
  | { kind: 'busy' }
  | { kind: 'done' }
  | { kind: 'error'; message: string };

export default function App(): React.JSX.Element {
  const [status, setStatus] = useState<Status>({ kind: 'idle' });
  const [filePath, setFilePath] = useState<string | undefined>(undefined);
  const [viewerPassword, setViewerPassword] = useState<string | undefined>(
    undefined,
  );

  async function runOptions(
    key: string,
    options: CreatePdfOptions,
  ): Promise<void> {
    setStatus({ kind: 'busy' });
    setFilePath(undefined);
    try {
      const result = await createPdf(options);

      console.log(`[${key}] createPdf result:`, result);
      setStatus({ kind: 'done' });
      setFilePath(result.filePath);
      setViewerPassword(options.security?.password);
    } catch (error) {
      const message =
        error instanceof Error ? error.message : 'Unexpected error';

      console.error(`[${key}] createPdf failed:`, error);
      setStatus({ kind: 'error', message });
    }
  }

  function run(preset: Preset): Promise<void> {
    return runOptions(preset.key, preset.options);
  }

  async function openExternally(): Promise<void> {
    if (!filePath) return;
    try {
      await FileViewer.open(filePath, { showOpenWithDialog: true });
    } catch (error) {
      console.error('[open-externally] failed:', error);
    }
  }

  async function pickFromDevice(): Promise<void> {
    let path: string;
    try {
      const image = await ImagePicker.openPicker({ mediaType: 'photo' });
      path = image.path;
    } catch (error: any) {
      if (error?.code === 'E_PICKER_CANCELLED') return; // user backed out, not an error
      const message =
        error instanceof Error ? error.message : 'Unexpected error';

      console.error('[picked] image picker failed:', error);
      setStatus({ kind: 'error', message });
      return;
    }
    await runOptions('picked', {
      images: [{ type: 'path', value: path }],
      page: { size: 'fit', fitMode: 'contain' },
      output: {
        directory: 'documents',
        fileName: 'picked-photo.pdf',
        format: 'file',
      },
    });
  }

  return (
    <SafeAreaView style={styles.flex}>
      <View style={styles.scrollContent}>
        <View style={styles.header}>
          <Text style={styles.title}>nitro-image2pdf</Text>
          <Text style={styles.subtitle}>
            Tap a case to convert &amp; preview
          </Text>
        </View>
        <View style={styles.presetRow}>
          {PRESETS.map(preset => (
            <Pressable
              key={preset.key}
              disabled={status.kind === 'busy'}
              onPress={() => run(preset)}
              style={({ pressed }) => [
                styles.presetCard,
                pressed && styles.presetCardPressed,
                status.kind === 'busy' && styles.presetCardDisabled,
              ]}
            >
              <Text style={styles.presetTitle}>{preset.title}</Text>
              <Text style={styles.presetSubtitle}>{preset.subtitle}</Text>
            </Pressable>
          ))}

          <Pressable
            disabled={status.kind === 'busy'}
            onPress={pickFromDevice}
            style={({ pressed }) => [
              styles.pickCard,
              pressed && styles.presetCardPressed,
              status.kind === 'busy' && styles.presetCardDisabled,
            ]}
          >
            <Text style={styles.pickTitle}>📷 Pick a photo from device</Text>
            <Text style={styles.presetSubtitle}>
              Tests the real `path` source type
            </Text>
          </Pressable>
        </View>

        <View style={[styles.statusRow, styles.statusRowInline]}>
          {status.kind === 'busy' && (
            <Text style={styles.statusBusy}>Converting…</Text>
          )}
          {status.kind === 'done' && (
            <>
              <Text style={styles.statusDone}>✓ Converted</Text>
              <Pressable onPress={openExternally} style={styles.openButton}>
                <Text style={styles.openButtonText}>
                  Open in default viewer
                </Text>
              </Pressable>
            </>
          )}
          {status.kind === 'error' && (
            <Text style={styles.statusError} numberOfLines={2}>
              ✕ {status.message}
            </Text>
          )}
          {status.kind === 'idle' && (
            <Text style={styles.statusIdle}>No conversion run yet</Text>
          )}
        </View>

        <View style={styles.viewer}>
          {filePath ? (
            <Pdf
              scrollEnabled
              enableAntialiasing
              enablePaging
              source={{ uri: filePath }}
              password={viewerPassword}
              style={styles.pdf}
              onError={error => console.error('PDF render error:', error)}
            />
          ) : (
            <View style={styles.viewerPlaceholder}>
              <Text style={styles.viewerPlaceholderText}>
                The generated PDF will render here
              </Text>
            </View>
          )}
        </View>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, backgroundColor: '#f8fafc' },
  scrollContent: { flexGrow: 1, paddingBottom: 24 },
  header: {
    paddingHorizontal: 20,
    paddingTop: Platform.OS === 'android' ? 44 : 8,
  },
  title: {
    fontSize: 22,
    fontWeight: '700',
    color: '#0f172a',
    letterSpacing: -0.3,
  },
  subtitle: { fontSize: 13, color: '#64748b', marginTop: 2, marginBottom: 14 },
  presetRow: { paddingHorizontal: 20, gap: 10 },
  presetCard: {
    backgroundColor: '#ffffff',
    borderRadius: 14,
    padding: 14,
    borderWidth: 1,
    borderColor: '#e2e8f0',
  },
  presetCardPressed: { backgroundColor: '#eff6ff', borderColor: '#bfdbfe' },
  presetCardDisabled: { opacity: 0.5 },
  presetTitle: { fontSize: 14, fontWeight: '700', color: '#1d4ed8' },
  presetSubtitle: { fontSize: 12, color: '#64748b', marginTop: 2 },
  pickCard: {
    backgroundColor: '#ffffff',
    borderRadius: 14,
    padding: 14,
    borderWidth: 1.5,
    borderStyle: 'dashed',
    borderColor: '#cbd5e1',
  },
  pickTitle: { fontSize: 14, fontWeight: '700', color: '#334155' },
  statusRow: {
    paddingHorizontal: 20,
    paddingVertical: 12,
  },
  statusRowInline: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  statusIdle: { fontSize: 12, color: '#94a3b8' },
  statusBusy: { fontSize: 12, color: '#2563eb', fontWeight: '600' },
  statusDone: { fontSize: 12, color: '#16a34a', fontWeight: '600' },
  statusError: { fontSize: 12, color: '#dc2626', fontWeight: '600' },
  openButton: {
    paddingVertical: 4,
    paddingHorizontal: 10,
    borderRadius: 8,
    backgroundColor: '#eef2ff',
  },
  openButtonText: { fontSize: 11, fontWeight: '600', color: '#3730a3' },
  viewer: {
    height: 500,
    marginHorizontal: 20,
    marginBottom: 20,
    borderRadius: 14,
    overflow: 'hidden',
    backgroundColor: '#e2e8f0',
  },
  pdf: { flex: 1, backgroundColor: '#e2e8f0' },
  viewerPlaceholder: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  viewerPlaceholderText: { fontSize: 13, color: '#94a3b8' },
});
