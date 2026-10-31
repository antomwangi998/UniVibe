import { beforeEach, describe, expect, it } from 'vitest';
import type { ImageProcessor } from '../src/imaging';
import { clearLinkCardCache, readLinkCard, type CardSources } from '../src/linkcards';
import type { LinkPreviewData } from '../src/linkpreview';

const PAGE: LinkPreviewData = { host: 'example.com', title: 'Hello', description: null, siteName: null, imageUrl: 'https://example.com/a.png' };

function fakeStorage() {
  const written: string[] = [];
  return { written, storage: { write: async (key: string) => { written.push(key); }, remove: async () => undefined } as never };
}
const images = (counter: { n: number }): ImageProcessor => ({
  process: async () => { counter.n++; const v = { bytes: new Uint8Array(10), contentType: 'image/webp', width: 40, height: 20 }; return { full: v, thumb: null }; },
});
function sources(counts: { page: number; image: number }, page: LinkPreviewData | null = PAGE): CardSources {
  return {
    fetchPreview: async () => { counts.page++; return page; },
    fetchImage: async () => { counts.image++; return { bytes: new Uint8Array([1]), contentType: 'image/png' }; },
  };
}

describe('readLinkCard', () => {
  beforeEach(() => clearLinkCardCache());

  it('reads a page once for many messages, but gives every message its own stored picture', async () => {
    const counts = { page: 0, image: 0 };
    const processed = { n: 0 };
    const { storage, written } = fakeStorage();
    const a = await readLinkCard({ storage }, images(processed), 'https://example.com/x', sources(counts));
    const b = await readLinkCard({ storage }, images(processed), 'https://example.com/x', sources(counts));
    expect(a?.preview.title).toBe('Hello');
    expect(counts).toEqual({ page: 1, image: 1 });
    expect(processed.n).toBe(1);
    expect(written).toHaveLength(2);
    expect(a?.image?.key).not.toBe(b?.image?.key);
    expect(a?.image).toMatchObject({ width: 40, height: 20 });
  });

  it('remembers a page that gave nothing, so a dead address is not fetched again straight away', async () => {
    const counts = { page: 0, image: 0 };
    const { storage } = fakeStorage();
    expect(await readLinkCard({ storage }, undefined, 'https://dead.example/x', sources(counts, null))).toBeNull();
    expect(await readLinkCard({ storage }, undefined, 'https://dead.example/x', sources(counts, null))).toBeNull();
    expect(counts.page).toBe(1);
  });

  it('gives a text-only card when storage is off, the picture is bad or storing it fails', async () => {
    const counts = { page: 0, image: 0 };
    expect((await readLinkCard({ storage: null }, images({ n: 0 }), 'https://example.com/1', sources(counts)))?.image).toBeNull();
    clearLinkCardCache();
    const failing = { storage: { write: async () => { throw new Error('disk'); }, remove: async () => undefined } as never };
    expect((await readLinkCard(failing, images({ n: 0 }), 'https://example.com/2', sources(counts)))?.image).toBeNull();
    clearLinkCardCache();
    const broken: ImageProcessor = { process: async () => { throw new Error('not an image'); } };
    const card = await readLinkCard(fakeStorage(), broken, 'https://example.com/3', sources(counts));
    expect(card?.preview.title).toBe('Hello');
    expect(card?.image).toBeNull();
  });
});
