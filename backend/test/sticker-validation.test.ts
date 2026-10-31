import { describe, expect, it } from 'vitest';
import {
  addStickerSchema, createPackSchema, keywordsSchema, packListSchema, reorderSchema, updatePackSchema, webpIsAnimated,
} from '../src/modules/stickers/sticker_validation';

const riff = (vp8x: boolean, flags: number) => {
  const b = new Uint8Array(32);
  b.set([0x52, 0x49, 0x46, 0x46], 0); b.set([0x57, 0x45, 0x42, 0x50], 8);
  b.set(vp8x ? [0x56, 0x50, 0x38, 0x58] : [0x56, 0x50, 0x38, 0x20], 12); b[20] = flags;
  return b;
};
const id = (n: number) => `11111111-1111-4111-8111-${String(n).padStart(12, '0')}`;

describe('webpIsAnimated', () => {
  it('reads the animation flag of an extended WebP', () => {
    expect(webpIsAnimated(riff(true, 0x02))).toBe(true);
    expect(webpIsAnimated(riff(true, 0x10))).toBe(false); // alpha only
    expect(webpIsAnimated(riff(false, 0x02))).toBe(false); // a plain lossy file has no flags
  });
  it('is false for short or foreign data', () => {
    expect(webpIsAnimated(new Uint8Array(5))).toBe(false);
    expect(webpIsAnimated(new TextEncoder().encode('GIF89a-this-is-not-a-webp-file-at-all'))).toBe(false);
  });
});

describe('keywords', () => {
  it('lowercases, trims and removes duplicates', () => expect(keywordsSchema.parse([' LOL ', 'lol', 'Bruh'])).toEqual(['lol', 'bruh']));
  it('refuses markup, long words and more than 8', () => {
    expect(keywordsSchema.safeParse(['<b>']).success).toBe(false);
    expect(keywordsSchema.safeParse(['x'.repeat(30)]).success).toBe(false);
    expect(keywordsSchema.safeParse(Array.from({ length: 9 }, (_, i) => `k${i}`)).success).toBe(false);
  });
});

describe('pack schemas', () => {
  const ok = { name: 'Campus Life', slug: 'campus-life', category: 'campus_vibes', coverMediaId: id(1), license: 'univibe_original', sourceAttribution: 'UniVibe design team' };
  it('accepts a valid pack, free by default', () => expect(createPackSchema.parse(ok).premium).toBe(false));
  it('refuses unknown categories, licences, bad slugs, extra fields and missing attribution', () => {
    for (const bad of [{ category: 'memes' }, { license: 'ripped' }, { slug: 'Has Space' }, { sourceAttribution: '' }, { objectKey: 'x' }]) {
      expect(createPackSchema.safeParse({ ...ok, ...bad }).success).toBe(false);
    }
  });
  it('an update must change something', () => {
    expect(updatePackSchema.safeParse({}).success).toBe(false);
    expect(updatePackSchema.safeParse({ featured: true }).success).toBe(true);
    expect(updatePackSchema.safeParse({ status: 'published' }).success).toBe(false);
  });
  it('a sticker is registered by media id only', () => {
    expect(addStickerSchema.safeParse({ mediaId: id(2), label: 'LOL', keywords: ['laugh'] }).success).toBe(true);
    expect(addStickerSchema.safeParse({ mediaId: id(2), url: 'http://x' }).success).toBe(false);
  });
  it('a reorder lists each sticker once', () => {
    expect(reorderSchema.safeParse({ stickerIds: [id(1), id(2)] }).success).toBe(true);
    expect(reorderSchema.safeParse({ stickerIds: [id(1), id(1)] }).success).toBe(false);
    expect(reorderSchema.safeParse({ stickerIds: [] }).success).toBe(false);
  });
});

describe('pack list', () => {
  it('limits the page and rejects unknown filters', () => {
    expect(packListSchema.safeParse({ limit: '51' }).success).toBe(false);
    expect(packListSchema.safeParse({ category: 'seasonal', premium: 'free' }).success).toBe(true);
    expect(packListSchema.safeParse({ category: 'nope' }).success).toBe(false);
  });
});
