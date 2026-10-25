import type { FastifyInstance } from 'fastify';
import type { Pool } from 'pg';
import { expect } from 'vitest';
import { buildApp } from '../src/app';
import type { Config } from '../src/config';
import type { Mailer } from '../src/email';
import { Hub } from '../src/realtime';
import { createMemoryStorage } from '../src/storage';

export const url = process.env.TEST_DATABASE_URL;
export const PASSWORD = 'correct-horse-9';
export const JPEG = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0, 16, 0x4a, 0x46, 0x49, 0x46, 0, 1, 2, 3, 4, 5]);

export const testConfig = (): Config => ({
  NODE_ENV: 'test', PORT: 0, DATABASE_URL: url ?? '',
  JWT_SECRET: 'j'.repeat(40), CODE_PEPPER: 'p'.repeat(40), EMAIL_FROM: 'UniVibe <test@example.com>',
});

export interface Student { id: string; token: string; email: string; handle: string }

export function harness(pool: () => Pool) {
  const codes = new Map<string, string>();
  const storage = createMemoryStorage();
  const mailer: Mailer = {
    sendVerification: async (to, _n, code) => { codes.set(to, code); },
    sendPasswordReset: async () => {},
  };
  let app: FastifyInstance;
  let n = 0;
  const make = (hub?: Hub) => { app = buildApp({ pool: pool(), mailer, config: testConfig(), storage, hub }); return app; };

  async function call(method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE', path: string, token?: string, payload?: unknown) {
    const res = await app.inject({ method, url: path, payload: payload as object | undefined, headers: token ? { authorization: `Bearer ${token}` } : undefined });
    return { status: res.statusCode, body: res.body ? JSON.parse(res.body) : null };
  }

  async function student(domain = 'lakeside.example', universityId = 'lakeside'): Promise<Student> {
    const email = `s${Date.now()}x${n++}r${Math.random().toString(36).slice(2, 8)}@${domain}`;
    await call('POST', '/auth/register', undefined, { fullName: 'Test Student', email, universityId, password: PASSWORD, acceptedTerms: true });
    // The email goes out just after the response, so under load the code can arrive a moment later.
    for (let i = 0; i < 100 && !codes.has(email); i++) await new Promise((r) => setTimeout(r, 20));
    const res = await call('POST', '/auth/verify-email', undefined, { email, code: codes.get(email) });
    expect(res.status, `${email}: ${JSON.stringify(res.body)} code=${codes.get(email)}`).toBe(200);
    return { id: res.body.user.id, token: res.body.accessToken, email, handle: res.body.user.handle };
  }

  /** Finishes onboarding (course and interests), which Discover requires. */
  async function onboarded(course: string, interests: string[] = ['chess']): Promise<Student> {
    const s = await student();
    const res = await call('PUT', '/me/profile', s.token, { course, interests, bio: '' });
    expect(res.status, JSON.stringify(res.body)).toBe(200);
    return s;
  }

  /** Runs the upstream upload flow for a small valid JPEG and returns the finished media id. */
  async function image(token: string, purpose: 'post' | 'avatar' | 'cover' | 'message' | 'event' | 'story') {
    const up = await call('POST', '/media/uploads', token, { kind: 'image', purpose, contentType: 'image/jpeg', size: JPEG.length });
    expect(up.status, JSON.stringify(up.body)).toBe(201);
    const key = (await pool().query<{ object_key: string }>('select object_key from media where id = $1', [up.body.mediaId])).rows[0].object_key;
    storage.put(key, JPEG, 'image/jpeg');
    expect((await call('POST', `/media/${up.body.mediaId}/complete`, token)).status).toBe(200);
    return { id: up.body.mediaId as string, key };
  }
  return { make, call, student, onboarded, image, storage, app: () => app, close: () => app.close() };
}
