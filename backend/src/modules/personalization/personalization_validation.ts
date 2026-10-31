import { z } from 'zod';

/** Where a catalogue image may be applied. Kept in step with the check on theme_assets.destinations. */
export const DESTINATIONS = ['profile_cover', 'chat_background', 'story_background', 'note_background', 'avatar_decoration'] as const;
export const LICENSES = ['univibe_original', 'licensed_provider', 'creator_submission'] as const;
export const BUBBLE_SHAPES = ['rounded', 'soft', 'sharp', 'pill'] as const;

/** Premium chat looks carry the id prefix "p_" (see PremiumChatThemes.kt). The server uses the same rule as the app. */
export const isPremiumChatId = (id: string): boolean => id.startsWith('p_');

const id = z.string().regex(/^[a-z0-9_]{1,32}$/);

export const chatAppearanceSchema = z.object({
  conversationId: z.string().uuid().nullable().optional(),
  themeId: id.default('default'),
  wallpaperId: id.default('none'),
  wallpaperAssetId: z.string().uuid().nullable().optional(),
  blur: z.number().int().min(0).max(25).default(0),
  dim: z.number().int().min(0).max(80).default(0),
  bubbleShape: z.enum(BUBBLE_SHAPES).default('rounded'),
}).strict();
export type ChatAppearanceInput = z.infer<typeof chatAppearanceSchema>;

export const assetListSchema = z.object({
  category: z.string().regex(/^[a-z0-9-]{2,40}$/).optional(),
  q: z.string().trim().min(1).max(60).optional(),
  premium: z.enum(['free', 'premium']).optional(),
  destination: z.enum(DESTINATIONS).optional(),
  limit: z.coerce.number().int().min(1).max(50).default(20),
  cursor: z.string().max(300).optional(),
}).strict();

export const collectionNameSchema = z.object({ name: z.string().trim().min(1).max(40) }).strict();
export const collectionItemSchema = z.object({ assetId: z.string().uuid() }).strict();

/** Staff register an image that has ALREADY passed the normal upload checks (a ready `cover` photo they own). */
export const registerAssetSchema = z.object({
  mediaId: z.string().uuid(),
  categorySlug: z.string().regex(/^[a-z0-9-]{2,40}$/),
  title: z.string().trim().min(2).max(80),
  description: z.string().trim().max(500).default(''),
  sourceAttribution: z.string().trim().min(2).max(200),
  license: z.enum(LICENSES),
  premium: z.boolean().default(false),
  destinations: z.array(z.enum(DESTINATIONS)).min(1).max(5).default(['profile_cover', 'chat_background', 'story_background', 'note_background']),
}).strict();

export const assetStatusSchema = z.object({ status: z.enum(['published', 'unpublished', 'removed']) }).strict();

/** A search word may not act as a LIKE pattern. */
export const likePattern = (q: string): string => `%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;
