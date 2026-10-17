import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { blockedBetween, notSuspended } from '../access';
import type { AuthUser, Ctx } from '../context';
import { withTx } from '../db';
import { HttpError } from '../errors';
import { decodeCursor, idParam, pageSchema, toPage, tsText } from '../http';
import { cardColumns, cardJoin, type CardRow, toCard } from './cards';
import { notify } from './notify';

const list = z.array(z.string().trim().min(1).max(80)).max(10);
const prefsSchema = z.object({ courses: list, faculties: list, years: list, interests: list }).partial().strict();
const filterSchema = pageSchema.extend({
  course: z.string().trim().max(80).optional(),
  faculty: z.string().trim().max(80).optional(),
  yearOfStudy: z.string().trim().max(20).optional(),
  interest: z.string().trim().max(30).optional(),
});

/**
 * Who may appear in Discover for viewer $1 (university $2). Uses alias u.
 * Excludes: self, other universities, suspended, blocked either way, opted out, not onboarded,
 * people already passed on or connected with, and people the viewer already follows or has requested.
 */
const ELIGIBLE = (viewer = '$1', uni = '$2') => `
  u.id <> ${viewer} and u.university_id = ${uni} and u.onboarded_at is not null and ${notSuspended('u')}
  and not ${blockedBetween(viewer, 'u.id')}
  and coalesce((select ps.discoverable from privacy_settings ps where ps.user_id = u.id), true)
  and not exists (select 1 from discover_actions da where da.user_id = ${viewer} and da.target_id = u.id)
  and not exists (select 1 from follows f where f.follower_id = ${viewer} and f.followee_id = u.id)`;

async function assertEligible(ctx: Ctx, me: AuthUser, targetId: string) {
  const { rowCount } = await ctx.pool.query(
    `select 1 from users u where u.id = $3 and u.id <> $1 and u.university_id = $2 and u.onboarded_at is not null and ${notSuspended('u')}
        and not ${blockedBetween('$1', 'u.id')}
        and coalesce((select ps.discoverable from privacy_settings ps where ps.user_id = u.id), true)`,
    [me.id, me.universityId, targetId],
  );
  if (!rowCount) throw new HttpError(404, 'not_found', 'Not found.');
}

export function discoverRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;

    app.get('/discover', async (req) => {
      const me = await ctx.auth(req);
      const q = filterSchema.parse(req.query);
      ctx.limit(`discover:${me.id}`, 120, 600);
      const cur = decodeCursor(q.cursor);
      const prefs = (await pool.query('select courses, faculties, years, interests from discover_preferences where user_id = $1', [me.id])).rows[0];
      // Explicit query filters win over saved preferences.
      const courses = q.course ? [q.course] : prefs?.courses ?? [];
      const faculties = q.faculty ? [q.faculty] : prefs?.faculties ?? [];
      const years = q.yearOfStudy ? [q.yearOfStudy] : prefs?.years ?? [];
      const interests = q.interest ? [q.interest] : prefs?.interests ?? [];
      const { rows } = await pool.query(
        `select u.id, u.course, u.faculty, u.year_of_study, u.interests, u.bio,
                (select count(*) from unnest(u.interests) i where i = any((select interests from users where id = $1)::text[])) as shared_interests,
                ${tsText('u.created_at')} as "cursorT", ${cardColumns('u')}
           from users u ${cardJoin('u')}
          where ${ELIGIBLE()}
            and (cardinality($3::text[]) = 0 or lower(u.course) = any($3::text[]))
            and (cardinality($4::text[]) = 0 or lower(u.faculty) = any($4::text[]))
            and (cardinality($5::text[]) = 0 or lower(u.year_of_study) = any($5::text[]))
            and (cardinality($6::text[]) = 0 or exists (select 1 from unnest(u.interests) i where lower(i) = any($6::text[])))
            and ($7::timestamptz is null or (u.created_at, u.id) < ($7::timestamptz, $8::uuid))
          order by u.created_at desc, u.id desc limit $9`,
        [me.id, me.universityId, courses.map((s: string) => s.toLowerCase()), faculties.map((s: string) => s.toLowerCase()),
          years.map((s: string) => s.toLowerCase()), interests.map((s: string) => s.toLowerCase()), cur?.t ?? null, cur?.id ?? null, q.limit + 1],
      );
      const page = toPage(rows as (CardRow & { cursorT: string; id: string })[], q.limit);
      const items = await Promise.all(page.items.map(async (r) => {
        const row = r as unknown as CardRow & { course: string; faculty: string; year_of_study: string; interests: string[]; bio: string; shared_interests: string };
        return {
          ...(await toCard(ctx, row)), course: row.course, faculty: row.faculty, yearOfStudy: row.year_of_study,
          interests: row.interests, bio: row.bio, sharedInterests: Number(row.shared_interests),
        };
      }));
      return { items, nextCursor: page.nextCursor };
    });

    app.post('/discover/:userId/pass', async (req, reply) => {
      const me = await ctx.auth(req);
      const targetId = idParam((req.params as { userId: string }).userId);
      ctx.limit(`discover-act:${me.id}`, 300, 3600);
      await assertEligible(ctx, me, targetId);
      await pool.query("insert into discover_actions (user_id, target_id, action) values ($1, $2, 'pass') on conflict do nothing", [me.id, targetId]);
      return reply.status(204).send();
    });

    // "Connect" is a follow (or follow request) made from Discover. The other student sees an ordinary follow,
    // never that it came from Discover, and never who passed on them.
    app.post('/discover/:userId/like', async (req) => {
      const me = await ctx.auth(req);
      const targetId = idParam((req.params as { userId: string }).userId);
      ctx.limit(`discover-act:${me.id}`, 300, 3600);
      await assertEligible(ctx, me, targetId);
      return withTx(pool, async (client) => {
        await client.query("insert into discover_actions (user_id, target_id, action) values ($1, $2, 'connect') on conflict do nothing", [me.id, targetId]);
        // Upstream's follows table: a follow is a row. Safe to repeat.
        const made = await client.query('insert into follows (follower_id, followee_id) values ($1, $2) on conflict do nothing', [me.id, targetId]);
        if (made.rowCount) await notify(client, { recipientId: targetId, actorId: me.id, type: 'follow', entityType: 'user', entityId: me.id });
        return { status: 'accepted' as const };
      });
    });

    const prefsOut = (r?: { courses: string[]; faculties: string[]; years: string[]; interests: string[] }) =>
      ({ courses: r?.courses ?? [], faculties: r?.faculties ?? [], years: r?.years ?? [], interests: r?.interests ?? [] });

    app.get('/me/discover/preferences', async (req) => {
      const me = await ctx.auth(req);
      return prefsOut((await pool.query('select courses, faculties, years, interests from discover_preferences where user_id = $1', [me.id])).rows[0]);
    });

    app.put('/me/discover/preferences', async (req) => {
      const me = await ctx.auth(req);
      const b = prefsSchema.parse(req.body ?? {});
      const res = await pool.query(
        `insert into discover_preferences (user_id, courses, faculties, years, interests)
         values ($1, coalesce($2::text[], '{}'), coalesce($3::text[], '{}'), coalesce($4::text[], '{}'), coalesce($5::text[], '{}'))
         on conflict (user_id) do update set courses = coalesce($2::text[], discover_preferences.courses),
           faculties = coalesce($3::text[], discover_preferences.faculties), years = coalesce($4::text[], discover_preferences.years),
           interests = coalesce($5::text[], discover_preferences.interests), updated_at = now()
         returning courses, faculties, years, interests`,
        [me.id, b.courses ?? null, b.faculties ?? null, b.years ?? null, b.interests ?? null],
      );
      return prefsOut(res.rows[0]);
    });
  };
}
