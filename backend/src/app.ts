import { isStaff } from './permissions';
import { audit } from './modules/moderation';
import { randomInt, randomUUID } from 'crypto';
import Fastify, { FastifyInstance, FastifyRequest } from 'fastify';
import type { Pool } from 'pg';
import { z, ZodError } from 'zod';
import type { Config } from './config';
import { withTx } from './db';
import type { Mailer } from './email';
import { HttpError } from './errors';
import { resolveCampus } from './campus';
import { makeCtx } from './context';
import { RateLimiter } from './limiter';
import { sessionIsLive } from './sessions';
import { READ_LINK_SECONDS, registerMediaRoutes } from './media';
import { registerPeopleRoutes } from './people';
import { registerPostRoutes } from './posts';
import { apiV1 } from './routes';
import { publicEventRoutes } from './modules/public_events';
import { adminPageRoutes } from './admin_ui/page';
import { releaseOwnedCommunities } from './modules/communities';
import { Hub } from './realtime';
import * as sec from './security';
import type { ImageProcessor } from './imaging';
import type { Storage } from './storage';
import { registerGoogleAuthRoutes } from './google_auth';
import type { GoogleVerifier } from './google';

export interface AppDeps {
  pool: Pool;
  mailer: Mailer;
  config: Config;
  limiter?: RateLimiter;
  /** Private photo and video storage. Without it, uploads answer 503 and everything else works. */
  storage?: Storage;
  /** Photo processing (resize, strip metadata, thumbnails). The server always passes one. */
  images?: ImageProcessor;
  /** Live connections. The server passes its own so the job worker can publish to the same sockets. */
  hub?: Hub;
  /** Checks Google ID tokens. Without it the /auth/google routes answer 503. */
  googleVerifier?: GoogleVerifier;
}

export type UserRow = {
  id: string;
  role: string;
  email: string;
  full_name: string;
  handle: string;
  year_of_study: string;
  /** Null for an account that only signs in with Google and has not set a password. */
  password_hash: string | null;
  university_id: string;
  university_name: string;
  university_domains: string[];
  admission_number: string | null;
  email_verified_at: Date | null;
  student_verified: boolean;
  course: string;
  interests: string[];
  bio: string;
  has_photo: boolean;
  onboarded_at: Date | null;
  avatar_key: string | null;
  cover_key: string | null;
  suspended_until: Date | null;
  campus_id: string | null;
  campus_name: string | null;
}

const USER_SQL =
  'select u.*, uni.name as university_name, uni.email_domains as university_domains, ' +
  'av.object_key as avatar_key, cv.object_key as cover_key, camp.name as campus_name ' +
  'from users u join universities uni on uni.id = u.university_id ' +
  'left join campuses camp on camp.id = u.campus_id ' +
  'left join media av on av.id = u.avatar_media_id left join media cv on cv.id = u.cover_media_id';

export type Query = (text: string, params: unknown[]) => Promise<unknown>;

// ---- request schemas
const email = z.string().trim().toLowerCase().email('Enter a valid email address.').max(254);
const password = z
  .string()
  .min(8, 'Use at least 8 characters.')
  .max(128, 'Use at most 128 characters.');
const code = z.string().regex(/^\d{6}$/, 'Enter the 6-digit code.');

const registerSchema = z.object({
  fullName: z.string().trim().min(2, 'Enter your full name.').max(80),
  email,
  admissionNumber: z
    .string()
    .trim()
    .regex(/^([A-Za-z0-9/_-]{3,24})?$/, 'Use 3–24 letters, numbers, / or -.')
    .optional(),
  universityId: z.string().min(1, 'Choose your university.').max(64),
  campusId: z.string().trim().min(1).max(64).optional(),
  password,
  acceptedTerms: z.boolean().refine((v) => v === true, 'Accept the terms to continue.'),
  // The Terms version the app showed (for example "0.1"); stored so we know exactly what was accepted.
  termsVersion: z.string().trim().regex(/^[A-Za-z0-9._-]{1,20}$/).optional(),
});
// deviceName is only a label for the sessions list ("Pixel 8"); it is never used to make a decision.
const deviceName = z.string().trim().max(80).optional();
const verifySchema = z.object({ email, code, deviceName });
const emailOnlySchema = z.object({ email });
const loginSchema = z.object({ email, password: z.string().min(1).max(128), deviceName });
const refreshSchema = z.object({ refreshToken: z.string().min(20).max(200) });
const resetConfirmSchema = z.object({ email, code, newPassword: password });
const changePasswordSchema = z.object({ currentPassword: z.string().min(1).max(128), newPassword: password });
const campusSchema = z.object({ campusId: z.string().trim().min(1, 'Choose your campus.').max(64) });
const deleteSchema = z.object({ password: z.string().min(1).max(128) });
const profileSchema = z.object({
  fullName: z.string().trim().min(2, 'Enter your full name.').max(80).optional(),
  yearOfStudy: z.string().trim().max(20).optional(),
  course: z.string().trim().max(80),
  interests: z.array(z.string().trim().min(1).max(30)).max(10),
  bio: z.string().trim().max(300),
});

function parse<S extends z.ZodTypeAny>(schema: S, data: unknown): z.infer<S> {
  return schema.parse(data ?? {});
}

function handleBase(name: string): string {
  const slug = name.toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 15);
  return slug.length >= 3 ? slug : 'student';
}

const isUniqueViolation = (e: unknown) => (e as { code?: string } | null)?.code === '23505';

export function buildApp(deps: AppDeps): FastifyInstance {
  const { pool, mailer, config, storage } = deps;
  const limiter = deps.limiter ?? new RateLimiter();
  const app = Fastify({ logger: config.NODE_ENV !== 'test', trustProxy: true, bodyLimit: 64 * 1024 });

  // Android's HttpURLConnection labels body-less POST/PUT requests (like /media/:id/complete) as form posts.
  // Accept those when the body is empty instead of answering 415 "Bad request."
  app.addContentTypeParser('application/x-www-form-urlencoded', { parseAs: 'string' }, (_req, body, done) => {
    if (body === '') return done(null, undefined);
    done(new HttpError(415, 'unsupported_media_type', 'Send JSON.'), undefined);
  });

  // ---- cross-cutting
  app.addHook('onRequest', async (req) => {
    const wait = limiter.hit(`ip:${req.ip}`, 300, 60);
    if (wait) throw new HttpError(429, 'rate_limited', 'Too many requests. Please slow down.', wait);
  });

  app.addHook('onSend', async (req, reply, payload) => {
    reply.header('X-Request-Id', req.id);
    reply.header('Cache-Control', 'no-store');
    reply.header('X-Content-Type-Options', 'nosniff');
    return payload;
  });

  app.setErrorHandler((err, req, reply) => {
    const e: unknown = err;
    if (e instanceof HttpError) {
      if (e.retryAfter) reply.header('Retry-After', String(e.retryAfter));
      return reply.status(e.status).send({ error: e.code, message: e.message, retryAfter: e.retryAfter });
    }
    if (e instanceof ZodError) {
      return reply.status(400).send({ error: 'validation', message: e.issues[0]?.message ?? 'Invalid request.' });
    }
    const status = (e as { statusCode?: number }).statusCode;
    if (status && status >= 400 && status < 500) {
      return reply.status(status).send({ error: 'bad_request', message: 'Bad request.' });
    }
    req.log.error(e);
    return reply.status(500).send({ error: 'server_error', message: 'Something went wrong.' });
  });

  app.setNotFoundHandler((_req, reply) => reply.status(404).send({ error: 'not_found', message: 'Not found.' }));

  // ---- helpers
  const tooMany = (key: string, max: number, windowSeconds: number) => {
    const wait = limiter.hit(key, max, windowSeconds);
    if (wait) throw new HttpError(429, 'rate_limited', 'Too many attempts. Please try again later.', wait);
  };

  async function findUserByEmail(address: string): Promise<UserRow | undefined> {
    return (await pool.query<UserRow>(`${USER_SQL} where u.email = $1`, [address])).rows[0];
  }

  async function findUserById(id: string): Promise<UserRow | undefined> {
    return (await pool.query<UserRow>(`${USER_SQL} where u.id = $1`, [id])).rows[0];
  }

  const toUser = async (u: UserRow) => ({
    id: u.id,
    fullName: u.full_name,
    handle: u.handle,
    yearOfStudy: u.year_of_study,
    email: u.email,
    universityId: u.university_id,
    universityName: u.university_name,
    campusId: u.campus_id,
    campusName: u.campus_name,
    emailVerified: u.email_verified_at !== null,
    studentVerified: u.student_verified,
    course: u.course,
    interests: u.interests,
    bio: u.bio,
    hasPhoto: u.avatar_key !== null,
    avatarUrl: u.avatar_key && storage ? await storage.signedUrl(u.avatar_key, READ_LINK_SECONDS) : null,
    coverUrl: u.cover_key && storage ? await storage.signedUrl(u.cover_key, READ_LINK_SECONDS) : null,
    onboardingComplete: u.onboarded_at !== null,
  });

  async function requireUser(req: FastifyRequest): Promise<UserRow> {
    const header = req.headers.authorization;
    const token = header && header.startsWith('Bearer ') ? header.slice(7) : null;
    const claims = token ? await sec.verifyAccessClaims(token, config.JWT_SECRET) : null;
    const user = claims ? await findUserById(claims.userId) : undefined;
    if (!user || !user.email_verified_at) throw new HttpError(401, 'unauthorized', 'Please log in again.');
    // A login that was ended from "Log out other devices" stops working at once, not when its access token expires.
    if (claims?.sessionId && !(await sessionIsLive(pool, claims.sessionId))) throw new HttpError(401, 'unauthorized', 'Please log in again.');
    if (user.suspended_until && user.suspended_until.getTime() > Date.now()) {
      throw new HttpError(403, 'account_suspended', 'This account is suspended.');
    }
    return user;
  }

  /** A readable, unique handle such as "aishanjoki" or "aishanjoki482". Names are not unique, handles are. */
  async function uniqueHandle(name: string): Promise<string> {
    const base = handleBase(name);
    for (let attempt = 0; attempt < 6; attempt++) {
      const candidate = attempt === 0 ? base : `${base}${randomInt(10, 10000)}`;
      const taken = await pool.query('select 1 from users where handle = $1', [candidate]);
      if (!taken.rowCount) return candidate;
    }
    return `${base}${randomInt(100000, 1000000)}`;
  }

  async function createSession(query: Query, userId: string, familyId: string = randomUUID(), deviceLabel = '') {
    const refreshToken = sec.newRefreshToken();
    await query(
      'insert into refresh_tokens (user_id, family_id, token_hash, expires_at, device_label) ' +
        'values ($1, $2, $3, now() + make_interval(days => $4::int), $5)',
      [userId, familyId, sec.hashToken(refreshToken), sec.REFRESH_TTL_DAYS, deviceLabel],
    );
    const accessToken = await sec.signAccessToken(userId, config.JWT_SECRET, familyId);
    return { accessToken, refreshToken, expiresIn: sec.ACCESS_TTL_SECONDS };
  }

  /** Creates a new code unless one was issued in the last cooldown period. Returns null when it did not. */
  async function issueCode(userId: string, purpose: 'verify' | 'reset', pendingPasswordHash?: string) {
    const recent = await pool.query(
      'select 1 from email_codes where user_id = $1 and purpose = $2 ' +
        'and created_at > now() - make_interval(secs => $3::double precision)',
      [userId, purpose, sec.RESEND_COOLDOWN_SECONDS],
    );
    if (recent.rowCount) return null;
    const plain = sec.newCode();
    await withTx(pool, async (client) => {
      await client.query(
        'update email_codes set used_at = now() where user_id = $1 and purpose = $2 and used_at is null',
        [userId, purpose],
      );
      await client.query(
        'insert into email_codes (user_id, purpose, code_hash, pending_password_hash, expires_at) ' +
          'values ($1, $2, $3, $4, now() + make_interval(mins => $5::int))',
        [userId, purpose, sec.hashCode(plain, userId, purpose, config.CODE_PEPPER), pendingPasswordHash ?? null, sec.CODE_TTL_MINUTES],
      );
    });
    return plain;
  }

  /** Checks the newest unused code. Counts the attempt first, so guessing is capped at MAX_CODE_ATTEMPTS. */
  async function checkCode(userId: string, purpose: 'verify' | 'reset', submitted: string) {
    const { rows } = await pool.query<{ id: string; code_hash: string; expires_at: Date; pending_password_hash: string | null }>(
      'select id, code_hash, expires_at, pending_password_hash from email_codes ' +
        'where user_id = $1 and purpose = $2 and used_at is null order by created_at desc limit 1',
      [userId, purpose],
    );
    const row = rows[0];
    if (!row) throw new HttpError(400, 'invalid_code', 'That code is not valid.');
    if (row.expires_at.getTime() < Date.now()) throw new HttpError(400, 'expired_code', 'That code has expired.');
    const counted = await pool.query(
      'update email_codes set attempts = attempts + 1 where id = $1 and attempts < $2 returning id',
      [row.id, sec.MAX_CODE_ATTEMPTS],
    );
    if (!counted.rowCount) throw new HttpError(429, 'rate_limited', 'Too many attempts. Request a new code.', 600);
    if (!sec.safeEqualHex(row.code_hash, sec.hashCode(submitted, userId, purpose, config.CODE_PEPPER))) {
      throw new HttpError(400, 'invalid_code', 'That code is not valid.');
    }
    return row;
  }

  async function trySend(send: () => Promise<void>) {
    try {
      await send();
    } catch (e) {
      app.log.error({ err: e }, 'email send failed');
    }
  }

  const revokeAllSessions = (query: Query, userId: string) =>
    query('update refresh_tokens set revoked_at = now() where user_id = $1 and revoked_at is null', [userId]);

  // ---- public
  app.get('/health', async () => ({ status: 'ok', uploads: storage !== undefined }));
  // Readiness: can this instance actually serve requests (is the database reachable)? Reveals nothing else.
  app.get('/ready', async (_req, reply) => {
    try {
      await pool.query('select 1');
      return { status: 'ready' };
    } catch {
      return reply.status(503).send({ status: 'unavailable' });
    }
  });

  app.get('/universities', async () => {
    const { rows } = await pool.query<{ id: string; name: string; email_domains: string[]; campuses: { id: string; name: string }[] }>(
      'select u.id, u.name, u.email_domains, ' +
        "coalesce((select json_agg(json_build_object('id', c.id, 'name', c.name) order by c.sort_order, c.name) from campuses c where c.university_id = u.id), '[]'::json) as campuses " +
        'from universities u order by u.name',
    );
    return rows.map((r) => ({ id: r.id, name: r.name, emailDomains: r.email_domains, campuses: r.campuses }));
  });

  // ---- sign-up and verification
  app.post('/auth/register', async (req, reply) => {
    const b = parse(registerSchema, req.body);
    if (b.password.toLowerCase() === b.email) {
      throw new HttpError(400, 'validation', 'Choose a password that is different from your email.');
    }
    tooMany(`reg:ip:${req.ip}`, 10, 3600);
    tooMany(`reg:email:${b.email}`, 3, 3600);

    const uni = await pool.query('select 1 from universities where id = $1', [b.universityId]);
    if (!uni.rowCount) throw new HttpError(400, 'validation', 'Choose your university.');
    const campusId = await resolveCampus(pool, b.universityId, b.campusId);

    const hash = await sec.hashPassword(b.password);
    let userId: string | null = null;
    let name = b.fullName;
    try {
      const created = await pool.query<{ id: string }>(
        'insert into users (email, full_name, handle, password_hash, university_id, admission_number, accepted_terms_at, accepted_terms_version, campus_id) ' +
          'values ($1, $2, $3, $4, $5, $6, now(), $7, $8) returning id',
        [b.email, b.fullName, await uniqueHandle(b.fullName), hash, b.universityId, b.admissionNumber || null, b.termsVersion ?? '', campusId],
      );
      userId = created.rows[0].id;
    } catch (e) {
      if (!isUniqueViolation(e)) throw e;
      // Same answer as a new sign-up. An unverified account just gets a fresh code; a verified one is left alone.
      const existing = await findUserByEmail(b.email);
      if (existing && !existing.email_verified_at) {
        userId = existing.id;
        name = existing.full_name;
      }
    }
    if (userId) {
      const plain = await issueCode(userId, 'verify', hash);
      if (plain) await trySend(() => mailer.sendVerification(b.email, name, plain));
    }
    return reply.status(202).send({ status: 'verification_sent', resendCooldownSeconds: sec.RESEND_COOLDOWN_SECONDS });
  });

  app.post('/auth/verify-email', async (req) => {
    const b = parse(verifySchema, req.body);
    tooMany(`verify:ip:${req.ip}`, 20, 900);
    tooMany(`verify:email:${b.email}`, 10, 900);

    const user = await findUserByEmail(b.email);
    if (!user || user.email_verified_at) throw new HttpError(400, 'invalid_code', 'That code is not valid.');
    const row = await checkCode(user.id, 'verify', b.code);

    // Student status comes only from the verified email's domain matching the university.
    const student = sec.emailMatchesDomains(user.email, user.university_domains);
    const session = await withTx(pool, async (client) => {
      const run: Query = (t, p) => client.query(t, p);
      await run('update email_codes set used_at = now() where id = $1', [row.id]);
      await run(
        'update users set email_verified_at = now(), student_verified = $2, password_hash = coalesce($3, password_hash) where id = $1',
        [user.id, student, row.pending_password_hash],
      );
      return createSession(run, user.id, undefined, b.deviceName ?? '');
    });
    const fresh = await findUserById(user.id);
    return { ...session, user: await toUser(fresh as UserRow) };
  });

  app.post('/auth/resend-verification', async (req, reply) => {
    const b = parse(emailOnlySchema, req.body);
    tooMany(`resend:ip:${req.ip}`, 10, 3600);
    tooMany(`resend:email:${b.email}`, 5, 3600);
    const user = await findUserByEmail(b.email);
    if (user && !user.email_verified_at) {
      const plain = await issueCode(user.id, 'verify');
      if (plain) await trySend(() => mailer.sendVerification(user.email, user.full_name, plain));
    }
    return reply.status(202).send({ status: 'verification_sent', resendCooldownSeconds: sec.RESEND_COOLDOWN_SECONDS });
  });

  app.get('/auth/status', async (req) => {
    const user = await requireUser(req);
    return { emailVerified: user.email_verified_at !== null, studentVerified: user.student_verified };
  });

  // ---- sessions
  app.post('/auth/login', async (req) => {
    const b = parse(loginSchema, req.body);
    tooMany(`login:ip:${req.ip}`, 30, 900);
    tooMany(`login:email:${b.email}`, 10, 900);

    const user = await findUserByEmail(b.email);
    if (!user) {
      await sec.burnPasswordCheck(b.password);
      throw new HttpError(401, 'invalid_credentials', 'Email or password is incorrect.');
    }
    if (!user.password_hash) {
      await sec.burnPasswordCheck(b.password); // Google-only account: same answer and timing as a wrong password
      throw new HttpError(401, 'invalid_credentials', 'Email or password is incorrect.');
    }
    if (!(await sec.verifyPassword(user.password_hash, b.password))) {
      // A wrong password on a staff account is a security event. The answer to the caller does not change.
      if (isStaff(user.role)) await audit(pool, user.id, 'staff_login_failed', 'user', user.id, {}).catch(() => {});
      throw new HttpError(401, 'invalid_credentials', 'Email or password is incorrect.');
    }
    // Only said after the password is right, so it does not reveal which emails have accounts.
    if (!user.email_verified_at) throw new HttpError(403, 'email_not_verified', 'Please verify your email to continue.');
    // Logging in is how a deactivated account comes back. Only reached after the password and email are confirmed.
    const reactivated = ((await pool.query('update users set deactivated_at = null where id = $1 and deactivated_at is not null', [user.id])).rowCount ?? 0) > 0;
    const session = await createSession((t, p) => pool.query(t, p), user.id, undefined, b.deviceName ?? '');
    if (isStaff(user.role)) await audit(pool, user.id, 'staff_login', 'user', user.id, {}).catch(() => {});
    return { ...session, user: await toUser(user), reactivated };
  });

  app.post('/auth/refresh', async (req) => {
    const b = parse(refreshSchema, req.body);
    const expired = () => new HttpError(401, 'unauthorized', 'Your session expired. Please log in again.');
    const hash = sec.hashToken(b.refreshToken);
    const { rows } = await pool.query<{
      id: string; user_id: string; family_id: string; expires_at: Date; used_at: Date | null; revoked_at: Date | null; device_label: string;
    }>('select id, user_id, family_id, expires_at, used_at, revoked_at, device_label from refresh_tokens where token_hash = $1', [hash]);
    const token = rows[0];
    if (!token) throw expired();
    if (token.used_at || token.revoked_at) {
      // A rotated token showing up again means it was copied: end the whole login family.
      await pool.query('update refresh_tokens set revoked_at = now() where family_id = $1 and revoked_at is null', [token.family_id]);
      throw expired();
    }
    if (token.expires_at.getTime() < Date.now()) throw expired();

    return withTx(pool, async (client) => {
      const run: Query = (t, p) => client.query(t, p);
      const claimed = await client.query('update refresh_tokens set used_at = now() where id = $1 and used_at is null returning id', [token.id]);
      if (!claimed.rowCount) throw expired();
      return createSession(run, token.user_id, token.family_id, token.device_label);
    });
  });

  app.post('/auth/logout', async (req, reply) => {
    const b = parse(refreshSchema, req.body);
    await pool.query(
      'update refresh_tokens set revoked_at = now() where revoked_at is null and family_id = ' +
        '(select family_id from refresh_tokens where token_hash = $1)',
      [sec.hashToken(b.refreshToken)],
    );
    return reply.status(204).send();
  });

  // ---- password reset and change
  app.post('/auth/password-reset/request', async (req, reply) => {
    const b = parse(emailOnlySchema, req.body);
    tooMany(`reset:ip:${req.ip}`, 10, 3600);
    tooMany(`reset:email:${b.email}`, 3, 3600);
    const user = await findUserByEmail(b.email);
    if (user && user.email_verified_at) {
      const plain = await issueCode(user.id, 'reset');
      if (plain) await trySend(() => mailer.sendPasswordReset(user.email, user.full_name, plain));
    }
    return reply.status(202).send({ status: 'ok' });
  });

  app.post('/auth/password-reset/confirm', async (req, reply) => {
    const b = parse(resetConfirmSchema, req.body);
    tooMany(`resetc:ip:${req.ip}`, 20, 900);
    tooMany(`resetc:email:${b.email}`, 10, 900);
    const user = await findUserByEmail(b.email);
    if (!user || !user.email_verified_at) throw new HttpError(400, 'invalid_code', 'That code is not valid.');
    const row = await checkCode(user.id, 'reset', b.code);
    const hash = await sec.hashPassword(b.newPassword);
    await withTx(pool, async (client) => {
      const run: Query = (t, p) => client.query(t, p);
      await run('update email_codes set used_at = now() where id = $1', [row.id]);
      await run('update users set password_hash = $2 where id = $1', [user.id, hash]);
      await revokeAllSessions(run, user.id);
    });
    return reply.status(204).send();
  });

  app.post('/auth/change-password', async (req, reply) => {
    const user = await requireUser(req);
    const b = parse(changePasswordSchema, req.body);
    tooMany(`chpw:${user.id}`, 5, 900);
    if (!user.password_hash) throw new HttpError(400, 'password_not_set', 'This account signs in with Google and has no password yet. Use "Forgot password" on the log-in screen to set one.');
    if (!(await sec.verifyPassword(user.password_hash, b.currentPassword))) {
      throw new HttpError(401, 'invalid_credentials', 'Your current password is incorrect.');
    }
    const hash = await sec.hashPassword(b.newPassword);
    await withTx(pool, async (client) => {
      const run: Query = (t, p) => client.query(t, p);
      await run('update users set password_hash = $2 where id = $1', [user.id, hash]);
      await revokeAllSessions(run, user.id);
    });
    return reply.status(204).send();
  });

  // ---- the signed-in student
  app.get('/me', async (req) => toUser(await requireUser(req)));

  app.put('/me/profile', async (req) => {
    const user = await requireUser(req);
    const b = parse(profileSchema, req.body);
    await pool.query(
      'update users set course = $2, interests = $3, bio = $4, full_name = coalesce($5::text, full_name), ' +
        'year_of_study = coalesce($6::text, year_of_study), onboarded_at = coalesce(onboarded_at, now()) where id = $1',
      [user.id, b.course, b.interests, b.bio, b.fullName ?? null, b.yearOfStudy ?? null],
    );
    return toUser((await findUserById(user.id)) as UserRow);
  });

  // Moves the student to another campus of their own university (also used by people who signed up before campuses existed).
  app.put('/me/campus', async (req) => {
    const user = await requireUser(req);
    const b = parse(campusSchema, req.body);
    const campusId = await resolveCampus(pool, user.university_id, b.campusId);
    if (!campusId) throw new HttpError(400, 'validation', 'Your university has no campuses to choose from.');
    await pool.query('update users set campus_id = $2 where id = $1', [user.id, campusId]);
    return toUser((await findUserById(user.id)) as UserRow);
  });

  app.post('/me/delete-account', async (req, reply) => {
    const user = await requireUser(req);
    const b = parse(deleteSchema, req.body);
    tooMany(`delete:${user.id}`, 5, 900);
    if (!user.password_hash) throw new HttpError(400, 'password_not_set', 'This account signs in with Google and has no password yet. Use "Forgot password" on the log-in screen to set one.');
    if (!(await sec.verifyPassword(user.password_hash, b.password))) {
      throw new HttpError(401, 'invalid_credentials', 'Your password is incorrect.');
    }
    const files = await pool.query<{ object_key: string; thumb_key: string | null }>('select object_key, thumb_key from media where owner_id = $1', [user.id]);
    await withTx(pool, async (client) => {
      // Communities the person owns pass to a successor (or close) first, so none is left without an owner.
      await releaseOwnedCommunities(client, user.id);
      await client.query('delete from users where id = $1', [user.id]); // codes, sessions, posts and media records cascade
    });
    if (storage && files.rowCount) await storage.remove(files.rows.flatMap((r) => (r.thumb_key ? [r.object_key, r.thumb_key] : [r.object_key]))).catch(() => undefined);
    return reply.status(204).send();
  });

  // Hides the account without deleting anything. Posts, comments, the profile and Discover presence disappear from other
  // students (see access.ts); every login is ended; logging in again brings it all back.
  app.post('/me/deactivate', async (req, reply) => {
    const user = await requireUser(req);
    const b = parse(deleteSchema, req.body);
    tooMany(`deactivate:${user.id}`, 5, 900);
    if (!user.password_hash) throw new HttpError(400, 'password_not_set', 'This account signs in with Google and has no password yet. Use "Forgot password" on the log-in screen to set one.');
    if (!(await sec.verifyPassword(user.password_hash, b.password))) {
      throw new HttpError(401, 'invalid_credentials', 'Your password is incorrect.');
    }
    await withTx(pool, async (client) => {
      await client.query('update users set deactivated_at = now(), updated_at = now() where id = $1', [user.id]);
      await client.query('update refresh_tokens set revoked_at = now() where user_id = $1 and revoked_at is null', [user.id]);
      // No more push notifications to this person's phones while the account is hidden.
      await client.query("update device_tokens set revoked_at = now(), revoked_reason = 'deactivated' where user_id = $1 and revoked_at is null", [user.id]);
    });
    return reply.status(204).send();
  });

  // A copy of the person's own data as JSON. Built on the spot, so there is no "pending" state: it either comes back or fails.
  // Each list is capped; a list that hit its cap is named in `truncated` so the file never pretends to be complete.
  app.post('/me/export', async (req, reply) => {
    const user = await requireUser(req);
    tooMany(`export:${user.id}`, 3, 3600);
    const CAP = { posts: 5000, comments: 5000, messages: 20000 };
    const take = async <T>(sql: string, params: unknown[], cap?: number) => {
      const { rows } = await pool.query(sql + (cap ? ` limit ${cap + 1}` : ''), params);
      const truncated = cap !== undefined && rows.length > cap;
      return { rows: (truncated ? rows.slice(0, cap) : rows) as T[], truncated };
    };
    const me = [user.id];
    const [profile, privacy, prefs, posts, comments, following, followers, blocks, communities, events, messages] = await Promise.all([
      pool.query(
        `select email, full_name as "fullName", handle, university_id as "universityId", admission_number as "admissionNumber", course,
                year_of_study as "yearOfStudy", faculty, graduation_year as "graduationYear", bio, interests,
                student_verified as "studentVerified", email_verified_at as "emailVerifiedAt", accepted_terms_at as "acceptedTermsAt",
                accepted_terms_version as "acceptedTermsVersion", created_at as "createdAt"
           from users where id = $1`, me),
      pool.query('select discoverable, show_online as "showOnline", message_permission as "messagePermission", read_receipts as "readReceipts", typing_indicators as "typingIndicators" from privacy_settings where user_id = $1', me),
      pool.query('select * from notification_preferences where user_id = $1', me),
      take(`select id, text, audience, community_id as "communityId", status, created_at as "createdAt", edited_at as "editedAt" from posts where author_id = $1 order by created_at desc`, me, CAP.posts),
      take(`select id, post_id as "postId", text, status, created_at as "createdAt" from comments where author_id = $1 order by created_at desc`, me, CAP.comments),
      pool.query('select u.handle, f.created_at as "since" from follows f join users u on u.id = f.followee_id where f.follower_id = $1 order by f.created_at desc', me),
      pool.query('select u.handle, f.created_at as "since" from follows f join users u on u.id = f.follower_id where f.followee_id = $1 order by f.created_at desc', me),
      pool.query('select u.handle, b.created_at as "since" from blocks b join users u on u.id = b.blocked_id where b.blocker_id = $1 order by b.created_at desc', me),
      pool.query('select c.id, c.name, m.role, m.joined_at as "joinedAt" from community_members m join communities c on c.id = m.community_id where m.user_id = $1 order by m.joined_at desc', me),
      pool.query('select e.id, e.title, r.status, r.registered_at as "registeredAt" from event_registrations r join events e on e.id = r.event_id where r.user_id = $1 order by r.registered_at desc', me),
      take(`select id, conversation_id as "conversationId", content, created_at as "createdAt", edited_at as "editedAt" from messages where sender_id = $1 and status = 'active' order by created_at desc`, me, CAP.messages),
    ]);
    const { user_id: _omit, ...notificationPreferences } = prefs.rows[0] ?? {};
    reply.header('cache-control', 'no-store');
    return {
      format: 1,
      generatedAt: new Date().toISOString(),
      note: 'Your own data. Passwords, login tokens and other people\'s messages are never included.',
      profile: profile.rows[0] ?? null,
      privacy: privacy.rows[0] ?? null,
      notificationPreferences,
      posts: posts.rows,
      comments: comments.rows,
      following: following.rows,
      followers: followers.rows,
      blocked: blocks.rows,
      communities: communities.rows,
      eventRegistrations: events.rows,
      messagesSent: messages.rows,
      truncated: { posts: posts.truncated, comments: comments.truncated, messagesSent: messages.truncated },
    };
  });

  // ---- Sign in with Google (token check, registration, account linking)
  registerGoogleAuthRoutes(app, { pool, config, verifier: deps.googleVerifier, tooMany, createSession, toUser, findUserById, findUserByEmail, uniqueHandle });

  // ---- feed, comments, people
  const userView = async (id: string) => toUser((await findUserById(id)) as UserRow);
  const routeContext = { pool, requireUser, tooMany, storage, images: deps.images, userView };
  registerMediaRoutes(app, routeContext);
  registerPostRoutes(app, routeContext);
  registerPeopleRoutes(app, routeContext);

  // ---- new modules (blocking, privacy, notifications, Discover, messaging, events), versioned under /api/v1
  const v1ctx = makeCtx(pool, config, limiter, storage ?? null);
  void app.register(apiV1(v1ctx, deps.hub ?? new Hub()), { prefix: '/api/v1' });
  // Public, no sign-in: the share-link page (/e/<id>) and its JSON preview. Public events only; see public_events.ts.
  void app.register(publicEventRoutes(v1ctx));
  // The admin dashboard page (see admin_ui/). It holds no data; the API decides what a signed-in account may see.
  void app.register(adminPageRoutes());

  return app;
}
