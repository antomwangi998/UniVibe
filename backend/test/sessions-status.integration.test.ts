import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, PASSWORD, url } from './helpers';

describe.skipIf(!url)('sessions and account status (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';

  beforeAll(async () => { pool = createPool(url as string); await migrate(pool); });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  const login = async (email: string, deviceName: string) => {
    const res = await call('POST', '/auth/login', undefined, { email, password: PASSWORD, deviceName });
    expect(res.status).toBe(200);
    return res.body as { accessToken: string; refreshToken: string };
  };

  it('lists only real logins, marks the current one, and ends others immediately', async () => {
    const s = await h.student();
    const phone = await login(s.email, 'Pixel 8');
    const tablet = await login(s.email, 'Galaxy Tab');

    const list = await call('GET', `${V1}/me/sessions`, phone.accessToken);
    expect(list.status).toBe(200);
    const labels = list.body.items.map((i: { deviceLabel: string }) => i.deviceLabel);
    expect(labels).toEqual(expect.arrayContaining(['Pixel 8', 'Galaxy Tab']));
    expect(list.body.items.filter((i: { current: boolean }) => i.current)).toHaveLength(1);
    expect(list.body.items.find((i: { current: boolean }) => i.current).deviceLabel).toBe('Pixel 8');
    expect(JSON.stringify(list.body)).not.toMatch(/refresh|token_hash|ip/i);

    const res = await call('POST', `${V1}/me/sessions/revoke-others`, phone.accessToken);
    expect(res.status).toBe(200);
    expect(res.body.revoked).toBeGreaterThanOrEqual(2); // the tablet and the sign-up login
    expect((await call('GET', '/me', tablet.accessToken)).status).toBe(401);
    expect((await call('POST', '/auth/refresh', undefined, { refreshToken: tablet.refreshToken })).status).toBe(401);
    expect((await call('GET', '/me', phone.accessToken)).status).toBe(200);
    expect((await call('GET', `${V1}/me/sessions`, phone.accessToken)).body.items).toHaveLength(1);
  });

  it('keeps the label through token rotation and cannot end someone else’s session', async () => {
    const a = await h.student();
    const b = await h.student();
    const first = await login(a.email, 'Pixel 8');
    const rotated = (await call('POST', '/auth/refresh', undefined, { refreshToken: first.refreshToken })).body;
    const list = await call('GET', `${V1}/me/sessions`, rotated.accessToken);
    expect(list.body.items.find((i: { current: boolean }) => i.current).deviceLabel).toBe('Pixel 8');

    const theirs = (await call('GET', `${V1}/me/sessions`, (await login(b.email, 'Other')).accessToken)).body.items[0].id;
    expect((await call('DELETE', `${V1}/me/sessions/${theirs}`, rotated.accessToken)).status).toBe(404);
    expect((await call('DELETE', `${V1}/me/sessions/not-a-uuid`, rotated.accessToken)).status).toBe(404);
  });

  it('can end the current session, which logs that device out', async () => {
    const s = await h.student();
    const only = await login(s.email, 'Pixel 8');
    const cur = (await call('GET', `${V1}/me/sessions`, only.accessToken)).body.currentSessionId;
    expect((await call('DELETE', `${V1}/me/sessions/${cur}`, only.accessToken)).status).toBe(204);
    expect((await call('GET', '/me', only.accessToken)).status).toBe(401);
  });

  it('reports account status, hides internal notes, and stays readable while suspended', async () => {
    const s = await h.student();
    let status = await call('GET', `${V1}/me/account-status`, s.token);
    expect(status.status).toBe(200);
    expect(status.body).toMatchObject({ standing: 'good', restrictions: [], appeals: { supported: false } });

    await pool.query("update users set suspended_until = now() + interval '3 days' where id = $1", [s.id]);
    await pool.query(
      "insert into moderation_actions (action, target_type, target_id, note, user_message) values ('suspend_user', 'user', $1, 'INTERNAL-NOTE', 'Repeated spam.')", [s.id]);
    status = await call('GET', `${V1}/me/account-status`, s.token);
    expect(status.status).toBe(200);
    expect(status.body.standing).toBe('suspended');
    expect(status.body.restrictions[0].kind).toBe('suspension');
    expect(status.body.notices[0].message).toBe('Repeated spam.');
    expect(JSON.stringify(status.body)).not.toContain('INTERNAL-NOTE');
    expect((await call('GET', `${V1}/me/sessions`, s.token)).status).toBe(403);
    expect((await call('GET', `${V1}/me/account-status`)).status).toBe(401);
  });
});
