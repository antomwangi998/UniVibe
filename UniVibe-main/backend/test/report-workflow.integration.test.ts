import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, url } from './helpers';

describe.skipIf(!url)('report workflow (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';
  beforeAll(async () => { pool = createPool(url as string); await migrate(pool); });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  const asRole = async (role: 'admin' | 'moderator') => { const u = await h.student(); await pool.query('update users set role = $2 where id = $1', [u.id, role]); return u; };
  /** A fresh report about a fresh post. */
  const newReport = async () => {
    const author = await h.student(); const reporter = await h.student();
    const post = (await call('POST', '/posts', author.token, { text: 'buy cheap stuff now', audience: 'campus' })).body.id as string;
    const rep = await call('POST', `${V1}/reports`, reporter.token, { targetType: 'post', targetId: post, reason: 'spam' });
    expect(rep.status, JSON.stringify(rep.body)).toBe(201);
    return { id: rep.body.id as string, post, author, reporter };
  };
  const row = async (id: string) => (await pool.query('select * from reports where id = $1', [id])).rows[0];

  it('a moderator can take a report, others cannot decide it, an admin can move it', async () => {
    const m1 = await asRole('moderator'); const m2 = await asRole('moderator'); const admin = await asRole('admin');
    const { id } = await newReport();
    expect((await row(id)).first_reviewed_at).toBeNull();

    expect((await call('POST', `${V1}/moderation/reports/${id}/assign`, m1.token, { to: 'me' })).status).toBe(200);
    const r = await row(id);
    expect(r.assigned_to).toBe(m1.id); expect(r.status).toBe('under_review'); expect(r.first_reviewed_at).not.toBeNull();

    // m2 can neither take it nor decide it, so the same report cannot be handled twice
    const take = await call('POST', `${V1}/moderation/reports/${id}/assign`, m2.token, { to: 'me' });
    expect(take.status).toBe(409); expect(take.body.message).toMatch(/is handling this report/);
    expect((await call('POST', `${V1}/moderation/reports/${id}/review`, m2.token, { status: 'dismissed', note: 'nope' })).status).toBe(409);
    expect((await call('POST', `${V1}/moderation/reports/${id}/action`, m2.token, { action: 'remove_content', note: 'x' })).status).toBe(409);
    expect((await call('POST', `${V1}/moderation/reports/${id}/assign`, m2.token, { to: m2.id })).status).toBe(403);

    // an administrator can hand it over, and only to a moderator
    expect((await call('POST', `${V1}/moderation/reports/${id}/assign`, admin.token, { to: m2.id })).status).toBe(200);
    expect((await row(id)).assigned_to).toBe(m2.id);
    const student = await h.student();
    expect((await call('POST', `${V1}/moderation/reports/${id}/assign`, admin.token, { to: student.id })).status).toBe(422);
    expect((await call('POST', `${V1}/moderation/reports/${id}/assign`, student.token, { to: 'me' })).status).toBe(403);
    expect((await call('POST', `${V1}/moderation/reports/${id}/assign`, admin.token, { to: 'nobody' })).status).toBe(400);

    // the new assignee decides it; deciding twice is refused
    expect((await call('POST', `${V1}/moderation/reports/${id}/review`, m2.token, { status: 'dismissed', note: 'fine' })).status).toBe(200);
    expect((await call('POST', `${V1}/moderation/reports/${id}/review`, m2.token, { status: 'dismissed', note: 'fine' })).status).toBe(409);
    expect((await call('POST', `${V1}/moderation/reports/${id}/assign`, m2.token, { to: 'none' })).status).toBe(409);
    const audit = (await pool.query("select action from audit_logs where target_id = $1 and action like 'report.%' order by created_at", [id])).rows.map((x) => x.action);
    expect(audit).toEqual(['report.assign', 'report.assign', 'report.dismissed']);
  });

  it('lists by assignee, shows who has what, and filters escalated', async () => {
    const m1 = await asRole('moderator'); const m2 = await asRole('moderator');
    const a = await newReport(); const b = await newReport();
    await call('POST', `${V1}/moderation/reports/${a.id}/assign`, m1.token, { to: 'me' });
    const list = async (qs: string, t = m1.token) => (await call('GET', `${V1}/moderation/reports?${qs}`, t)).body.items as { id: string; assignedTo: { id: string } | null; escalatedAt: string | null; noteCount: number }[];
    expect((await list('status=under_review&assigned=me')).map((x) => x.id)).toContain(a.id);
    expect((await list('status=under_review&assigned=me', m2.token)).map((x) => x.id)).not.toContain(a.id);
    expect((await list('status=open&assigned=unassigned')).map((x) => x.id)).toContain(b.id);
    expect((await list('status=under_review')).find((x) => x.id === a.id)?.assignedTo?.id).toBe(m1.id);

    expect((await call('POST', `${V1}/moderation/reports/${a.id}/escalate`, m1.token, { note: '' })).status).toBe(400);
    expect((await call('POST', `${V1}/moderation/reports/${b.id}/escalate`, m2.token, { note: 'looks like a coordinated spam ring' })).status).toBe(200);
    expect((await call('POST', `${V1}/moderation/reports/${b.id}/escalate`, m2.token, { note: 'again please' })).status).toBe(409);
    const esc = await list('status=under_review&escalated=1');
    expect(esc.map((x) => x.id)).toContain(b.id);
    expect(esc.map((x) => x.id)).not.toContain(a.id);
    expect(esc.find((x) => x.id === b.id)?.noteCount).toBe(1);
    // the app's existing queues still work: an escalated report is simply under review
    expect((await row(b.id)).status).toBe('under_review');
  });

  it('keeps private notes with the report, out of the audit log, and shows the full picture on the detail page', async () => {
    const mod = await asRole('moderator'); const outsider = await h.student();
    const { id } = await newReport();
    expect((await call('POST', `${V1}/moderation/reports/${id}/notes`, outsider.token, { note: 'hi' })).status).toBe(403);
    expect((await call('POST', `${V1}/moderation/reports/${id}/notes`, mod.token, { note: '' })).status).toBe(400);
    expect((await call('POST', `${V1}/moderation/reports/${id}/notes`, mod.token, { note: 'Same seller as last week.' })).status).toBe(200);
    const d = await call('GET', `${V1}/moderation/reports/${id}`, mod.token);
    expect(d.status, JSON.stringify(d.body)).toBe(200);
    expect(d.body.notes.map((n: { note: string }) => n.note)).toEqual(['Same seller as last week.']);
    expect(d.body.reportsOnThis).toEqual([{ reason: 'spam', n: 1 }]);
    expect(d.body.preview).toContain('buy cheap');
    expect(JSON.stringify((await pool.query("select details from audit_logs where action = 'report.note' and target_id = $1", [id])).rows)).not.toContain('seller');
    expect((await call('GET', `${V1}/moderation/reports/00000000-0000-4000-8000-000000000000`, mod.token)).status).toBe(404);
    expect((await call('GET', `${V1}/moderation/reports/${id}`, outsider.token)).status).toBe(403);
  });

  it('records when a report was first looked at, separately from when it was resolved', async () => {
    const mod = await asRole('moderator');
    const { id, post } = await newReport();
    expect((await call('POST', `${V1}/moderation/reports/${id}/review`, mod.token, { status: 'under_review' })).status).toBe(200);
    const first = (await row(id)).first_reviewed_at; expect(first).not.toBeNull(); expect((await row(id)).reviewed_at).toBeNull();
    expect((await call('POST', `${V1}/moderation/reports/${id}/action`, mod.token, { action: 'remove_content', note: 'spam' })).status).toBe(200);
    const after = await row(id);
    expect(after.reviewed_at).not.toBeNull();
    expect(new Date(after.first_reviewed_at).getTime()).toBe(new Date(first).getTime());   // never moved by the later decision
    expect((await pool.query('select status from posts where id = $1', [post])).rows[0].status).toBe('removed');
    // undoing from a settled report still works (existing behaviour)
    expect((await call('POST', `${V1}/moderation/reports/${id}/action`, mod.token, { action: 'restore_content', note: 'mistake' })).status).toBe(200);
  });

  it('trust and safety stats: admins only, honest about what is still waiting', async () => {
    const admin = await asRole('admin'); const mod = await asRole('moderator');
    const a = await newReport(); const b = await newReport(); await newReport();
    await call('POST', `${V1}/moderation/reports/${a.id}/review`, mod.token, { status: 'dismissed', note: 'ok' });
    await call('POST', `${V1}/moderation/reports/${b.id}/escalate`, mod.token, { note: 'needs an admin look' });
    expect((await call('GET', `${V1}/admin/trust-safety`, mod.token)).status).toBe(404);
    const t = await call('GET', `${V1}/admin/trust-safety?days=7`, admin.token);
    expect(t.status, JSON.stringify(t.body)).toBe(200);
    expect(t.body.daily.reports).toHaveLength(7);
    expect(t.body.received).toBeGreaterThanOrEqual(3);
    expect(t.body.byReason[0]).toEqual({ reason: 'spam', n: expect.any(Number) });
    expect(t.body.waiting.escalated).toBeGreaterThanOrEqual(1);
    expect(t.body.waiting.total).toBeGreaterThanOrEqual(2);
    expect(t.body.firstReview.n).toBeGreaterThanOrEqual(2);
    expect(t.body.resolution.n).toBeGreaterThanOrEqual(1);
    expect(t.body.stillWaitingFromPeriod).toBeGreaterThanOrEqual(1);
    expect(JSON.stringify(t.body)).not.toMatch(/buy cheap|email|details/);
    const empty = await call('GET', `${V1}/admin/trust-safety?from=2001-01-01&to=2001-01-05`, admin.token);
    expect(empty.body.received).toBe(0); expect(empty.body.firstReview).toEqual({ n: 0, avg: null, median: null });
  });
});
