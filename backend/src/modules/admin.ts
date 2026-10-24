import type { FastifyInstance } from 'fastify';
import type { Pool, PoolClient } from 'pg';
import { z } from 'zod';
import type { AuthUser, Ctx } from '../context';
import { withTx } from '../db';
import { HttpError } from '../errors';
import { decodeCursor, idParam, pageSchema, toPage, tsText } from '../http';
import { jobStats } from '../jobs';
import { audit } from './moderation';

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

/** Keeps a search word from acting as a pattern. */
const likeOf = (q: string) => `%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;

const suspendSchema = z.object({
  days: z.number().int().min(1).max(30).default(7),
  note: z.string().trim().max(500).default(''),
  userMessage: z.string().trim().max(300).default(''),
}).strict();
const unsuspendSchema = z.object({ note: z.string().trim().max(500).default(''), userMessage: z.string().trim().max(300).default('') }).strict();

export function adminRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;
    const requireAdmin = async (req: Parameters<Ctx['auth']>[0]): Promise<AuthUser> => {
      const me = await ctx.auth(req);
      if (me.role !== 'admin') throw new HttpError(404, 'not_found', 'Not found.');
      ctx.limit(`admin:${me.id}`, 120, 60);
      return me;
    };

    // ---- 1. overview: how big, how active, how busy the safety queue is
    app.get('/admin/overview', async (req) => {
      await requireAdmin(req);
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
      await requireAdmin(req);
      const q = z.object({ q: z.string().trim().min(2).max(80), limit: z.coerce.number().int().min(1).max(50).default(25) }).parse(req.query);
      const { rows } = await pool.query(
        `select u.id, u.full_name, u.handle, u.email, u.university_id, u.role, u.created_at, u.email_verified_at, u.suspended_until, u.deactivated_at
           from users u where u.email ilike $1 or u.full_name ilike $1 or u.handle ilike $1
          order by u.created_at desc limit $2`, [likeOf(q.q), q.limit]);
      return { items: rows.map(userRow) };
    });

    app.get('/admin/users/:id', async (req) => {
      await requireAdmin(req);
      const id = idParam((req.params as { id: string }).id);
      const u = (await pool.query(
        `select u.id, u.full_name, u.handle, u.email, u.university_id, u.role, u.created_at, u.email_verified_at, u.suspended_until, u.deactivated_at,
                (select count(*) from posts p where p.author_id = u.id and p.status = 'active')::int as posts,
                (select count(*) from comments c where c.author_id = u.id and c.status = 'active')::int as comments,
                (select count(*) from messages m where m.sender_id = u.id and m.status = 'active')::int as messages,
                (select count(*) from reports r where r.target_type = 'user' and r.target_id = u.id)::int as "reportsAboutThem",
                (select count(*) from reports r where r.reporter_id = u.id)::int as "reportsTheyMade",
                (select count(*) from refresh_tokens t where t.user_id = u.id and t.revoked_at is null and t.expires_at > now())::int as sessions
           from users u where u.id = $1`, [id])).rows[0];
      if (!u) throw new HttpError(404, 'not_found', 'Not found.');
      const actions = (await pool.query(
        `select a.action, a.note, a.created_at, m.full_name as by
           from moderation_actions a left join users m on m.id = a.moderator_id
          where a.target_type = 'user' and a.target_id = $1 order by a.created_at desc limit 10`, [id])).rows;
      return {
        ...userRow(u),
        counts: { posts: u.posts, comments: u.comments, messages: u.messages, reportsAboutThem: u.reportsAboutThem, reportsTheyMade: u.reportsTheyMade, sessions: u.sessions },
        actions: actions.map((a) => ({ action: a.action, note: a.note, at: a.created_at, by: a.by })),
      };
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
      const me = await requireAdmin(req);
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
      const me = await requireAdmin(req);
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
      await requireAdmin(req);
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
      await requireAdmin(req);
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
  };
}

function userRow(u: Record<string, unknown>) {
  const suspendedUntil = u.suspended_until ? new Date(u.suspended_until as string) : null;
  return {
    id: u.id, name: u.full_name, handle: u.handle, email: u.email, university: u.university_id, role: u.role,
    joinedAt: u.created_at, verified: Boolean(u.email_verified_at), deactivated: Boolean(u.deactivated_at),
    suspendedUntil: suspendedUntil && suspendedUntil.getTime() > Date.now() ? suspendedUntil.toISOString() : null,
  };
}
