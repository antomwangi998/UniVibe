import { describe, expect, it } from 'vitest';
import {
  assetListSchema, chatAppearanceSchema, collectionNameSchema, isPremiumChatId, likePattern, registerAssetSchema,
} from '../src/modules/personalization/personalization_validation';

describe('chatAppearanceSchema', () => {
  it('fills defaults', () => {
    const r = chatAppearanceSchema.parse({});
    expect(r).toMatchObject({ themeId: 'default', wallpaperId: 'none', blur: 0, dim: 0, bubbleShape: 'rounded' });
  });
  it('refuses out of range blur and dim, bad shapes, free text ids and unknown fields', () => {
    for (const bad of [{ blur: 26 }, { blur: -1 }, { dim: 81 }, { bubbleShape: 'star' }, { themeId: 'A B' }, { themeId: '../x' }, { other: 1 }, { blur: 1.5 }]) {
      expect(chatAppearanceSchema.safeParse(bad).success).toBe(false);
    }
  });
  it('a conversation id must be a uuid', () => expect(chatAppearanceSchema.safeParse({ conversationId: 'abc' }).success).toBe(false));
  it('premium ids carry the p_ prefix', () => { expect(isPremiumChatId('p_aurora')).toBe(true); expect(isPremiumChatId('default')).toBe(false); });
});

describe('assetListSchema', () => {
  it('caps the page size and rejects unknown filters', () => {
    expect(assetListSchema.safeParse({ limit: '51' }).success).toBe(false);
    expect(assetListSchema.parse({}).limit).toBe(20);
    expect(assetListSchema.safeParse({ destination: 'profile_cover', premium: 'free' }).success).toBe(true);
    expect(assetListSchema.safeParse({ destination: 'toilet' }).success).toBe(false);
    expect(assetListSchema.safeParse({ sql: '1' }).success).toBe(false);
  });
});

describe('collections and registration', () => {
  it('trims and bounds a collection name', () => {
    expect(collectionNameSchema.parse({ name: '  Nature  ' }).name).toBe('Nature');
    expect(collectionNameSchema.safeParse({ name: '   ' }).success).toBe(false);
    expect(collectionNameSchema.safeParse({ name: 'x'.repeat(41) }).success).toBe(false);
  });
  it('a catalogue image must declare its licence and source', () => {
    const ok = { mediaId: '11111111-1111-4111-8111-111111111111', categorySlug: 'nature', title: 'Forest', sourceAttribution: 'UniVibe', license: 'univibe_original' };
    expect(registerAssetSchema.safeParse(ok).success).toBe(true);
    expect(registerAssetSchema.safeParse({ ...ok, license: 'found_on_pinterest' }).success).toBe(false);
    expect(registerAssetSchema.safeParse({ ...ok, sourceAttribution: '' }).success).toBe(false);
    expect(registerAssetSchema.safeParse({ ...ok, objectKey: 'x' }).success).toBe(false); // keys are never taken from the client
  });
  it('a search word cannot act as a LIKE pattern', () => expect(likePattern('50%_\\')).toBe('%50\\%\\_\\\\%'));
});
