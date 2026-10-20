import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { blockedBetween } from '../access';
import type { Ctx } from '../context';
import type { Hub } from '../realtime';
import { unreadCount } from './delivery';
import { HttpError } from '../errors';
import { decodeCursor, idParam, pageSchema, toPage, tsText } from '../http';
import { CARD_COLUMNS, CARD_JOIN, type CardRow, toCard } from './cards';

const prefsSchema = z.object({
  follows: z.boolean().optional(), likes: z.boolean().optional(), comments: z.boolean().optional(),
  messages: z.boolean().optional(), events: z.boolean().optional(), groups: z.boolean().optional(), communities: z.boolean().optional(),
  pushEnabled: z.boolean().optional(),
  pushMessages: z.boolean().optional(), pushSocial: z.boolean().optional(), pushEvents: z.boolean().optional(),
}).strict();

const PREF_SQL =
  'select follows, likes, comments, messages, events, groups, communities, push_enabled, push_messages, push_social, push_events from notification_preferences where user_id = $1';
const toPrefs = (r?: Record<string, boolean>) => ({
  follows: r?.follows ?? true, likes: r?.likes ?? true, comments: r?.comments ?? true, messages: r?.messages ?? true,
  events: r?.events ?? true, groups: r?.groups ?? true, communities: r?.communities ?? true, pushEnabled: r?.push_enabled ?? true,
  pushMessages: r?.push_messages ?? true, pushSocial: r?.push_social ?? true, pushEvents: r?.push_events ?? true,
});

export function notificationRoutes(ctx: Ctx, hub: Hub) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;

    app.get('/notifications', async (req) => {
      const me = await ctx.auth(req);
      const q = pageSchema.extend({ unread: z.enum(['true', 'false']).optional() }).parse(req.query);
      const cur = decodeCursor(q.cursor);
      const { rows } = await pool.query(
        `select n.id, n.type, n.entity_type, n.entity_id, n.metadata, n.read_at, n.created_at, ${tsText('n.created_at')} as "cursorT",
                floor(extract(epoch from (now() - n.created_at)))::int as age_seconds,
                ${CARD_COLUMNS}
           from notifications n
           left join users u on u.id = n.actor_id ${CARD_JOIN}
          where n.recipient_id = $1
            and (n.actor_id is null or not ${blockedBetween('$1', 'n.actor_id')})
            and ($2::boolean is not true or n.read_at is null)
            and ($3::timestamptz is null or (n.created_at, n.id) < ($3::timestamptz, $4::uuid))
          order by n.created_at desc, n.id desc limit $5`,
        [me.id, q.unread === 'true' ? true : null, cur?.t ?? null, cur?.id ?? null, q.limit + 1],
      );
      const page = toPage(rows as (Record<string, unknown> & { cursorT: string; id: string })[], q.limit);
      const items = await Promise.all(
        page.items.map(async (r) => {
          const row = r as unknown as CardRow & {
            id: string; type: string; entity_type: string | null; entity_id: string | null;
            metadata: object; read_at: Date | null; created_at: Date; age_seconds: number;
          };
          return {
            id: row.id, type: row.type, actor: row.card_id ? await toCard(ctx, row) : null,
            entityType: row.entity_type, entityId: row.entity_id, metadata: row.metadata,
            readAt: row.read_at, createdAt: row.created_at, ageSeconds: row.age_seconds,
          };
        }),
      );
      return { items, nextCursor: page.nextCursor };
    });

    app.get('/notifications/unread-count', async (req) => {
      const me = await ctx.auth(req);
      const { rows } = await pool.query<{ count: string }>(
        `select count(*) from notifications n where n.recipient_id = $1 and n.read_at is null
            and (n.actor_id is null or not ${blockedBetween('$1', 'n.actor_id')})`,
        [me.id],
      );
      return { count: Number(rows[0].count) };
    });

    app.post('/notifications/read-all', async (req, reply) => {
      const me = await ctx.auth(req);
      await pool.query('update notifications set read_at = now() where recipient_id = $1 and read_at is null', [me.id]);
      hub.send(me.id, { type: 'notification:unread', unreadCount: 0 }); // the person's other open apps update their badge
      return reply.status(204).send();
    });

    app.post('/notifications/:id/read', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = idParam((req.params as { id: string }).id);
      const res = await pool.query(
        'update notifications set read_at = coalesce(read_at, now()) where id = $1 and recipient_id = $2', [id, me.id],
      );
      if (!res.rowCount) throw new HttpError(404, 'not_found', 'Not found.');
      hub.send(me.id, { type: 'notification:unread', unreadCount: await unreadCount(ctx, me.id) });
      return reply.status(204).send();
    });

    app.delete('/notifications/:id', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = idParam((req.params as { id: string }).id);
      const res = await pool.query('delete from notifications where id = $1 and recipient_id = $2', [id, me.id]);
      if (!res.rowCount) throw new HttpError(404, 'not_found', 'Not found.');
      hub.send(me.id, { type: 'notification:unread', unreadCount: await unreadCount(ctx, me.id) });
      return reply.status(204).send();
    });

    app.get('/me/notification-preferences', async (req) => {
      const me = await ctx.auth(req);
      return toPrefs((await pool.query(PREF_SQL, [me.id])).rows[0]);
    });

    app.put('/me/notification-preferences', async (req) => {
      const me = await ctx.auth(req);
      const b = prefsSchema.parse(req.body ?? {});
      await pool.query(
        `insert into notification_preferences (user_id, follows, likes, comments, messages, events, groups, push_enabled, push_messages, push_social, push_events, communities)
         values ($1, coalesce($2, true), coalesce($3, true), coalesce($4, true), coalesce($5, true), coalesce($6, true), coalesce($7, true), coalesce($8, true), coalesce($9, true), coalesce($10, true), coalesce($11, true), coalesce($12, true))
         on conflict (user_id) do update set
           follows = coalesce($2, notification_preferences.follows), likes = coalesce($3, notification_preferences.likes),
           comments = coalesce($4, notification_preferences.comments), messages = coalesce($5, notification_preferences.messages),
           events = coalesce($6, notification_preferences.events), groups = coalesce($7, notification_preferences.groups),
           push_enabled = coalesce($8, notification_preferences.push_enabled),
           push_messages = coalesce($9, notification_preferences.push_messages), push_social = coalesce($10, notification_preferences.push_social),
           push_events = coalesce($11, notification_preferences.push_events),
           communities = coalesce($12, notification_preferences.communities)`,
        [me.id, b.follows ?? null, b.likes ?? null, b.comments ?? null, b.messages ?? null, b.events ?? null, b.groups ?? null, b.pushEnabled ?? null, b.pushMessages ?? null, b.pushSocial ?? null, b.pushEvents ?? null, b.communities ?? null],
      );
      return toPrefs((await pool.query(PREF_SQL, [me.id])).rows[0]);
    });
  };
}
