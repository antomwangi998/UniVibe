import { z } from 'zod';

export const STICKER_CATEGORIES = ['campus_vibes', 'funny_reactions', 'emotions', 'love_friendship', 'study_mode', 'tech_gaming', 'univibe_originals', 'seasonal'] as const;
export const LICENSES = ['univibe_original', 'licensed_provider', 'creator_submission'] as const;
export const MAX_STICKERS_PER_PACK = 60;
export const MAX_INSTALLED_PACKS = 50;
export const PREMIUM_PACK_KEY = 'premium.sticker_packs';

/** True when a WebP file is animated: an extended (VP8X) file with the animation flag set. Pure, so it can be tested alone. */
export function webpIsAnimated(b: Uint8Array): boolean {
  if (b.length < 21) return false;
  const tag = (from: number) => String.fromCharCode(b[from], b[from + 1], b[from + 2], b[from + 3]);
  return tag(0) === 'RIFF' && tag(8) === 'WEBP' && tag(12) === 'VP8X' && (b[20] & 0x02) !== 0;
}

/** Search words are trimmed, lower-cased and de-duplicated; at most 8, each short and plain. */
export const keywordsSchema = z.array(z.string().trim().toLowerCase().regex(/^[\p{L}\p{N}][\p{L}\p{N} '-]{0,23}$/u)).max(8).default([])
  .transform((k) => [...new Set(k)]);

export const packListSchema = z.object({
  category: z.enum(STICKER_CATEGORIES).optional(),
  q: z.string().trim().min(1).max(60).optional(),
  premium: z.enum(['free', 'premium']).optional(),
  featured: z.enum(['true']).optional(),
  limit: z.coerce.number().int().min(1).max(50).default(20),
  cursor: z.string().max(300).optional(),
}).strict();

export const stickerSearchSchema = z.object({ q: z.string().trim().min(1).max(40) }).strict();

export const createPackSchema = z.object({
  name: z.string().trim().min(2).max(60),
  slug: z.string().regex(/^[a-z0-9-]{3,60}$/),
  description: z.string().trim().max(300).default(''),
  category: z.enum(STICKER_CATEGORIES),
  coverMediaId: z.string().uuid(),
  premium: z.boolean().default(false),
  license: z.enum(LICENSES),
  sourceAttribution: z.string().trim().min(2).max(200),
}).strict();

export const updatePackSchema = z.object({
  name: z.string().trim().min(2).max(60).optional(),
  description: z.string().trim().max(300).optional(),
  category: z.enum(STICKER_CATEGORIES).optional(),
  premium: z.boolean().optional(),
  featured: z.boolean().optional(),
  sortOrder: z.number().int().min(-1000).max(1000).optional(),
}).strict().refine((o) => Object.keys(o).length > 0, 'Nothing to change.');

export const addStickerSchema = z.object({
  mediaId: z.string().uuid(),
  label: z.string().trim().min(1).max(40).optional(),
  keywords: keywordsSchema,
}).strict();

export const reorderSchema = z.object({ stickerIds: z.array(z.string().uuid()).min(1).max(MAX_STICKERS_PER_PACK) }).strict()
  .refine((o) => new Set(o.stickerIds).size === o.stickerIds.length, 'Each sticker may appear once.');

export const packStatusSchema = z.object({ status: z.enum(['published', 'unpublished', 'removed']) }).strict();

export const likePattern = (q: string): string => `%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;
