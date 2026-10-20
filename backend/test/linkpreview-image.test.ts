import sharp from 'sharp';
import { describe, expect, it } from 'vitest';
import { createSharpProcessor } from '../src/imaging';

const processor = createSharpProcessor({ maxPixels: 20_000_000 });

describe('link card pictures', () => {
  it('are shrunk to the card size and stored as webp, keeping the proportions', async () => {
    const big = await sharp({ create: { width: 2000, height: 1000, channels: 3, background: '#2dd4bf' } }).jpeg().toBuffer();
    const out = await processor.process(big, 'image/jpeg', 'linkpreview');
    expect(out.full).toMatchObject({ width: 640, height: 320, contentType: 'image/webp' });
    expect(out.thumb).toBeNull();
  });

  it('are never enlarged, and drop metadata', async () => {
    const small = await sharp({ create: { width: 300, height: 200, channels: 3, background: '#fff' } }).withMetadata({ exif: { IFD0: { Copyright: 'secret owner' } } }).jpeg().toBuffer();
    const out = await processor.process(small, 'image/jpeg', 'linkpreview');
    expect(out.full).toMatchObject({ width: 300, height: 200 });
    expect(Buffer.from(out.full.bytes).includes(Buffer.from('secret owner'))).toBe(false);
  });
});
