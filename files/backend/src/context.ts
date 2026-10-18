import type { FastifyRequest } from 'fastify';
import type { Pool } from 'pg';
import type { Config } from './config';
import { HttpError } from './errors';
import type { RateLimiter } from './limiter';
import * as sec from './security';
import { sessionIsLive } from './sessions';
import type { Storage } from './storage';

/** The signed-in student as every v1 module sees them. Identity only ever comes from the verified access token. */
export interface AuthUser {
  id: string;
  universityId: string;
  role: 'user' | 'moderator' | 'admin';
  handle: string;
  fullName: string;
  /** The login this request belongs to; null for tokens issued before sessions were tracked. */
  sessionId: string | null;
}

export interface Ctx {
  pool: Pool;
  config: Config;
  limiter: RateLimiter;
  storage: Storage | null;
  auth(req: FastifyRequest): Promise<AuthUser>;
  /** Same check for a raw bearer token (WebSocket handshake messages). */
  authToken(token: string | null): Promise<AuthUser>;
  /** Throws 429 when the key has used up max attempts in the window. */
  limit(key: string, max: number, windowSeconds: number): void;
}

export function makeCtx(pool: Pool, config: Config, limiter: RateLimiter, storage: Storage | null): Ctx {
  const ctx: Ctx = {
    pool, config, limiter, storage,
    async auth(req) {
      const header = req.headers.authorization;
      return ctx.authToken(header && header.startsWith('Bearer ') ? header.slice(7) : null);
    },
    async authToken(token) {
      const claims = token ? await sec.verifyAccessClaims(token, config.JWT_SECRET) : null;
      const id = claims?.userId ?? null;
      const row = id
        ? (await pool.query<{
            id: string; university_id: string; role: AuthUser['role']; handle: string; full_name: string;
            email_verified_at: Date | null; suspended: boolean;
          }>(
            'select id, university_id, role, handle, full_name, email_verified_at, ' +
              '(suspended_until is not null and suspended_until > now()) as suspended from users where id = $1',
            [id],
          )).rows[0]
        : undefined;
      if (!row || !row.email_verified_at) throw new HttpError(401, 'unauthorized', 'Please log in again.');
      if (row.suspended) throw new HttpError(403, 'account_suspended', 'This account is suspended.');
      if (claims?.sessionId && !(await sessionIsLive(pool, claims.sessionId))) throw new HttpError(401, 'unauthorized', 'Please log in again.');
      return { id: row.id, universityId: row.university_id, role: row.role, handle: row.handle, fullName: row.full_name, sessionId: claims?.sessionId ?? null };
    },
    limit(key, max, windowSeconds) {
      const wait = limiter.hit(key, max, windowSeconds);
      if (wait) throw new HttpError(429, 'rate_limited', 'Too many attempts. Please try again later.', wait);
    },
  };
  return ctx;
}
