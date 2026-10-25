import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, url, PASSWORD } from './helpers';

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

    expect((await call('POST', `${V1}/admin/users/${target.id}/unsuspend`, admin.token, { note: 'appeal accepted' })).status).toBe(200);
    expect((await call('GET', `${V1}/admin/users/${target.id}`, admin.token)).body.suspendedUntil).toBeNull();

    const audit = await call('GET', `${V1}/admin/audit`, admin.token);
    const kinds = audit.body.items.map((a: { action: string }) => a.action);
    expect(kinds).toContain('suspend_user');
    expect(kinds).toContain('unsuspend_user');
  });

  it('never suspends an administrator or yourself, and rejects bad input', async () => {
    const admin = await asRole('admin');
    const other = await asRole('admin');
    expect((await call('POST', `${V1}/admin/users/${other.id}/suspend`, admin.token, { note: 'testing' })).status).toBe(403);
    expect((await call('POST', `${V1}/admin/users/${admin.id}/suspend`, admin.token, { note: 'testing' })).status).toBe(403);
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

  it('analytics: admins only, honours the range, and matches the records', async () => {
    const student = await h.student();
    const mod = await asRole('moderator');
    for (const t of [student.token, mod.token]) expect((await call('GET', `${V1}/admin/analytics`, t)).status).toBe(404);

    const admin = await asRole('admin');
    const a = await call('GET', `${V1}/admin/analytics?days=7`, admin.token);
    expect(a.status, JSON.stringify(a.body)).toBe(200);
    expect(a.body.range.length).toBe(7);
    expect(a.body.daily.signups).toHaveLength(7);
    expect(a.body.daily.posts).toHaveLength(7);
    const sum = (rows: { n: number }[]) => rows.reduce((x, r) => x + r.n, 0);
    expect(a.body.totals.signups.current).toBe(sum(a.body.daily.signups));
    const real = (await pool.query("select count(*)::int as n from users where created_at >= current_date - 6")).rows[0].n;
    expect(a.body.totals.signups.current).toBeLessThanOrEqual(real + 0);
    expect(a.body.totals.signups.current).toBeGreaterThan(0);
    expect(a.body.accounts.users).toBeGreaterThan(0);
    expect(JSON.stringify(a.body)).not.toMatch(/email|password|body/i);

    const today = new Date().toISOString().slice(0, 10);
    const custom = await call('GET', `${V1}/admin/analytics?from=${today}&to=${today}`, admin.token);
    expect(custom.status).toBe(200);
    expect(custom.body.daily.messages).toHaveLength(1);

    const empty = await call('GET', `${V1}/admin/analytics?from=2001-01-01&to=2001-01-10`, admin.token);
    expect(empty.status).toBe(200);
    expect(empty.body.totals.signups.current).toBe(0);
    expect(empty.body.reports.avgHoursToFirstReview).toBeNull();

    expect((await call('GET', `${V1}/admin/analytics?days=13`, admin.token)).status).toBe(400);
    expect((await call('GET', `${V1}/admin/analytics?from=2026-01-01`, admin.token)).status).toBe(400);
  });

  it('tells the dashboard what each role may do, and nobody else', async () => {
    const student = await h.student();
    const mod = await asRole('moderator');
    const admin = await asRole('admin');
    expect((await call('GET', `${V1}/moderation/access`, student.token)).status).toBe(403);
    const m = await call('GET', `${V1}/moderation/access`, mod.token);
    expect(m.body.capabilities).toEqual(['reports.review']);
    const a = await call('GET', `${V1}/moderation/access`, admin.token);
    expect(a.body.capabilities).toEqual(expect.arrayContaining(['audit.view', 'users.suspend', 'analytics.view']));
    // A moderator cannot suspend, even by calling the route directly.
    const target = await h.student();
    expect((await call('POST', `${V1}/admin/users/${target.id}/suspend`, mod.token, { days: 1 })).status).toBe(404);
    expect((await pool.query('select suspended_until from users where id = $1', [target.id])).rows[0].suspended_until).toBeNull();
  });

  it('records a moderator reaching for an administrator route, once a minute at most', async () => {
    const mod = await asRole('moderator');
    for (let i = 0; i < 3; i++) expect((await call('GET', `${V1}/admin/audit`, mod.token)).status).toBe(404);
    const rows = (await pool.query("select details from audit_logs where actor_id = $1 and action = 'admin_access_denied'", [mod.id])).rows;
    expect(rows).toHaveLength(1);
    expect(rows[0].details).toEqual({ capability: 'audit.view', path: '/api/v1/admin/audit' });
  });

  it('keeps the audit trail append-only, yet lets a deleted person go', async () => {
    const who = await h.student();
    await pool.query("insert into audit_logs (actor_id, action, details) values ($1, 'test_entry', '{\"a\":1}')", [who.id]);
    const id = (await pool.query("select id from audit_logs where actor_id = $1 and action = 'test_entry'", [who.id])).rows[0].id;
    await expect(pool.query("update audit_logs set details = '{}' where id = $1", [id])).rejects.toThrow(/cannot be changed/);
    await expect(pool.query("update audit_logs set actor_id = null, action = 'x' where id = $1", [id])).rejects.toThrow(/cannot be changed/);
    await expect(pool.query('delete from audit_logs where id = $1', [id])).rejects.toThrow(/cannot be deleted/);
    await pool.query('delete from users where id = $1', [who.id]);
    const after = (await pool.query('select actor_id, action from audit_logs where id = $1', [id])).rows[0];
    expect(after).toEqual({ actor_id: null, action: 'test_entry' });
  });

  it('records staff sign-ins and failed attempts, but not students', async () => {
    const mod = await asRole('moderator');
    const student = await h.student();
    const bad = await call('POST', '/auth/login', undefined, { email: mod.email, password: 'wrong-password-1' });
    expect(bad.status).toBe(401);
    await call('POST', '/auth/login', undefined, { email: student.email, password: 'wrong-password-1' });
    const ok = await call('POST', '/auth/login', undefined, { email: mod.email, password: PASSWORD });
    expect(ok.status, JSON.stringify(ok.body)).toBe(200);
    const seen = (await pool.query("select action, details from audit_logs where actor_id = $1 and action like 'staff_login%' order by created_at", [mod.id])).rows;
    expect(seen.map((r) => r.action)).toEqual(['staff_login_failed', 'staff_login']);
    expect(JSON.stringify(seen)).not.toMatch(/password|wrong/i);
    expect((await pool.query("select 1 from audit_logs where actor_id = $1", [student.id])).rowCount).toBe(0);
  });

  it('people directory: filters, sorting and pages are done by the server', async () => {
    const admin = await asRole('admin');
    const a = await h.student(); const b = await h.student(); await h.student();
    expect((await call('POST', `${V1}/admin/users/${a.id}/suspend`, admin.token, { days: 2, note: 'spam' })).status).toBe(200);
    const list = async (qs: string) => { const r = await call('GET', `${V1}/admin/users${qs}`, admin.token); expect(r.status, JSON.stringify(r.body)).toBe(200); return r.body; };

    const all = await list('');                       // no search word needed
    expect(all.total).toBeGreaterThanOrEqual(4);
    expect(all.items.length).toBeLessThanOrEqual(25);
    expect(all.universities.length).toBeGreaterThan(0);

    const sus = await list('?status=suspended');
    expect(sus.items.map((u: { id: string }) => u.id)).toContain(a.id);
    expect(sus.items.every((u: { suspendedUntil: string | null }) => u.suspendedUntil)).toBe(true);
    const act = await list('?status=active&limit=50');
    expect(act.items.map((u: { id: string }) => u.id)).not.toContain(a.id);
    expect(act.items.map((u: { id: string }) => u.id)).toContain(b.id);
    expect((await list('?status=unverified')).items.every((u: { verified: boolean }) => !u.verified)).toBe(true);
    expect((await list('?status=recent')).items.map((u: { id: string }) => u.id)).toContain(b.id);
    expect((await list('?status=reported')).items.every((u: { openReports: number }) => u.openReports > 0)).toBe(true);
    expect((await list('?university=lakeside')).items.every((u: { university: string }) => u.university === 'lakeside')).toBe(true);
    expect((await list(`?q=${encodeURIComponent(b.email.slice(0, 12))}&status=active`)).items.map((u: { id: string }) => u.id)).toContain(b.id);

    const p1 = await list('?limit=2&page=1'); const p2 = await list('?limit=2&page=2');
    expect(p1.items).toHaveLength(2);
    expect(p1.pages).toBe(Math.ceil(p1.total / 2));
    expect(p2.items.map((u: { id: string }) => u.id).some((id: string) => p1.items.map((u: { id: string }) => u.id).includes(id))).toBe(false);
    const names = (await list('?sort=name&limit=50')).items.map((u: { name: string }) => u.name.toLowerCase());
    expect(names).toEqual([...names].sort());
    const oldest = (await list('?sort=oldest&limit=2')).items; const newest = (await list('?sort=newest&limit=2')).items;
    expect(new Date(oldest[0].joinedAt).getTime()).toBeLessThanOrEqual(new Date(newest[0].joinedAt).getTime());

    for (const bad of ['?status=everything', '?sort=email', '?page=0', '?limit=500', '?q=a', '?evil=1']) expect((await call('GET', `${V1}/admin/users${bad}`, admin.token)).status, bad).toBe(400);
    const mod = await asRole('moderator');
    expect((await call('GET', `${V1}/admin/users`, mod.token)).status).toBe(404);
  });

  it('requires a reason to suspend or lift, and keeps private notes out of the audit log', async () => {
    const admin = await asRole('admin'); const mod = await asRole('moderator'); const target = await h.student();
    expect((await call('POST', `${V1}/admin/users/${target.id}/suspend`, admin.token, { days: 1 })).status).toBe(400);
    expect((await call('POST', `${V1}/admin/users/${target.id}/suspend`, admin.token, { days: 1, note: ' ' })).status).toBe(400);
    expect((await call('POST', `${V1}/admin/users/${target.id}/unsuspend`, admin.token, {})).status).toBe(400);
    expect((await call('GET', `${V1}/admin/users/${target.id}`, admin.token)).body.suspendedUntil).toBeNull();

    expect((await call('POST', `${V1}/admin/users/${target.id}/notes`, mod.token, { note: 'hi' })).status).toBe(404);
    expect((await call('POST', `${V1}/admin/users/${target.id}/notes`, admin.token, { note: '' })).status).toBe(400);
    expect((await call('POST', `${V1}/admin/users/${target.id}/notes`, admin.token, { note: 'Warned about spam in chat.' })).status).toBe(200);
    expect((await call('POST', `${V1}/admin/users/00000000-0000-4000-8000-000000000000/notes`, admin.token, { note: 'x' })).status).toBe(404);
    const d = await call('GET', `${V1}/admin/users/${target.id}`, admin.token);
    expect(d.body.notes[0].note).toBe('Warned about spam in chat.');
    expect(d.body.counts).toEqual(expect.objectContaining({ communities: 0, eventsHosted: 0, eventsJoined: 0 }));
    const logged = (await pool.query("select details from audit_logs where action = 'admin_note_add' and target_id = $1", [target.id])).rows;
    expect(logged).toHaveLength(1);
    expect(JSON.stringify(logged)).not.toContain('spam');
    // the person themself never sees it
    expect(JSON.stringify((await call('GET', `${V1}/me`, target.token)).body)).not.toContain('Warned');
  });
});
