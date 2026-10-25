import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import type { Ctx } from '../context';
import { withTx } from '../db';
import { HttpError } from '../errors';
import { idParam } from '../http';
import { likeOf, makeNeed, ymd } from '../admin_guard';
import { audit } from './moderation';

/**
 * Admin directories for communities, content and events. They reuse the existing tables and lifecycles (a community's
 * status, a post's status, an event's hidden_at); nothing here creates a second copy of anything.
 *
 * Privacy: lists show previews of public content only. A post or event inside a private community, and a followers-only
 * post, appears only once someone has reported it (the reports queue already shows moderators that much). Member lists
 * of communities are never returned, only counts and the people who run them. Message text is never returned.
 */

const paging = { page: z.coerce.number().int().min(1).max(1000).default(1), limit: z.coerce.number().int().min(1).max(50).default(25) };
const reason = z.string().trim().min(3, 'Please give a reason (at least 3 characters).').max(500);
const actionBody = z.object({ note: reason, userMessage: z.string().trim().max(300).default('') }).strict();
const pageOut = (total: number, page: number, limit: number) => ({ total, page, pages: Math.max(1, Math.ceil(total / limit)) });
/** Open reports about one thing, as a correlated subquery over [alias].id. Fixed text. */
const openReports = (type: string, alias: string) =>
  `(select count(*) from reports r where r.target_type = '${type}' and r.target_id = ${alias}.id and r.status in ('open', 'under_review'))::int`;
const anyReport = (type: string, alias: string) => `exists (select 1 from reports r where r.target_type = '${type}' and r.target_id = ${alias}.id)`;

export function adminDirectoryRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;
    const need = makeNeed(ctx);
    const universities = async () => (await pool.query('select id, name from universities order by name')).rows;
    const person = (id: unknown, name: unknown, handle: unknown) => ({ id, name, handle });

    // ---------------------------------------------------------------- communities
    app.get('/admin/communities', async (req) => {
      await need(req, 'communities.view');
      const q = z.object({
        q: z.string().trim().min(2).max(60).optional(),
        status: z.enum(['all', 'active', 'archived', 'suspended', 'deleted']).default('all'),
        visibility: z.enum(['public', 'private']).optional(),
        university: z.string().trim().min(1).max(60).optional(),
        reported: z.enum(['1']).optional(),
        sort: z.enum(['newest', 'oldest', 'name', 'members']).default('newest'),
        ...paging,
      }).strict().parse(req.query);
      const where: string[] = []; const args: unknown[] = [];
      if (q.q) { args.push(likeOf(q.q)); where.push(`c.name ilike $${args.length}`); }
      if (q.status !== 'all') { args.push(q.status); where.push(`c.status = $${args.length}`); }
      if (q.visibility) { args.push(q.visibility); where.push(`c.visibility = $${args.length}`); }
      if (q.university) { args.push(q.university); where.push(`c.university_id = $${args.length}`); }
      if (q.reported) where.push(`${openReports('community', 'c')} > 0`);
      const ORDER: Record<string, string> = { newest: 'c.created_at desc, c.id desc', oldest: 'c.created_at asc, c.id asc', name: 'lower(c.name) asc, c.id asc', members: 'c.member_count desc, c.id desc' };
      const clause = where.length ? `where ${where.join(' and ')}` : '';
      const total = (await pool.query<{ n: number }>(`select count(*)::int as n from communities c ${clause}`, args)).rows[0].n;
      const { rows } = await pool.query(
        `select c.id, c.name, c.visibility, c.status, c.category, c.member_count, c.created_at, c.university_id, o.id as owner_id, o.full_name as owner_name, o.handle as owner_handle,
                (select count(*) from posts p where p.community_id = c.id and p.status = 'active')::int as posts,
                (select count(*) from events e where e.community_id = c.id and e.status in ('published', 'completed'))::int as events,
                ${openReports('community', 'c')} as "openReports"
           from communities c join users o on o.id = c.owner_id ${clause} order by ${ORDER[q.sort]} limit $${args.length + 1} offset $${args.length + 2}`, [...args, q.limit, (q.page - 1) * q.limit]);
      return {
        items: rows.map((c) => ({ id: c.id, name: c.name, visibility: c.visibility, status: c.status, category: c.category, members: c.member_count, createdAt: c.created_at, university: c.university_id,
          owner: person(c.owner_id, c.owner_name, c.owner_handle), posts: c.posts, events: c.events, openReports: c.openReports })),
        ...pageOut(total, q.page, q.limit), universities: await universities(),
      };
    });

    app.get('/admin/communities/:id', async (req) => {
      await need(req, 'communities.view');
      const id = idParam((req.params as { id: string }).id);
      const c = (await pool.query(
        `select c.*, o.full_name as owner_name, o.handle as owner_handle, sb.full_name as suspended_by_name from communities c
           join users o on o.id = c.owner_id left join users sb on sb.id = c.suspended_by where c.id = $1`, [id])).rows[0];
      if (!c) throw new HttpError(404, 'not_found', 'Not found.');
      const one = async (sql: string) => (await pool.query<{ n: number }>(sql, [id])).rows[0].n;
      const staff = (await pool.query(
        `select u.id, u.full_name, u.handle, m.role from community_members m join users u on u.id = m.user_id
          where m.community_id = $1 and m.status = 'active' and m.role in ('owner', 'moderator') order by (m.role = 'owner') desc, u.full_name`, [id])).rows;
      const byRole = (await pool.query('select role, status, count(*)::int as n from community_members where community_id = $1 group by 1, 2', [id])).rows;
      const history = (await pool.query(
        `select m.action, m.note, m.created_at, a.full_name as by from moderation_actions m left join users a on a.id = m.moderator_id
          where m.target_type = 'community' and m.target_id = $1 order by m.created_at desc limit 20`, [id])).rows;
      return {
        id, name: c.name, description: c.description, category: c.category, visibility: c.visibility, membershipPolicy: c.membership_policy, discoverable: c.discoverable,
        postPermission: c.post_permission, rules: c.rules, status: c.status, university: c.university_id, createdAt: c.created_at, members: c.member_count,
        owner: person(c.owner_id, c.owner_name, c.owner_handle),
        suspension: c.status === 'suspended' ? { at: c.suspended_at, by: c.suspended_by_name, from: c.suspended_from } : null,
        staff: staff.map((s) => ({ ...person(s.id, s.full_name, s.handle), role: s.role })),
        memberCounts: byRole,
        activity: {
          posts: await one("select count(*)::int as n from posts where community_id = $1 and status = 'active'"),
          postsLast7Days: await one("select count(*)::int as n from posts where community_id = $1 and status = 'active' and created_at > now() - interval '7 days'"),
          channels: await one('select count(*)::int as n from community_channels where community_id = $1 and archived_at is null'),
          channelMessagesLast7Days: await one(`select count(*)::int as n from community_channel_messages cm join community_channels ch on ch.id = cm.channel_id
            where ch.community_id = $1 and cm.status = 'active' and cm.created_at > now() - interval '7 days'`),
          upcomingEvents: await one("select count(*)::int as n from events where community_id = $1 and status = 'published' and ends_at > now() and hidden_at is null"),
          joinsLast7Days: await one("select count(*)::int as n from community_members where community_id = $1 and joined_at > now() - interval '7 days'"),
          pendingRequests: await one("select count(*)::int as n from community_requests where community_id = $1 and status = 'pending'"),
        },
        reports: { open: await one("select count(*)::int as n from reports where target_type = 'community' and target_id = $1 and status in ('open', 'under_review')"),
                   total: await one("select count(*)::int as n from reports where target_type = 'community' and target_id = $1") },
        history: history.map((h) => ({ action: h.action, note: h.note, at: h.created_at, by: h.by })),
      };
    });

    for (const suspend of [true, false]) {
      app.post(`/admin/communities/:id/${suspend ? 'suspend' : 'restore'}`, async (req) => {
        const me = await need(req, 'communities.suspend');
        const id = idParam((req.params as { id: string }).id);
        const b = actionBody.parse(req.body ?? {});
        return withTx(pool, async (client) => {
          const c = (await client.query<{ status: string }>('select status from communities where id = $1 for update', [id])).rows[0];
          if (!c) throw new HttpError(404, 'not_found', 'Not found.');
          if (c.status === 'deleted') throw new HttpError(409, 'conflict', 'This community was deleted.');
          if (suspend) {
            if (c.status === 'suspended') throw new HttpError(409, 'conflict', 'This community is already suspended.');
            await client.query("update communities set status = 'suspended', suspended_from = $2, suspended_at = now(), suspended_by = $3, updated_at = now() where id = $1", [id, c.status, me.id]);
          } else {
            if (c.status !== 'suspended') throw new HttpError(409, 'conflict', 'This community is not suspended.');
            await client.query("update communities set status = coalesce(suspended_from, 'active'), suspended_from = null, suspended_at = null, suspended_by = null, updated_at = now() where id = $1", [id]);
          }
          await client.query(
            "insert into moderation_actions (report_id, moderator_id, action, target_type, target_id, note, user_message) values (null, $1, $2, 'community', $3, $4, $5)",
            [me.id, suspend ? 'suspend_community' : 'restore_community', id, b.note, b.userMessage]);
          if (suspend) {
            // The same decision settles every report still open about this community.
            await client.query(
              `update reports set status = 'action_taken', reviewed_by = $1, resolution_note = $2, reviewed_at = now(), updated_at = now(), first_reviewed_at = coalesce(first_reviewed_at, now())
                where target_type = 'community' and target_id = $3 and status in ('open', 'under_review')`, [me.id, b.note, id]);
          }
          await audit(client, me.id, suspend ? 'community.suspend' : 'community.restore', 'community', id, {});
          return { id, status: suspend ? 'suspended' : 'active' };
        });
      });
    }

    // ---------------------------------------------------------------- events
    app.get('/admin/events', async (req) => {
      await need(req, 'events.view');
      const q = z.object({
        q: z.string().trim().min(2).max(80).optional(),
        when: z.enum(['all', 'upcoming', 'past']).default('all'),
        status: z.enum(['all', 'published', 'cancelled', 'completed']).default('all'),
        hidden: z.enum(['1']).optional(), reported: z.enum(['1']).optional(),
        university: z.string().trim().min(1).max(60).optional(),
        community: z.string().uuid().optional(), organizer: z.string().uuid().optional(),
        sort: z.enum(['newest', 'soonest', 'latest']).default('newest'),
        ...paging,
      }).strict().parse(req.query);
      // Drafts belong to their organizer. Events of private communities show only once reported or hidden.
      const where: string[] = ["e.status <> 'draft'", `(e.community_id is null or c.visibility = 'public' or e.hidden_at is not null or ${anyReport('event', 'e')})`];
      const args: unknown[] = [];
      if (q.q) { args.push(likeOf(q.q)); where.push(`e.title ilike $${args.length}`); }
      if (q.when === 'upcoming') where.push("e.ends_at > now() and e.status = 'published'");
      if (q.when === 'past') where.push('e.ends_at <= now()');
      if (q.status !== 'all') { args.push(q.status); where.push(`e.status = $${args.length}`); }
      if (q.hidden) where.push('e.hidden_at is not null');
      if (q.reported) where.push(`${openReports('event', 'e')} > 0`);
      if (q.university) { args.push(q.university); where.push(`e.university_id = $${args.length}`); }
      if (q.community) { args.push(q.community); where.push(`e.community_id = $${args.length}`); }
      if (q.organizer) { args.push(q.organizer); where.push(`e.organizer_id = $${args.length}`); }
      const ORDER: Record<string, string> = { newest: 'e.created_at desc, e.id desc', soonest: 'e.starts_at asc, e.id asc', latest: 'e.starts_at desc, e.id desc' };
      const from = 'from events e join users o on o.id = e.organizer_id left join communities c on c.id = e.community_id';
      const clause = `where ${where.join(' and ')}`;
      const total = (await pool.query<{ n: number }>(`select count(*)::int as n ${from} ${clause}`, args)).rows[0].n;
      const { rows } = await pool.query(
        `select e.id, e.title, e.starts_at, e.ends_at, e.status, e.event_type, e.venue_name, e.visibility, e.hidden_at, e.created_at, e.university_id, e.community_id, c.name as community_name,
                o.id as organizer_id, o.full_name as organizer_name, o.handle as organizer_handle,
                (select count(*) from event_registrations er where er.event_id = e.id and er.status = 'registered')::int as registered,
                ${openReports('event', 'e')} as "openReports"
           ${from} ${clause} order by ${ORDER[q.sort]} limit $${args.length + 1} offset $${args.length + 2}`, [...args, q.limit, (q.page - 1) * q.limit]);
      return {
        items: rows.map((e) => ({
          id: e.id, title: e.title, startsAt: e.starts_at, endsAt: e.ends_at, status: e.status, type: e.event_type,
          venue: e.event_type === 'online' ? null : e.venue_name, visibility: e.visibility, hidden: !!e.hidden_at, createdAt: e.created_at, university: e.university_id,
          community: e.community_id ? { id: e.community_id, name: e.community_name } : null, organizer: person(e.organizer_id, e.organizer_name, e.organizer_handle),
          registered: e.registered, openReports: e.openReports,
        })),
        ...pageOut(total, q.page, q.limit), universities: await universities(),
      };
    });

    // ---------------------------------------------------------------- content (posts and comments)
    app.get('/admin/content', async (req) => {
      await need(req, 'content.view');
      const q = z.object({
        type: z.enum(['post', 'comment']).default('post'),
        q: z.string().trim().min(2).max(80).optional(),
        status: z.enum(['all', 'active', 'removed']).default('all'),
        university: z.string().trim().min(1).max(60).optional(),
        author: z.string().uuid().optional(), community: z.string().uuid().optional(),
        reported: z.enum(['1']).optional(), from: ymd.optional(), to: ymd.optional(),
        sort: z.enum(['newest', 'oldest']).default('newest'),
        ...paging,
      }).strict().parse(req.query);
      if (q.from && q.to && q.from > q.to) throw new HttpError(400, 'bad_request', 'The start date is after the end date.');
      const isPost = q.type === 'post';
      const t = isPost ? 'p' : 'cm';
      const args: unknown[] = []; const where: string[] = [];
      // Only what any student on campus could read, or what has been reported.
      where.push(`(p.audience = 'campus' or (p.audience = 'community' and pc.visibility = 'public') or ${anyReport(isPost ? 'post' : 'comment', t)})`);
      if (q.q) { args.push(likeOf(q.q)); where.push(`${t}.text ilike $${args.length}`); }
      if (q.status !== 'all') { args.push(q.status); where.push(`${t}.status = $${args.length}`); }
      if (q.university) { args.push(q.university); where.push(`a.university_id = $${args.length}`); }
      if (q.author) { args.push(q.author); where.push(`${t}.author_id = $${args.length}`); }
      if (q.community) { args.push(q.community); where.push(`p.community_id = $${args.length}`); }
      if (q.reported) where.push(`${openReports(q.type, t)} > 0`);
      if (q.from) { args.push(q.from); where.push(`${t}.created_at >= $${args.length}::date`); }
      if (q.to) { args.push(q.to); where.push(`${t}.created_at < $${args.length}::date + 1`); }
      const from = isPost
        ? 'from posts p join users a on a.id = p.author_id left join communities pc on pc.id = p.community_id'
        : 'from comments cm join posts p on p.id = cm.post_id join users a on a.id = cm.author_id left join communities pc on pc.id = p.community_id';
      const clause = `where ${where.join(' and ')}`;
      const total = (await pool.query<{ n: number }>(`select count(*)::int as n ${from} ${clause}`, args)).rows[0].n;
      const { rows } = await pool.query(
        `select ${t}.id, left(${t}.text, 200) as preview, ${t}.status, ${t}.created_at, a.id as author_id, a.full_name as author_name, a.handle as author_handle, a.university_id,
                pc.name as community_name, ${openReports(q.type, t)} as "openReports",
                (select count(*) from reports r where r.target_type = '${q.type}' and r.target_id = ${t}.id)::int as "totalReports"
           ${from} ${clause} order by ${t}.created_at ${q.sort === 'oldest' ? 'asc' : 'desc'}, ${t}.id limit $${args.length + 1} offset $${args.length + 2}`, [...args, q.limit, (q.page - 1) * q.limit]);
      return {
        type: q.type,
        items: rows.map((r) => ({ id: r.id, preview: r.preview, status: r.status, createdAt: r.created_at, author: person(r.author_id, r.author_name, r.author_handle),
          university: r.university_id, community: r.community_name, openReports: r.openReports, totalReports: r.totalReports })),
        ...pageOut(total, q.page, q.limit), universities: await universities(),
      };
    });

    for (const remove of [true, false]) {
      app.post(`/admin/content/:type/:id/${remove ? 'remove' : 'restore'}`, async (req) => {
        const me = await need(req, 'content.moderate');
        const params = z.object({ type: z.enum(['post', 'comment']), id: z.string() }).parse(req.params);
        const id = idParam(params.id);
        const b = actionBody.parse(req.body ?? {});
        const table = params.type === 'post' ? 'posts' : 'comments';
        return withTx(pool, async (client) => {
          const row = (await client.query<{ status: string }>(`select status from ${table} where id = $1 for update`, [id])).rows[0];
          if (!row) throw new HttpError(404, 'not_found', 'Not found.');
          if (remove && row.status !== 'active') throw new HttpError(409, 'conflict', 'This is already removed.');
          if (!remove && row.status !== 'removed') throw new HttpError(409, 'conflict', 'This is not removed.');
          await client.query(`update ${table} set status = $2 where id = $1`, [id, remove ? 'removed' : 'active']);
          await client.query(
            "insert into moderation_actions (report_id, moderator_id, action, target_type, target_id, note, user_message) values (null, $1, $2, $3, $4, $5, $6)",
            [me.id, remove ? 'remove_content' : 'restore_content', params.type, id, b.note, b.userMessage]);
          if (remove) {
            await client.query(
              `update reports set status = 'action_taken', reviewed_by = $1, resolution_note = $2, reviewed_at = now(), updated_at = now(), first_reviewed_at = coalesce(first_reviewed_at, now())
                where target_type = $3 and target_id = $4 and status in ('open', 'under_review')`, [me.id, b.note, params.type, id]);
          }
          await audit(client, me.id, remove ? 'remove_content' : 'restore_content', params.type, id, {});
          return { id, status: remove ? 'removed' : 'active' };
        });
      });
    }
  };
}
