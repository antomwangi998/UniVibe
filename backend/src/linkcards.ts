import { randomUUID } from 'node:crypto';
import type { Ctx } from './context';
import type { ImageProcessor } from './imaging';
import { fetchLinkImage, fetchLinkPreview, type LinkPreviewData } from './linkpreview';
import { shareInFlight, TtlCache } from './previewcache';

/** One link card ready to be stored: what the page says, and the picture already saved in private storage (if any). */
export interface LinkCard {
  preview: LinkPreviewData;
  image: { key: string; width: number; height: number } | null;
}

interface Picture { bytes: Uint8Array; contentType: string; width: number; height: number }

/** What the card readers use to reach the outside world; tests replace these. */
export interface CardSources {
  fetchPreview: (url: string) => Promise<LinkPreviewData | null>;
  fetchImage: typeof fetchLinkImage;
}
const realSources: CardSources = { fetchPreview: (url) => fetchLinkPreview(url), fetchImage: (url) => fetchLinkImage(url) };

const MINUTE = 60_000;
// The same address in many messages (a link going round a campus) is read once, not once per message. A page that
// could not be read is remembered for a shorter time, so a dead or hostile address is not hit again and again.
const pages = new TtlCache<LinkPreviewData | null>({ ttlMs: 30 * MINUTE, maxEntries: 500 });
const pageReads = new Map<string, Promise<LinkPreviewData | null>>();
const FAILED_TTL_MS = 10 * MINUTE;
// The re-encoded pictures (a few tens of KB each) are kept in memory only; every message still gets its own stored
// copy, so deleting one message never removes a picture another message shows.
const pictures = new TtlCache<Picture | null>({ ttlMs: 30 * MINUTE, maxEntries: 100, maxWeight: 8 * 1024 * 1024 }, (p) => p?.bytes.length ?? 0);
const pictureReads = new Map<string, Promise<Picture | null>>();

/** For tests: forget everything that was cached. */
export function clearLinkCardCache(): void { pages.clear(); pictures.clear(); pageReads.clear(); pictureReads.clear(); }

async function readPage(url: string, sources: CardSources): Promise<LinkPreviewData | null> {
  const hit = pages.get(url);
  if (hit !== undefined) return hit;
  return shareInFlight(pageReads, url, async () => {
    const preview = await sources.fetchPreview(url);
    pages.set(url, preview, preview ? undefined : FAILED_TTL_MS);
    return preview;
  });
}

async function readPicture(imageUrl: string, images: ImageProcessor, sources: CardSources): Promise<Picture | null> {
  const hit = pictures.get(imageUrl);
  if (hit !== undefined) return hit;
  return shareInFlight(pictureReads, imageUrl, async () => {
    let picture: Picture | null = null;
    try {
      const got = await sources.fetchImage(imageUrl);
      if (got) {
        const out = await images.process(got.bytes, got.contentType, 'linkpreview'); // re-encoded: no metadata, no odd formats
        picture = { bytes: out.full.bytes, contentType: out.full.contentType, width: out.full.width, height: out.full.height };
      }
    } catch { picture = null; }
    pictures.set(imageUrl, picture, picture ? undefined : FAILED_TTL_MS);
    return picture;
  });
}

/**
 * Reads the card for one address: the page (title, description, site) and, when storage is on, its picture saved under
 * a new private key. The picture is optional: if it cannot be fetched, is not a real image or cannot be stored, the card
 * is text only. Returns null when the page gives nothing worth showing (or must not be read at all, see linkpreview.ts).
 */
export async function readLinkCard(
  ctx: Pick<Ctx, 'storage'>, images: ImageProcessor | undefined, url: string, sources: CardSources = realSources,
): Promise<LinkCard | null> {
  const preview = await readPage(url, sources);
  if (!preview) return null;
  let image: LinkCard['image'] = null;
  if (preview.imageUrl && ctx.storage && images) {
    const picture = await readPicture(preview.imageUrl, images, sources);
    if (picture) {
      try {
        const key = `link-previews/${randomUUID()}.webp`;
        await ctx.storage.write(key, picture.bytes, picture.contentType);
        image = { key, width: picture.width, height: picture.height };
      } catch { image = null; }
    }
  }
  return { preview, image };
}

/** Deletes stored picture files in the background; a failure only leaves a file behind, never an error for the person. */
export function removeCardFiles(ctx: Pick<Ctx, 'storage'>, keys: (string | null | undefined)[]): void {
  const list = keys.filter((k): k is string => Boolean(k));
  if (list.length && ctx.storage) void ctx.storage.remove(list).catch(() => undefined);
}
