import type { FastifyInstance } from 'fastify';
import type { Ctx } from '../context';
import { HttpError } from '../errors';
import { idParam, tsText } from '../http';

interface SessionRow { id: string; deviceLabel: string; createdAt: string; lastActiveAt: string }

/**
 * Where you are logged in. A "session" is one login: the chain of refresh tokens that rotate from it.
 * Only what the server really knows is returned: a label the app supplied, when the login started and when it
 * last refreshed. There is no IP address or location, because none is stored.
 * Ending a session revokes its refresh tokens, and its access token stops working immediately (see sessionIsLive).
 */
export function sessionRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;

    app.get('/me/sessions', async (req) => {
      const me = await ctx.auth(req);
      const { rows } = await pool.query<SessionRow>(
        `select family_id as id,
                coalesce(max(device_label) filter (where device_label <> ''), '') as "deviceLabel",
                ${tsText('min(created_at)')} as "createdAt",
                ${tsText('max(created_at)')} as "lastActiveAt"
           from refresh_tokens
          where user_id = $1
          group by family_id
         having bool_or(revoked_at is null and expires_at > now())
          order by max(created_at) desc, family_id desc
          limit 50`,
        [me.id],
      );
      return {
        // null when the access token predates session tracking: the app then asks the person to log in again.
        currentSessionId: me.sessionId,
        items: rows.map((r) => ({ ...r, current: r.id === me.sessionId })),
      };
    });

    app.delete('/me/sessions/:id', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = idParam((req.params as { id: string }).id);
      ctx.limit(`sessions:${me.id}`, 30, 3600);
      // Only the signed-in person's own sessions can be ended; anything else reads as not found.
      const res = await pool.query(
        'update refresh_tokens set revoked_at = now() where family_id = $1 and user_id = $2 and revoked_at is null',
        [id, me.id],
      );
      if (!res.rowCount) throw new HttpError(404, 'not_found', 'Not found.');
      return reply.status(204).send();
    });

    app.post('/me/sessions/revoke-others', async (req) => {
      const me = await ctx.auth(req);
      if (!me.sessionId) throw new HttpError(409, 'relogin_required', 'Log in again on this device to manage other sessions.');
      ctx.limit(`sessions:${me.id}`, 30, 3600);
      const res = await pool.query(
        `update refresh_tokens set revoked_at = now()
          where user_id = $1 and family_id <> $2 and revoked_at is null
          returning family_id`,
        [me.id, me.sessionId],
      );
      return { revoked: new Set(res.rows.map((r: { family_id: string }) => r.family_id)).size };
    });
  };
}
