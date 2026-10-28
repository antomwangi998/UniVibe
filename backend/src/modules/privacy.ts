import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import type { Ctx } from '../context';
import { withTx } from '../db';
import { HttpError } from '../errors';
import { decodeCursor, idParam, pageSchema, toPage, tsText } from '../http';
import { crossCampusOk } from '../access';
import { CARD_COLUMNS, CARD_JOIN, type CardRow, toCard } from './cards';

const privacyPut = z.object({
  discoverable: z.boolean(),
  showOnline: z.boolean(),
  readReceipts: z.boolean(),
  typingIndicators: z.boolean(),
  showOtherCampuses: z.boolean(),
  messagePermission: z.enum(['everyone', 'followers', 'nobody']),
}).partial().strict();

const PRIVACY_SQL = 'select discoverable, show_online, read_receipts, typing_indicators, message_permission, show_other_campuses from privacy_settings where user_id = $1';
const toPrivacy = (r?: Record<string, unknown>) => ({
  discoverable: r?.discoverable ?? true,
  showOnline: r?.show_online ?? true,
  readReceipts: r?.read_receipts ?? true,
  typingIndicators: r?.typing_indicators ?? true,
  messagePermission: r?.message_permission ?? 'everyone',
  showOtherCampuses: r?.show_other_campuses ?? true,
});

/**
 * Privacy settings and blocking.
 *
 * What a block does (both directions look identical and read as "not found"):
 *  - profiles, posts, comments, follower lists and Discover hide each person from the other;
 *  - any follow between the two is removed and neither can follow again;
 *  - messaging, new conversations and event attendee lists exclude the pair, and notifications between them are cleared.
 */
export function privacyRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;

    app.get('/me/privacy', async (req) => {
      const me = await ctx.auth(req);
      return toPrivacy((await pool.query(PRIVACY_SQL, [me.id])).rows[0]);
    });

    app.put('/me/privacy', async (req) => {
      const me = await ctx.auth(req);
      const b = privacyPut.parse(req.body ?? {});
      await pool.query(
        `insert into privacy_settings (user_id, discoverable, show_online, message_permission, read_receipts, typing_indicators, show_other_campuses)
         values ($1, coalesce($2::boolean, true), coalesce($3::boolean, true), coalesce($4::text, 'everyone'), coalesce($5::boolean, true), coalesce($6::boolean, true), coalesce($7::boolean, true))
         on conflict (user_id) do update set
           discoverable = coalesce($2::boolean, privacy_settings.discoverable),
           show_online = coalesce($3::boolean, privacy_settings.show_online),
           message_permission = coalesce($4::text, privacy_settings.message_permission),
           read_receipts = coalesce($5::boolean, privacy_settings.read_receipts),
           typing_indicators = coalesce($6::boolean, privacy_settings.typing_indicators),
           show_other_campuses = coalesce($7::boolean, privacy_settings.show_other_campuses), updated_at = now()`,
        [me.id, b.discoverable ?? null, b.showOnline ?? null, b.messagePermission ?? null, b.readReceipts ?? null, b.typingIndicators ?? null, b.showOtherCampuses ?? null],
      );
      return toPrivacy((await pool.query(PRIVACY_SQL, [me.id])).rows[0]);
    });

    app.post('/users/:userId/block', async (req, reply) => {
      const me = await ctx.auth(req);
      const targetId = idParam((req.params as { userId: string }).userId);
      if (targetId === me.id) throw new HttpError(400, 'validation', 'You cannot block yourself.');
      ctx.limit(`block:${me.id}`, 60, 3600);
      const exists = await pool.query(`select 1 from users u where u.id = $1 and ${crossCampusOk('u', '$2')}`, [targetId, me.universityId]);
      if (!exists.rowCount) throw new HttpError(404, 'not_found', 'Not found.');
      await withTx(pool, async (client) => {
        await client.query('insert into blocks (blocker_id, blocked_id) values ($1, $2) on conflict do nothing', [me.id, targetId]);
        await client.query(
          'delete from follows where (follower_id = $1 and followee_id = $2) or (follower_id = $2 and followee_id = $1)', [me.id, targetId]);
        await client.query(
          'delete from notifications where (recipient_id = $1 and actor_id = $2) or (recipient_id = $2 and actor_id = $1)', [me.id, targetId]);
      });
      return reply.status(204).send();
    });

    app.delete('/users/:userId/block', async (req, reply) => {
      const me = await ctx.auth(req);
      await pool.query('delete from blocks where blocker_id = $1 and blocked_id = $2', [me.id, idParam((req.params as { userId: string }).userId)]);
      return reply.status(204).send();
    });

    app.get('/me/blocked-users', async (req) => {
      const me = await ctx.auth(req);
      const q = pageSchema.parse(req.query);
      const cur = decodeCursor(q.cursor);
      const { rows } = await pool.query(
        `select u.id, ${CARD_COLUMNS}, ${tsText('b.created_at')} as "cursorT"
           from blocks b join users u on u.id = b.blocked_id ${CARD_JOIN}
          where b.blocker_id = $1 and ($2::timestamptz is null or (b.created_at, u.id) < ($2::timestamptz, $3::uuid))
          order by b.created_at desc, u.id desc limit $4`,
        [me.id, cur?.t ?? null, cur?.id ?? null, q.limit + 1],
      );
      const page = toPage(rows as (CardRow & { cursorT: string; id: string })[], q.limit);
      return { items: await Promise.all(page.items.map((r) => toCard(ctx, r as unknown as CardRow))), nextCursor: page.nextCursor };
    });
  };
}
