import type { FastifyInstance, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { blockedBetween } from '../access';
import type { AuthUser, Ctx } from '../context';
import { withTx } from '../db';
import { HttpError } from '../errors';
import { idParam } from '../http';
import { jobStats } from '../jobs';
import type { Hub } from '../realtime';
import { audience, loadMessage, membership } from './messaging';

export const REACTIONS = ['heart', 'like', 'laugh', 'fire', 'sad'] as const;

const installationId = z.string().regex(/^[A-Za-z0-9._:-]{8,128}$/);
const deviceBody = z.object({
  installationId,
  fcmToken: z.string().min(20).max(4096),
  platform: z.literal('android').default('android'),
}).strict();

const settingsBody = z.object({
  // Mute until a future time; null unmutes. A far-future time is how the app expresses "always".
  mutedUntil: z.string().datetime().nullable(),
  archived: z.boolean(),
  pinned: z.boolean(),
}).partial().strict();

/** Escapes LIKE wildcards so a search for "100%" finds "100%" rather than everything. */
export const likePattern = (q: string) => `%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;

/** Reaction counts for one message, e.g. { heart: 2, fire: 1 }. */
export async function reactionCounts(ctx: Ctx, messageId: string): Promise<Record<string, number>> {
  const { rows } = await ctx.pool.query<{ reaction: string; n: string }>(
    'select reaction, count(*) as n from message_reactions where message_id = $1 group by reaction', [messageId]);
  return Object.fromEntries(rows.map((r) => [r.reaction, Number(r.n)]));
}

/** Marks everything currently stored as delivered to this person and tells each sender. Called when a device connects or syncs. */
export async function markDeliveredAll(ctx: Ctx, hub: Hub, me: AuthUser): Promise<void> {
  const { rows } = await ctx.pool.query<{ conversation_id: string; last_delivered_seq: string }>(
    `update conversation_members cm
        set last_delivered_seq = latest.s
       from (select m.conversation_id, max(m.seq) as s from messages m
              where m.conversation_id in (select conversation_id from conversation_members where user_id = $1)
              group by m.conversation_id) latest
      where cm.user_id = $1 and cm.conversation_id = latest.conversation_id and cm.last_delivered_seq < latest.s
      returning cm.conversation_id, cm.last_delivered_seq`, [me.id]);
  for (const r of rows) {
    const others = (await audience(ctx.pool, r.conversation_id, me.id)).filter((u) => u !== me.id);
    hub.sendMany(others, { type: 'message:delivered', conversationId: r.conversation_id, userId: me.id, upToSeq: Number(r.last_delivered_seq) });
  }
}

export function chatExtraRoutes(ctx: Ctx, hub: Hub) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;
    const param = (req: FastifyRequest, name = 'id') => idParam((req.params as Record<string, string>)[name]);

    // ---- push devices
    app.put('/me/devices', async (req, reply) => {
      const me = await ctx.auth(req);
      const b = deviceBody.parse(req.body ?? {});
      ctx.limit(`device:${me.id}`, 30, 3600);
      await withTx(pool, async (client) => {
        // A token that moves to another account (shared phone, log out and in) must stop receiving the old account's pushes.
        await client.query(
          `update device_tokens set revoked_at = now(), revoked_reason = 'token_reassigned'
            where fcm_token = $3 and revoked_at is null and not (user_id = $1 and installation_id = $2)`,
          [me.id, b.installationId, b.fcmToken]);
        await client.query(
          `insert into device_tokens (user_id, installation_id, fcm_token, platform) values ($1, $2, $3, $4)
           on conflict (user_id, installation_id) do update
             set fcm_token = excluded.fcm_token, platform = excluded.platform, registered_at = now(), revoked_at = null, revoked_reason = null`,
          [me.id, b.installationId, b.fcmToken, b.platform]);
      });
      return reply.status(204).send();
    });

    // Call before logging out. Idempotent: an unknown installation is not an error.
    app.delete('/me/devices/:installationId', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = installationId.safeParse((req.params as { installationId: string }).installationId);
      if (id.success) {
        await pool.query(
          "update device_tokens set revoked_at = now(), revoked_reason = 'signed_out' where user_id = $1 and installation_id = $2 and revoked_at is null", [me.id, id.data]);
      }
      return reply.status(204).send();
    });

    // ---- per-person conversation settings
    // PUT is accepted too: some HTTP stacks (older Android clients) cannot send PATCH.
    app.route({ method: ['PATCH', 'PUT'], url: '/conversations/:id/settings', handler: async (req) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const b = settingsBody.parse(req.body ?? {});
      await membership(pool, id, me.id);
      if (b.mutedUntil && Date.parse(b.mutedUntil) <= Date.now()) throw new HttpError(400, 'validation', 'Mute until a time in the future, or send null to unmute.');
      const res = await pool.query(
        `update conversation_members set
            muted_until = case when $3::boolean then $4::timestamptz else muted_until end,
            archived = coalesce($5::boolean, archived), pinned = coalesce($6::boolean, pinned)
          where conversation_id = $1 and user_id = $2
          returning muted_until, archived, pinned, marked_unread`,
        [id, me.id, 'mutedUntil' in b, b.mutedUntil ?? null, b.archived ?? null, b.pinned ?? null]);
      const r = res.rows[0];
      hub.send(me.id, { type: 'conversation:updated', conversationId: id }); // the person's other devices refresh
      return { mutedUntil: r.muted_until, archived: r.archived, pinned: r.pinned, markedUnread: r.marked_unread };
    } });

    app.post('/conversations/:id/mark-unread', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      await membership(pool, id, me.id);
      await pool.query('update conversation_members set marked_unread = true where conversation_id = $1 and user_id = $2', [id, me.id]);
      hub.send(me.id, { type: 'conversation:updated', conversationId: id });
      return reply.status(204).send();
    });

    // A device that received messages by push or sync acknowledges them here (delivery, not reading).
    app.post('/conversations/:id/delivered', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const b = z.object({ upToSeq: z.number().int().positive() }).strict().parse(req.body ?? {});
      await membership(pool, id, me.id);
      const res = await pool.query<{ last_delivered_seq: string }>(
        `update conversation_members set last_delivered_seq = greatest(last_delivered_seq, least($3::bigint, (select coalesce(max(seq), 0) from messages where conversation_id = $1)))
          where conversation_id = $1 and user_id = $2 returning last_delivered_seq`, [id, me.id, b.upToSeq]);
      const others = (await audience(pool, id, me.id)).filter((u) => u !== me.id);
      hub.sendMany(others, { type: 'message:delivered', conversationId: id, userId: me.id, upToSeq: Number(res.rows[0].last_delivered_seq) });
      return reply.status(204).send();
    });

    // ---- reactions: one per person per message; sending the same one again is a no-op, a different one replaces it
    app.put('/messages/:id/reaction', async (req) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const b = z.object({ reaction: z.enum(REACTIONS) }).strict().parse(req.body ?? {});
      ctx.limit(`react:${me.id}`, 120, 60);
      const conversationId = await reactableConversation(id, me);
      await pool.query(
        `insert into message_reactions (message_id, user_id, reaction) values ($1, $2, $3)
         on conflict (message_id, user_id) do update set reaction = excluded.reaction, created_at = now()`, [id, me.id, b.reaction]);
      return announceReaction(conversationId, id, me, b.reaction);
    });

    app.delete('/messages/:id/reaction', async (req) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const conversationId = await reactableConversation(id, me);
      await pool.query('delete from message_reactions where message_id = $1 and user_id = $2', [id, me.id]);
      return announceReaction(conversationId, id, me, null);
    });

    /** The conversation of an active message the person may react to; anything else is "not found". */
    async function reactableConversation(messageId: string, me: AuthUser): Promise<string> {
      const row = (await pool.query<{ conversation_id: string; sender_id: string | null }>(
        `select m.conversation_id, m.sender_id from messages m
           join conversation_members cm on cm.conversation_id = m.conversation_id and cm.user_id = $2
          where m.id = $1 and m.status = 'active'`, [messageId, me.id])).rows[0];
      if (!row) throw new HttpError(404, 'not_found', 'Not found.');
      if (row.sender_id && row.sender_id !== me.id
        && (await pool.query(`select ${blockedBetween('$1::uuid', '$2::uuid')} as b`, [me.id, row.sender_id])).rows[0].b) {
        throw new HttpError(404, 'not_found', 'Not found.');
      }
      return row.conversation_id;
    }

    async function announceReaction(conversationId: string, messageId: string, me: AuthUser, reaction: string | null) {
      const reactions = await reactionCounts(ctx, messageId);
      hub.sendMany(await audience(pool, conversationId, me.id), { type: 'message:reaction', conversationId, messageId, userId: me.id, reaction, reactions });
      return { messageId, reaction, reactions };
    }

    // ---- search the messages of conversations you belong to (newest first; `before` is the last seq of the previous page)
    app.get('/messages/search', async (req) => {
      const me = await ctx.auth(req);
      const q = z.object({
        q: z.string().trim().min(2).max(80),
        conversationId: z.string().uuid().optional(),
        limit: z.coerce.number().int().min(1).max(50).default(20),
        before: z.coerce.number().int().positive().optional(),
      }).parse(req.query);
      ctx.limit(`msgsearch:${me.id}`, 60, 60);
      if (q.conversationId) await membership(pool, q.conversationId, me.id);
      // Membership is the access rule: a person who left (or was removed) no longer finds anything from that conversation.
      const { rows } = await pool.query(
        `select m.id from messages m
           join conversation_members cm on cm.conversation_id = m.conversation_id and cm.user_id = $1
          where m.status = 'active' and m.content ilike $2 escape '\\'
            and ($3::uuid is null or m.conversation_id = $3)
            and ($4::bigint is null or m.seq < $4)
            and (m.sender_id is null or m.sender_id = $1 or not ${blockedBetween('$1', 'm.sender_id')})
          order by m.seq desc limit $5`,
        [me.id, likePattern(q.q), q.conversationId ?? null, q.before ?? null, q.limit + 1]);
      const more = rows.length > q.limit;
      const ids = (more ? rows.slice(0, q.limit) : rows).map((r) => r.id as string);
      const items = await Promise.all(ids.map((id) => loadMessage(ctx, id, me.id)));
      return { items, nextBefore: more && items.length ? items[items.length - 1].seq : null };
    });

    // ---- operations: queue depth, failures and lag (admins only; everyone else sees "not found")
    app.get('/admin/jobs', async (req) => {
      const me = await ctx.auth(req);
      if (me.role !== 'admin') throw new HttpError(404, 'not_found', 'Not found.');
      return jobStats(pool);
    });
  };
}
