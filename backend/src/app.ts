import { randomInt, randomUUID } from 'crypto';
import Fastify, { FastifyInstance, FastifyRequest } from 'fastify';
import type { Pool } from 'pg';
import { z, ZodError } from 'zod';
import type { Config } from './config';
import { withTx } from './db';
import type { Mailer } from './email';
import { HttpError } from './errors';
import { RateLimiter } from './limiter';
import * as sec from './security';

export interface AppDeps {
  pool: Pool;
  mailer: Mailer;
  config: Config;
  limiter?: RateLimiter;
}

type UserRow = {
  id: string;
  email: string;
  full_name: string;
  handle: string;
  year_of_study: string;
  password_hash: string;
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
}

const USER_SQL =
  'select u.*, uni.name as university_name, uni.email_domains as university_domains ' +
  'from users u join universities uni on uni.id = u.university_id';

type Query = (text: string, params: unknown[]) => Promise<unknown>;

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
  password,
  acceptedTerms: z.boolean().refine((v) => v === true, 'Accept the terms to continue.'),
});
const verifySchema = z.object({ email, code });
const emailOnlySchema = z.object({ email });
const loginSchema = z.object({ email, password: z.string().min(1).max(128) });
const refreshSchema = z.object({ refreshToken: z.string().min(20).max(200) });
const resetConfirmSchema = z.object({ email, code, newPassword: password });
const changePasswordSchema = z.object({ currentPassword: z.string().min(1).max(128), newPassword: password });
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
  const { pool, mailer, config } = deps;
  const limiter = deps.limiter ?? new RateLimiter();
  const app = Fastify({ logger: config.NODE_ENV !== 'test', trustProxy: true, bodyLimit: 64 * 1024 });

  // ---- cross-cutting
  app.addHook('onRequest', async (req) => {
    const wait = limiter.hit(`ip:${req.ip}`, 300, 60);
    if (wait) throw new HttpError(429, 'rate_limited', 'Too many requests. Please slow down.', wait);
  });

  app.addHook('onSend', async (_req, reply, payload) => {
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

  const toUser = (u: UserRow) => ({
    id: u.id,
    fullName: u.full_name,
    handle: u.handle,
    yearOfStudy: u.year_of_study,
    email: u.email,
    universityId: u.university_id,
    universityName: u.university_name,
    emailVerified: u.email_verified_at !== null,
    studentVerified: u.student_verified,
    course: u.course,
    interests: u.interests,
    bio: u.bio,
    hasPhoto: u.has_photo,
    onboardingComplete: u.onboarded_at !== null,
  });

  async function requireUser(req: FastifyRequest): Promise<UserRow> {
    const header = req.headers.authorization;
    const token = header && header.startsWith('Bearer ') ? header.slice(7) : null;
    const id = token ? await sec.verifyAccessToken(token, config.JWT_SECRET) : null;
    const user = id ? await findUserById(id) : undefined;
    if (!user || !user.email_verified_at) throw new HttpError(401, 'unauthorized', 'Please log in again.');
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

  async function createSession(query: Query, userId: string, familyId: string = randomUUID()) {
    const refreshToken = sec.newRefreshToken();
    await query(
      'insert into refresh_tokens (user_id, family_id, token_hash, expires_at) ' +
        'values ($1, $2, $3, now() + make_interval(days => $4::int))',
      [userId, familyId, sec.hashToken(refreshToken), sec.REFRESH_TTL_DAYS],
    );
    const accessToken = await sec.signAccessToken(userId, config.JWT_SECRET);
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
  app.get('/health', async () => ({ status: 'ok' }));

  app.get('/universities', async () => {
    const { rows } = await pool.query<{ id: string; name: string; email_domains: string[] }>(
      'select id, name, email_domains from universities order by name',
    );
    return rows.map((r) => ({ id: r.id, name: r.name, emailDomains: r.email_domains }));
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

    const hash = await sec.hashPassword(b.password);
    let userId: string | null = null;
    let name = b.fullName;
    try {
      const created = await pool.query<{ id: string }>(
        'insert into users (email, full_name, handle, password_hash, university_id, admission_number, accepted_terms_at) ' +
          'values ($1, $2, $3, $4, $5, $6, now()) returning id',
        [b.email, b.fullName, await uniqueHandle(b.fullName), hash, b.universityId, b.admissionNumber || null],
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
      return createSession(run, user.id);
    });
    const fresh = await findUserById(user.id);
    return { ...session, user: toUser(fresh as UserRow) };
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
    if (!(await sec.verifyPassword(user.password_hash, b.password))) {
      throw new HttpError(401, 'invalid_credentials', 'Email or password is incorrect.');
    }
    // Only said after the password is right, so it does not reveal which emails have accounts.
    if (!user.email_verified_at) throw new HttpError(403, 'email_not_verified', 'Please verify your email to continue.');
    const session = await createSession((t, p) => pool.query(t, p), user.id);
    return { ...session, user: toUser(user) };
  });

  app.post('/auth/refresh', async (req) => {
    const b = parse(refreshSchema, req.body);
    const expired = () => new HttpError(401, 'unauthorized', 'Your session expired. Please log in again.');
    const hash = sec.hashToken(b.refreshToken);
    const { rows } = await pool.query<{
      id: string; user_id: string; family_id: string; expires_at: Date; used_at: Date | null; revoked_at: Date | null;
    }>('select id, user_id, family_id, expires_at, used_at, revoked_at from refresh_tokens where token_hash = $1', [hash]);
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
      return createSession(run, token.user_id, token.family_id);
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

  app.post('/me/delete-account', async (req, reply) => {
    const user = await requireUser(req);
    const b = parse(deleteSchema, req.body);
    tooMany(`delete:${user.id}`, 5, 900);
    if (!(await sec.verifyPassword(user.password_hash, b.password))) {
      throw new HttpError(401, 'invalid_credentials', 'Your password is incorrect.');
    }
    await pool.query('delete from users where id = $1', [user.id]); // codes and sessions cascade
    return reply.status(204).send();
  });

  return app;
}
