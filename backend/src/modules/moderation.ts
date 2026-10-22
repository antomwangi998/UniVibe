import type { FastifyInstance } from 'fastify';
import type { PoolClient } from 'pg';
import { z } from 'zod';
import { acceptedFollow, userVisible } from '../access';
import type { AuthUser, Ctx } from '../context';
import { withTx } from '../db';
import { HttpError } from '../errors';
import { decodeCursor, idParam, pageSchema, toPage, tsText } from '../http';
import { commentShown, visible } from '../posts';
import { CARD_COLUMNS, CARD_JOIN, type CardRow, toCard } from './cards';
import { eventVisible } from './events';

export const REPORT_REASONS = ['spam', 'harassment', 'hate', 'sexual', 'violence', 'self_harm', 'impersonation', 'misinformation', 'other'] as const;
const TARGETS = ['user', 'post', 'comment', 'message', 'event', 'community', 'story', 'channel_message'] as const;
const MAX_SUSPENSION_DAYS = 30;

const reportSchema = z.object({
  targetType: z.enum(TARGETS),
  targetId: z.string().uuid(),
  reason: z.enum(REPORT_REASONS),
  details: z.string().trim().max(500).default(''),
}).strict();
const reviewSchema = z.object({ status: z.enum(['under_review', 'dismissed']), note: z.string().trim().max(500).default('') }).strict();
const actionSchema = z.object({
  action: z.enum(['remove_content', 'restore_content', 'suspend_user', 'unsuspend_user', 'hide_event', 'restore_event']),
  note: z.string().trim().max(500).default(''),
  /** Shown to the person the action is about, in Settings → Account status. `note` stays internal. */
  userMessage: z.string().trim().max(300).default(''),
  days: z.number().int().min(1).max(MAX_SUSPENSION_DAYS).default(7),
}).strict();

type Q = Pick<PoolClient, 'query'>;

/** Writes who did what to what. Privileged actions always call this inside the same transaction as the action. */
export async function audit(q: Q, actorId: string | null, action: string, targetType: string | null, targetId: string | null, details: Record<string, unknown> = {}) {
  await q.query('insert into audit_logs (actor_id, action, target_type, target_id, details) values ($1, $2, $3, $4, $5)', [actorId, action, targetType, targetId, JSON.stringify(details)]);
}

/** Who owns the reported thing, so an action can reach the right person. */
async function ownerOf(q: Q, type: string, id: string): Promise<string | null> {
  const sql: Record<string, string> = {
    user: 'select id as owner from users where id = $1',
    post: 'select author_id as owner from posts where id = $1',
    comment: 'select author_id as owner from comments where id = $1',
    message: 'select sender_id as owner from messages where id = $1',
    event: 'select organizer_id as owner from events where id = $1',
    community: 'select owner_id as owner from communities where id = $1',
    story: 'select author_id as owner from stories where id = $1',
    channel_message: 'select author_id as owner from community_channel_messages where id = $1',
  };
  return (await q.query<{ owner: string | null }>(sql[type], [id])).rows[0]?.owner ?? null;
}

export function moderationRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;

    const requireModerator = async (req: Parameters<Ctx['auth']>[0]): Promise<AuthUser> => {
      const me = await ctx.auth(req);
      if (me.role !== 'moderator' && me.role !== 'admin') throw new HttpError(403, 'forbidden', 'This is only available to moderators.');
      return me;
    };

    /** A report is accepted only for something the reporter could see (so reporting cannot probe hidden content). */
    async function assertReportable(me: AuthUser, type: (typeof TARGETS)[number], id: string): Promise<void> {
      const checks: Record<string, [string, unknown[]]> = {
        user: [`select 1 from users u where u.id = $3 and u.id <> $1 and ${userVisible('u', '$1', '$2')}`, [me.id, me.universityId, id]],
        post: [`select 1 from posts p join users a on a.id = p.author_id where p.id = $3 and p.author_id <> $1 and ${visible('p', 'a')}`, [me.id, me.universityId, id]],
        comment: [`select 1 from comments c join posts p on p.id = c.post_id join users a on a.id = p.author_id
                    where c.id = $3 and c.author_id <> $1 and ${commentShown('c')} and ${visible('p', 'a')}`, [me.id, me.universityId, id]],
        message: [`select 1 from messages m join conversation_members cm on cm.conversation_id = m.conversation_id and cm.user_id = $1
                    where m.id = $2 and m.sender_id is distinct from $1 and m.status = 'active'`, [me.id, id]],
        event: [`select 1 from events e where e.id = $3 and e.organizer_id <> $1 and ${eventVisible('$1', '$2')}`, [me.id, me.universityId, id]],
        // Only an active Story the reporter may see (same audience rules as viewing it).
        story: [`select 1 from stories s join users a on a.id = s.author_id where s.id = $3 and s.author_id <> $1 and s.expires_at > now()
                  and ${userVisible('a', '$1', '$2')} and (s.audience = 'campus' or ${acceptedFollow('$1::uuid', 's.author_id')})`, [me.id, me.universityId, id]],
        // A channel message only an active member who may read that channel can report (so reporting never reveals private content).
        channel_message: [`select 1 from community_channel_messages cm join community_channels ch on ch.id = cm.channel_id join communities c on c.id = ch.community_id
                            join community_members mm on mm.community_id = c.id and mm.user_id = $1 and mm.status = 'active'
                           where cm.id = $3 and cm.author_id is distinct from $1 and cm.status = 'active' and c.university_id = $2 and c.status in ('active', 'archived')
                             and (ch.view_role = 'members' or mm.role in ('owner', 'moderator')) and (ch.archived_at is null or mm.role in ('owner', 'moderator'))`, [me.id, me.universityId, id]],
        // Only communities whose profile the reporter could see (public, discoverable, or theirs).
        community: [`select 1 from communities c where c.id = $3 and c.owner_id <> $1 and c.university_id = $2 and c.status <> 'deleted' and (c.visibility = 'public' or c.discoverable or exists (select 1 from community_members vm where vm.community_id = c.id and vm.user_id = $1 and vm.status = 'active'))`, [me.id, me.universityId, id]],
      };
      const [sql, params] = checks[type];
      if (!(await pool.query(sql, params)).rowCount) throw new HttpError(404, 'not_found', 'Not found.');
    }

    app.post('/reports', async (req, reply) => {
      const me = await ctx.auth(req);
      const b = reportSchema.parse(req.body ?? {});
      ctx.limit(`report:${me.id}`, 20, 3600);
      await assertReportable(me, b.targetType, b.targetId);
      const made = await pool.query<{ id: string }>(
        `insert into reports (reporter_id, university_id, target_type, target_id, reason, details) values ($1, $2, $3, $4, $5, $6)
         on conflict (reporter_id, target_type, target_id) where reporter_id is not null do nothing returning id`,
        [me.id, me.universityId, b.targetType, b.targetId, b.reason, b.details],
      );
      if (made.rowCount) return reply.status(201).send({ id: made.rows[0].id });
      // Already reported by this person: same answer, nothing new created, nothing revealed.
      const existing = await pool.query<{ id: string }>('select id from reports where reporter_id = $1 and target_type = $2 and target_id = $3', [me.id, b.targetType, b.targetId]);
      return reply.status(200).send({ id: existing.rows[0].id });
    });

    async function preview(r: { target_type: string; target_id: string }): Promise<string | null> {
      const sql: Record<string, string> = {
        post: 'select left(text, 200) as p from posts where id = $1',
        comment: 'select left(text, 200) as p from comments where id = $1',
        message: "select case when status = 'active' then left(content, 200) end as p from messages where id = $1",
        user: 'select handle as p from users where id = $1',
        event: 'select title as p from events where id = $1',
        community: 'select name as p from communities where id = $1',
        channel_message: "select case when status = 'active' then left(coalesce(title, content), 200) end as p from community_channel_messages where id = $1",
        story: "select case when kind = 'text' then left(text, 200) else coalesce(left(caption, 200), kind) end as p from stories where id = $1",
      };
      return (await pool.query<{ p: string | null }>(sql[r.target_type], [r.target_id])).rows[0]?.p ?? null;
    }

    app.get('/moderation/reports', async (req) => {
      const me = await requireModerator(req);
      const q = pageSchema.extend({ status: z.enum(['open', 'under_review', 'action_taken', 'dismissed']).default('open'), targetType: z.enum(TARGETS).optional() }).parse(req.query);
      const cur = decodeCursor(q.cursor);
      const { rows } = await pool.query(
        `select r.id, r.target_type, r.target_id, r.reason, r.details, r.status, r.created_at, ${tsText('r.created_at')} as "cursorT", ${CARD_COLUMNS},
                (select count(*) from reports x where x.target_type = r.target_type and x.target_id = r.target_id)::int as report_count
           from reports r left join users u on u.id = r.reporter_id ${CARD_JOIN}
          where r.university_id = $1 and r.status = $2 and ($6::text is null or r.target_type = $6) and ($3::timestamptz is null or (r.created_at, r.id) < ($3::timestamptz, $4::uuid))
          order by r.created_at desc, r.id desc limit $5`,
        [me.universityId, q.status, cur?.t ?? null, cur?.id ?? null, q.limit + 1, q.targetType ?? null],
      );
      const page = toPage(rows as (CardRow & { cursorT: string; id: string })[], q.limit);
      const items = await Promise.all(page.items.map(async (r) => {
        const row = r as unknown as CardRow & { id: string; target_type: string; target_id: string; reason: string; details: string; status: string; created_at: Date; report_count: number };
        return {
          id: row.id, targetType: row.target_type, targetId: row.target_id, reason: row.reason, details: row.details, status: row.status,
          createdAt: row.created_at, reportCount: row.report_count, preview: await preview(row),
          reporter: row.card_id ? await toCard(ctx, row) : null, // moderators only
        };
      }));
      return { items, nextCursor: page.nextCursor };
    });

    async function loadReport(me: AuthUser, id: string, client: Q = pool) {
      const r = (await client.query('select * from reports where id = $1 and university_id = $2 for update', [id, me.universityId])).rows[0];
      if (!r) throw new HttpError(404, 'not_found', 'Not found.');
      return r as { id: string; target_type: string; target_id: string; status: string };
    }

    app.post('/moderation/reports/:id/review', async (req) => {
      const me = await requireModerator(req);
      const id = idParam((req.params as { id: string }).id);
      const b = reviewSchema.parse(req.body ?? {});
      return withTx(pool, async (client) => {
        const report = await loadReport(me, id, client);
        if (report.status === 'action_taken') throw new HttpError(409, 'conflict', 'This report was already resolved.');
        await client.query(
          `update reports set status = $2, reviewed_by = $3, resolution_note = $4, updated_at = now(),
                 reviewed_at = case when $2 = 'dismissed' then now() else reviewed_at end where id = $1`,
          [id, b.status, me.id, b.note]);
        await audit(client, me.id, `report.${b.status}`, 'report', id);
        return { id, status: b.status };
      });
    });

    /** Hides or restores an event and records who did it. [reportId] is null when a moderator acts from the event itself. */
    async function setEventHidden(client: Q, me: AuthUser, eventId: string, hide: boolean, note: string, userMessage: string, reportId: string | null) {
      const res = await client.query(
        `update events set hidden_at = ${hide ? 'now()' : 'null'}, hidden_by = ${hide ? '$2::uuid' : 'null'}, updated_at = now() where id = $1 and university_id = $3`,
        [eventId, me.id, me.universityId]);
      if (!res.rowCount) throw new HttpError(404, 'not_found', 'That event no longer exists.');
      await client.query(
        'insert into moderation_actions (report_id, moderator_id, action, target_type, target_id, note, user_message) values ($1, $2, $3, $4, $5, $6, $7)',
        [reportId, me.id, hide ? 'hide_event' : 'restore_event', 'event', eventId, note, userMessage]);
      await client.query('insert into event_audit (event_id, actor_id, action) values ($1, $2, $3)', [eventId, me.id, hide ? 'hide' : 'restore']);
      await audit(client, me.id, hide ? 'hide_event' : 'restore_event', 'event', eventId, reportId ? { reportId } : {});
      if (hide) {
        // The reports about a hidden event are settled by the same decision, as with every other action.
        await client.query(
          `update reports set status = 'action_taken', reviewed_by = $1, resolution_note = $2, reviewed_at = now(), updated_at = now()
            where target_type = 'event' and target_id = $3 and university_id = $4 and status in ('open', 'under_review')`,
          [me.id, note, eventId, me.universityId]);
      }
    }

    // Lets the app ask once whether to show moderator tools. 403 for everyone else.
    app.get('/moderation/access', async (req) => { await requireModerator(req); return { allowed: true }; });

    const eventActionSchema = z.object({ note: z.string().trim().max(500).default(''), userMessage: z.string().trim().max(300).default('') }).strict();
    for (const hide of [true, false]) {
      // A moderator can hide an event they came across, without waiting for a report.
      app.post(`/moderation/events/:eventId/${hide ? 'hide' : 'restore'}`, async (req) => {
        const me = await requireModerator(req);
        const eventId = idParam((req.params as { eventId: string }).eventId);
        const b = eventActionSchema.parse(req.body ?? {});
        await withTx(pool, (client) => setEventHidden(client, me, eventId, hide, b.note, b.userMessage, null));
        return { id: eventId, hidden: hide };
      });
    }

    app.post('/moderation/reports/:id/action', async (req) => {
      const me = await requireModerator(req);
      const id = idParam((req.params as { id: string }).id);
      const b = actionSchema.parse(req.body ?? {});
      return withTx(pool, async (client) => {
        const report = await loadReport(me, id, client);
        const { target_type: type, target_id: targetId } = report;
        const record = (targetType: string, tid: string) =>
          client.query('insert into moderation_actions (report_id, moderator_id, action, target_type, target_id, note, user_message) values ($1, $2, $3, $4, $5, $6, $7)', [id, me.id, b.action, targetType, tid, b.note, b.userMessage]);

        if (b.action === 'remove_content' || b.action === 'restore_content') {
          if (type !== 'post' && type !== 'comment' && type !== 'channel_message') throw new HttpError(422, 'validation', 'Only posts, comments and channel messages can be removed or restored.');
          const table = type === 'post' ? 'posts' : type === 'comment' ? 'comments' : 'community_channel_messages';
          // A channel message its author or channel staff already deleted has no content left to restore: only platform removals are reversible.
          const only = type === 'channel_message' ? (b.action === 'remove_content' ? " and status = 'active'" : " and status = 'removed'") : '';
          const res = await client.query(`update ${table} set status = $2${type === 'channel_message' && b.action === 'remove_content' ? ', pinned_at = null, pinned_by = null' : ''} where id = $1${only}`, [targetId, b.action === 'remove_content' ? 'removed' : 'active']);
          if (!res.rowCount) throw new HttpError(404, 'not_found', 'That content no longer exists.');
          await record(type, targetId);
          await audit(client, me.id, b.action, type, targetId, { reportId: id });
        } else if (b.action === 'hide_event' || b.action === 'restore_event') {
          // A hidden event vanishes for everyone but its organizer (see eventVisible); its feed post goes with it.
          if (type !== 'event') throw new HttpError(422, 'validation', 'Only events can be hidden or restored this way.');
          await setEventHidden(client, me, targetId, b.action === 'hide_event', b.note, b.userMessage, id);
        } else {
          const owner = await ownerOf(client, type, targetId);
          if (!owner) throw new HttpError(404, 'not_found', 'That person no longer exists.');
          const person = (await client.query<{ role: string; university_id: string }>('select role, university_id from users where id = $1', [owner])).rows[0];
          if (owner === me.id) throw new HttpError(403, 'forbidden', 'You cannot suspend yourself.');
          // Admins are never suspended by moderation; moderators only by admins.
          if (person.role === 'admin' || (person.role === 'moderator' && me.role !== 'admin')) throw new HttpError(403, 'forbidden', 'You cannot act on this account.');
          if (person.university_id !== me.universityId) throw new HttpError(404, 'not_found', 'Not found.');
          if (b.action === 'suspend_user') {
            await client.query("update users set suspended_until = now() + make_interval(days => $2::int), updated_at = now() where id = $1", [owner, b.days]);
          } else {
            await client.query('update users set suspended_until = null, updated_at = now() where id = $1', [owner]);
          }
          await record('user', owner);
          await audit(client, me.id, b.action, 'user', owner, { reportId: id, days: b.action === 'suspend_user' ? b.days : undefined });
        }
        // Everything open about the same target is settled by the same decision.
        await client.query(
          `update reports set status = 'action_taken', reviewed_by = $1, resolution_note = $2, reviewed_at = now(), updated_at = now()
            where target_type = $3 and target_id = $4 and university_id = $5 and status in ('open', 'under_review')`,
          [me.id, b.note, type, targetId, me.universityId]);
        return { id, status: 'action_taken' };
      });
    });

    app.get('/moderation/audit-logs', async (req) => {
      const me = await ctx.auth(req);
      if (me.role !== 'admin') throw new HttpError(403, 'forbidden', 'This is only available to administrators.');
      const q = pageSchema.parse(req.query);
      const cur = decodeCursor(q.cursor);
      const { rows } = await pool.query(
        `select a.id, a.actor_id, a.action, a.target_type, a.target_id, a.details, a.created_at, ${tsText('a.created_at')} as "cursorT"
           from audit_logs a join users u on u.id = a.actor_id
          where u.university_id = $1 and ($2::timestamptz is null or (a.created_at, a.id) < ($2::timestamptz, $3::uuid))
          order by a.created_at desc, a.id desc limit $4`,
        [me.universityId, cur?.t ?? null, cur?.id ?? null, q.limit + 1],
      );
      const page = toPage(rows as (Record<string, unknown> & { cursorT: string; id: string })[], q.limit);
      return {
        items: page.items.map((r) => ({ id: r.id, actorId: r.actor_id, action: r.action, targetType: r.target_type, targetId: r.target_id, details: r.details, createdAt: r.created_at })),
        nextCursor: page.nextCursor,
      };
    });
  };
}
