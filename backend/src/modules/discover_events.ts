import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import type { Ctx } from '../context';
import { HttpError } from '../errors';
import { idParam } from '../http';
import { EVENT_SELECT, eventTextMatch, eventVisible, toEventView } from './events';

/**
 * Event discovery. Everything here starts from the same visibility rule as the Events tab (eventVisible), so private
 * community events, drafts and blocked organizers never appear, and from the same canonical event view, so a card in
 * Discover is the same event as everywhere else. Ranking is a plain sum of named signals, each of which can be explained
 * to the person in one line (the `reason` on every item).
 */

const GROUP_TITLES: Record<string, string> = {
  academic_career: 'Academic and career',
  tech_innovation: 'Technology and innovation',
  sports_entertainment: 'Sports and entertainment',
};
const titleOf = (code: string) => GROUP_TITLES[code] ?? code.replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());

const csv = <T extends z.ZodTypeAny>(item: T, max: number) =>
  z.string().max(400).transform((v) => v.split(',').map((x) => x.trim()).filter(Boolean)).pipe(z.array(item).min(1).max(max));
const bool = z.enum(['true', 'false']).transform((v) => v === 'true');
const when = z.string().datetime({ offset: true });

const filterSchema = z.object({
  scope: z.enum(['upcoming', 'past']).default('upcoming'),
  category: csv(z.string().regex(/^[a-z][a-z_]{1,31}$/), 8).optional(),
  format: csv(z.enum(['in_person', 'online', 'hybrid']), 3).optional(),
  from: when.optional(), to: when.optional(),
  communityId: z.string().uuid().optional(),
  campus: z.enum(['mine', 'any']).default('any'),
  registrationOpen: bool.optional(),
  saved: bool.optional(),
  q: z.string().trim().min(1).max(80).optional(),
  sort: z.enum(['rank', 'time']).default('rank'),
  limit: z.coerce.number().int().min(1).max(30).default(20),
  cursor: z.string().max(60).optional(),
});
type Filters = z.infer<typeof filterSchema>;

const MAX_OFFSET = 300;
function offsetOf(raw: string | undefined): number {
  if (!raw) return 0;
  const m = /^o:(\d{1,9})$/.exec(Buffer.from(raw, 'base64url').toString('utf8'));
  const n = m ? Number(m[1]) : -1;
  if (n < 0) throw new HttpError(400, 'invalid_cursor', 'That page cursor is not valid.');
  return n;
}
const cursorFor = (offset: number) => Buffer.from(`o:${offset}`).toString('base64url');

/** Signals for one candidate event (alias e). $1 = viewer, $2 = viewer's university. */
const SIGNALS = `
  exists (select 1 from community_members m where m.community_id = e.community_id and m.user_id = $1 and m.status = 'active' and m.notification_level <> 'muted') as f_comm,
  exists (select 1 from follows f where f.follower_id = $1 and f.followee_id = e.organizer_id) as f_follow,
  exists (select 1 from users vu cross join lateral unnest(vu.interests) as i join event_categories ic on ic.code = e.category
           where vu.id = $1 and char_length(i) >= 3 and (lower(i) = ic.code or position(lower(i) in lower(ic.label)) > 0)) as f_interest,
  (e.university_id = $2) as f_campus,
  (exists (select 1 from event_registrations h join events he on he.id = h.event_id where h.user_id = $1 and h.status = 'registered' and he.category = e.category and he.id <> e.id)
   or exists (select 1 from saved_events sh join events she on she.id = sh.event_id where sh.user_id = $1 and she.category = e.category and she.id <> e.id)) as f_history,
  (select count(*) from event_registrations r where r.event_id = e.id and r.status = 'registered')::int as reg,
  ((select count(*) from event_interest i2 where i2.event_id = e.id) + (select count(*) from saved_events s2 where s2.event_id = e.id))::int as soft,
  exists (select 1 from event_registrations mine where mine.event_id = e.id and mine.user_id = $1 and mine.status = 'registered') as going,
  extract(epoch from (e.starts_at - now())) / 86400.0 as days_out`;

/** The score is the sum of these named parts. Raw popularity only counts on a log scale, so it can never drown relevance. */
const SCORE = `
  (case when f_comm then 4 else 0 end) + (case when f_follow then 3 else 0 end) + (case when f_interest then 2 else 0 end)
  + (case when f_campus then 1.5 else 0 end) + (case when f_history then 1 else 0 end)
  + greatest(0, 3 - greatest(days_out, 0) * 0.3)
  + 0.8 * ln(1 + reg + soft)
  + (case when has_cover then 0.5 else 0 end) + (case when described then 0.5 else 0 end)
  + (case when is_full then -2 when deadline_passed then -1 else 0 end)
  + (case when going then -3 else 0 end)`;

interface Ranked { id: string; score: number; reason: string | null; engage: number; event_type: string; category: string; group: string | null; days_out: number; f_comm: boolean; f_follow: boolean }

function reasonOf(r: { f_comm: boolean; f_follow: boolean; f_interest: boolean; days_out: number; reg: number; soft: number; f_campus: boolean }): string | null {
  if (r.f_comm) return 'From a community you joined';
  if (r.f_follow) return 'From someone you follow';
  if (r.f_interest) return 'Matches your interests';
  if (r.days_out >= 0 && r.days_out <= 3) return 'Starting soon';
  if (r.reg + r.soft >= 5) return 'Popular on your campus';
  if (r.f_campus) return 'At your campus';
  return null;
}

export function discoverEventRoutes(ctx: Ctx) {
  return async function plugin(app: FastifyInstance) {
    const { pool } = ctx;

    /**
     * Candidate events for this viewer, filtered and scored. Every filter is applied in SQL, so what the screen offers is what
     * the server can deliver. Hidden events ("not interested") and organizers the person hid are excluded.
     */
    async function rank(me: { id: string; universityId: string }, f: Filters, limit: number, offset: number): Promise<Ranked[]> {
      const params: unknown[] = [me.id, me.universityId];
      const add = (v: unknown) => { params.push(v); return `$${params.length}`; };
      const past = f.scope === 'past';
      const where: string[] = [
        eventVisible('$1', '$2'),
        past ? "(e.status in ('published', 'completed') and (e.ends_at <= now() or e.status = 'completed'))" : "e.status = 'published' and e.ends_at > now()",
        'not exists (select 1 from event_feedback ef where ef.user_id = $1 and ef.event_id = e.id)',
        "not exists (select 1 from discover_feedback hf where hf.user_id = $1 and hf.kind = 'hide_author' and hf.author_id = e.organizer_id)",
      ];
      if (f.category) where.push(`e.category = any(${add(f.category)}::text[])`);
      if (f.format) where.push(`e.event_type = any(${add(f.format)}::text[])`);
      if (f.from) where.push(`e.starts_at >= ${add(f.from)}::timestamptz`);
      if (f.to) where.push(`e.starts_at < ${add(f.to)}::timestamptz`);
      if (f.communityId) where.push(`e.community_id = ${add(f.communityId)}::uuid`);
      if (f.campus === 'mine') where.push('e.university_id = $2');
      if (f.registrationOpen === true) {
        where.push("(e.registration_deadline is null or e.registration_deadline > now())");
        where.push("(e.capacity is null or (select count(*) from event_registrations rr where rr.event_id = e.id and rr.status = 'registered') < e.capacity)");
      }
      if (f.saved === true) where.push('exists (select 1 from saved_events sv where sv.event_id = e.id and sv.user_id = $1)');
      if (f.q) where.push(eventTextMatch(add(f.q.replace(/[%_\\]/g, '\\$&'))));
      const byTime = f.sort === 'time' || past;
      const lim = add(limit); const off = add(offset);
      const { rows } = await pool.query(
        `select t.*, (${SCORE}) as score from (
           select e.id, e.starts_at, e.event_type, e.category, ${SIGNALS},
                  (e.cover_media_id is not null) as has_cover, (char_length(e.description) >= 40) as described,
                  (e.capacity is not null and (select count(*) from event_registrations rr where rr.event_id = e.id and rr.status = 'registered') >= e.capacity) as is_full,
                  (e.registration_deadline is not null and e.registration_deadline <= now()) as deadline_passed,
                  (select discover_group from event_categories gc where gc.code = e.category) as grp
             from events e where ${where.join(' and ')}
         ) t
         order by ${byTime ? (past ? 't.starts_at desc' : 't.starts_at asc') : 'score desc, t.starts_at asc'}, t.id asc limit ${lim} offset ${off}`,
        params,
      );
      return rows.map((r) => ({
        id: r.id, score: Number(r.score), reason: reasonOf({ ...r, days_out: Number(r.days_out), reg: r.reg, soft: r.soft }),
        engage: r.reg + r.soft, event_type: r.event_type, category: r.category, group: r.grp, days_out: Number(r.days_out), f_comm: r.f_comm, f_follow: r.f_follow,
      }));
    }

    /** Canonical views, in the order given. An event that stopped being visible in between is dropped. */
    async function views(me: { id: string; universityId: string }, ids: string[], reasons: Map<string, string | null>) {
      if (ids.length === 0) return [];
      const { rows } = await pool.query(`${EVENT_SELECT} where e.id = any($3::uuid[]) and ${eventVisible('$1', '$2')}`, [me.id, me.universityId, ids]);
      const built = new Map<string, Awaited<ReturnType<typeof toEventView>>>();
      for (const r of rows) built.set(r.id as string, await toEventView(ctx, me.id, r as Record<string, unknown>));
      return ids.flatMap((id) => (built.has(id) ? [{ ...built.get(id)!, reason: reasons.get(id) ?? null }] : []));
    }

    // Filtered, ranked (or time-ordered) events. Pages by position: the ranking is recomputed per page, so clients drop repeats by id.
    app.get('/discover/events', async (req) => {
      const me = await ctx.auth(req);
      const f = filterSchema.parse(req.query ?? {});
      ctx.limit(`discover-events:${me.id}`, 120, 60);
      const offset = offsetOf(f.cursor);
      if (offset > MAX_OFFSET) return { items: [], nextCursor: null };
      const ranked = await rank(me, f, f.limit + 1, offset);
      const more = ranked.length > f.limit;
      const page = more ? ranked.slice(0, f.limit) : ranked;
      const items = await views(me, page.map((r) => r.id), new Map(page.map((r) => [r.id, r.reason])));
      return { items, nextCursor: more && offset + f.limit <= MAX_OFFSET ? cursorFor(offset + f.limit) : null };
    });

    // The sections of the Events part of Discover. One ranked pool is split into sections and every event appears in at most
    // one of them, so the same event is never repeated down the page. Empty sections are left out.
    app.get('/discover/events/sections', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`discover-events:${me.id}`, 120, 60);
      const base = filterSchema.parse({});
      const pool100 = await rank(me, base, 100, 0);
      const used = new Set<string>();
      const sections: Array<{ key: string; title: string; ids: string[] }> = [];
      const take = (key: string, title: string, rows: Ranked[], n: number) => {
        const picked = rows.filter((r) => !used.has(r.id)).slice(0, n);
        if (picked.length === 0) return;
        picked.forEach((r) => used.add(r.id));
        sections.push({ key, title, ids: picked.map((r) => r.id) });
      };
      take('communities', 'From your communities', pool100.filter((r) => r.f_comm), 6);
      take('following', 'From people you follow', pool100.filter((r) => r.f_follow), 6);
      take('for_you', 'Recommended for you', pool100, 8);
      take('trending', 'Trending on campus', [...pool100].filter((r) => r.engage >= 3).sort((a, b) => b.engage - a.engage), 6);
      take('this_week', 'This week', pool100.filter((r) => r.days_out <= 7).sort((a, b) => a.days_out - b.days_out), 6);
      const groups = [...new Set(pool100.flatMap((r) => (r.group ? [r.group] : [])))];
      for (const g of groups) take(`group_${g}`, titleOf(g), pool100.filter((r) => r.group === g), 6);
      take('online', 'Online events', pool100.filter((r) => r.event_type !== 'in_person'), 6);
      const reasons = new Map(pool100.map((r) => [r.id, r.reason]));
      const built = await views(me, sections.flatMap((s) => s.ids), reasons);
      const byId = new Map(built.map((v) => [v.id as string, v]));
      return {
        sections: sections
          .map((s) => ({ key: s.key, title: s.title, items: s.ids.flatMap((id) => (byId.has(id) ? [byId.get(id)!] : [])) }))
          .filter((s) => s.items.length > 0),
      };
    });

    // "Not interested": private, idempotent, and only about recommendations. The event stays reachable everywhere else.
    app.put('/discover/events/:eventId/not-interested', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = idParam((req.params as { eventId?: string }).eventId);
      ctx.limit(`event-feedback:${me.id}`, 120, 3600);
      const seen = await pool.query(`select 1 from events e where e.id = $3 and ${eventVisible('$1', '$2')}`, [me.id, me.universityId, id]);
      if (!seen.rowCount) throw new HttpError(404, 'not_found', 'Event not found.');
      await pool.query('insert into event_feedback (user_id, event_id) values ($1, $2) on conflict do nothing', [me.id, id]);
      return reply.status(204).send();
    });

    app.delete('/discover/events/:eventId/not-interested', async (req, reply) => {
      const me = await ctx.auth(req);
      await pool.query('delete from event_feedback where user_id = $1 and event_id = $2', [me.id, idParam((req.params as { eventId?: string }).eventId)]);
      return reply.status(204).send();
    });
  };
}
