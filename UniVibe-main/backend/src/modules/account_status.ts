import type { FastifyInstance, FastifyRequest } from 'fastify';
import type { Ctx } from '../context';
import { HttpError } from '../errors';
import { tsText } from '../http';
import * as sec from '../security';
import { sessionIsLive } from '../sessions';

type Action = 'remove_content' | 'restore_content' | 'suspend_user' | 'unsuspend_user';
interface NoticeRow { id: string; action: Action; targetType: string; message: string; createdAt: string }

/**
 * Account status: what the server records about the person's standing. It is the only source the app uses, so the app
 * never implies a restriction that the server did not report.
 *
 * A suspended person is still allowed to read this (and only this): they could not otherwise find out why.
 * Moderator notes are internal and never returned; notices carry only the message a moderator wrote for the person.
 * Appeals are not built yet, and the response says so instead of the app guessing.
 */
export function accountStatusRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;

    async function identify(req: FastifyRequest): Promise<string> {
      const header = req.headers.authorization;
      const token = header && header.startsWith('Bearer ') ? header.slice(7) : null;
      const claims = token ? await sec.verifyAccessClaims(token, ctx.config.JWT_SECRET) : null;
      if (!claims || (claims.sessionId && !(await sessionIsLive(pool, claims.sessionId)))) {
        throw new HttpError(401, 'unauthorized', 'Please log in again.');
      }
      return claims.userId;
    }

    app.get('/me/account-status', async (req) => {
      const userId = await identify(req);
      const user = (await pool.query<{ email_verified_at: Date | null; verification_status: string; suspended_until: Date | null; suspended: boolean }>(
        `select email_verified_at, verification_status, suspended_until,
                (suspended_until is not null and suspended_until > now()) as suspended
           from users where id = $1`, [userId])).rows[0];
      if (!user || !user.email_verified_at) throw new HttpError(401, 'unauthorized', 'Please log in again.');

      const notices = await pool.query<NoticeRow>(
        `select a.id, a.action, a.target_type as "targetType", a.user_message as message, ${tsText('a.created_at')} as "createdAt"
           from moderation_actions a
          where (a.target_type = 'user' and a.target_id = $1)
             or (a.target_type = 'post' and a.target_id in (select id from posts where author_id = $1))
             or (a.target_type = 'comment' and a.target_id in (select id from comments where author_id = $1))
             or (a.target_type = 'event' and a.target_id in (select id from events where organizer_id = $1))
             or (a.target_type = 'community' and a.target_id in (select id from communities where owner_id = $1))
          order by a.created_at desc, a.id desc limit 20`, [userId]);
      const removed = (await pool.query<{ posts: string; comments: string }>(
        `select (select count(*) from posts where author_id = $1 and status = 'removed') as posts,
                (select count(*) from comments where author_id = $1 and status = 'removed') as comments`, [userId])).rows[0];

      return {
        standing: user.suspended ? 'suspended' : 'good',
        restrictions: user.suspended
          ? [{ kind: 'suspension', until: user.suspended_until!.toISOString() }]
          : [],
        removedContent: { posts: Number(removed.posts), comments: Number(removed.comments) },
        campusVerification: user.verification_status,
        notices: notices.rows,
        appeals: { supported: false },
      };
    });
  };
}
