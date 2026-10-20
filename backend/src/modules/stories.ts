import type { FastifyInstance } from 'fastify';
import type { Pool } from 'pg';
import { z } from 'zod';
import { acceptedFollow, blockedBetween, userVisible } from '../access';
import type { Ctx } from '../context';
import { withTx } from '../db';
import { HttpError } from '../errors';
import { decodeCursor, idParam, pageSchema, toPage, tsText } from '../http';
import type { Hub } from '../realtime';
import { isUniqueViolation } from '../shared';
import type { Storage } from '../storage';
import { CARD_COLUMNS, CARD_JOIN, type CardRow, toCard, type UserCard } from './cards';
import { REACTIONS } from './chat_extras';
import { sendMessage } from './messaging';
import { notify } from './notify';
import { characterCount, cleanNoteText } from './notes';

export const DEFAULT_STORY_TTL_HOURS = 24;
export const STORY_TEXT_MAX = 280;
export const STORY_CAPTION_MAX = 140;
/** Media links handed out with a Story. Short, and minted only after the audience check has passed. */
export const STORY_MEDIA_LINK_SECONDS = 600;
const BACKGROUNDS = ['teal', 'indigo', 'rose', 'amber', 'slate'] as const;

const createSchema = z.object({
  kind: z.enum(['photo', 'video', 'text']),
  mediaId: z.string().uuid().optional(),
  text: z.string().max(2000).optional(),
  caption: z.string().max(2000).optional(),
  background: z.enum(BACKGROUNDS).optional(),
  audience: z.enum(['campus', 'followers']).default('campus'),
  clientId: z.string().min(1).max(64).optional(),
}).strict();
const audienceSchema = z.object({ audience: z.enum(['campus', 'followers']) }).strict();
const reactionSchema = z.object({ reaction: z.enum(REACTIONS) }).strict();
const replySchema = z.object({
  content: z.string().trim().min(1, 'Write a reply first.').max(500),
  clientId: z.string().min(1).max(64).optional(),
}).strict();

export interface StoryItem {
  id: string;
  kind: 'photo' | 'video' | 'text';
  /** Short-lived link, present for photo and video Stories only. */
  mediaUrl: string | null;
  width: number | null;
  height: number | null;
  durationMs: number | null;
  text: string | null;
  caption: string | null;
  background: string | null;
  audience: 'campus' | 'followers';
  createdAt: string;
  expiresAt: string;
  viewedByMe: boolean;
  /** The reaction I gave this Story, if any. */
  myReaction: string | null;
}

/** What the author sees about their own Story. Counts only: who viewed is a separate, paged, author-only call. */
export interface MyStoryItem extends StoryItem { viewCount: number; reactions: Record<string, number> }

interface StoryRow {
  id: string; kind: 'photo' | 'video' | 'text'; text: string | null; caption: string | null; background: string | null;
  audience: 'campus' | 'followers'; created_at: Date; expires_at: Date; viewed: boolean; my_reaction: string | null;
  object_key: string | null; width: number | null; height: number | null; duration_ms: number | null;
}

const ITEM_COLUMNS = `s.id, s.kind, s.text, s.caption, s.background, s.audience, s.created_at, s.expires_at,
  m.object_key, m.width, m.height, m.duration_ms,
  (s.author_id = $1 or exists (select 1 from story_views v where v.story_id = s.id and v.viewer_id = $1)) as viewed,
  (select r.reaction from story_reactions r where r.story_id = s.id and r.user_id = $1) as my_reaction`;
const ITEM_FROM = `from stories s join users u on u.id = s.author_id left join media m on m.id = s.media_id and m.status = 'ready'`;

/** May viewer $1 (university $2) see story `s` by author `u` right now? Every Story read goes through this. */
const VISIBLE = `s.expires_at > now() and (s.kind = 'text' or m.id is not null) and (s.author_id = $1 or (
  ${userVisible('u', '$1::uuid', '$2')} and (s.audience = 'campus' or ${acceptedFollow('$1::uuid', 's.author_id')})))`;

async function toItem(storage: Storage | null, r: StoryRow): Promise<StoryItem> {
  return {
    id: r.id, kind: r.kind,
    mediaUrl: r.object_key && storage ? await storage.signedUrl(r.object_key, STORY_MEDIA_LINK_SECONDS) : null,
    width: r.width, height: r.height, durationMs: r.duration_ms,
    text: r.text, caption: r.caption, background: r.background, audience: r.audience,
    createdAt: r.created_at.toISOString(), expiresAt: r.expires_at.toISOString(), viewedByMe: r.viewed, myReaction: r.my_reaction,
  };
}

/**
 * Removes Stories that expired more than an hour ago, with their files. The file is deleted first and the rows after,
 * so a storage failure leaves everything in place for the next run (it is safe to repeat). A Story's file belongs to it
 * alone: other features only accept media of their own purpose, so nothing else can still be using it.
 */
export async function purgeExpiredStories(pool: Pool, storage: Storage | null): Promise<{ stories: number }> {
  const { rows } = await pool.query<{ id: string; media_id: string | null; object_key: string | null; thumb_key: string | null }>(
    `select s.id, s.media_id, m.object_key, m.thumb_key from stories s left join media m on m.id = s.media_id
      where s.expires_at < now() - interval '1 hour' order by s.expires_at limit 200`);
  if (rows.length === 0) return { stories: 0 };
  const keys = rows.flatMap((r) => [r.object_key, r.thumb_key]).filter((k): k is string => !!k);
  if (keys.length > 0) {
    if (!storage) return { stories: 0 }; // cannot delete the files, so keep the rows and try again later
    await storage.remove(keys);
  }
  await withTx(pool, async (c) => {
    await c.query('delete from stories where id = any($1::uuid[])', [rows.map((r) => r.id)]);
    const mediaIds = rows.map((r) => r.media_id).filter((x): x is string => !!x);
    if (mediaIds.length > 0) await c.query('delete from media where id = any($1::uuid[])', [mediaIds]);
  });
  return { stories: rows.length };
}

/**
 * Stories: temporary photo, video or text updates.
 *
 * Policies:
 *  - Expiry is the database clock (STORY_TTL_HOURS, default 24). Expired Stories are never returned or opened.
 *  - Audience ('campus' or 'followers'), blocks, suspension and university are enforced in SQL on every read.
 *    Media links are minted per request, only for a Story the viewer may see, and last 10 minutes. A link already
 *    handed out stays usable until it expires: that is how signed links work, and why they are short.
 *  - A view is recorded once per viewer per Story, when the app reports it (after the Story has been on screen for a
 *    moment). Reporting again changes nothing, so counts cannot be inflated. The author's own views are not recorded.
 *  - Publishing is idempotent per clientId: a retried request returns the first Story instead of making a second.
 *  - Video length is reported by the app and capped at upload time; the server also caps the file size.
 */
export function storyRoutes(ctx: Ctx, hub: Hub) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;
    const ttlHours = ctx.config.STORY_TTL_HOURS ?? DEFAULT_STORY_TTL_HOURS;

    async function announce(authorId: string, universityId: string, audience: 'campus' | 'followers', kind: 'created' | 'deleted' | 'updated') {
      const event = { type: 'story:changed', userId: authorId, kind } as const;
      hub.send(authorId, event);
      const online = hub.onlineUserIds().filter((id) => id !== authorId);
      if (online.length === 0) return;
      const { rows } = await pool.query<{ id: string }>(
        `select u.id from users u
          where u.id = any($1::uuid[]) and u.university_id = $3 and u.email_verified_at is not null
            and not ${blockedBetween('u.id', '$2::uuid')}
            and ($4 = 'campus' or ${acceptedFollow('u.id', '$2::uuid')})`,
        [online, authorId, universityId, kind === 'created' ? audience : 'campus']);
      hub.sendMany(rows.map((r) => r.id), event);
    }

    async function itemById(id: string, viewerId: string, universityId: string): Promise<StoryItem | null> {
      const { rows } = await pool.query<StoryRow>(`select ${ITEM_COLUMNS} ${ITEM_FROM} where s.id = $3 and ${VISIBLE}`, [viewerId, universityId, id]);
      return rows[0] ? toItem(ctx.storage, rows[0]) : null;
    }

    /** Who has an active Story I may see: one entry per person, unviewed first. No media links here, so it is cheap. */
    app.get('/stories', async (req) => {
      const me = await ctx.auth(req);
      const [clock, groups] = await Promise.all([
        pool.query<{ now: Date }>('select now() as now'),
        pool.query<CardRow & { n: number; latest: Date; has_unviewed: boolean }>(
          `select ${CARD_COLUMNS}, count(*)::int as n, max(s.created_at) as latest,
                  bool_or(s.author_id <> $1 and not exists (select 1 from story_views v where v.story_id = s.id and v.viewer_id = $1)) as has_unviewed
             ${ITEM_FROM} ${CARD_JOIN}
            where ${VISIBLE}
            group by u.id, u.handle, u.full_name, u.verification_status, av.object_key
            order by (u.id = $1) desc, has_unviewed desc, latest desc limit 60`,
          [me.id, me.universityId]),
      ]);
      return {
        serverTime: clock.rows[0].now.toISOString(),
        items: await Promise.all(groups.rows.map(async (g) => ({
          author: await toCard(ctx, g), count: g.n, latestAt: g.latest.toISOString(),
          hasUnviewed: g.has_unviewed, isMine: g.card_id === me.id,
        }))),
      };
    });

    /** One person's active Stories, oldest first, with media links. 404 when there is nothing this student may see. */
    app.get('/users/:userId/stories', async (req) => {
      const me = await ctx.auth(req);
      const authorId = idParam((req.params as { userId: string }).userId);
      const { rows } = await pool.query<StoryRow & CardRow>(
        `select ${ITEM_COLUMNS}, ${CARD_COLUMNS} ${ITEM_FROM} ${CARD_JOIN}
          where s.author_id = $3 and ${VISIBLE} order by s.created_at asc, s.id asc`,
        [me.id, me.universityId, authorId]);
      if (rows.length === 0) throw new HttpError(404, 'not_found', 'Not found.');
      const author: UserCard = await toCard(ctx, rows[0]);
      return { author, items: await Promise.all(rows.map((r) => toItem(ctx.storage, r))) };
    });

    app.post('/stories', async (req, reply) => {
      const me = await ctx.auth(req);
      ctx.limit(`story:${me.id}`, 30, 3600);
      const parsed = createSchema.safeParse(req.body ?? {});
      if (!parsed.success) throw new HttpError(400, 'validation', 'That Story is not valid.');
      const b = parsed.data;

      const text = b.text === undefined ? null : cleanNoteText(b.text);
      const caption = b.caption === undefined ? null : cleanNoteText(b.caption);
      if (b.kind === 'text') {
        if (!text) throw new HttpError(400, 'validation', 'Write something first.');
        if (characterCount(text) > STORY_TEXT_MAX) throw new HttpError(400, 'validation', `A text Story can be at most ${STORY_TEXT_MAX} characters.`);
        if (b.mediaId || caption) throw new HttpError(400, 'validation', 'A text Story has no photo, video or caption.');
      } else {
        if (!b.mediaId) throw new HttpError(400, 'validation', 'Choose a photo or video first.');
        if (text || b.background) throw new HttpError(400, 'validation', 'Use a caption for a photo or video Story.');
        if (caption && characterCount(caption) > STORY_CAPTION_MAX) throw new HttpError(400, 'validation', `A caption can be at most ${STORY_CAPTION_MAX} characters.`);
      }

      // A retry of the same publish returns the Story it already made.
      if (b.clientId) {
        const existing = await pool.query<{ id: string }>('select id from stories where author_id = $1 and client_id = $2', [me.id, b.clientId]);
        if (existing.rows[0]) return reply.status(200).send(await itemById(existing.rows[0].id, me.id, me.universityId));
      }

      let id: string;
      try {
        id = await withTx(pool, async (c) => {
          if (b.kind !== 'text') {
            const m = await c.query<{ kind: string }>(
              "select kind from media where id = $1 and owner_id = $2 and purpose = 'story' and status = 'ready' for update", [b.mediaId, me.id]);
            const wanted = b.kind === 'photo' ? 'image' : 'video';
            if (!m.rows[0] || m.rows[0].kind !== wanted) throw new HttpError(400, 'validation', 'That file is not available. Upload it again.');
          }
          const res = await c.query<{ id: string }>(
            `insert into stories (author_id, kind, media_id, text, caption, background, audience, client_id, expires_at)
             values ($1, $2, $3, $4, $5, $6, $7, $8, now() + make_interval(hours => $9::int)) returning id`,
            [me.id, b.kind, b.mediaId ?? null, b.kind === 'text' ? text : null, b.kind === 'text' ? null : (caption || null),
              b.kind === 'text' ? (b.background ?? 'teal') : null, b.audience, b.clientId ?? null, ttlHours]);
          return res.rows[0].id;
        });
      } catch (e) {
        if (isUniqueViolation(e)) {
          // Two requests with the same clientId raced: the other one won, so return its Story. A reused media file is a conflict.
          const existing = b.clientId
            ? await pool.query<{ id: string }>('select id from stories where author_id = $1 and client_id = $2', [me.id, b.clientId])
            : { rows: [] as { id: string }[] };
          if (existing.rows[0]) return reply.status(200).send(await itemById(existing.rows[0].id, me.id, me.universityId));
          throw new HttpError(409, 'media_in_use', 'That file is already used by another Story.');
        }
        throw e;
      }
      await announce(me.id, me.universityId, b.audience, 'created');
      return reply.status(201).send(await itemById(id, me.id, me.universityId));
    });

    /** The app calls this once a Story has been on screen for a moment. Safe to repeat. Your own Stories are not counted. */
    app.post('/stories/:id/view', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = idParam((req.params as { id: string }).id);
      ctx.limit(`story-view:${me.id}`, 1200, 3600);
      const { rows } = await pool.query<{ author_id: string }>(
        `select s.author_id ${ITEM_FROM} where s.id = $3 and ${VISIBLE}`, [me.id, me.universityId, id]);
      if (!rows[0]) throw new HttpError(404, 'not_found', 'Not found.');
      if (rows[0].author_id !== me.id) {
        await pool.query('insert into story_views (story_id, viewer_id) values ($1, $2) on conflict do nothing', [id, me.id]);
      }
      return reply.status(204).send();
    });

    /** My own active Stories with counts, newest first. Only the author gets counts. */
    app.get('/me/stories', async (req) => {
      const me = await ctx.auth(req);
      const [clock, rows] = await Promise.all([
        pool.query<{ now: Date }>('select now() as now'),
        pool.query<StoryRow & { view_count: number; reactions: Record<string, number> }>(
          `select ${ITEM_COLUMNS},
                  (select count(*)::int from story_views v where v.story_id = s.id) as view_count,
                  (select coalesce(jsonb_object_agg(x.reaction, x.n), '{}'::jsonb)
                     from (select r.reaction, count(*)::int as n from story_reactions r where r.story_id = s.id group by r.reaction) x) as reactions
             ${ITEM_FROM}
            where s.author_id = $1 and s.expires_at > now() and (s.kind = 'text' or m.id is not null)
            order by s.created_at desc, s.id desc`, [me.id]),
      ]);
      return {
        serverTime: clock.rows[0].now.toISOString(),
        items: await Promise.all(rows.rows.map(async (r): Promise<MyStoryItem> => ({ ...(await toItem(ctx.storage, r)), viewCount: r.view_count, reactions: r.reactions }))),
      };
    });

    /** Who viewed one of MY active Stories, newest first, with the reaction they gave. Anyone else gets "not found". */
    app.get('/stories/:id/viewers', async (req) => {
      const me = await ctx.auth(req);
      const id = idParam((req.params as { id: string }).id);
      const q = pageSchema.parse(req.query);
      const cur = decodeCursor(q.cursor);
      const own = await pool.query('select 1 from stories where id = $1 and author_id = $2 and expires_at > now()', [id, me.id]);
      if (!own.rowCount) throw new HttpError(404, 'not_found', 'Not found.');
      const { rows } = await pool.query<CardRow & { id: string; cursorT: string; viewed_at: Date; reaction: string | null }>(
        `select v.viewer_id as id, v.viewed_at, ${tsText('v.viewed_at')} as "cursorT", ${CARD_COLUMNS}, r.reaction
           from story_views v join users u on u.id = v.viewer_id ${CARD_JOIN}
           left join story_reactions r on r.story_id = v.story_id and r.user_id = v.viewer_id
          where v.story_id = $1 and not ${blockedBetween('u.id', '$2::uuid')}
            and ($3::timestamptz is null or (v.viewed_at, v.viewer_id) < ($3::timestamptz, $4::uuid))
          order by v.viewed_at desc, v.viewer_id desc limit $5`,
        [id, me.id, cur?.t ?? null, cur?.id ?? null, q.limit + 1]);
      const page = toPage(rows, q.limit);
      const total = await pool.query<{ n: number }>('select count(*)::int as n from story_views where story_id = $1', [id]);
      return {
        total: total.rows[0].n, nextCursor: page.nextCursor,
        items: await Promise.all(page.items.map(async (r) => ({
          viewer: await toCard(ctx, r as unknown as CardRow), viewedAt: (r as unknown as { viewed_at: Date }).viewed_at.toISOString(),
          reaction: (r as unknown as { reaction: string | null }).reaction,
        }))),
      };
    });

    /**
     * Change who may see a Story. The server checks the audience on every read, so narrowing takes effect at once for
     * everyone: someone who no longer qualifies gets "not found" even with a cached card or an unexpired media link
     * (the link itself lasts at most STORY_MEDIA_LINK_SECONDS). Views and reactions already recorded are kept.
     */
    app.patch('/stories/:id', async (req) => {
      const me = await ctx.auth(req);
      const id = idParam((req.params as { id: string }).id);
      const b = audienceSchema.safeParse(req.body ?? {});
      if (!b.success) throw new HttpError(400, 'validation', 'Choose who can see this Story.');
      const res = await pool.query('update stories set audience = $3 where id = $1 and author_id = $2 and expires_at > now()', [id, me.id, b.data.audience]);
      if (!res.rowCount) throw new HttpError(404, 'not_found', 'Not found.');
      await announce(me.id, me.universityId, b.data.audience, 'updated');
      return (await itemById(id, me.id, me.universityId));
    });

    /** React to a Story I may see. One reaction per person: sending another replaces it. The author is told only the first time. */
    app.put('/stories/:id/reaction', async (req) => {
      const me = await ctx.auth(req);
      const id = idParam((req.params as { id: string }).id);
      ctx.limit(`story-react:${me.id}`, 300, 3600);
      const b = reactionSchema.safeParse(req.body ?? {});
      if (!b.success) throw new HttpError(400, 'validation', 'That reaction is not available.');
      const { rows } = await pool.query<{ author_id: string }>(`select s.author_id ${ITEM_FROM} where s.id = $3 and ${VISIBLE}`, [me.id, me.universityId, id]);
      if (!rows[0]) throw new HttpError(404, 'not_found', 'Not found.');
      if (rows[0].author_id === me.id) throw new HttpError(400, 'validation', 'You cannot react to your own Story.');
      await withTx(pool, async (c) => {
        const first = await c.query(
          'insert into story_reactions (story_id, user_id, reaction) values ($1, $2, $3) on conflict (story_id, user_id) do nothing', [id, me.id, b.data.reaction]);
        if (first.rowCount) {
          await notify(c, { recipientId: rows[0].author_id, actorId: me.id, type: 'story_reaction', entityType: 'story', entityId: id, metadata: { storyId: id, reaction: b.data.reaction } });
        } else {
          await c.query('update story_reactions set reaction = $3, updated_at = now() where story_id = $1 and user_id = $2 and reaction <> $3', [id, me.id, b.data.reaction]);
        }
      });
      return { reaction: b.data.reaction };
    });

    app.delete('/stories/:id/reaction', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = idParam((req.params as { id: string }).id);
      await pool.query('delete from story_reactions where story_id = $1 and user_id = $2', [id, me.id]);
      return reply.status(204).send();
    });

    /**
     * Reply to a Story. This is an ordinary chat message in the direct conversation with the author (created if needed),
     * so every messaging rule applies unchanged: blocks, suspension, university and the author's "who can message me"
     * setting. The message remembers which Story it answers (id and kind only); once the Story is gone the chat shows
     * that it is no longer available and nothing of its content remains.
     */
    app.post('/stories/:id/reply', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = idParam((req.params as { id: string }).id);
      const b = replySchema.safeParse(req.body ?? {});
      if (!b.success) throw new HttpError(400, 'validation', b.error.issues[0]?.message ?? 'Write a reply first.');
      const { rows } = await pool.query<{ author_id: string; kind: 'photo' | 'video' | 'text' }>(
        `select s.author_id, s.kind ${ITEM_FROM} where s.id = $3 and ${VISIBLE}`, [me.id, me.universityId, id]);
      if (!rows[0]) throw new HttpError(404, 'not_found', 'Not found.');
      const other = rows[0].author_id;
      if (other === me.id) throw new HttpError(400, 'validation', 'You cannot reply to your own Story.');
      const perm = (await pool.query<{ p: string; follows: boolean }>(
        `select coalesce(ps.message_permission, 'everyone') as p, ${acceptedFollow('$2::uuid', '$1::uuid')} as follows
           from users u left join privacy_settings ps on ps.user_id = u.id where u.id = $1`, [other, me.id])).rows[0];
      if (!perm || perm.p === 'nobody' || (perm.p === 'followers' && !perm.follows)) throw new HttpError(403, 'cannot_message', 'You cannot message this person.');
      const key = [me.id, other].sort().join(':');
      const conversationId = await withTx(pool, async (c) => {
        const made = await c.query<{ id: string }>(
          "insert into conversations (kind, created_by, direct_key) values ('direct', $1, $2) on conflict (direct_key) do nothing returning id", [me.id, key]);
        if (!made.rowCount) return (await c.query<{ id: string }>('select id from conversations where direct_key = $1', [key])).rows[0].id;
        await c.query("insert into conversation_members (conversation_id, user_id, role) values ($1, $2, 'member'), ($1, $3, 'member')", [made.rows[0].id, me.id, other]);
        return made.rows[0].id;
      });
      const { message, created } = await sendMessage(ctx, hub, me, conversationId, { content: b.data.content, clientId: b.data.clientId }, { story: { id, kind: rows[0].kind } });
      return reply.status(created ? 201 : 200).send({ conversationId, message });
    });

    /** Author only. Anything else (someone else's Story, or none) looks like "not found". The file goes with it. */
    app.delete('/stories/:id', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = idParam((req.params as { id: string }).id);
      const gone = await withTx(pool, async (c) => {
        const s = await c.query<{ audience: 'campus' | 'followers'; media_id: string | null }>(
          'delete from stories where id = $1 and author_id = $2 returning audience, media_id', [id, me.id]);
        if (!s.rows[0]) return null;
        const keys: string[] = [];
        if (s.rows[0].media_id) {
          const m = await c.query<{ object_key: string; thumb_key: string | null }>('delete from media where id = $1 returning object_key, thumb_key', [s.rows[0].media_id]);
          if (m.rows[0]) keys.push(m.rows[0].object_key, ...(m.rows[0].thumb_key ? [m.rows[0].thumb_key] : []));
        }
        return { audience: s.rows[0].audience, keys };
      });
      if (!gone) throw new HttpError(404, 'not_found', 'Not found.');
      // The link to the file stops working with its row; removing the bytes is best effort (the purge never needs it).
      if (gone.keys.length > 0 && ctx.storage) await ctx.storage.remove(gone.keys).catch(() => undefined);
      await announce(me.id, me.universityId, 'campus', 'deleted');
      return reply.status(204).send();
    });
  };
}
