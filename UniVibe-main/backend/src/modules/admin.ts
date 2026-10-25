import type { FastifyInstance } from 'fastify';
import type { Pool, PoolClient } from 'pg';
import { z } from 'zod';
import type { AuthUser, Ctx } from '../context';
import { withTx } from '../db';
import { HttpError } from '../errors';
import { decodeCursor, idParam, pageSchema, toPage, tsText } from '../http';
import { jobStats } from '../jobs';
import { audit } from './moderation';
import { makeNeed, likeOf, ymd } from '../admin_guard';

/**
 * The admin dashboard's data. Numbers and account state only: nothing here returns message text, and nobody but an
 * administrator can call it (everyone else is told "not found", so the routes do not advertise themselves).
 * Moderators use the existing /moderation/* routes for the reports queue.
 */

/** Daily counts for the last 14 days. These strings are fixed in this file; no user input is ever added to them. */
const SERIES: Record<string, string> = {
  signups: 'select created_at::date as day, count(*) as n from users where created_at >= current_date - 13 group by 1',
  posts: "select created_at::date as day, count(*) as n from posts where status = 'active' and created_at >= current_date - 13 group by 1",
  messages: "select created_at::date as day, count(*) as n from messages where status = 'active' and created_at >= current_date - 13 group by 1",
  events: 'select created_at::date as day, count(*) as n from events where created_at >= current_date - 13 group by 1',
  reports: 'select created_at::date as day, count(*) as n from reports where created_at >= current_date - 13 group by 1',
};

async function series(pool: Pool, sql: string): Promise<{ day: string; n: number }[]> {
  const { rows } = await pool.query<{ day: string; n: number }>(
    `select to_char(d, 'YYYY-MM-DD') as day, coalesce(c.n, 0)::int as n
       from generate_series(current_date - 13, current_date, interval '1 day') d
       left join (${sql}) c on c.day = d::date order by d`);
  return rows;
}

/**
 * Analytics metrics. Every table, column and condition below is a fixed string in this file; the only things that
 * come from the request are two dates, and those are passed as query parameters, never joined into the SQL.
 * Days are UTC days, so a "day" means the same thing no matter where the server runs.
 */
const METRICS: Record<string, { table: string; ts: string; where: string }> = {
  signups: { table: 'users', ts: 'created_at', where: 'true' },
  verified: { table: 'users', ts: 'email_verified_at', where: 'true' },
  posts: { table: 'posts', ts: 'created_at', where: "status = 'active'" },
  comments: { table: 'comments', ts: 'created_at', where: "status = 'active'" },
  messages: { table: 'messages', ts: 'created_at', where: "status = 'active'" },
  communities: { table: 'communities', ts: 'created_at', where: 'true' },
  joins: { table: 'community_members', ts: 'joined_at', where: 'true' },
  events: { table: 'events', ts: 'created_at', where: 'true' },
  rsvps: { table: 'event_registrations', ts: 'registered_at', where: "status = 'registered'" },
  reports: { table: 'reports', ts: 'created_at', where: 'true' },
};

const rangeSchema = z.object({ days: z.coerce.number().int().refine((n) => [7, 30, 90].includes(n)).optional(), from: ymd.optional(), to: ymd.optional() }).strict();
const DAY = 86_400_000;
const MAX_RANGE_DAYS = 366;
const utcDay = (d: Date) => d.toISOString().slice(0, 10);

/** Turns ?days=30 or ?from=&to= into an inclusive UTC date range, plus the equally long period just before it. */
export function resolveRange(query: unknown, now = new Date()) {
  const q = rangeSchema.safeParse(query);
  if (!q.success) throw new HttpError(400, 'bad_request', 'Pick 7, 30 or 90 days, or a valid from and to date.');
  const today = new Date(`${utcDay(now)}T00:00:00Z`);
  let from: Date; let to: Date;
  if (q.data.from || q.data.to) {
    if (!q.data.from || !q.data.to) throw new HttpError(400, 'bad_request', 'Give both a start and an end date.');
    from = new Date(`${q.data.from}T00:00:00Z`); to = new Date(`${q.data.to}T00:00:00Z`);
    if (to > today) to = today;
    if (from > to) throw new HttpError(400, 'bad_request', 'The start date is after the end date.');
  } else {
    const n = q.data.days ?? 30;
    to = today; from = new Date(today.getTime() - (n - 1) * DAY);
  }
  const length = Math.round((to.getTime() - from.getTime()) / DAY) + 1;
  if (length > MAX_RANGE_DAYS) throw new HttpError(400, 'bad_request', `Choose at most ${MAX_RANGE_DAYS} days.`);
  const prevTo = new Date(from.getTime() - DAY); const prevFrom = new Date(prevTo.getTime() - (length - 1) * DAY);
  return { from: utcDay(from), to: utcDay(to), prevFrom: utcDay(prevFrom), prevTo: utcDay(prevTo), length };
}

const DAILY = (m: { table: string; ts: string; where: string }) =>
  `select to_char(d, 'YYYY-MM-DD') as day, coalesce(c.n, 0)::int as n
     from generate_series($1::date, $2::date, interval '1 day') d
     left join (select (${m.ts} at time zone 'utc')::date as day, count(*) as n from ${m.table}
                 where ${m.where} and ${m.ts} >= $1::date and ${m.ts} < $2::date + 1 group by 1) c on c.day = d::date
    order by d`;


// A reason is required for every suspension and every lifting of one; it is kept with the action and shown to staff.
const reason = z.string().trim().min(3, 'Please give a reason (at least 3 characters).').max(500);
const suspendSchema = z.object({
  days: z.number().int().min(1).max(30).default(7),
  note: reason,
  userMessage: z.string().trim().max(300).default(''),
}).strict();
const unsuspendSchema = z.object({ note: reason, userMessage: z.string().trim().max(300).default('') }).strict();

export function adminRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;
    const need = makeNeed(ctx);

    // ---- 1. overview: how big, how active, how busy the safety queue is
    app.get('/admin/overview', async (req) => {
      await need(req, 'overview.view');
      const totals = (await pool.query(
        `select
           (select count(*) from users where deactivated_at is null)::int as users,
           (select count(*) from users where deactivated_at is null and email_verified_at is not null)::int as verified,
           (select count(*) from users where suspended_until > now())::int as suspended,
           (select count(distinct user_id) from refresh_tokens where created_at > now() - interval '1 day')::int as "activeToday",
           (select count(distinct user_id) from refresh_tokens where created_at > now() - interval '7 days')::int as "activeWeek",
           (select count(*) from posts where status = 'active')::int as posts,
           (select count(*) from comments where status = 'active')::int as comments,
           (select count(*) from messages where status = 'active')::int as messages,
           (select count(*) from events)::int as events,
           (select count(*) from communities)::int as communities`)).rows[0];
      const reports = Object.fromEntries((await pool.query<{ status: string; n: number }>('select status, count(*)::int as n from reports group by status')).rows.map((r) => [r.status, r.n]));
      const days: Record<string, { day: string; n: number }[]> = {};
      for (const [name, sql] of Object.entries(SERIES)) days[name] = await series(pool, sql);
      return { totals, reports, days };
    });

    // ---- 2. people: find an account, see its state, suspend or restore it
    app.get('/admin/users', async (req) => {
      await need(req, 'users.view');
      const q = z.object({
        q: z.string().trim().min(2).max(80).optional(),
        status: z.enum(['all', 'active', 'suspended', 'unverified', 'deactivated', 'reported', 'recent']).default('all'),
        university: z.string().trim().min(1).max(60).optional(),
        sort: z.enum(['newest', 'oldest', 'name', 'seen']).default('newest'),
        page: z.coerce.number().int().min(1).max(1000).default(1),
        limit: z.coerce.number().int().min(1).max(50).default(25),
      }).strict().parse(req.query);
      // Every fragment below is fixed text in this file; only the values travel as parameters.
      const where: string[] = []; const args: unknown[] = [];
      if (q.q) { args.push(likeOf(q.q)); where.push(`(u.email ilike $${args.length} or u.full_name ilike $${args.length} or u.handle ilike $${args.length})`); }
      if (q.university) { args.push(q.university); where.push(`u.university_id = $${args.length}`); }
      const STATUS: Record<string, string> = {
        active: 'u.deactivated_at is null and (u.suspended_until is null or u.suspended_until <= now())',
        suspended: 'u.suspended_until > now()',
        unverified: 'u.email_verified_at is null',
        deactivated: 'u.deactivated_at is not null',
        reported: "exists (select 1 from reports r where r.target_type = 'user' and r.target_id = u.id and r.status in ('open', 'under_review'))",
        recent: "u.created_at > now() - interval '7 days'",
      };
      if (q.status !== 'all') where.push(STATUS[q.status]);
      const ORDER: Record<string, string> = {
        newest: 'u.created_at desc, u.id desc', oldest: 'u.created_at asc, u.id asc',
        name: 'lower(u.full_name) asc, u.id asc', seen: 'u.last_seen_at desc nulls last, u.id desc',
      };
      const clause = where.length ? `where ${where.join(' and ')}` : '';
      const total = (await pool.query<{ n: number }>(`select count(*)::int as n from users u ${clause}`, args)).rows[0].n;
      const { rows } = await pool.query(
        `select u.id, u.full_name, u.handle, u.email, u.university_id, u.role, u.created_at, u.email_verified_at, u.suspended_until, u.deactivated_at, u.last_seen_at,
                (select count(*) from reports r where r.target_type = 'user' and r.target_id = u.id and r.status in ('open', 'under_review'))::int as "openReports"
           from users u ${clause} order by ${ORDER[q.sort]} limit $${args.length + 1} offset $${args.length + 2}`, [...args, q.limit, (q.page - 1) * q.limit]);
      const universities = (await pool.query('select id, name from universities order by name')).rows;
      return { items: rows.map(userRow), total, page: q.page, pages: Math.max(1, Math.ceil(total / q.limit)), universities };
    });

    app.get('/admin/users/:id', async (req) => {
      await need(req, 'users.view');
      const id = idParam((req.params as { id: string }).id);
      const u = (await pool.query(
        `select u.id, u.full_name, u.handle, u.email, u.university_id, u.role, u.created_at, u.email_verified_at, u.suspended_until, u.deactivated_at,
                (select count(*) from posts p where p.author_id = u.id and p.status = 'active')::int as posts,
                (select count(*) from comments c where c.author_id = u.id and c.status = 'active')::int as comments,
                (select count(*) from messages m where m.sender_id = u.id and m.status = 'active')::int as messages,
                (select count(*) from reports r where r.target_type = 'user' and r.target_id = u.id)::int as "reportsAboutThem",
                (select count(*) from reports r where r.reporter_id = u.id)::int as "reportsTheyMade",
                (select count(*) from refresh_tokens t where t.user_id = u.id and t.revoked_at is null and t.expires_at > now())::int as sessions,
                (select count(*) from community_members cm where cm.user_id = u.id and cm.status = 'active')::int as communities,
                (select count(*) from events e where e.organizer_id = u.id)::int as "eventsHosted",
                (select count(*) from event_registrations er where er.user_id = u.id and er.status = 'registered')::int as "eventsJoined",
                u.last_seen_at
           from users u where u.id = $1`, [id])).rows[0];
      if (!u) throw new HttpError(404, 'not_found', 'Not found.');
      const actions = (await pool.query(
        `select a.action, a.note, a.created_at, m.full_name as by
           from moderation_actions a left join users m on m.id = a.moderator_id
          where a.target_type = 'user' and a.target_id = $1 order by a.created_at desc limit 10`, [id])).rows;
      const notes = (await pool.query(
        `select n.id, n.note, n.created_at, a.full_name as by from admin_notes n left join users a on a.id = n.author_id
          where n.user_id = $1 order by n.created_at desc limit 30`, [id])).rows;
      return {
        ...userRow(u),
        counts: { posts: u.posts, comments: u.comments, messages: u.messages, reportsAboutThem: u.reportsAboutThem, reportsTheyMade: u.reportsTheyMade, sessions: u.sessions,
                  communities: u.communities, eventsHosted: u.eventsHosted, eventsJoined: u.eventsJoined },
        notes: notes.map((n) => ({ id: n.id, note: n.note, at: n.created_at, by: n.by })),
        actions: actions.map((a) => ({ action: a.action, note: a.note, at: a.created_at, by: a.by })),
      };
    });

    app.post('/admin/users/:id/notes', async (req) => {
      const me = await need(req, 'users.note');
      const id = idParam((req.params as { id: string }).id);
      const b = z.object({ note: z.string().trim().min(1).max(1000) }).strict().parse(req.body ?? {});
      await withTx(pool, async (client) => {
        if (!(await client.query('select 1 from users where id = $1', [id])).rowCount) throw new HttpError(404, 'not_found', 'Not found.');
        await client.query('insert into admin_notes (user_id, author_id, note) values ($1, $2, $3)', [id, me.id, b.note]);
        await audit(client, me.id, 'admin_note_add', 'user', id, { length: b.note.length }); // the note itself stays out of the log
      });
      return { ok: true };
    });

    const settle = async (me: AuthUser, id: string, run: (client: PoolClient) => Promise<void>) =>
      withTx(pool, async (client) => {
        const person = (await client.query<{ role: string }>('select role from users where id = $1 for update', [id])).rows[0];
        if (!person) throw new HttpError(404, 'not_found', 'Not found.');
        if (id === me.id) throw new HttpError(403, 'forbidden', 'You cannot do this to your own account.');
        if (person.role === 'admin') throw new HttpError(403, 'forbidden', 'Administrators cannot be suspended here.');
        await run(client);
      });

    app.post('/admin/users/:id/suspend', async (req) => {
      const me = await need(req, 'users.suspend');
      const id = idParam((req.params as { id: string }).id);
      const b = suspendSchema.parse(req.body ?? {});
      await settle(me, id, async (client) => {
        await client.query("update users set suspended_until = now() + make_interval(days => $2::int), updated_at = now() where id = $1", [id, b.days]);
        await client.query("insert into moderation_actions (report_id, moderator_id, action, target_type, target_id, note, user_message) values (null, $1, 'suspend_user', 'user', $2, $3, $4)", [me.id, id, b.note, b.userMessage]);
        await audit(client, me.id, 'suspend_user', 'user', id, { days: b.days, from: 'dashboard' });
      });
      return { ok: true };
    });

    app.post('/admin/users/:id/unsuspend', async (req) => {
      const me = await need(req, 'users.suspend');
      const id = idParam((req.params as { id: string }).id);
      const b = unsuspendSchema.parse(req.body ?? {});
      await settle(me, id, async (client) => {
        await client.query('update users set suspended_until = null, updated_at = now() where id = $1', [id]);
        await client.query("insert into moderation_actions (report_id, moderator_id, action, target_type, target_id, note, user_message) values (null, $1, 'unsuspend_user', 'user', $2, $3, $4)", [me.id, id, b.note, b.userMessage]);
        await audit(client, me.id, 'unsuspend_user', 'user', id, { from: 'dashboard' });
      });
      return { ok: true };
    });

    // ---- 3. system: is anything stuck or failing
    app.get('/admin/health', async (req) => {
      await need(req, 'system.view');
      const one = async (sql: string) => (await pool.query(sql)).rows[0];
      const tokens = await one('select count(*) filter (where revoked_at is null)::int as active, count(*) filter (where revoked_at is not null)::int as revoked from device_tokens');
      const media = await one("select count(*) filter (where status = 'pending')::int as pending, count(*)::int as total from media");
      const live = await one('select count(*)::int as n from live_locations where ended_at is null and expires_at > now()');
      const db = await one('select pg_database_size(current_database())::bigint as bytes');
      const mem = process.memoryUsage();
      return {
        jobs: await jobStats(pool),
        pushTokens: tokens,
        media,
        liveShares: live.n,
        database: { bytes: Number(db.bytes) },
        server: { uptimeSeconds: Math.round(process.uptime()), node: process.version, rssMb: Math.round(mem.rss / 1048576), heapMb: Math.round(mem.heapUsed / 1048576) },
        checkedAt: new Date().toISOString(),
      };
    });

    // ---- 4. audit trail, with who did it
    app.get('/admin/audit', async (req) => {
      await need(req, 'audit.view');
      const q = pageSchema.parse(req.query);
      const cur = decodeCursor(q.cursor);
      const { rows } = await pool.query(
        `select a.id, a.action, a.target_type, a.target_id, a.details, a.created_at, ${tsText('a.created_at')} as "cursorT", u.full_name as actor_name, u.handle as actor_handle
           from audit_logs a left join users u on u.id = a.actor_id
          where ($1::timestamptz is null or (a.created_at, a.id) < ($1::timestamptz, $2::uuid))
          order by a.created_at desc, a.id desc limit $3`,
        [cur?.t ?? null, cur?.id ?? null, q.limit + 1]);
      const page = toPage(rows as (Record<string, unknown> & { cursorT: string; id: string })[], q.limit);
      return {
        items: page.items.map((r) => ({ id: r.id, action: r.action, targetType: r.target_type, targetId: r.target_id, details: r.details, at: r.created_at, actor: r.actor_name ? { name: r.actor_name, handle: r.actor_handle } : null })),
        nextCursor: page.nextCursor,
      };
    });
    // ---- 5. analytics: activity inside a chosen period, compared with the period just before it
    app.get('/admin/analytics', async (req) => {
      await need(req, 'analytics.view');
      const r = resolveRange(req.query);
      const daily: Record<string, { day: string; n: number }[]> = {};
      const totals: Record<string, { current: number; previous: number }> = {};
      for (const [name, m] of Object.entries(METRICS)) {
        const rows = (await pool.query<{ day: string; n: number }>(DAILY(m), [r.from, r.to])).rows;
        const prev = (await pool.query<{ n: number }>(
          `select count(*)::int as n from ${m.table} where ${m.where} and ${m.ts} >= $1::date and ${m.ts} < $2::date + 1`, [r.prevFrom, r.prevTo])).rows[0].n;
        daily[name] = rows;
        totals[name] = { current: rows.reduce((a, x) => a + x.n, 0), previous: prev };
      }
      // "Active" = signed in or kept a session alive that day (refresh_tokens rows are created at both).
      const active = (await pool.query<{ day: string; n: number }>(
        `select to_char(d, 'YYYY-MM-DD') as day, coalesce(c.n, 0)::int as n
           from generate_series($1::date, $2::date, interval '1 day') d
           left join (select (created_at at time zone 'utc')::date as day, count(distinct user_id) as n from refresh_tokens
                       where created_at >= $1::date and created_at < $2::date + 1 group by 1) c on c.day = d::date order by d`, [r.from, r.to])).rows;
      const activeDistinct = async (a: string, b: string) => (await pool.query<{ n: number }>(
        'select count(distinct user_id)::int as n from refresh_tokens where created_at >= $1::date and created_at < $2::date + 1', [a, b])).rows[0].n;
      const reasons = (await pool.query<{ reason: string; n: number }>(
        'select reason, count(*)::int as n from reports where created_at >= $1::date and created_at < $2::date + 1 group by 1 order by n desc', [r.from, r.to])).rows;
      const review = (await pool.query<{ reviewed: number; hours: number | null }>(
        `select count(*)::int as reviewed, round((avg(extract(epoch from (first_reviewed_at - created_at))) / 3600)::numeric, 1)::float as hours
           from reports where created_at >= $1::date and created_at < $2::date + 1 and first_reviewed_at is not null`, [r.from, r.to])).rows[0];
      const state = (await pool.query(
        `select count(*) filter (where deactivated_at is null)::int as users,
                count(*) filter (where deactivated_at is null and email_verified_at is not null)::int as verified,
                count(*) filter (where suspended_until > now())::int as suspended
           from users`)).rows[0];
      return {
        range: r, daily, totals,
        active: { daily: active, distinct: { current: await activeDistinct(r.from, r.to), previous: await activeDistinct(r.prevFrom, r.prevTo) } },
        reports: { byReason: reasons, reviewed: review.reviewed, avgHoursToFirstReview: review.hours },
        accounts: state,
      };
    });
    // ---- 6. trust and safety: what came in, how fast it is handled, what is still waiting
    app.get('/admin/trust-safety', async (req) => {
      await need(req, 'analytics.view');
      const r = resolveRange(req.query);
      const win = 'created_at >= $1::date and created_at < $2::date + 1';
      const rows = async <T>(sql: string, args: unknown[] = [r.from, r.to]) => (await pool.query(sql, args)).rows as T[];
      const timing = async (stamp: string, where: string) => (await pool.query<{ n: number; avg: number | null; median: number | null }>(
        `select count(*)::int as n,
                round((avg(extract(epoch from (${stamp} - created_at))) / 3600)::numeric, 1)::float as avg,
                round((percentile_cont(0.5) within group (order by extract(epoch from (${stamp} - created_at))) / 3600)::numeric, 1)::float as median
           from reports where ${win} and ${where}`, [r.from, r.to])).rows[0];
      const waiting = (await pool.query(
        `select count(*)::int as total,
                count(*) filter (where created_at > now() - interval '1 day')::int as "underDay",
                count(*) filter (where created_at <= now() - interval '1 day' and created_at > now() - interval '3 days')::int as "oneToThreeDays",
                count(*) filter (where created_at <= now() - interval '3 days')::int as "overThreeDays",
                count(*) filter (where escalated_at is not null)::int as escalated,
                count(*) filter (where assigned_to is null)::int as unassigned
           from reports where status in ('open', 'under_review')`)).rows[0];
      return {
        range: r,
        daily: {
          reports: (await pool.query(DAILY(METRICS.reports), [r.from, r.to])).rows,
          actions: (await pool.query(DAILY({ table: 'moderation_actions', ts: 'created_at', where: 'true' }), [r.from, r.to])).rows,
        },
        received: (await pool.query(`select count(*)::int as n from reports where ${win}`, [r.from, r.to])).rows[0].n,
        byReason: await rows<{ reason: string; n: number }>(`select reason, count(*)::int as n from reports where ${win} group by 1 order by n desc`),
        byTarget: await rows<{ targetType: string; n: number }>(`select target_type as "targetType", count(*)::int as n from reports where ${win} group by 1 order by n desc`),
        actionsByType: await rows<{ action: string; n: number }>(`select action, count(*)::int as n from moderation_actions where ${win} group by 1 order by n desc`),
        waiting,
        // Only reports filed in the period are measured, and the ones still waiting are counted separately so a long queue cannot hide behind a fast average.
        firstReview: await timing('first_reviewed_at', 'first_reviewed_at is not null'),
        resolution: await timing('reviewed_at', "status in ('action_taken', 'dismissed') and reviewed_at is not null"),
        stillWaitingFromPeriod: (await pool.query(`select count(*)::int as n from reports where ${win} and status in ('open', 'under_review')`, [r.from, r.to])).rows[0].n,
        repeated: {
          targets: (await pool.query(`select count(*)::int as n from (select 1 from reports where ${win} group by target_type, target_id having count(*) >= 2) x`, [r.from, r.to])).rows[0].n,
          top: await rows<{ targetType: string; targetId: string; n: number }>(
            `select target_type as "targetType", target_id as "targetId", count(*)::int as n from reports where ${win} group by 1, 2 having count(*) >= 2 order by n desc, 2 limit 5`),
        },
      };
    });
  };
}

function userRow(u: Record<string, unknown>) {
  const suspendedUntil = u.suspended_until ? new Date(u.suspended_until as string) : null;
  return {
    id: u.id, name: u.full_name, handle: u.handle, email: u.email, university: u.university_id, role: u.role,
    joinedAt: u.created_at, lastSeenAt: u.last_seen_at ?? null, openReports: (u.openReports as number | undefined) ?? 0, verified: Boolean(u.email_verified_at), deactivated: Boolean(u.deactivated_at),
    suspendedUntil: suspendedUntil && suspendedUntil.getTime() > Date.now() ? suspendedUntil.toISOString() : null,
  };
}
