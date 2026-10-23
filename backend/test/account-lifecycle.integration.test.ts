import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, PASSWORD, url } from './helpers';

describe.skipIf(!url)('account deactivation and data export (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;

  beforeAll(async () => { pool = createPool(url as string); await migrate(pool); });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  it('needs the right password to deactivate, and a wrong one changes nothing', async () => {
    const a = await h.student();
    const wrong = await call('POST', '/me/deactivate', a.token, { password: 'not-the-password-1' });
    expect(wrong.status).toBe(401);
    const stillIn = await call('GET', '/posts', a.token);
    expect(stillIn.status).toBe(200);
  });

  it('hides posts and comments from others, ends every login, and logging in brings it all back', async () => {
    const a = await h.student();
    const b = await h.student();
    const post = await call('POST', '/posts', a.token, { text: 'hello from a', audience: 'campus' });
    expect(post.status, JSON.stringify(post.body)).toBe(201);
    const bPost = await call('POST', '/posts', b.token, { text: 'hello from b', audience: 'campus' });
    const comment = await call('POST', `/posts/${bPost.body.id}/comments`, a.token, { text: 'nice one' });
    expect(comment.status, JSON.stringify(comment.body)).toBe(201);

    expect((await call('GET', `/posts/${post.body.id}`, b.token)).status).toBe(200);
    expect((await call('GET', `/posts/${bPost.body.id}/comments`, b.token)).body).toHaveLength(1);

    const off = await call('POST', '/me/deactivate', a.token, { password: PASSWORD });
    expect(off.status).toBe(204);

    // Other students no longer see the post or the comment.
    expect((await call('GET', `/posts/${post.body.id}`, b.token)).status).toBe(404);
    expect((await call('GET', `/posts/${bPost.body.id}/comments`, b.token)).body).toHaveLength(0);
    // The deactivated person's own login is over at once.
    expect((await call('GET', '/posts', a.token)).status).toBe(401);
    // Nothing was deleted.
    expect((await pool.query('select 1 from posts where id = $1', [post.body.id])).rowCount).toBe(1);

    const back = await call('POST', '/auth/login', undefined, { email: a.email, password: PASSWORD });
    expect(back.status, JSON.stringify(back.body)).toBe(200);
    expect(back.body.reactivated).toBe(true);
    expect((await call('GET', `/posts/${post.body.id}`, b.token)).status).toBe(200);
    expect((await call('GET', `/posts/${bPost.body.id}/comments`, b.token)).body).toHaveLength(1);

    // An ordinary login afterwards is not a reactivation.
    const again = await call('POST', '/auth/login', undefined, { email: a.email, password: PASSWORD });
    expect(again.body.reactivated).toBe(false);
  });

  it('stops push notifications to a deactivated account', async () => {
    const a = await h.student();
    await pool.query("insert into device_tokens (user_id, installation_id, fcm_token, platform) values ($1, 'inst-0001', $2, 'android')", [a.id, 'x'.repeat(40)]);
    expect((await call('POST', '/me/deactivate', a.token, { password: PASSWORD })).status).toBe(204);
    const live = await pool.query('select 1 from device_tokens where user_id = $1 and revoked_at is null', [a.id]);
    expect(live.rowCount).toBe(0);
  });

  it('exports only the caller\'s own data and never secrets', async () => {
    const a = await h.student();
    const b = await h.student();
    await call('POST', '/posts', a.token, { text: 'my post', audience: 'campus' });
    await call('POST', '/posts', b.token, { text: 'their post', audience: 'campus' });
    const res = await call('POST', '/me/export', a.token);
    expect(res.status, JSON.stringify(res.body)).toBe(200);
    expect(res.body.profile.email).toBe(a.email);
    expect(res.body.posts.map((p: { text: string }) => p.text)).toEqual(['my post']);
    expect(res.body.truncated).toEqual({ posts: false, comments: false, messagesSent: false });
    const text = JSON.stringify(res.body);
    expect(text).not.toContain('password');
    expect(text).not.toContain(b.email);
  });

  it('limits how often a copy can be requested', async () => {
    const a = await h.student();
    for (let i = 0; i < 3; i++) expect((await call('POST', '/me/export', a.token)).status).toBe(200);
    expect((await call('POST', '/me/export', a.token)).status).toBe(429);
  });
});
