import type { FastifyInstance } from 'fastify';
import type { Ctx } from '../../context';
import { HttpError } from '../../errors';
import { EMPTY_LOOK, effectiveLook, keysNeeded, validateLook, type Look } from './profile_look';
import { userEntitlements } from './subscriptions';
import type { Q } from './service';

type Row = { theme: string | null; accent: string | null; frame: string | null; badge: string | null };
const toLook = (r: Row | undefined): Look | null => (r ? { theme: r.theme, accent: r.accent, frame: r.frame, badge: r.badge } : null);

/** The look anyone may see on this student's profile, or null. Used by GET /students/:id. */
export async function publicLookFor(q: Q, userId: string): Promise<Look | null> {
  const row = (await q.query<Row>('select theme, accent, frame, badge from profile_looks where user_id = $1', [userId])).rows[0];
  if (!row) return null;
  return effectiveLook(toLook(row), await userEntitlements(q, userId));
}

export function profileLookRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;

    /** My saved look, plus what I may use right now. A saved look stays saved while Premium is off; it just is not shown. */
    app.get('/me/profile-look', async (req) => {
      const me = await ctx.auth(req);
      const saved = toLook((await pool.query<Row>('select theme, accent, frame, badge from profile_looks where user_id = $1', [me.id])).rows[0]);
      const keys = await userEntitlements(pool, me.id);
      return {
        look: saved ?? EMPTY_LOOK,
        shown: effectiveLook(saved, keys),
        unlocked: { themes: keys.includes('premium.profile_themes'), frames: keys.includes('premium.profile_frames') },
      };
    });

    /** Replaces my look. Anything chosen must be unlocked right now; clearing is always allowed. The server checks, not the app. */
    app.put('/me/profile-look', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`profile-look:${me.id}`, 60, 3600);
      const v = validateLook(req.body ?? {});
      if (!v.ok) throw new HttpError(422, 'validation', v.error);
      const keys = await userEntitlements(pool, me.id);
      if (keysNeeded(v.look).some((k) => !keys.includes(k))) throw new HttpError(403, 'premium_required', 'These options need UniVibe Premium.');
      const l = v.look;
      if (!l.theme && !l.accent && !l.frame && !l.badge) await pool.query('delete from profile_looks where user_id = $1', [me.id]);
      else await pool.query(
        `insert into profile_looks (user_id, theme, accent, frame, badge) values ($1, $2, $3, $4, $5)
         on conflict (user_id) do update set theme = $2, accent = $3, frame = $4, badge = $5, updated_at = now()`,
        [me.id, l.theme, l.accent, l.frame, l.badge]);
      return { look: l };
    });
  };
}
