import { randomBytes } from 'crypto';
import type { FastifyInstance } from 'fastify';
import { SignJWT, jwtVerify } from 'jose';
import type { Pool } from 'pg';
import { z } from 'zod';
import type { Query, UserRow } from './app';
import type { Config } from './config';
import { withTx } from './db';
import { HttpError } from './errors';
import { GoogleTokenError, type GoogleIdentity, type GoogleVerifier } from './google';
import * as sec from './security';

/** What the Google routes borrow from buildApp, so they share its sessions, rate limiter and user view. */
export interface GoogleAuthContext {
  pool: Pool;
  config: Config;
  /** Undefined when GOOGLE_WEB_CLIENT_ID is not set: every Google route then answers 503. */
  verifier?: GoogleVerifier;
  tooMany: (key: string, max: number, windowSeconds: number) => void;
  createSession: (query: Query, userId: string, familyId?: string, deviceLabel?: string) => Promise<{ accessToken: string; refreshToken: string; expiresIn: number }>;
  toUser: (u: UserRow) => Promise<unknown>;
  findUserById: (id: string) => Promise<UserRow | undefined>;
  findUserByEmail: (email: string) => Promise<UserRow | undefined>;
  uniqueHandle: (name: string) => Promise<string>;
}

const NONCE_TTL_SECONDS = 300;
const FLOW_TOKEN_MINUTES = 15;
const ISSUER = 'univibe';
const REGISTER_AUDIENCE = 'univibe-google-register';
const LINK_AUDIENCE = 'univibe-google-link';

const deviceName = z.string().trim().max(80).optional();
const nonce = z.string().regex(/^[A-Za-z0-9_-]{16,128}$/, 'Invalid request.');
const signInSchema = z.object({ idToken: z.string().min(20).max(8192), nonce, deviceName });
const registerSchema = z.object({
  registrationToken: z.string().min(20).max(4096),
  universityId: z.string().min(1, 'Choose your university.').max(64),
  fullName: z.string().trim().min(2, 'Enter your full name.').max(80).optional(),
  admissionNumber: z.string().trim().regex(/^([A-Za-z0-9/_-]{3,24})?$/, 'Use 3–24 letters, numbers, / or -.').optional(),
  acceptedTerms: z.boolean().refine((v) => v === true, 'Accept the terms to continue.'),
  termsVersion: z.string().trim().regex(/^[A-Za-z0-9._-]{1,20}$/).optional(),
  deviceName,
});
const linkSchema = z.object({ linkToken: z.string().min(20).max(4096), password: z.string().min(1).max(128), deviceName });

const isUniqueViolation = (e: unknown) => (e as { code?: string } | null)?.code === '23505';
const invalidToken = () => new HttpError(401, 'invalid_google_token', 'Google sign-in could not be verified. Please try again.');

export function registerGoogleAuthRoutes(app: FastifyInstance, ctx: GoogleAuthContext) {
  const { pool, config } = ctx;
  const key = new TextEncoder().encode(config.JWT_SECRET);
  const run: Query = (t, p) => pool.query(t, p);

  const requireVerifier = (): GoogleVerifier => {
    if (!ctx.verifier) throw new HttpError(503, 'google_unavailable', 'Google sign-in is not available right now.');
    return ctx.verifier;
  };

  /** Short-lived, signed claim of what Google verified. The app can carry it but cannot change it. Not usable as an access token (different audience). */
  const signFlowToken = (audience: string, claims: Record<string, string>, subject: string) =>
    new SignJWT(claims).setProtectedHeader({ alg: 'HS256' }).setSubject(subject).setIssuer(ISSUER).setAudience(audience)
      .setIssuedAt().setExpirationTime(`${FLOW_TOKEN_MINUTES}m`).sign(key);

  async function readFlowToken(token: string, audience: string) {
    try {
      const { payload } = await jwtVerify(token, key, { issuer: ISSUER, audience, algorithms: ['HS256'] });
      if (typeof payload.sub !== 'string' || typeof payload.email !== 'string') throw new Error('claims');
      return { subject: payload.sub, email: payload.email, name: typeof payload.name === 'string' ? payload.name : '', userId: typeof payload.uid === 'string' ? payload.uid : null };
    } catch {
      throw new HttpError(401, 'invalid_google_token', 'This step expired. Please start again with Google.');
    }
  }

  async function verifyGoogle(idToken: string, submittedNonce: string): Promise<GoogleIdentity> {
    let identity: GoogleIdentity;
    try {
      identity = await requireVerifier()(idToken);
    } catch (e) {
      if (!(e instanceof GoogleTokenError)) throw e;
      app.log.info({ reason: e.message }, 'google token rejected');
      throw invalidToken();
    }
    if (identity.nonce !== submittedNonce) throw invalidToken();
    // Single use: the nonce is deleted by the first request that presents it.
    const used = await pool.query('delete from auth_nonces where nonce_hash = $1 and purpose = $2 and expires_at > now() returning 1', [sec.hashToken(submittedNonce), 'google']);
    if (!used.rowCount) throw invalidToken();
    return identity;
  }

  function assertNotSuspended(user: UserRow) {
    if (user.suspended_until && user.suspended_until.getTime() > Date.now()) {
      throw new HttpError(403, 'account_suspended', 'This account is suspended.');
    }
  }

  async function authenticated(user: UserRow, label: string) {
    // Logging in again brings a deactivated account back.
    await run('update users set deactivated_at = null where id = $1 and deactivated_at is not null', [user.id]);
    const session = await ctx.createSession(run, user.id, undefined, label);
    return { status: 'authenticated', ...session, user: await ctx.toUser(user) };
  }

  // A fresh nonce for the app to put into its Google request.
  app.post('/auth/google/nonce', async (req) => {
    requireVerifier();
    ctx.tooMany(`gnonce:ip:${req.ip}`, 30, 900);
    const value = randomBytes(24).toString('base64url');
    await pool.query('delete from auth_nonces where expires_at < now()');
    await pool.query(`insert into auth_nonces (nonce_hash, purpose, expires_at) values ($1, 'google', now() + make_interval(secs => $2::int))`, [sec.hashToken(value), NONCE_TTL_SECONDS]);
    return { nonce: value, expiresIn: NONCE_TTL_SECONDS };
  });

  // Step 1: verify the ID token, then say what happens next. Never creates an account.
  app.post('/auth/google', async (req) => {
    const body = signInSchema.parse(req.body ?? {});
    ctx.tooMany(`google:ip:${req.ip}`, 30, 900);
    const google = await verifyGoogle(body.idToken, body.nonce);
    ctx.tooMany(`google:sub:${google.subject}`, 20, 900);

    const linked = await pool.query<{ user_id: string }>(`select user_id from auth_identities where provider = 'google' and provider_subject = $1`, [google.subject]);
    if (linked.rows[0]) {
      const user = await ctx.findUserById(linked.rows[0].user_id);
      if (!user) throw invalidToken();
      assertNotSuspended(user);
      await pool.query(`update auth_identities set updated_at = now() where provider = 'google' and provider_subject = $1`, [google.subject]);
      return authenticated(user, body.deviceName ?? '');
    }

    const sameEmail = await ctx.findUserByEmail(google.email);
    if (sameEmail && sameEmail.email_verified_at) {
      // Never merge on an email match alone: the person must prove they own the existing account (its password).
      const linkToken = await signFlowToken(LINK_AUDIENCE, { email: google.email, uid: sameEmail.id }, google.subject);
      return { status: 'link_required', linkToken, email: google.email };
    }
    // No account, or only a never-verified sign-up attempt (nobody has proven they own it; it is replaced on registration).
    const registrationToken = await signFlowToken(REGISTER_AUDIENCE, { email: google.email, name: google.name }, google.subject);
    return { status: 'registration_required', registrationToken, prefill: { fullName: google.name, email: google.email } };
  });

  // Step 2 (new people): choose a university and accept the terms. The account is created only here.
  app.post('/auth/google/register', async (req) => {
    requireVerifier();
    const b = registerSchema.parse(req.body ?? {});
    ctx.tooMany(`greg:ip:${req.ip}`, 10, 3600);
    const claims = await readFlowToken(b.registrationToken, REGISTER_AUDIENCE);
    ctx.tooMany(`greg:sub:${claims.subject}`, 5, 3600);

    const uni = await pool.query<{ email_domains: string[] }>('select email_domains from universities where id = $1', [b.universityId]);
    if (!uni.rows[0]) throw new HttpError(400, 'validation', 'Choose your university.');
    // Google verified this email, so the existing rule applies: the university's email domain decides student status.
    const student = sec.emailMatchesDomains(claims.email, uni.rows[0].email_domains);
    const name = (b.fullName ?? claims.name).trim() || claims.email.split('@')[0];

    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        const handle = await ctx.uniqueHandle(name);
        const result = await withTx(pool, async (client) => {
          const q: Query = (t, p) => client.query(t, p);
          await q('delete from users where email = $1 and email_verified_at is null', [claims.email]);
          const created = (await q(
            'insert into users (email, full_name, handle, password_hash, university_id, admission_number, accepted_terms_at, accepted_terms_version, email_verified_at, student_verified) ' +
              'values ($1, $2, $3, null, $4, $5, now(), $6, now(), $7) returning id',
            [claims.email, name.slice(0, 80), handle, b.universityId, b.admissionNumber || null, b.termsVersion ?? '', student],
          )) as { rows: { id: string }[] };
          const userId = created.rows[0].id;
          await q(`insert into auth_identities (user_id, provider, provider_subject) values ($1, 'google', $2)`, [userId, claims.subject]);
          return ctx.createSession(q, userId, undefined, b.deviceName ?? '');
        });
        const user = (await ctx.findUserByEmail(claims.email)) as UserRow;
        return { status: 'authenticated', ...result, user: await ctx.toUser(user) };
      } catch (e) {
        if (!isUniqueViolation(e)) throw e;
        if (String((e as { constraint?: string }).constraint ?? '').includes('handle')) continue; // lost a race for the handle: pick another
        // The email or the Google identity already has an account (a double submit, or a parallel sign-up).
        throw new HttpError(409, 'account_exists', 'An account already exists. Continue with Google again to sign in.');
      }
    }
    throw new HttpError(409, 'account_exists', 'Please try again.');
  });

  // Step 2 (existing email/password account): prove ownership with the account's password, then link.
  app.post('/auth/google/link', async (req) => {
    requireVerifier();
    const b = linkSchema.parse(req.body ?? {});
    ctx.tooMany(`glink:ip:${req.ip}`, 20, 900);
    const claims = await readFlowToken(b.linkToken, LINK_AUDIENCE);
    if (!claims.userId) throw invalidToken();
    ctx.tooMany(`glink:user:${claims.userId}`, 5, 900);

    const user = await ctx.findUserById(claims.userId);
    if (!user || user.email !== claims.email || !user.email_verified_at) throw invalidToken();
    if (!user.password_hash) {
      throw new HttpError(409, 'link_unavailable', 'This account has no password. Use "Forgot password" to set one, then try again.');
    }
    if (!(await sec.verifyPassword(user.password_hash, b.password))) {
      throw new HttpError(401, 'invalid_credentials', 'Email or password is incorrect.');
    }
    assertNotSuspended(user);
    try {
      await pool.query(`insert into auth_identities (user_id, provider, provider_subject) values ($1, 'google', $2)`, [user.id, claims.subject]);
    } catch (e) {
      if (isUniqueViolation(e)) throw new HttpError(409, 'account_conflict', 'This account is already linked to a Google account.');
      throw e;
    }
    return authenticated(user, b.deviceName ?? '');
  });
}
