import sharp from 'sharp';
import { describe, expect, it } from 'vitest';
import { ImageRejected, createSharpProcessor } from '../src/imaging';

const processor = createSharpProcessor({ maxPixels: 20_000_000 });

const jpeg = (width: number, height: number, tweak: (s: sharp.Sharp) => sharp.Sharp = (s) => s) =>
  tweak(sharp({ create: { width, height, channels: 3, background: '#2dd4bf' } }).jpeg()).toBuffer();

describe('photo processing', () => {
  it('shrinks big post photos and makes a feed thumbnail, keeping the proportions', async () => {
    const out = await processor.process(await jpeg(4000, 3000), 'image/jpeg', 'post');
    expect(out.full).toMatchObject({ width: 2048, height: 1536, contentType: 'image/webp' });
    expect(out.thumb).toMatchObject({ width: 720, height: 540, contentType: 'image/webp' });
  });

  it('never enlarges a small photo', async () => {
    const out = await processor.process(await jpeg(300, 200), 'image/jpeg', 'post');
    expect(out.full).toMatchObject({ width: 300, height: 200 });
    expect(out.thumb).toMatchObject({ width: 300, height: 200 });
  });

  it('turns a sideways photo upright', async () => {
    const sideways = await jpeg(400, 200, (s) => s.withMetadata({ orientation: 6 }));
    const out = await processor.process(sideways, 'image/jpeg', 'post');
    expect(out.full).toMatchObject({ width: 200, height: 400 });
  });

  it('removes metadata such as owner and location', async () => {
    const tagged = await jpeg(200, 200, (s) => s.withMetadata({ exif: { IFD0: { Copyright: 'secret owner' } } }));
    expect((await sharp(tagged).metadata()).exif).toBeDefined(); // the test file really carries it
    const out = await processor.process(tagged, 'image/jpeg', 'post');
    const kept = await sharp(out.full.bytes).metadata();
    expect(kept.exif).toBeUndefined();
    expect(Buffer.from(out.full.bytes).includes('secret owner')).toBe(false);
  });

  it('profile and cover photos are bounded and get no thumbnail', async () => {
    const avatar = await processor.process(await jpeg(2000, 2000), 'image/jpeg', 'avatar');
    expect(avatar.full).toMatchObject({ width: 512, height: 512 });
    expect(avatar.thumb).toBeNull();
    const cover = await processor.process(await jpeg(3000, 1000), 'image/jpeg', 'cover');
    expect(cover.full).toMatchObject({ width: 1600, height: 533 });
  });

  it('refuses files that only look like photos', async () => {
    const fake = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0, 16, 0x4a, 0x46, 0x49, 0x46, 0, 1, 2, 3, 4, 5]);
    await expect(processor.process(fake, 'image/jpeg', 'post')).rejects.toBeInstanceOf(ImageRejected);
  });

  it('refuses a photo whose real type is not the declared one', async () => {
    const png = await sharp({ create: { width: 10, height: 10, channels: 3, background: '#fff' } }).png().toBuffer();
    await expect(processor.process(png, 'image/jpeg', 'post')).rejects.toThrow('The file type does not match.');
  });

  it('refuses photos with too many pixels, whatever their file size', async () => {
    const strict = createSharpProcessor({ maxPixels: 1000 });
    await expect(strict.process(await jpeg(100, 100), 'image/jpeg', 'post')).rejects.toThrow(/too large/);
  });
});
