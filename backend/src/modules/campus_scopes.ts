import type { FastifyInstance } from 'fastify';
import type { Ctx } from '../context';

/**
 * The campus bridge menu: which campuses a student can switch between in Home, Discover and Search.
 *
 * Campuses stay separate communities (every person, post and event keeps its own campus); this endpoint only says
 * which ones exist and how lively they are. A campus appears once it has at least one student, so adding a campus
 * is just a row in `universities`. People who opted out of "Show me to other campuses" are not counted for others.
 *
 * `homeQuiet` is true while the student's own campus is small, so the app can open on "All campuses" with a note
 * instead of an empty feed. The threshold is CAMPUS_QUIET_MEMBERS (default 40 students).
 */
const MAX_CAMPUSES = 30;

export function campusScopeRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;

    app.get('/campuses/scopes', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`campus-scopes:${me.id}`, 120, 600);
      const { rows } = await pool.query<{ id: string; name: string; short_name: string; members: number }>(
        `select uni.id, uni.name, coalesce(uni.short_name, uni.name) as short_name,
                count(u.id) filter (where uni.id = $1 or coalesce(ps.show_other_campuses, true))::int as members
           from universities uni
           left join users u on u.university_id = uni.id and u.email_verified_at is not null and u.onboarded_at is not null
                and (u.suspended_until is null or u.suspended_until <= now()) and u.deactivated_at is null
           left join privacy_settings ps on ps.user_id = u.id
          group by uni.id, uni.name, uni.short_name`,
        [me.universityId],
      );
      const toItem = (r: { id: string; name: string; short_name: string; members: number }) =>
        ({ id: r.id, name: r.name, shortName: r.short_name, members: r.members });
      const home = rows.find((r) => r.id === me.universityId);
      const others = rows
        .filter((r) => r.id !== me.universityId && r.members > 0)
        .sort((a, b) => b.members - a.members || a.name.localeCompare(b.name))
        .slice(0, MAX_CAMPUSES);
      const quietBelow = Number(process.env.CAMPUS_QUIET_MEMBERS ?? 40);
      const homeMembers = home?.members ?? 0;
      return {
        home: home ? toItem(home) : { id: me.universityId, name: me.universityId, shortName: me.universityId, members: 0 },
        others: others.map(toItem),
        homeQuiet: homeMembers < quietBelow,
      };
    });
  };
}
