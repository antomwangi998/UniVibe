import { SignJWT, createLocalJWKSet, exportJWK, generateKeyPair } from 'jose';
import type { FastifyInstance } from 'fastify';
import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app';
import { createPool, migrate } from '../src/db';
import { createGoogleVerifier } from '../src/google';
import { RateLimiter } from '../src/limiter';
import { PASSWORD, testConfig, url } from './helpers';

const CLIENT = 'web-client.apps.googleusercontent.com';

describe.skipIf(!url)('Sign in with Google (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  let app: FastifyInstance;
  let key: CryptoKey;
  let verifier: ReturnType<typeof createGoogleVerifier>;
  const codes = new Map<string, string>();
  let n = 0;

  beforeAll(async () => {
    pool = createPool(url as string);
    await migrate(pool);
    const pair = await generateKeyPair('RS256');
    key = pair.privateKey;
    verifier = createGoogleVerifier([CLIENT], createLocalJWKSet({ keys: [{ ...(await exportJWK(pair.publicKey)), kid: 'k', alg: 'RS256', use: 'sig' }] }));
  });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => {
    codes.clear();
    app = buildApp({
      pool, config: testConfig(), limiter: new RateLimiter(), googleVerifier: verifier,
      mailer: { sendVerification: async (to, _n, code) => { codes.set(to, code); }, sendPasswordReset: async (to, _n, code) => { codes.set(`reset:${to}`, code); } },
    });
  });
  afterEach(async () => { await app.close(); });

  const call = async (method: 'GET' | 'POST', path: string, payload?: unknown, token?: string) => {
    const res = await app.inject({ method, url: path, payload: payload as object | undefined, headers: token ? { authorization: `Bearer ${token}` } : undefined });
    return { status: res.statusCode, body: res.body ? JSON.parse(res.body) : null };
  };
  const person = (domain = 'gmail.example') => ({ sub: `g${Date.now()}x${n++}`, email: `p${Date.now()}x${n++}@${domain}`, name: 'Grace Hopper' });
  const nonce = async () => (await call('POST', '/auth/google/nonce')).body.nonce as string;
  async function idToken(p: { sub: string; email: string; name: string }, nonceValue: string, o: { aud?: string; exp?: string; verified?: boolean } = {}) {
    return new SignJWT({ email: p.email, email_verified: o.verified ?? true, name: p.name, nonce: nonceValue })
      .setProtectedHeader({ alg: 'RS256', kid: 'k' }).setSubject(p.sub).setIssuer('https://accounts.google.com')
      .setAudience(o.aud ?? CLIENT).setIssuedAt().setExpirationTime(o.exp ?? '1h').sign(key);
  }
  async function signIn(p: { sub: string; email: string; name: string }, o?: Parameters<typeof idToken>[2]) {
    const nv = await nonce();
    return call('POST', '/auth/google', { idToken: await idToken(p, nv, o), nonce: nv });
  }
  const register = (registrationToken: string, extra: object = {}) =>
    call('POST', '/auth/google/register', { registrationToken, universityId: 'lakeside', acceptedTerms: true, ...extra });
  const count = async (sql: string, args: unknown[]) => Number((await pool.query<{ c: string }>(sql, args)).rows[0].c);

  it('new person: prefill from verified claims, no account until registration completes', async () => {
    const p = person();
    const first = await signIn(p);
    expect(first.status).toBe(200);
    expect(first.body).toMatchObject({ status: 'registration_required', prefill: { fullName: 'Grace Hopper', email: p.email } });
    expect(first.body.accessToken).toBeUndefined();
    expect(await count('select count(*) c from users where email = $1', [p.email])).toBe(0);

    const done = await register(first.body.registrationToken);
    expect(done.status, JSON.stringify(done.body)).toBe(200);
    expect(done.body.user).toMatchObject({ email: p.email, emailVerified: true, studentVerified: false, fullName: 'Grace Hopper' });
    expect(done.body.accessToken).toBeTruthy();
    const me = await call('GET', '/me', undefined, done.body.accessToken);
    expect(me.status).toBe(200);
  });

  it('registration requires the terms and a real university', async () => {
    const first = await signIn(person());
    expect((await register(first.body.registrationToken, { acceptedTerms: false })).status).toBe(400);
    expect((await register(first.body.registrationToken, { universityId: 'nope' })).status).toBe(400);
  });

  it('student status comes only from the verified email domain, never from Google sign-in itself', async () => {
    const uni = await signIn(person('lakeside.example'));
    expect((await register(uni.body.registrationToken)).body.user.studentVerified).toBe(true);
    const other = await signIn(person('gmail.example'));
    expect((await register(other.body.registrationToken)).body.user.studentVerified).toBe(false);
  });

  it('returning person signs in without registering again, and gets the normal response shape', async () => {
    const p = person();
    const created = await register((await signIn(p)).body.registrationToken);
    const again = await signIn(p);
    expect(again.status).toBe(200);
    expect(again.body.status).toBe('authenticated');
    expect(again.body.user.id).toBe(created.body.user.id);
    expect(Object.keys(again.body)).toEqual(expect.arrayContaining(['accessToken', 'refreshToken', 'expiresIn', 'user']));
    expect(await count('select count(*) c from auth_identities where provider_subject = $1', [p.sub])).toBe(1);
    const refreshed = await call('POST', '/auth/refresh', { refreshToken: again.body.refreshToken });
    expect(refreshed.status).toBe(200);
  });

  it('identity is keyed on the Google subject, not the email', async () => {
    const p = person();
    const created = await register((await signIn(p)).body.registrationToken);
    const changedEmail = await signIn({ ...p, email: `moved${n++}@gmail.example` });
    expect(changedEmail.body.status).toBe('authenticated');
    expect(changedEmail.body.user.id).toBe(created.body.user.id);
  });

  it('simultaneous registrations create one account and one identity', async () => {
    const p = person();
    const token = (await signIn(p)).body.registrationToken;
    const results = await Promise.all([register(token), register(token), register(token)]);
    expect(results.filter((r) => r.status === 200)).toHaveLength(1);
    expect(results.filter((r) => r.status === 409).length).toBe(2);
    expect(await count('select count(*) c from users where email = $1', [p.email])).toBe(1);
    expect(await count('select count(*) c from auth_identities where provider_subject = $1', [p.sub])).toBe(1);
  });

  it('rejects invalid, expired, wrong-audience and unverified-email tokens with one generic error', async () => {
    const p = person();
    const nv = await nonce();
    for (const bad of [
      'x'.repeat(40),
      await idToken(p, nv, { aud: 'someone-elses-client' }),
      await idToken(p, nv, { exp: '-10m' }),
      await idToken(p, nv, { verified: false }),
    ]) {
      const r = await call('POST', '/auth/google', { idToken: bad, nonce: nv });
      expect(r.status).toBe(401);
      expect(r.body.error).toBe('invalid_google_token');
    }
    expect(await count('select count(*) c from users where email = $1', [p.email])).toBe(0);
  });

  it('a nonce works once (a copied token cannot be replayed) and must match the token', async () => {
    const p = person();
    const nv = await nonce();
    const token = await idToken(p, nv);
    expect((await call('POST', '/auth/google', { idToken: token, nonce: nv })).status).toBe(200);
    expect((await call('POST', '/auth/google', { idToken: token, nonce: nv })).status).toBe(401);
    const other = await nonce();
    expect((await call('POST', '/auth/google', { idToken: await idToken(p, nv), nonce: other })).status).toBe(401);
    expect((await call('POST', '/auth/google', { idToken: await idToken(p, 'a'.repeat(24)), nonce: 'a'.repeat(24) })).status).toBe(401); // never issued
  });

  it('existing email/password account: no automatic merge, link only after the password proves ownership', async () => {
    const email = `local${Date.now()}x${n++}@gmail.example`;
    await call('POST', '/auth/register', { fullName: 'Local User', email, universityId: 'lakeside', password: PASSWORD, acceptedTerms: true });
    for (let i = 0; i < 100 && !codes.has(email); i++) await new Promise((r) => setTimeout(r, 20));
    const verified = await call('POST', '/auth/verify-email', { email, code: codes.get(email) });
    const p = { sub: `g${n++}`, email, name: 'Local User' };

    const first = await signIn(p);
    expect(first.body.status).toBe('link_required');
    expect(first.body.accessToken).toBeUndefined();
    expect(await count('select count(*) c from auth_identities where user_id = $1', [verified.body.user.id])).toBe(0);

    const wrong = await call('POST', '/auth/google/link', { linkToken: first.body.linkToken, password: 'not-the-password-1' });
    expect(wrong.status).toBe(401);
    expect(await count('select count(*) c from auth_identities where user_id = $1', [verified.body.user.id])).toBe(0);

    const linked = await call('POST', '/auth/google/link', { linkToken: first.body.linkToken, password: PASSWORD });
    expect(linked.status, JSON.stringify(linked.body)).toBe(200);
    expect(linked.body.user.id).toBe(verified.body.user.id);
    expect((await signIn(p)).body.status).toBe('authenticated');
    // email/password sign-in is unaffected
    expect((await call('POST', '/auth/login', { email, password: PASSWORD })).status).toBe(200);
  });

  it('a link token cannot be forged, reused as an access token, or built from a registration token', async () => {
    const reg = (await signIn(person())).body.registrationToken;
    expect((await call('POST', '/auth/google/link', { linkToken: reg, password: PASSWORD })).status).toBe(401);
    expect((await call('POST', '/auth/google/link', { linkToken: 'x'.repeat(60), password: PASSWORD })).status).toBe(401);
    expect((await call('GET', '/me', undefined, reg)).status).toBe(401);
    expect((await register('y'.repeat(60))).status).toBe(401);
  });

  it('a never-verified sign-up squatting on the email is replaced, and its password does not survive', async () => {
    const p = person();
    await call('POST', '/auth/register', { fullName: 'Squatter', email: p.email, universityId: 'lakeside', password: PASSWORD, acceptedTerms: true });
    const first = await signIn(p);
    expect(first.body.status).toBe('registration_required');
    expect((await register(first.body.registrationToken)).status).toBe(200);
    expect((await call('POST', '/auth/login', { email: p.email, password: PASSWORD })).status).toBe(401);
  });

  it('a Google-only account cannot log in with a password; password reset can give it one', async () => {
    const p = person();
    await register((await signIn(p)).body.registrationToken);
    const denied = await call('POST', '/auth/login', { email: p.email, password: PASSWORD });
    expect(denied.status).toBe(401);
    expect(denied.body.error).toBe('invalid_credentials');

    await call('POST', '/auth/password-reset/request', { email: p.email });
    for (let i = 0; i < 100 && !codes.has(`reset:${p.email}`); i++) await new Promise((r) => setTimeout(r, 20));
    const reset = await call('POST', '/auth/password-reset/confirm', { email: p.email, code: codes.get(`reset:${p.email}`), newPassword: PASSWORD });
    expect(reset.status).toBe(204);
    expect((await call('POST', '/auth/login', { email: p.email, password: PASSWORD })).status).toBe(200);
    expect((await signIn(p)).body.status).toBe('authenticated'); // Google still works
  });

  it('change-password and delete-account ask a Google-only person to set a password first', async () => {
    const p = person();
    const created = await register((await signIn(p)).body.registrationToken);
    const t = created.body.accessToken;
    expect((await call('POST', '/auth/change-password', { currentPassword: 'x', newPassword: PASSWORD }, t)).body.error).toBe('password_not_set');
    expect((await call('POST', '/me/delete-account', { password: 'x' }, t)).body.error).toBe('password_not_set');
  });

  it('a suspended account cannot sign in with Google', async () => {
    const p = person();
    const created = await register((await signIn(p)).body.registrationToken);
    await pool.query(`update users set suspended_until = now() + interval '1 day' where id = $1`, [created.body.user.id]);
    const r = await signIn(p);
    expect(r.status).toBe(403);
    expect(r.body.error).toBe('account_suspended');
  });

  it('deleting the account removes the linked identity', async () => {
    const p = person();
    const created = await register((await signIn(p)).body.registrationToken);
    await pool.query('delete from users where id = $1', [created.body.user.id]);
    expect(await count('select count(*) c from auth_identities where provider_subject = $1', [p.sub])).toBe(0);
  });

  it('answers 503 when Google sign-in is not configured', async () => {
    const bare = buildApp({ pool, config: testConfig(), limiter: new RateLimiter(), mailer: { sendVerification: async () => {}, sendPasswordReset: async () => {} } });
    const r = await bare.inject({ method: 'POST', url: '/auth/google/nonce', payload: {} });
    expect(r.statusCode).toBe(503);
    await bare.close();
  });
});
