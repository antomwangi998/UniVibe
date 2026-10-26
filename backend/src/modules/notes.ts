import type { FastifyInstance } from 'fastify';
import type { Pool } from 'pg';
import { z } from 'zod';
import { acceptedFollow, blockedBetween, userVisible } from '../access';
import type { Ctx } from '../context';
import { withTx } from '../db';
import { HttpError } from '../errors';
import { idParam } from '../http';
import type { Hub } from '../realtime';
import { CARD_COLUMNS, CARD_JOIN, type CardRow, toCard, type UserCard } from './cards';
import { sendMessage } from './messaging';
import { type Presence, presenceOf } from './presence';

const replySchema = z.object({
  content: z.string().trim().min(1, 'Write a reply first.').max(500, 'That reply is too long.'),
  clientId: z.string().min(1).max(64).optional(),
}).strict();

export const DEFAULT_NOTE_MAX_CHARS = 60;
export const DEFAULT_NOTE_TTL_HOURS = 24;

/** Characters as people count them: an emoji or an accented letter is one, not two UTF-16 units. */
export const characterCount = (s: string) => Array.from(s).length;

/** Control characters (other than a line break) are dropped, line endings are normalised, then the ends are trimmed. */
export function cleanNoteText(raw: string): string {
  return raw
    .replace(/\r\n?/g, '\n')
    // eslint-disable-next-line no-control-regex
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '')
    .replace(/\n{2,}/g, '\n')
    .trim();
}

const putSchema = (maxChars: number) =>
  z.object({
    text: z.string().max(2000, 'That Note is too long.'),
    audience: z.enum(['campus', 'followers']).default('campus'),
  }).strict().transform((v, ctx) => {
    const text = cleanNoteText(v.text);
    if (text.length === 0) {
      ctx.addIssue({ code: 'custom', path: ['text'], message: 'Write something first.' });
      return z.NEVER;
    }
    // Never truncated silently: a Note over the limit is refused so the person can shorten it themselves.
    if (characterCount(text) > maxChars) {
      ctx.addIssue({ code: 'custom', path: ['text'], message: `A Note can be at most ${maxChars} characters.` });
      return z.NEVER;
    }
    return { text, audience: v.audience };
  });

interface NoteRow extends CardRow {
  id: string; author_id: string; text: string; audience: 'campus' | 'followers'; version: string;
  created_at: Date; expires_at: Date;
}

export interface NoteView {
  id: string;
  author: UserCard;
  text: string;
  audience: 'campus' | 'followers';
  version: number;
  createdAt: string;
  expiresAt: string;
  /** Whether the author is active now / when they were last. Null when they keep it private. Absent on your own Note. */
  presence?: Presence | null;
}

const NOTE_COLUMNS = `n.id, n.author_id, n.text, n.audience, n.version::text as version, n.created_at, n.expires_at, ${CARD_COLUMNS}`;

async function toView(ctx: Ctx, r: NoteRow, presence?: Map<string, Presence | null>): Promise<NoteView> {
  return {
    id: r.id, author: await toCard(ctx, r), text: r.text, audience: r.audience, version: Number(r.version),
    createdAt: r.created_at.toISOString(), expiresAt: r.expires_at.toISOString(),
    ...(presence ? { presence: presence.get(r.author_id) ?? null } : {}),
  };
}

/** Removes Notes that expired more than a day ago. Reads never depend on this: expired rows are already filtered out. */
export async function purgeExpiredNotes(pool: Pool): Promise<{ notes: number }> {
  const res = await pool.query("delete from notes where expires_at < now() - interval '1 day'");
  return { notes: res.rowCount ?? 0 };
}

/**
 * Notes: a short text update that disappears by itself.
 *
 * Policies (one place, so every client and the server agree):
 *  - One active Note per student. Publishing again replaces it.
 *  - Every successful publish or edit restarts the clock: expiry = server time + NOTE_TTL_HOURS (default 24).
 *  - Expiry is the database clock. A Note whose expires_at has passed is never returned, even before the purge runs.
 *  - Audience is enforced in the query on every read: 'campus' (same university) or 'followers' (people who follow the author).
 *  - A block in either direction, a suspended or unverified author, or a mute by the viewer hides the Note.
 *  - Live updates carry no text. They only say "this person's Note changed", and the app re-reads from here.
 */
export function noteRoutes(ctx: Ctx, hub: Hub) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;
    const maxChars = ctx.config.NOTE_MAX_CHARS ?? DEFAULT_NOTE_MAX_CHARS;
    const ttlHours = ctx.config.NOTE_TTL_HOURS ?? DEFAULT_NOTE_TTL_HOURS;
    const schema = putSchema(maxChars);
    const limits = { maxChars, ttlHours };

    /** Tells the author's other devices, and every connected student who may see the Note, that it changed. */
    async function announce(authorId: string, universityId: string, audience: 'campus' | 'followers', kind: 'updated' | 'deleted') {
      const event = { type: 'note:changed', userId: authorId, kind } as const;
      hub.send(authorId, event);
      const online = hub.onlineUserIds().filter((id) => id !== authorId);
      if (online.length === 0) return;
      // For a delete the old audience is unknown to the caller, so use the widest one: a "changed" nudge reveals nothing.
      const { rows } = await pool.query<{ id: string }>(
        `select u.id from users u
          where u.id = any($1::uuid[]) and u.university_id = $3 and u.email_verified_at is not null
            and not ${blockedBetween('u.id', '$2::uuid')}
            and ($4 = 'campus' or ${acceptedFollow('u.id', '$2::uuid')})
            and not exists (select 1 from note_mutes nm where nm.user_id = u.id and nm.muted_id = $2::uuid)`,
        [online, authorId, universityId, kind === 'deleted' ? 'campus' : audience],
      );
      hub.sendMany(rows.map((r) => r.id), event);
    }

    /** The signed-in student's own active Note, then everyone else's visible ones, newest first. */
    app.get('/notes', async (req) => {
      const me = await ctx.auth(req);
      const [clock, mine, others] = await Promise.all([
        pool.query<{ now: Date }>('select now() as now'),
        pool.query<NoteRow>(
          `select ${NOTE_COLUMNS} from notes n join users u on u.id = n.author_id ${CARD_JOIN}
            where n.author_id = $1 and n.expires_at > now()`, [me.id]),
        pool.query<NoteRow>(
          `select ${NOTE_COLUMNS} from notes n join users u on u.id = n.author_id ${CARD_JOIN}
            where n.expires_at > now() and n.author_id <> $1
              and ${userVisible('u', '$1::uuid', '$2')}
              and (n.audience = 'campus' or ${acceptedFollow('$1::uuid', 'n.author_id')})
              and not exists (select 1 from note_mutes nm where nm.user_id = $1 and nm.muted_id = n.author_id)
            order by n.created_at desc, n.id desc limit 60`, [me.id, me.universityId]),
      ]);
      const presence = await presenceOf(ctx, hub, me.id, others.rows.map((r) => r.author_id));
      return {
        serverTime: clock.rows[0].now.toISOString(),
        limits,
        mine: mine.rows[0] ? await toView(ctx, mine.rows[0]) : null,
        items: await Promise.all(others.rows.map((r) => toView(ctx, r, presence))),
      };
    });

    /** One person's active Note, if this student may see it. Anything else is "not found", so nothing leaks. */
    app.get('/users/:userId/note', async (req) => {
      const me = await ctx.auth(req);
      const authorId = idParam((req.params as { userId: string }).userId);
      const { rows } = await pool.query<NoteRow>(
        `select ${NOTE_COLUMNS} from notes n join users u on u.id = n.author_id ${CARD_JOIN}
          where n.author_id = $1 and n.expires_at > now()
            and (n.author_id = $2 or (
              ${userVisible('u', '$2::uuid', '$3')}
              and (n.audience = 'campus' or ${acceptedFollow('$2::uuid', 'n.author_id')})))`,
        [authorId, me.id, me.universityId]);
      if (!rows[0]) throw new HttpError(404, 'not_found', 'Not found.');
      return toView(ctx, rows[0], rows[0].author_id === me.id ? undefined : await presenceOf(ctx, hub, me.id, [authorId]));
    });

    /** Publish, edit or replace my Note. Safe to repeat: the same request twice leaves one Note. */
    app.put('/me/note', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`note:${me.id}`, 30, 3600);
      const parsed = schema.safeParse(req.body ?? {});
      if (!parsed.success) throw new HttpError(400, 'validation', parsed.error.issues[0]?.message ?? 'That Note is not valid.');
      const { text, audience } = parsed.data;
      const { rows } = await pool.query<{ id: string }>(
        `insert into notes (author_id, text, audience, expires_at)
         values ($1, $2, $3, now() + make_interval(hours => $4::int))
         on conflict (author_id) do update set
           text = excluded.text, audience = excluded.audience,
           version = notes.version + 1, created_at = now(), updated_at = now(), expires_at = excluded.expires_at
         returning id`,
        [me.id, text, audience, ttlHours]);
      const view = (await pool.query<NoteRow>(
        `select ${NOTE_COLUMNS} from notes n join users u on u.id = n.author_id ${CARD_JOIN} where n.id = $1`, [rows[0].id])).rows[0];
      await announce(me.id, me.universityId, audience, 'updated');
      return toView(ctx, view);
    });

    /** Remove my Note. Deleting when there is none is fine (204 either way). */
    app.delete('/me/note', async (req, reply) => {
      const me = await ctx.auth(req);
      await pool.query('delete from notes where author_id = $1', [me.id]);
      await announce(me.id, me.universityId, 'campus', 'deleted');
      return reply.status(204).send();
    });

    /**
     * Reply to someone's Note. An ordinary chat message in the direct conversation with the author (created if needed), so
     * every messaging rule applies unchanged: blocks, suspension, university and the author's "who can message me" setting.
     * The message opens with a short quote of the Note, because the Note itself disappears after its lifetime.
     */
    app.post('/users/:userId/note/reply', async (req, reply) => {
      const me = await ctx.auth(req);
      const authorId = idParam((req.params as { userId: string }).userId);
      const b = replySchema.safeParse(req.body ?? {});
      if (!b.success) throw new HttpError(400, 'validation', b.error.issues[0]?.message ?? 'Write a reply first.');
      if (authorId === me.id) throw new HttpError(400, 'validation', 'You cannot reply to your own Note.');
      const { rows } = await pool.query<{ text: string }>(
        `select n.text from notes n join users u on u.id = n.author_id
          where n.author_id = $1 and n.expires_at > now()
            and ${userVisible('u', '$2::uuid', '$3')}
            and (n.audience = 'campus' or ${acceptedFollow('$2::uuid', 'n.author_id')})`,
        [authorId, me.id, me.universityId]);
      if (!rows[0]) throw new HttpError(404, 'not_found', 'Not found.');
      const perm = (await pool.query<{ p: string; follows: boolean }>(
        `select coalesce(ps.message_permission, 'everyone') as p, ${acceptedFollow('$2::uuid', '$1::uuid')} as follows
           from users u left join privacy_settings ps on ps.user_id = u.id where u.id = $1`, [authorId, me.id])).rows[0];
      if (!perm || perm.p === 'nobody' || (perm.p === 'followers' && !perm.follows)) throw new HttpError(403, 'cannot_message', 'You cannot message this person.');
      const key = [me.id, authorId].sort().join(':');
      const conversationId = await withTx(pool, async (c) => {
        const made = await c.query<{ id: string }>(
          "insert into conversations (kind, created_by, direct_key) values ('direct', $1, $2) on conflict (direct_key) do nothing returning id", [me.id, key]);
        if (!made.rowCount) return (await c.query<{ id: string }>('select id from conversations where direct_key = $1', [key])).rows[0].id;
        await c.query("insert into conversation_members (conversation_id, user_id, role) values ($1, $2, 'member'), ($1, $3, 'member')", [made.rows[0].id, me.id, authorId]);
        return made.rows[0].id;
      });
      const quoted = rows[0].text.replace(/\s+/g, ' ').trim();
      const content = `Replying to your note: \u201C${quoted}\u201D\n${b.data.content}`;
      const { message, created } = await sendMessage(ctx, hub, me, conversationId, { content, clientId: b.data.clientId });
      return reply.status(created ? 201 : 200).send({ conversationId, message });
    });

    app.put('/users/:userId/note-mute', async (req, reply) => {
      const me = await ctx.auth(req);
      const target = idParam((req.params as { userId: string }).userId);
      if (target === me.id) throw new HttpError(400, 'validation', 'You cannot mute your own Note.');
      ctx.limit(`note-mute:${me.id}`, 120, 3600);
      const exists = await pool.query(
        `select 1 from users u where u.id = $1 and ${userVisible('u', '$2::uuid', '$3')}`, [target, me.id, me.universityId]);
      if (!exists.rowCount) throw new HttpError(404, 'not_found', 'Not found.');
      await pool.query('insert into note_mutes (user_id, muted_id) values ($1, $2) on conflict do nothing', [me.id, target]);
      hub.send(me.id, { type: 'note:changed', userId: target, kind: 'deleted' });
      return reply.status(204).send();
    });

    app.delete('/users/:userId/note-mute', async (req, reply) => {
      const me = await ctx.auth(req);
      const target = idParam((req.params as { userId: string }).userId);
      await pool.query('delete from note_mutes where user_id = $1 and muted_id = $2', [me.id, target]);
      hub.send(me.id, { type: 'note:changed', userId: target, kind: 'updated' });
      return reply.status(204).send();
    });
  };
}
