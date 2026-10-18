import type { Ctx } from '../context';

export const URL_TTL_SECONDS = 900;

export interface UserCard {
  id: string;
  handle: string;
  fullName: string;
  avatarUrl: string | null;
  studentVerified: boolean;
}

/** Columns every card query selects. Needs `u` (users) and `av` (left join media on u.avatar_media_id). */
export const cardColumns = (u: string) =>
  `${u}.id as card_id, ${u}.handle as card_handle, ${u}.full_name as card_name, (${u}.verification_status = 'verified') as card_verified, av.object_key as card_avatar_key`;
export const CARD_COLUMNS = cardColumns('u');
export const cardJoin = (u: string) => `left join media av on av.id = ${u}.avatar_media_id and av.status = 'ready'`;
export const CARD_JOIN = cardJoin('u');

export interface CardRow { card_id: string; card_handle: string; card_name: string; card_verified: boolean; card_avatar_key: string | null }

/** Media is private: a URL is only ever minted for a payload the viewer is already allowed to see, and it expires. */
export async function signedUrl(ctx: Ctx, key: string | null): Promise<string | null> {
  if (!key || !ctx.storage) return null;
  return ctx.storage.signedUrl(key, URL_TTL_SECONDS);
}

export async function toCard(ctx: Ctx, r: CardRow): Promise<UserCard> {
  return {
    id: r.card_id, handle: r.card_handle, fullName: r.card_name,
    studentVerified: r.card_verified, avatarUrl: await signedUrl(ctx, r.card_avatar_key),
  };
}
