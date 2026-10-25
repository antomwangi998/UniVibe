import type { FastifyInstance } from 'fastify';
import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app';
import type { Config } from '../src/config';
import { createPool, migrate } from '../src/db';
import type { Mailer } from '../src/email';

const url = process.env.TEST_DATABASE_URL;

describe.skipIf(!url)('auth API (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  let app: FastifyInstance;
  const verification = new Map<string, string>();
  const reset = new Map<string, string>();
  let sent = 0;
  let n = 0;

  const mailer: Mailer = {
    sendVerification: async (to, _name, code) => { verification.set(to, code); sent++; },
    sendPasswordReset: async (to, _name, code) => { reset.set(to, code); },
  };
  const config: Config = {
    NODE_ENV: 'test', PORT: 0, DATABASE_URL: url ?? '',
    JWT_SECRET: 'j'.repeat(40), CODE_PEPPER: 'p'.repeat(40), EMAIL_FROM: 'UniVibe <test@example.com>',
  };

  beforeAll(async () => {
    pool = createPool(url as string);
    await migrate(pool);
  });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { app = buildApp({ pool, mailer, config }); verification.clear(); reset.clear(); sent = 0; });
  afterEach(async () => { await app.close(); });

  const newEmail = (domain = 'lakeside.example') => `t${Date.now()}x${n++}@${domain}`;
  const PASSWORD = 'correct-horse-9';

  async function call(method: 'GET' | 'POST' | 'PUT', path: string, payload?: unknown, token?: string) {
    const res = await app.inject({
      method, url: path, payload: payload as object | undefined,
      headers: token ? { authorization: `Bearer ${token}` } : undefined,
    });
    return { status: res.statusCode, body: res.body ? JSON.parse(res.body) : null };
  }

  const register = (email: string, password = PASSWORD) =>
    call('POST', '/auth/register', { fullName: 'Test Student', email, universityId: 'lakeside', password, acceptedTerms: true });

  async function signUpAndVerify(domain?: string) {
    const email = newEmail(domain);
    await register(email);
    const res = await call('POST', '/auth/verify-email', { email, code: verification.get(email) });
    expect(res.status).toBe(200);
    return { email, ...res.body };
  }

  it('records which Terms version was accepted', async () => {
    const withVersion = newEmail();
    expect((await call('POST', '/auth/register', { fullName: 'Test Student', email: withVersion, universityId: 'lakeside', password: PASSWORD, acceptedTerms: true, termsVersion: '0.1' })).status).toBeLessThan(300);
    const without = newEmail();
    expect((await register(without)).status).toBeLessThan(300);
    const row = async (email: string) => (await pool.query('select accepted_terms_version as v, accepted_terms_at as at from users where email = $1', [email])).rows[0];
    expect((await row(withVersion)).v).toBe('0.1');
    expect((await row(withVersion)).at).not.toBeNull();
    expect((await row(without)).v).toBe('');
    expect((await call('POST', '/auth/register', { fullName: 'Test Student', email: newEmail(), universityId: 'lakeside', password: PASSWORD, acceptedTerms: true, termsVersion: 'bad version!' })).status).toBe(400);
  });

  it('lists universities', async () => {
    const res = await call('GET', '/universities');
    expect(res.status).toBe(200);
    expect(res.body.some((u: { id: string }) => u.id === 'lakeside')).toBe(true);
  });

  it('sign-up answers the same for new and existing emails, and does not spam codes', async () => {
    const email = newEmail();
    const first = await register(email);
    const again = await register(email);
    expect(first.status).toBe(202);
    expect(again).toEqual(first);
    expect(sent).toBe(1); // second attempt fell inside the resend cooldown
  });

  it('rejects bad input', async () => {
    expect((await call('POST', '/auth/register', { fullName: 'A', email: 'nope', universityId: 'lakeside', password: 'x', acceptedTerms: false })).status).toBe(400);
    expect((await call('POST', '/auth/register', { fullName: 'Test Student', email: newEmail(), universityId: 'nowhere', password: PASSWORD, acceptedTerms: true })).status).toBe(400);
  });

  it('login before verifying says so only with the right password', async () => {
    const email = newEmail();
    await register(email);
    expect((await call('POST', '/auth/login', { email, password: PASSWORD })).body.error).toBe('email_not_verified');
    expect((await call('POST', '/auth/login', { email, password: 'wrong-password' })).body.error).toBe('invalid_credentials');
  });

  it('verifying with the emailed code signs the student in and marks them verified', async () => {
    const email = newEmail();
    await register(email);
    const wrong = verification.get(email) === '000000' ? '000001' : '000000';
    expect((await call('POST', '/auth/verify-email', { email, code: wrong })).body.error).toBe('invalid_code');
    const ok = await call('POST', '/auth/verify-email', { email, code: verification.get(email) });
    expect(ok.status).toBe(200);
    expect(ok.body.user.emailVerified).toBe(true);
    expect(ok.body.user.studentVerified).toBe(true);
    expect(ok.body.user.onboardingComplete).toBe(false);
    expect(ok.body.user.handle).toMatch(/^[a-z0-9]{3,}$/);
    expect(ok.body.user.yearOfStudy).toBe('');
    const status = await call('GET', '/auth/status', undefined, ok.body.accessToken);
    expect(status.body).toEqual({ emailVerified: true, studentVerified: true });
    // a used code cannot be replayed
    expect((await call('POST', '/auth/verify-email', { email, code: verification.get(email) })).status).toBe(400);
  });

  it('a non-university email verifies but is not marked as a verified student', async () => {
    const account = await signUpAndVerify('gmail.com');
    expect(account.user.emailVerified).toBe(true);
    expect(account.user.studentVerified).toBe(false);
  });

  it('locks a code after 5 wrong guesses', async () => {
    const email = newEmail();
    await register(email);
    const real = verification.get(email) as string;
    const wrong = real === '000000' ? '000001' : '000000';
    for (let i = 0; i < 5; i++) expect((await call('POST', '/auth/verify-email', { email, code: wrong })).status).toBe(400);
    const locked = await call('POST', '/auth/verify-email', { email, code: real });
    expect(locked.status).toBe(429);
  });

  it('login failures look the same for unknown emails and wrong passwords', async () => {
    const account = await signUpAndVerify();
    const wrongPassword = await call('POST', '/auth/login', { email: account.email, password: 'wrong-password' });
    const unknown = await call('POST', '/auth/login', { email: newEmail(), password: 'wrong-password' });
    expect(wrongPassword.status).toBe(401);
    expect(unknown).toEqual(wrongPassword);
    expect((await call('POST', '/auth/login', { email: account.email, password: PASSWORD })).status).toBe(200);
  });

  it('refresh tokens rotate, and replaying an old one ends the whole login', async () => {
    const account = await signUpAndVerify();
    const rotated = await call('POST', '/auth/refresh', { refreshToken: account.refreshToken });
    expect(rotated.status).toBe(200);
    expect(rotated.body.refreshToken).not.toBe(account.refreshToken);
    const replay = await call('POST', '/auth/refresh', { refreshToken: account.refreshToken });
    expect(replay.status).toBe(401);
    expect((await call('POST', '/auth/refresh', { refreshToken: rotated.body.refreshToken })).status).toBe(401);
  });

  it('logout revokes the session', async () => {
    const account = await signUpAndVerify();
    expect((await call('POST', '/auth/logout', { refreshToken: account.refreshToken })).status).toBe(204);
    expect((await call('POST', '/auth/refresh', { refreshToken: account.refreshToken })).status).toBe(401);
  });

  it('protected routes need a valid access token', async () => {
    expect((await call('GET', '/me')).status).toBe(401);
    expect((await call('GET', '/me', undefined, 'garbage')).status).toBe(401);
  });

  it('password reset: same answer for unknown emails, code resets and ends old sessions', async () => {
    const account = await signUpAndVerify();
    const unknown = await call('POST', '/auth/password-reset/request', { email: newEmail() });
    const known = await call('POST', '/auth/password-reset/request', { email: account.email });
    expect(unknown).toEqual(known);
    expect(reset.has(account.email)).toBe(true);

    const wrong = reset.get(account.email) === '000000' ? '000001' : '000000';
    expect((await call('POST', '/auth/password-reset/confirm', { email: account.email, code: wrong, newPassword: 'brand-new-pass-1' })).status).toBe(400);
    expect((await call('POST', '/auth/password-reset/confirm', { email: account.email, code: reset.get(account.email), newPassword: 'brand-new-pass-1' })).status).toBe(204);

    expect((await call('POST', '/auth/login', { email: account.email, password: PASSWORD })).status).toBe(401);
    expect((await call('POST', '/auth/login', { email: account.email, password: 'brand-new-pass-1' })).status).toBe(200);
    expect((await call('POST', '/auth/refresh', { refreshToken: account.refreshToken })).status).toBe(401);
  });

  it('onboarding profile is saved and validated', async () => {
    const account = await signUpAndVerify();
    const saved = await call('PUT', '/me/profile', { course: 'Computer Science', interests: ['Music', 'Football'], bio: 'Hi!' }, account.accessToken);
    expect(saved.status).toBe(200);
    expect(saved.body.onboardingComplete).toBe(true);
    expect(saved.body.interests).toEqual(['Music', 'Football']);
    expect((await call('GET', '/me', undefined, account.accessToken)).body.course).toBe('Computer Science');
    const tooMany = Array.from({ length: 11 }, (_, i) => `i${i}`);
    expect((await call('PUT', '/me/profile', { course: 'x', interests: tooMany, bio: '' }, account.accessToken)).status).toBe(400);
  });

  it('every student gets a different handle, even with the same name', async () => {
    const a = await signUpAndVerify();
    const b = await signUpAndVerify();
    expect(a.user.handle).not.toBe(b.user.handle);
  });

  it('profile edits can change the name and year of study', async () => {
    const account = await signUpAndVerify();
    const saved = await call('PUT', '/me/profile', { fullName: 'New Name', yearOfStudy: '2nd Year', course: 'Law', interests: [], bio: '' }, account.accessToken);
    expect(saved.status).toBe(200);
    expect(saved.body.fullName).toBe('New Name');
    expect(saved.body.yearOfStudy).toBe('2nd Year');
    expect(saved.body.handle).toBe(account.user.handle); // handles do not change with the name
    // leaving the optional fields out keeps what is there
    const again = await call('PUT', '/me/profile', { course: 'Law', interests: [], bio: 'x' }, account.accessToken);
    expect(again.body.fullName).toBe('New Name');
    expect(again.body.yearOfStudy).toBe('2nd Year');
    expect((await call('PUT', '/me/profile', { fullName: 'A', course: '', interests: [], bio: '' }, account.accessToken)).status).toBe(400);
  });

  it('changing the password needs the current one and ends other sessions', async () => {
    const account = await signUpAndVerify();
    expect((await call('POST', '/auth/change-password', { currentPassword: 'nope-nope-1', newPassword: 'another-pass-22' }, account.accessToken)).status).toBe(401);
    expect((await call('POST', '/auth/change-password', { currentPassword: PASSWORD, newPassword: 'another-pass-22' }, account.accessToken)).status).toBe(204);
    expect((await call('POST', '/auth/refresh', { refreshToken: account.refreshToken })).status).toBe(401);
    expect((await call('POST', '/auth/login', { email: account.email, password: 'another-pass-22' })).status).toBe(200);
  });

  it('deleting the account needs the password and removes everything', async () => {
    const account = await signUpAndVerify();
    expect((await call('POST', '/me/delete-account', { password: 'wrong-password' }, account.accessToken)).status).toBe(401);
    expect((await call('POST', '/me/delete-account', { password: PASSWORD }, account.accessToken)).status).toBe(204);
    expect((await call('POST', '/auth/login', { email: account.email, password: PASSWORD })).status).toBe(401);
    expect((await call('GET', '/me', undefined, account.accessToken)).status).toBe(401);
  });
});
