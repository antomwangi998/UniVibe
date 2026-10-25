import type { FastifyRequest } from 'fastify';
import { z } from 'zod';
import type { AuthUser, Ctx } from './context';
import { HttpError } from './errors';
import { audit } from './modules/moderation';
import { can, type Capability } from './permissions';

/** Keeps a search word from acting as a pattern. */
export const likeOf = (q: string) => `%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;

/**
 * One guard for every /admin route. Asked once per route: anyone without the capability is told "not found" so the
 * routes do not advertise themselves. A moderator reaching for an administrator route is worth a record, at most once
 * a minute per person. Admin routes are also rate limited per person.
 */
export function makeNeed(ctx: Ctx) {
  const lastDenied = new Map<string, number>();
  return async (req: FastifyRequest, cap: Capability): Promise<AuthUser> => {
    const me = await ctx.auth(req);
    if (!can(me.role, cap)) {
      if (me.role === 'moderator') {
        const now = Date.now();
        if (now - (lastDenied.get(me.id) ?? 0) > 60_000) {
          lastDenied.set(me.id, now);
          await audit(ctx.pool, me.id, 'admin_access_denied', null, null, { capability: cap, path: req.url.split('?')[0].slice(0, 120) }).catch(() => {});
        }
      }
      throw new HttpError(404, 'not_found', 'Not found.');
    }
    ctx.limit(`admin:${me.id}`, 120, 60);
    return me;
  };
}

/** A real calendar date as YYYY-MM-DD. Date.parse alone accepts 2026-02-31 (as 3 March), so the date must survive a round trip. */
export const ymd = z.string().regex(/^\d{4}-\d{2}-\d{2}$/).refine((v) => {
  const d = new Date(`${v}T00:00:00Z`);
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === v;
}, 'Not a real date.');
