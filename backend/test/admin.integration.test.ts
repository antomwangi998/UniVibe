import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, url } from './helpers';

describe.skipIf(!url)('admin dashboard API (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';

  beforeAll(async () => { pool = createPool(url as string); await migrate(pool); });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  const asRole = async (role: 'admin' | 'moderator') => {
    const u = await h.student();
    await pool.query('update users set role = $2 where id = $1', [u.id, role]);
    return u;
  };

  it('is invisible to everyone but administrators', async () => {
    const student = await h.student();
    const mod = await asRole('moderator');
    for (const t of [student.token, mod.token]) {
      expect((await call('GET', `${V1}/admin/overview`, t)).status).toBe(404);
      expect((await call('GET', `${V1}/admin/users?q=ab`, t)).status).toBe(404);
      expect((await call('GET', `${V1}/admin/health`, t)).status).toBe(404);
      expect((await call('GET', `${V1}/admin/audit`, t)).status).toBe(404);
    }
    expect((await call('GET', `${V1}/moderation/access`, mod.token)).status).toBe(200);
  });

  it('overview and health return numbers, never message text', async () => {
    const admin = await asRole('admin');
    const o = await call('GET', `${V1}/admin/overview`, admin.token);
    expect(o.status, JSON.stringify(o.body)).toBe(200);
    expect(o.body.totals.users).toBeGreaterThan(0);
    expect(o.body.days.signups).toHaveLength(14);
    expect(JSON.stringify(o.body)).not.toMatch(/content/);
    const s = await call('GET', `${V1}/admin/health`, admin.token);
    expect(s.status, JSON.stringify(s.body)).toBe(200);
    expect(s.body.database.bytes).toBeGreaterThan(0);
    expect(s.body.jobs).toBeTruthy();
  });

  it('finds a person, suspends and restores them, and records both in the audit log', async () => {
    const admin = await asRole('admin');
    const target = await h.student();
    const found = await call('GET', `${V1}/admin/users?q=${encodeURIComponent(target.email.slice(0, 8))}`, admin.token);
    expect(found.status, JSON.stringify(found.body)).toBe(200);
    expect(found.body.items.some((u: { id: string }) => u.id === target.id)).toBe(true);

    expect((await call('POST', `${V1}/admin/users/${target.id}/suspend`, admin.token, { days: 3, note: 'spam', userMessage: 'Please follow the rules.' })).status).toBe(200);
    const detail = await call('GET', `${V1}/admin/users/${target.id}`, admin.token);
    expect(detail.body.suspendedUntil).not.toBeNull();
    expect(detail.body.actions[0].action).toBe('suspend_user');

    expect((await call('POST', `${V1}/admin/users/${target.id}/unsuspend`, admin.token, {})).status).toBe(200);
    expect((await call('GET', `${V1}/admin/users/${target.id}`, admin.token)).body.suspendedUntil).toBeNull();

    const audit = await call('GET', `${V1}/admin/audit`, admin.token);
    const kinds = audit.body.items.map((a: { action: string }) => a.action);
    expect(kinds).toContain('suspend_user');
    expect(kinds).toContain('unsuspend_user');
  });

  it('never suspends an administrator or yourself, and rejects bad input', async () => {
    const admin = await asRole('admin');
    const other = await asRole('admin');
    expect((await call('POST', `${V1}/admin/users/${other.id}/suspend`, admin.token, {})).status).toBe(403);
    expect((await call('POST', `${V1}/admin/users/${admin.id}/suspend`, admin.token, {})).status).toBe(403);
    const target = await h.student();
    expect((await call('POST', `${V1}/admin/users/${target.id}/suspend`, admin.token, { days: 400 })).status).toBe(400);
    expect((await call('GET', `${V1}/admin/users?q=a`, admin.token)).status).toBe(400);
  });

  it('serves the dashboard page with a strict content-security policy', async () => {
    const page = await h.app().inject({ method: 'GET', url: '/admin' });
    expect(page.statusCode).toBe(200);
    expect(page.headers['content-security-policy']).toContain("default-src 'none'");
    expect(page.body).toContain('/admin/app.js');
    const js = await h.app().inject({ method: 'GET', url: '/admin/app.js' });
    expect(js.statusCode).toBe(200);
    expect(js.body).not.toContain('innerHTML');
  });
});
