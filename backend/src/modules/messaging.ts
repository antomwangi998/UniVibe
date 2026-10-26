import type { FastifyInstance, FastifyRequest } from 'fastify';
import type { PoolClient } from 'pg';
import { z } from 'zod';
import { acceptedFollow, blockedBetween, notSuspended } from '../access';
import type { AuthUser, Ctx } from '../context';
import { withTx } from '../db';
import { HttpError } from '../errors';
import { idParam, pageSchema } from '../http';
import type { Hub } from '../realtime';
import { cardColumns, cardJoin, type CardRow, signedUrl, toCard } from './cards';
import { enqueue } from '../jobs';
import { firstLink } from '../linkpreview';
import { groupInOrder } from '../group';
import { createHash } from 'node:crypto';
import { notify } from './notify';
import { presenceOf } from './presence';

/** Wipes positions that are no longer being shared (stopped, or past their end time). Safe to run any time. */
export async function purgeLiveLocations(pool: { query: (sql: string) => Promise<{ rowCount: number | null }> }): Promise<{ cleared: number }> {
  const res = await pool.query('update live_locations set lat = null, lng = null where lat is not null and (ended_at is not null or expires_at <= now())');
  return { cleared: res.rowCount ?? 0 };
}

export const EDIT_WINDOW_MINUTES = 15;
const MAX_GROUP_MEMBERS = 256;

export const sendSchema = z.object({
  content: z.string().trim().max(4000).default(''),
  clientId: z.string().min(1).max(64).optional(),
  replyToId: z.string().uuid().optional(),
  mediaIds: z.array(z.string().uuid()).max(4).default([]),
  /** "Send current location": one fixed point. Rounded to about 10 m before it is stored. */
  location: z.object({
    latitude: z.number().min(-90).max(90),
    longitude: z.number().min(-180).max(180),
    label: z.string().trim().max(80).nullable().default(null),
    /** Keep updating this position for 15 minutes, 1 hour or 8 hours. Absent means a one-time position. */
    liveMinutes: z.union([z.literal(15), z.literal(60), z.literal(480)]).optional(),
  }).strict().optional(),
}).strict();

/** About 10 m. Nobody needs more than that to find a person, and an exact position is never stored. */
const roundCoordinate = (n: number) => Math.round(n * 1e4) / 1e4;

export interface MessageView {
  id: string; seq: number; conversationId: string; senderId: string | null; content: string | null;
  deleted: boolean; createdAt: Date; editedAt: Date | null;
  replyTo: { id: string; senderId: string | null; preview: string | null } | null;
  media: { id: string; kind: string; contentType: string; url: string | null; width: number | null; height: number | null; durationMs: number | null; waveform: number[] | null; fileName: string | null; sizeBytes: number | null }[];
  forwarded: boolean;
  /** Set when this message answers a Story. `available` is false once the Story has expired or been deleted; its content is never copied here. */
  story: { id: string | null; kind: 'photo' | 'video' | 'text'; available: boolean } | null;
  /** A shared event. Only the id travels in the message; each reader's app asks for the event itself, so people who may not see it get "not available". */
  eventId: string | null;
  /** A shared place (fixed point). Null for every other kind of message. */
  location: { latitude: number; longitude: number; label: string | null } | null;
  /** Set when the place is shared live. `active` turns false when sharing stops or runs out, and `location` is then null. */
  live: { active: boolean; endsAt: Date; updatedAt: Date | null } | null;
  linkPreview: { url: string; host: string; title: string | null; description: string | null; siteName: string | null; imageUrl: string | null; imageWidth: number | null; imageHeight: number | null } | null;
  /** Counts per reaction, e.g. { heart: 2 }. `myReaction` is present only on responses made for one viewer. */
  reactions: Record<string, number>;
  myReaction?: string | null;
}

type Q = Pick<PoolClient, 'query'>;

async function membership(q: Q, conversationId: string, userId: string) {
  const { rows } = await q.query<{ kind: 'direct' | 'group'; role: string; last_read_seq: string; only_admins_can_message: boolean; only_admins_can_add_members: boolean }>(
    `select c.kind, m.role, m.last_read_seq, c.only_admins_can_message, c.only_admins_can_add_members from conversations c
       join conversation_members m on m.conversation_id = c.id and m.user_id = $2 where c.id = $1`,
    [conversationId, userId],
  );
  // Not a member looks exactly like "no such conversation".
  if (!rows[0]) throw new HttpError(404, 'not_found', 'Not found.');
  return rows[0];
}

/** Everyone in the conversation that should hear about something the actor did (blocked pairs are left out). */
async function audience(q: Q, conversationId: string, actorId: string): Promise<string[]> {
  const { rows } = await q.query<{ user_id: string }>(
    `select m.user_id from conversation_members m where m.conversation_id = $1
        and (m.user_id = $2 or not ${blockedBetween('m.user_id', '$2')})`,
    [conversationId, actorId],
  );
  return rows.map((r) => r.user_id);
}

async function toMessageViews(ctx: Ctx, rows: Record<string, unknown>[], viewerId?: string): Promise<MessageView[]> {
  const ids = rows.map((r) => r.id as string);
  const liveRows = ids.length
    ? (await ctx.pool.query<{ message_id: string; lat: number | null; lng: number | null; updated_at: Date | null; expires_at: Date; active: boolean }>(
        'select message_id, lat, lng, updated_at, expires_at, (ended_at is null and expires_at > now()) as active from live_locations where message_id = any($1::uuid[])', [ids])).rows
    : [];
  const liveBy = new Map(liveRows.map((l) => [l.message_id, l]));
  const reactionRows = ids.length
    ? (await ctx.pool.query<{ message_id: string; user_id: string; reaction: string }>(
        'select message_id, user_id, reaction from message_reactions where message_id = any($1::uuid[])', [ids])).rows
    : [];
  const media = ids.length
    ? (await ctx.pool.query(
        "select id, message_id, kind, content_type, object_key, position, width, height, duration_ms, waveform, file_name, size_bytes from media where message_id = any($1::uuid[]) and status = 'ready' order by position",
        [ids])).rows
    : [];
  const previews = ids.length
    ? (await ctx.pool.query<{ message_id: string; url: string; host: string; title: string | null; description: string | null; site_name: string | null; image_key: string | null; image_width: number | null; image_height: number | null }>(
        'select message_id, url, host, title, description, site_name, image_key, image_width, image_height from message_link_previews where message_id = any($1::uuid[])', [ids])).rows
    : [];
  const previewOf = new Map(previews.map((p) => [p.message_id, p] as const));
  // The picture is private like all media: a short-lived link is made for each viewer-visible card.
  const previewImage = new Map(await Promise.all(previews.map(async (p) => [p.message_id, await signedUrl(ctx, p.image_key)] as const)));
  // Every attachment of a message, in position order (see group.ts for why this is not done inside the async callback).
  const byMessage = await groupInOrder(media, async (m): Promise<MessageView['media'][number]> => ({
    id: m.id, kind: m.kind, contentType: m.content_type, url: await signedUrl(ctx, m.object_key), width: m.width ?? null, height: m.height ?? null,
    durationMs: m.duration_ms ?? null, waveform: m.waveform ?? null, fileName: m.file_name ?? null, sizeBytes: m.kind === 'document' ? Number(m.size_bytes) : null,
  }));
  return rows.map((r) => {
    const deleted = r.status === 'deleted';
    return {
      id: r.id as string, seq: Number(r.seq), conversationId: r.conversation_id as string, senderId: r.sender_id as string | null,
      content: deleted ? null : (r.content as string), deleted, createdAt: r.created_at as Date, editedAt: r.edited_at as Date | null,
      replyTo: r.reply_id ? {
        id: r.reply_id as string, senderId: r.reply_sender as string | null,
        preview: r.reply_status === 'active' ? String(r.reply_content).slice(0, 80) : null,
      } : null,
      media: deleted ? [] : byMessage.get(r.id as string) ?? [],
      forwarded: Boolean(r.forwarded),
      story: !deleted && r.story_kind ? { id: (r.story_id as string | null) ?? null, kind: r.story_kind as 'photo' | 'video' | 'text', available: Boolean(r.story_live) } : null,
      eventId: deleted ? null : (r.event_id as string | null) ?? null,
      live: (() => { const l = deleted ? undefined : liveBy.get(r.id as string); return l ? { active: l.active, endsAt: l.expires_at, updatedAt: l.updated_at } : null; })(),
      location: (() => {
        const l = deleted ? undefined : liveBy.get(r.id as string);
        if (l) return l.active && l.lat != null && l.lng != null ? { latitude: Number(l.lat), longitude: Number(l.lng), label: null } : null;
        return deleted || r.location_lat == null ? null : { latitude: Number(r.location_lat), longitude: Number(r.location_lng), label: (r.location_label as string | null) ?? null };
      })(),
      // Shown only while the stored address is still the first one in the text (an edit can change it).
      linkPreview: (() => {
        const p = deleted ? undefined : previewOf.get(r.id as string);
        return p && firstLink(r.content as string) === p.url
          ? { url: p.url, host: p.host, title: p.title, description: p.description, siteName: p.site_name, imageUrl: previewImage.get(p.message_id) ?? null, imageWidth: p.image_width, imageHeight: p.image_height }
          : null;
      })(),
      reactions: deleted ? {} : reactionRows.filter((x) => x.message_id === r.id).reduce<Record<string, number>>((acc, x) => { acc[x.reaction] = (acc[x.reaction] ?? 0) + 1; return acc; }, {}),
      ...(viewerId ? { myReaction: deleted ? null : reactionRows.find((x) => x.message_id === r.id && x.user_id === viewerId)?.reaction ?? null } : {}),
    };
  });
}

const MESSAGE_SELECT = `
  select m.id, m.seq, m.conversation_id, m.sender_id, m.content, m.status, m.created_at, m.edited_at, m.forwarded, m.event_id,
         m.location_lat, m.location_lng, m.location_label,
         r.id as reply_id, r.sender_id as reply_sender, r.status as reply_status, r.content as reply_content,
         m.story_id, m.story_kind, (sr.id is not null) as story_live
    from messages m left join messages r on r.id = m.reply_to_id
    left join stories sr on sr.id = m.story_id and sr.expires_at > now()`;

/** A short stable key for an address, so the same link in the same message is only ever read once. */
const linkKey = (url: string) => createHash('sha1').update(url).digest('hex').slice(0, 16);

export async function loadMessage(ctx: Ctx, id: string, viewerId?: string): Promise<MessageView> {
  const { rows } = await ctx.pool.query(`${MESSAGE_SELECT} where m.id = $1`, [id]);
  return (await toMessageViews(ctx, rows, viewerId))[0];
}

/**
 * The one place a message is created, used by REST and WebSocket alike.
 * It is stored first and announced second, so nobody is told about a message that failed to save.
 * A retry with the same clientId returns the stored message and creates nothing new.
 */
export async function sendMessage(ctx: Ctx, hub: Hub, me: AuthUser, conversationId: string, raw: unknown, opts: { forwarded?: boolean; story?: { id: string; kind: 'photo' | 'video' | 'text' }; eventId?: string } = {}) {
  const input = sendSchema.parse(raw ?? {});
  ctx.limit(`msg:${me.id}`, 40, 60);
  if (!input.content && !input.mediaIds.length && !opts.eventId && !input.location) throw new HttpError(400, 'validation', 'Write a message, attach a photo or a file, or record a voice message.');
  if (input.location && input.mediaIds.length) throw new HttpError(422, 'validation', 'A location is sent on its own.');
  const isLive = Boolean(input.location?.liveMinutes);
  // A live place lives only in live_locations (so it can be wiped); a one-time place is stored on the message.
  const place = input.location && !isLive ? { lat: roundCoordinate(input.location.latitude), lng: roundCoordinate(input.location.longitude), label: input.location.label } : null;

  const { id, created, superseded } = await withTx(ctx.pool, async (client) => {
    const member = await membership(client, conversationId, me.id);
    if (member.kind === 'group' && member.only_admins_can_message && member.role === 'member') {
      throw new HttpError(403, 'forbidden', 'Only admins can send messages in this group.');
    }
    if (member.kind === 'direct') {
      const other = (await client.query<{ id: string; permission: string; suspended: boolean; same_uni: boolean }>(
        `select u.id, coalesce(ps.message_permission, 'everyone') as permission,
                not ${notSuspended('u')} as suspended, u.university_id = $2 as same_uni
           from conversation_members m join users u on u.id = m.user_id left join privacy_settings ps on ps.user_id = u.id
          where m.conversation_id = $1 and m.user_id <> $3`, [conversationId, me.universityId, me.id])).rows[0];
      const blocked = other && (await client.query(`select ${blockedBetween('$1::uuid', '$2::uuid')} as b`, [me.id, other.id])).rows[0].b;
      const follows = other && (await client.query(`select ${acceptedFollow('$1::uuid', '$2::uuid')} as f`, [me.id, other.id])).rows[0].f;
      const denied = !other || blocked || other.suspended || !other.same_uni || other.permission === 'nobody'
        || (other.permission === 'followers' && !follows);
      // One generic answer, so a sender cannot tell a block from a privacy setting.
      if (denied) throw new HttpError(403, 'cannot_message', 'You cannot message this person.');
    }
    if (input.replyToId) {
      const ok = await client.query('select 1 from messages where id = $1 and conversation_id = $2', [input.replyToId, conversationId]);
      if (!ok.rowCount) throw new HttpError(422, 'validation', 'The message you are replying to is not in this conversation.');
    }
    const inserted = await client.query<{ id: string; seq: string }>(
      `insert into messages (conversation_id, sender_id, client_id, content, reply_to_id, forwarded, story_id, story_kind, event_id, location_lat, location_lng, location_label) values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
       on conflict (conversation_id, sender_id, client_id) where client_id is not null do nothing returning id, seq`,
      [conversationId, me.id, input.clientId ?? null, input.content, input.replyToId ?? null, opts.forwarded ?? false, opts.story?.id ?? null, opts.story?.kind ?? null, opts.eventId ?? null, place?.lat ?? null, place?.lng ?? null, place?.label ?? null],
    );
    if (!inserted.rowCount) {
      const existing = await client.query<{ id: string }>(
        'select id from messages where conversation_id = $1 and sender_id = $2 and client_id = $3', [conversationId, me.id, input.clientId]);
      return { id: existing.rows[0].id, created: false, superseded: [] as { id: string; conversationId: string }[] };
    }
    const messageId = inserted.rows[0].id;
    // The preview is read in the background, so sending is never slowed down. The job is written with the message (outbox).
    const link = firstLink(input.content);
    if (link) await enqueue(client, 'link.preview', { messageId, url: link }, { dedupeKey: `${messageId}:${linkKey(link)}`, maxAttempts: 2 });
    if (input.mediaIds.length) {
      const attached = await client.query(
        `update media m set message_id = $1, position = x.ord - 1
           from unnest($2::uuid[]) with ordinality as x(id, ord)
          where m.id = x.id and m.owner_id = $3 and m.purpose = 'message' and m.kind in ('image', 'audio', 'document') and m.status = 'ready' and m.message_id is null and m.channel_message_id is null`,
        [messageId, input.mediaIds, me.id]);
      if (attached.rowCount !== input.mediaIds.length) throw new HttpError(422, 'invalid_media', 'One of the attachments is not available.');
      // A voice message stands alone: no other attachments with it.
      const audio = await client.query("select 1 from media where message_id = $1 and kind = 'audio'", [messageId]);
      if (audio.rowCount && input.mediaIds.length > 1) throw new HttpError(422, 'invalid_media', 'A voice message cannot have other attachments.');
      // A document is sent on its own too (with a caption if wanted), never mixed with photos or other documents.
      const doc = await client.query("select 1 from media where message_id = $1 and kind = 'document'", [messageId]);
      if (doc.rowCount && input.mediaIds.length > 1) throw new HttpError(422, 'invalid_media', 'A document is sent on its own.');
    }
    // Live place: one active share per person. Starting a new one stops the old one, which is wiped on the spot.
    const superseded: { id: string; conversationId: string }[] = [];
    if (input.location?.liveMinutes) {
      const old = await client.query<{ message_id: string; conversation_id: string }>(
        `update live_locations ll set ended_at = now(), lat = null, lng = null from messages m
          where m.id = ll.message_id and ll.user_id = $1 and ll.ended_at is null and ll.expires_at > now() returning ll.message_id, m.conversation_id`, [me.id]);
      for (const r of old.rows) superseded.push({ id: r.message_id, conversationId: r.conversation_id });
      await client.query(
        'insert into live_locations (message_id, user_id, lat, lng, updated_at, expires_at) values ($1, $2, $3, $4, now(), now() + make_interval(mins => $5))',
        [messageId, me.id, roundCoordinate(input.location.latitude), roundCoordinate(input.location.longitude), input.location.liveMinutes]);
    }
    await client.query('update conversations set last_message_at = now() where id = $1', [conversationId]);
    await client.query(
      'update conversation_members set last_read_seq = greatest(last_read_seq, $3) where conversation_id = $1 and user_id = $2',
      [conversationId, me.id, inserted.rows[0].seq]);
    const others = await audience(client, conversationId, me.id);
    // Muted people get no alert and no feed entry for this conversation; the message itself is stored and delivered as usual.
    const muted = new Set((await client.query<{ user_id: string }>(
      'select user_id from conversation_members where conversation_id = $1 and muted_until is not null and muted_until > now()', [conversationId])).rows.map((r) => r.user_id));
    // A new message brings an archived conversation back to the inbox.
    await client.query('update conversation_members set archived = false where conversation_id = $1 and user_id <> $2 and archived', [conversationId, me.id]);
    for (const userId of others.filter((u) => u !== me.id && !muted.has(u))) {
      // No message text in notifications. One feed entry per conversation until it is read (see the dedupe index).
      await notify(client, { recipientId: userId, actorId: me.id, type: 'message', entityType: 'conversation', entityId: conversationId, metadata: { conversationId } });
      // One push job per message and recipient, written in this transaction (outbox): it exists if and only if the message does.
      await enqueue(client, 'push.message', { messageId: inserted.rows[0].id, recipientId: userId }, { dedupeKey: `${inserted.rows[0].id}:${userId}` });
    }
    return { id: messageId, created: true, superseded };
  });

  for (const old of superseded) {
    const view = await loadMessage(ctx, old.id);
    hub.sendMany(await audience(ctx.pool, old.conversationId, me.id), { type: 'message:updated', conversationId: old.conversationId, message: view });
  }
  const message = await loadMessage(ctx, id);
  if (created) {
    const recipients = await audience(ctx.pool, conversationId, me.id);
    hub.sendMany(recipients, { type: 'message:new', conversationId, message });
    // Whoever is connected right now has received it: record that and tell the sender.
    const live = recipients.filter((u) => u !== me.id && hub.isOnline(u));
    if (live.length) {
      await ctx.pool.query(
        'update conversation_members set last_delivered_seq = greatest(last_delivered_seq, $3) where conversation_id = $1 and user_id = any($2::uuid[])',
        [conversationId, live, message.seq]);
      for (const userId of live) hub.send(me.id, { type: 'message:delivered', conversationId, userId, upToSeq: message.seq });
    }
  }
  return { message, created };
}

export async function markRead(ctx: Ctx, hub: Hub, me: AuthUser, conversationId: string, upToSeq?: number) {
  await membership(ctx.pool, conversationId, me.id);
  const target = upToSeq ?? Number((await ctx.pool.query('select coalesce(max(seq), 0) as s from messages where conversation_id = $1', [conversationId])).rows[0].s);
  const res = await ctx.pool.query<{ last_read_seq: string }>(
    `update conversation_members set last_read_seq = greatest(last_read_seq, least($3::bigint, (select coalesce(max(seq), 0) from messages where conversation_id = $1)))
      where conversation_id = $1 and user_id = $2 returning last_read_seq`,
    [conversationId, me.id, target]);
  await ctx.pool.query("delete from notifications where recipient_id = $1 and type = 'message' and entity_id = $2", [me.id, conversationId]);
  await ctx.pool.query('update conversation_members set marked_unread = false where conversation_id = $1 and user_id = $2', [conversationId, me.id]);
  const seq = Number(res.rows[0].last_read_seq);
  // With read receipts off, other people are not told how far this person has read.
  const receipts = (await ctx.pool.query('select coalesce((select read_receipts from privacy_settings where user_id = $1), true) as enabled', [me.id])).rows[0].enabled as boolean;
  if (receipts) hub.sendMany((await audience(ctx.pool, conversationId, me.id)).filter((u) => u !== me.id), { type: 'message:read', conversationId, userId: me.id, upToSeq: seq });
  // Other devices of the same user learn the conversation is read.
  hub.send(me.id, { type: 'conversation:updated', conversationId });
  return seq;
}

export async function typingSignal(ctx: Ctx, hub: Hub, me: AuthUser, conversationId: string, on: boolean) {
  await membership(ctx.pool, conversationId, me.id);
  // With typing indicators off, other people are never told this person is typing.
  const allowed = (await ctx.pool.query('select coalesce((select typing_indicators from privacy_settings where user_id = $1), true) as enabled', [me.id])).rows[0].enabled as boolean;
  if (!allowed) return;
  const targets = (await audience(ctx.pool, conversationId, me.id)).filter((u) => u !== me.id);
  hub.typing(conversationId, me.id, on, (typing) => hub.sendMany(targets, { type: 'typing:update', conversationId, userId: me.id, typing }));
}

export { membership, audience };

const createSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('direct'), userId: z.string().uuid() }).strict(),
  z.object({ type: z.literal('group'), title: z.string().trim().min(1).max(40), memberIds: z.array(z.string().uuid()).min(1).max(255) }).strict(),
]);
const memberIdsSchema = z.object({ userIds: z.array(z.string().uuid()).min(1).max(50) }).strict();

export function messagingRoutes(ctx: Ctx, hub: Hub) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;
    const param = (req: FastifyRequest, name = 'id') => idParam((req.params as Record<string, string>)[name]);

    /** Of the requested people, those the creator may actually involve. Everyone else is skipped without saying why. */
    async function eligibleUsers(me: AuthUser, ids: string[]): Promise<string[]> {
      const { rows } = await pool.query<{ id: string }>(
        `select u.id from users u where u.id = any($3::uuid[]) and u.id <> $1 and u.university_id = $2 and ${notSuspended('u')}
            and not ${blockedBetween('$1', 'u.id')}`, [me.id, me.universityId, [...new Set(ids)]]);
      return rows.map((r) => r.id);
    }

    app.post('/conversations', async (req, reply) => {
      const me = await ctx.auth(req);
      const b = createSchema.parse(req.body ?? {});
      if (b.type === 'direct') {
        ctx.limit(`conv:${me.id}`, 30, 3600);
        const [other] = await eligibleUsers(me, [b.userId]);
        if (!other) throw new HttpError(404, 'not_found', 'Not found.');
        const perm = (await pool.query(
          `select coalesce(ps.message_permission, 'everyone') as p, ${acceptedFollow('$2::uuid', '$1::uuid')} as follows
             from users u left join privacy_settings ps on ps.user_id = u.id where u.id = $1`, [other, me.id])).rows[0];
        if (perm.p === 'nobody' || (perm.p === 'followers' && !perm.follows)) throw new HttpError(403, 'cannot_message', 'You cannot message this person.');
        const key = [me.id, other].sort().join(':');
        const result = await withTx(pool, async (client) => {
          const made = await client.query<{ id: string }>(
            "insert into conversations (kind, created_by, direct_key) values ('direct', $1, $2) on conflict (direct_key) do nothing returning id", [me.id, key]);
          if (!made.rowCount) {
            return { id: (await client.query<{ id: string }>('select id from conversations where direct_key = $1', [key])).rows[0].id, created: false };
          }
          await client.query(
            "insert into conversation_members (conversation_id, user_id, role) values ($1, $2, 'member'), ($1, $3, 'member')", [made.rows[0].id, me.id, other]);
          return { id: made.rows[0].id, created: true };
        });
        return reply.status(result.created ? 201 : 200).send(await conversationView(me, result.id));
      }
      ctx.limit(`group:${me.id}`, 10, 3600);
      const members = await eligibleUsers(me, b.memberIds);
      if (!members.length) throw new HttpError(422, 'validation', 'Choose at least one person you can add.');
      const id = await withTx(pool, async (client) => {
        const made = await client.query<{ id: string }>("insert into conversations (kind, title, created_by) values ('group', $1, $2) returning id", [b.title, me.id]);
        const convId = made.rows[0].id;
        await client.query("insert into conversation_members (conversation_id, user_id, role) values ($1, $2, 'owner')", [convId, me.id]);
        await client.query("insert into conversation_members (conversation_id, user_id) select $1, unnest($2::uuid[])", [convId, members]);
        for (const userId of members) await notify(client, { recipientId: userId, actorId: me.id, type: 'group_invite', entityType: 'conversation', entityId: convId, metadata: { conversationId: convId } });
        return convId;
      });
      hub.sendMany(members, { type: 'conversation:updated', conversationId: id });
      return reply.status(201).send({ ...(await conversationView(me, id)), skipped: b.memberIds.length - members.length > 0 });
    });

    async function conversationView(me: AuthUser, id: string) {
      await membership(pool, id, me.id);
      const conv = (await pool.query(
        `select c.id, c.kind, c.title, c.created_at, c.last_message_at, c.description, c.created_by,
                c.only_admins_can_message, c.only_admins_can_edit_info, c.only_admins_can_add_members,
                (select pm.object_key from media pm where pm.id = c.photo_media_id and pm.status = 'ready') as photo_key,
                (select p.id from messages p where p.id = c.pinned_message_id and p.status = 'active') as pinned_message_id
           from conversations c where c.id = $1`, [id])).rows[0];
      const mine = (await pool.query('select muted_until, archived, pinned from conversation_members where conversation_id = $1 and user_id = $2', [id, me.id])).rows[0];
      const { rows } = await pool.query(
        `select m.role, m.last_read_seq, m.last_delivered_seq, ${cardColumns('u')}, coalesce(ps.show_online, true) as show_online,
                coalesce(ps.read_receipts, true) as read_receipts from conversation_members m
           join users u on u.id = m.user_id ${cardJoin('u')} left join privacy_settings ps on ps.user_id = u.id
          where m.conversation_id = $1 order by m.joined_at, u.id`, [id]);
      const presence = await presenceOf(ctx, hub, me.id, rows.map((r) => r.card_id as string));
      return {
        id: conv.id, kind: conv.kind, title: conv.title, createdAt: conv.created_at, lastMessageAt: conv.last_message_at,
        description: conv.description ?? '', createdBy: conv.created_by, photoUrl: await signedUrl(ctx, conv.photo_key ?? null),
        permissions: { onlyAdminsCanMessage: conv.only_admins_can_message, onlyAdminsCanEditInfo: conv.only_admins_can_edit_info, onlyAdminsCanAddMembers: conv.only_admins_can_add_members },
        // The viewer's own settings for this conversation.
        mutedUntil: mine.muted_until, archived: mine.archived, pinned: mine.pinned, pinnedMessageId: conv.pinned_message_id,
        members: await Promise.all(rows.map(async (r) => ({
          ...(await toCard(ctx, r as CardRow)), role: r.role, // Other people's read position is hidden when they turned read receipts off.
          lastReadSeq: r.card_id === me.id || r.read_receipts ? Number(r.last_read_seq) : 0, lastDeliveredSeq: Number(r.last_delivered_seq),
          // Online state is shown only for people who allow it (always to themselves).
          online: r.card_id === me.id ? hub.isOnline(me.id) : r.show_online ? hub.isOnline(r.card_id) : null,
          // When they were last connected; null for someone who keeps their activity private (or has not connected yet).
          lastActiveAt: presence.get(r.card_id)?.lastActiveAt ?? null,
        }))),
      };
    }

    app.get('/conversations', async (req) => {
      const me = await ctx.auth(req);
      const q = pageSchema.parse(req.query);
      const archivedOnly = (req.query as { archived?: string }).archived === 'true';
      let cur: { t: string; id: string } | null = null;
      if (q.cursor) {
        try { cur = JSON.parse(Buffer.from(q.cursor, 'base64url').toString()); } catch { throw new HttpError(400, 'invalid_cursor', 'That page cursor is not valid.'); }
      }
      const { rows } = await pool.query(
        `select c.id, c.kind, c.title, c.last_message_at,
                (select pm.object_key from media pm where pm.id = c.photo_media_id and pm.status = 'ready') as photo_key, cm.muted_until, cm.archived, cm.pinned, cm.marked_unread,
                to_char(c.last_message_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') as cursor_t,
                (select count(*) from messages x where x.conversation_id = c.id and x.seq > cm.last_read_seq
                    and x.sender_id is distinct from $1 and x.status = 'active') as unread,
                lm.id as lm_id, lm.sender_id as lm_sender, lm.content as lm_content, lm.status as lm_status, lm.created_at as lm_created,
                (select count(*) from media md where md.message_id = lm.id and md.status = 'ready' and md.kind = 'image') as lm_media,
                (lm.event_id is not null) as lm_event, (lm.location_lat is not null or exists (select 1 from live_locations ll where ll.message_id = lm.id)) as lm_location,
                (select count(*) from media md where md.message_id = lm.id and md.status = 'ready' and md.kind = 'audio') as lm_audio,
                (select count(*) from media md where md.message_id = lm.id and md.status = 'ready' and md.kind = 'document') as lm_docs,
                (select su.full_name from users su where su.id = lm.sender_id) as lm_sender_name,
                (select count(*) from conversation_members mc where mc.conversation_id = c.id) as member_count,
                -- Up to six other members of a group (names only): used for typing labels, collage avatars and untitled groups.
                (select coalesce(json_agg(json_build_object('id', t.id, 'name', t.name)), '[]'::json)
                   from (select u2.id, u2.full_name as name from conversation_members m3 join users u2 on u2.id = m3.user_id
                          where m3.conversation_id = c.id and m3.user_id <> $1 and c.kind = 'group'
                          order by m3.joined_at, u2.id limit 6) t) as members_preview,
                -- Only for our own last message: how far it got, from the other members' delivery and read cursors.
                case when lm.sender_id = $1 then (select case when bool_and(m5.last_read_seq >= lm.seq and coalesce(ps5.read_receipts, true)) then 'read'
                                                              when bool_and(m5.last_delivered_seq >= lm.seq) then 'delivered' else 'sent' end
                                                    from conversation_members m5 left join privacy_settings ps5 on ps5.user_id = m5.user_id
                                                    where m5.conversation_id = c.id and m5.user_id <> $1) end as lm_delivery,
                ${cardColumns('o')}
           from conversation_members cm join conversations c on c.id = cm.conversation_id
           left join lateral (select * from messages x where x.conversation_id = c.id and x.seq > cm.cleared_seq order by x.seq desc limit 1) lm on true
           left join lateral (select u.* from conversation_members m2 join users u on u.id = m2.user_id
                               where m2.conversation_id = c.id and m2.user_id <> $1 and c.kind = 'direct' limit 1) o on true
           ${cardJoin('o')}
          where cm.user_id = $1 and (c.kind = 'group' or (o.id is not null and not ${blockedBetween('$1', 'o.id')}))
            and ($2::timestamptz is null or (c.last_message_at, c.id) < ($2::timestamptz, $3::uuid))
            and cm.archived = $5::boolean
          order by c.last_message_at desc, c.id desc limit $4`,
        [me.id, cur?.t ?? null, cur?.id ?? null, q.limit + 1, archivedOnly],
      );
      const more = rows.length > q.limit;
      const page = more ? rows.slice(0, q.limit) : rows;
      const last = page[page.length - 1];
      // Direct chats: is the other person active, and when were they last? Null when they keep that private.
      const presence = await presenceOf(ctx, hub, me.id, page.filter((r) => r.card_id).map((r) => r.card_id as string));
      return {
        items: await Promise.all(page.map(async (r) => ({
          id: r.id, kind: r.kind, title: r.title, lastMessageAt: r.last_message_at, unreadCount: Number(r.unread),
          mutedUntil: r.muted_until, archived: r.archived, pinned: r.pinned, markedUnread: r.marked_unread,
          memberCount: Number(r.member_count), members: r.members_preview, photoUrl: await signedUrl(ctx, r.photo_key ?? null),
          with: r.card_id ? await toCard(ctx, r as CardRow) : null,
          presence: r.card_id ? presence.get(r.card_id) ?? null : null,
          lastMessage: r.lm_id ? { id: r.lm_id, senderId: r.lm_sender, content: r.lm_status === 'active' ? r.lm_content : null, deleted: r.lm_status === 'deleted', createdAt: r.lm_created,
          // Additive: what kind of thing the last message was, so the app can show "Photo" instead of guessing from empty text.
          hasEvent: r.lm_status === 'active' && Boolean(r.lm_event), hasLocation: r.lm_status === 'active' && Boolean(r.lm_location), mediaCount: r.lm_status === 'active' ? Number(r.lm_media) : 0, audioCount: r.lm_status === 'active' ? Number(r.lm_audio) : 0, documentCount: r.lm_status === 'active' ? Number(r.lm_docs) : 0, senderName: r.lm_sender_name ?? null,
          delivery: r.lm_delivery ?? null } : null,
        }))),
        nextCursor: more && last ? Buffer.from(JSON.stringify({ t: last.cursor_t, id: last.id })).toString('base64url') : null,
      };
    });

    app.get('/conversations/:id', async (req) => conversationView(await ctx.auth(req), param(req)));

    app.get('/conversations/:id/messages', async (req) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const q = z.object({
        limit: z.coerce.number().int().min(1).max(100).default(30),
        before: z.coerce.number().int().positive().optional(),
        // Reconnect sync: everything newer than the last seq the device holds, oldest first.
        after: z.coerce.number().int().nonnegative().optional(),
      }).parse(req.query);
      await membership(pool, id, me.id);
      if (q.after !== undefined) {
        const { rows: newer } = await pool.query(
          `${MESSAGE_SELECT} where m.conversation_id = $1 and m.seq > $2 and m.seq > (select cleared_seq from conversation_members where conversation_id = $1 and user_id = $3)
              and (m.sender_id is null or m.sender_id = $3 or not ${blockedBetween('$3', 'm.sender_id')})
            order by m.seq asc limit $4`, [id, q.after, me.id, q.limit + 1]);
        const hasMore = newer.length > q.limit;
        const batch = await toMessageViews(ctx, hasMore ? newer.slice(0, q.limit) : newer, me.id);
        return { items: batch, nextAfter: hasMore && batch.length ? batch[batch.length - 1].seq : null };
      }
      // Newest first; `before` is the seq of the oldest message already loaded. Messages from blocked people are hidden.
      const { rows } = await pool.query(
        `${MESSAGE_SELECT} where m.conversation_id = $1 and ($2::bigint is null or m.seq < $2) and m.seq > (select cleared_seq from conversation_members where conversation_id = $1 and user_id = $3)
            and (m.sender_id is null or m.sender_id = $3 or not ${blockedBetween('$3', 'm.sender_id')})
          order by m.seq desc limit $4`,
        [id, q.before ?? null, me.id, q.limit + 1],
      );
      const more = rows.length > q.limit;
      const page = more ? rows.slice(0, q.limit) : rows;
      const items = await toMessageViews(ctx, page, me.id);
      const pinned = (await pool.query(
        `select p.id from conversations c join messages p on p.id = c.pinned_message_id and p.status = 'active'
          where c.id = $1 and p.seq > (select cleared_seq from conversation_members where conversation_id = c.id and user_id = $2)`, [id, me.id])).rows[0];
      return { items, nextBefore: more && items.length ? items[items.length - 1].seq : null, pinnedMessageId: pinned?.id ?? null };
    });

    app.post('/conversations/:id/messages', async (req, reply) => {
      const me = await ctx.auth(req);
      const { message, created } = await sendMessage(ctx, hub, me, param(req), req.body);
      return reply.status(created ? 201 : 200).send(message);
    });

    app.post('/conversations/:id/read', async (req, reply) => {
      const me = await ctx.auth(req);
      const b = z.object({ upToSeq: z.number().int().nonnegative().optional() }).strict().parse(req.body ?? {});
      await markRead(ctx, hub, me, param(req), b.upToSeq);
      return reply.status(204).send();
    });

    // PUT is accepted too: some HTTP stacks (older Android clients) cannot send PATCH.
    app.route({ method: ['PATCH', 'PUT'], url: '/messages/:id', handler: async (req) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const b = z.object({ content: z.string().trim().min(1).max(4000) }).strict().parse(req.body ?? {});
      // Own, still-active messages only, inside the edit window. Anything else is "not found".
      const res = await pool.query<{ conversation_id: string }>(
        `update messages set content = $3, edited_at = now() where id = $1 and sender_id = $2 and status = 'active'
            and created_at > now() - make_interval(mins => $4::int) returning conversation_id`,
        [id, me.id, b.content, EDIT_WINDOW_MINUTES]);
      if (!res.rowCount) throw new HttpError(404, 'not_found', 'Not found.');
      const conversationId = res.rows[0].conversation_id;
      const link = firstLink(b.content);
      if (link) await enqueue(pool, 'link.preview', { messageId: id, url: link }, { dedupeKey: `${id}:${linkKey(link)}`, maxAttempts: 2 });
      else {
        // The link was edited out: its card, picture included, goes too.
        const card = await pool.query<{ image_key: string | null }>('delete from message_link_previews where message_id = $1 returning image_key', [id]);
        const keys = card.rows.map((r) => r.image_key).filter((k): k is string => Boolean(k));
        if (keys.length && ctx.storage) void ctx.storage.remove(keys).catch(() => undefined);
      }
      const message = await loadMessage(ctx, id);
      hub.sendMany(await audience(pool, conversationId, me.id), { type: 'message:updated', conversationId, message });
      return message;
    } });

    // Delete means "unsend for everyone": the text and attachments are removed, a tombstone stays so threads keep their shape.
    app.delete('/messages/:id', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const { conversationId, previewImages } = await withTx(pool, async (client) => {
        const res = await client.query<{ conversation_id: string }>(
          "update messages set status = 'deleted', content = '', deleted_at = now() where id = $1 and sender_id = $2 and status = 'active' returning conversation_id", [id, me.id]);
        if (!res.rowCount) throw new HttpError(404, 'not_found', 'Not found.');
        const gone = await client.query<{ object_key: string; thumb_key: string | null }>('delete from media where message_id = $1 returning object_key, thumb_key', [id]);
        // The link card goes with the message, picture included.
        const card = await client.query<{ image_key: string | null }>('delete from message_link_previews where message_id = $1 returning image_key', [id]);
        const files = gone.rows.flatMap((r) => [r.object_key, r.thumb_key]).filter((k): k is string => Boolean(k));
        return { conversationId: res.rows[0].conversation_id, previewImages: [...files, ...card.rows.map((r) => r.image_key).filter((k): k is string => Boolean(k))] };
      });
      if (previewImages.length && ctx.storage) void ctx.storage.remove(previewImages).catch(() => undefined);
      hub.sendMany(await audience(pool, conversationId, me.id), { type: 'message:deleted', conversationId, messageId: id });
      return reply.status(204).send();
    });

    // ---- live location: the sender's phone keeps this up to date; everyone in the chat gets the message again, changed.
    app.put('/messages/:id/live', async (req) => {
      const me = await ctx.auth(req);
      const id = param(req);
      ctx.limit(`live:${me.id}`, 30, 60);
      const b = z.object({ latitude: z.number().min(-90).max(90), longitude: z.number().min(-180).max(180) }).strict().parse(req.body ?? {});
      const res = await pool.query<{ conversation_id: string }>(
        `update live_locations ll set lat = $3, lng = $4, updated_at = now() from messages m
          where m.id = ll.message_id and ll.message_id = $1 and ll.user_id = $2 and ll.ended_at is null and ll.expires_at > now() returning m.conversation_id`,
        [id, me.id, roundCoordinate(b.latitude), roundCoordinate(b.longitude)]);
      if (!res.rowCount) throw new HttpError(409, 'live_ended', 'This live location has ended.');
      const conversationId = res.rows[0].conversation_id;
      const message = await loadMessage(ctx, id);
      hub.sendMany(await audience(pool, conversationId, me.id), { type: 'message:updated', conversationId, message });
      return { ok: true };
    });

    // Stopping wipes the stored position at once. Calling it again, or after it ran out, changes nothing.
    app.delete('/messages/:id/live', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const res = await pool.query<{ conversation_id: string }>(
        `update live_locations ll set ended_at = now(), lat = null, lng = null from messages m
          where m.id = ll.message_id and ll.message_id = $1 and ll.user_id = $2 and ll.ended_at is null returning m.conversation_id`, [id, me.id]);
      if (res.rowCount) {
        const conversationId = res.rows[0].conversation_id;
        const message = await loadMessage(ctx, id);
        hub.sendMany(await audience(pool, conversationId, me.id), { type: 'message:updated', conversationId, message });
      }
      return reply.status(204).send();
    });

    // ---- group membership
    app.post('/conversations/:id/members', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const b = memberIdsSchema.parse(req.body ?? {});
      ctx.limit(`members:${me.id}`, 30, 3600);
      const mine = await membership(pool, id, me.id);
      if (mine.kind !== 'group') throw new HttpError(400, 'validation', 'People can only be added to groups.');
      if (mine.role === 'member' && mine.only_admins_can_add_members) throw new HttpError(403, 'forbidden', 'Only admins can add people.');
      const candidates = await eligibleUsers(me, b.userIds);
      const added = await withTx(pool, async (client) => {
        await client.query('select 1 from conversations where id = $1 for update', [id]); // serialises concurrent adds against the cap
        const count = Number((await client.query('select count(*) from conversation_members where conversation_id = $1', [id])).rows[0].count);
        if (count + candidates.length > MAX_GROUP_MEMBERS) throw new HttpError(422, 'group_full', `Groups can have up to ${MAX_GROUP_MEMBERS} people.`);
        const res = await client.query<{ user_id: string }>(
          'insert into conversation_members (conversation_id, user_id) select $1, unnest($2::uuid[]) on conflict do nothing returning user_id', [id, candidates]);
        for (const row of res.rows) await notify(client, { recipientId: row.user_id, actorId: me.id, type: 'group_invite', entityType: 'conversation', entityId: id, metadata: { conversationId: id } });
        return res.rows.map((r) => r.user_id);
      });
      const everyone = (await pool.query<{ user_id: string }>('select user_id from conversation_members where conversation_id = $1', [id])).rows.map((r) => r.user_id);
      hub.sendMany(everyone, { type: 'conversation:updated', conversationId: id });
      return reply.status(200).send({ added: added.length });
    });

    app.delete('/conversations/:id/members/:userId', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const targetId = param(req, 'userId');
      const mine = await membership(pool, id, me.id);
      if (mine.kind !== 'group') throw new HttpError(400, 'validation', 'You cannot remove people from a direct chat.');
      const remaining = await withTx(pool, async (client) => {
        const target = (await client.query<{ role: string }>(
          'select role from conversation_members where conversation_id = $1 and user_id = $2 for update', [id, targetId])).rows[0];
        if (!target) throw new HttpError(404, 'not_found', 'Not found.');
        if (targetId !== me.id) {
          // Owners remove anyone; admins remove plain members only; members remove nobody.
          const allowed = mine.role === 'owner' || (mine.role === 'admin' && target.role === 'member');
          if (!allowed) throw new HttpError(403, 'forbidden', 'You cannot remove that person.');
        }
        await client.query('delete from conversation_members where conversation_id = $1 and user_id = $2', [id, targetId]);
        const left = (await client.query<{ user_id: string; role: string }>(
          "select user_id, role from conversation_members where conversation_id = $1 order by (role = 'admin') desc, joined_at, user_id", [id])).rows;
        if (!left.length) {
          await client.query('delete from conversations where id = $1', [id]); // the last person left: the group is gone
        } else if (target.role === 'owner') {
          // Ownership passes to the longest-standing admin, otherwise the longest-standing member.
          await client.query("update conversation_members set role = 'owner' where conversation_id = $1 and user_id = $2", [id, left[0].user_id]);
        }
        return left.map((r) => r.user_id);
      });
      hub.send(targetId, { type: 'conversation:updated', conversationId: id });
      hub.sendMany(remaining, { type: 'conversation:updated', conversationId: id });
      return reply.status(204).send();
    });
  };
}
