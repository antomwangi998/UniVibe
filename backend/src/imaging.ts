import sharp from 'sharp';

export type ImagePurpose = 'post' | 'avatar' | 'cover' | 'message' | 'event' | 'group';

export interface ImageVariant {
  bytes: Uint8Array;
  width: number;
  height: number;
  contentType: 'image/webp';
}

export interface ProcessedImage {
  /** The photo as it is kept and shown full screen. */
  full: ImageVariant;
  /** A smaller copy for feed lists, or null for photos that are never shown in a feed. */
  thumb: ImageVariant | null;
}

/** Turns an uploaded photo into what the server stores. Throws [ImageRejected] when the file is not a usable photo. */
export interface ImageProcessor {
  process(bytes: Uint8Array, declaredType: string, purpose: ImagePurpose): Promise<ProcessedImage>;
}

/** The message is written for the person who uploaded the photo, so it is passed on as is. */
export class ImageRejected extends Error {}

/** Longest edge, in pixels, of the stored photo and of the feed thumbnail. */
export const IMAGE_PRESETS: Record<ImagePurpose, { full: number; thumb: number | null }> = {
  post: { full: 2048, thumb: 720 },
  message: { full: 2048, thumb: null },
  event: { full: 2048, thumb: null },
  avatar: { full: 512, thumb: null },
  group: { full: 512, thumb: null },
  cover: { full: 1600, thumb: null },
};

const FORMAT_OF: Record<string, string> = { 'image/jpeg': 'jpeg', 'image/png': 'png', 'image/webp': 'webp' };
export const DEFAULT_MAX_PIXELS = 30_000_000;
const FULL_QUALITY = 82;
const THUMB_QUALITY = 76;

/**
 * Real image processing with libvips (sharp). For every photo it:
 *  - checks the file really decodes, matches its declared type and is not absurdly large (decompression bombs);
 *  - turns it upright using its orientation tag, then drops ALL metadata (location, camera, owner);
 *  - saves it as WebP no larger than the preset's longest edge, plus a feed thumbnail for post photos.
 * Nothing is ever enlarged and the aspect ratio is never changed.
 */
export function createSharpProcessor(options: { maxPixels?: number } = {}): ImageProcessor {
  const maxPixels = options.maxPixels ?? DEFAULT_MAX_PIXELS;
  // A small server: one image at a time, and no cache of decoded images between requests.
  sharp.cache(false);
  sharp.concurrency(1);
  const open = (bytes: Uint8Array) => sharp(bytes, { limitInputPixels: maxPixels, failOn: 'error' });

  async function render(bytes: Uint8Array, edge: number, quality: number): Promise<ImageVariant> {
    const { data, info } = await open(bytes)
      .rotate() // upright according to the orientation tag
      .resize({ width: edge, height: edge, fit: 'inside', withoutEnlargement: true })
      .webp({ quality })
      .toBuffer({ resolveWithObject: true });
    return { bytes: new Uint8Array(data), width: info.width, height: info.height, contentType: 'image/webp' };
  }

  return {
    async process(bytes, declaredType, purpose) {
      try {
        const meta = await open(bytes).metadata();
        if (!meta.width || !meta.height) throw new ImageRejected('That is not a valid photo.');
        if (meta.format !== FORMAT_OF[declaredType]) throw new ImageRejected('The file type does not match.');
        const preset = IMAGE_PRESETS[purpose];
        const full = await render(bytes, preset.full, FULL_QUALITY);
        const thumb = preset.thumb ? await render(bytes, preset.thumb, THUMB_QUALITY) : null;
        return { full, thumb };
      } catch (e) {
        if (e instanceof ImageRejected) throw e;
        const message = e instanceof Error ? e.message : '';
        if (message.includes('pixel limit')) {
          throw new ImageRejected(`That photo is too large (the limit is ${Math.round(maxPixels / 1_000_000)} megapixels).`);
        }
        throw new ImageRejected('That photo could not be read. Try a different one.');
      }
    },
  };
}
