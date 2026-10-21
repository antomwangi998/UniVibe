import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, url } from './helpers';

describe.skipIf(!url)('events stage 7: drafts, early close, guest list, forwarding (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';

  beforeAll(async () => { pool = createPool(url as string); await migrate(pool); });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  const inHours = (n: number) => new Date(Date.now() + n * 3600_000).toISOString();
  const create = async (token: string, over: Record<string, unknown> = {}) => {
    const r = await call('POST', `${V1}/events`, token, {
      title: `Stage seven ${Math.random().toString(36).slice(2, 7)}`, description: 'Come along', category: 'technology',
      venueName: 'Lab 3', startsAt: inHours(30), endsAt: inHours(33), visibility: 'public', ...over,
    });
    expect(r.status, JSON.stringify(r.body)).toBe(201);
    return r.body;
  };

  it('a draft is private to its organizer until published', async () => {
    const org = await h.student();
    const other = await h.student();
    const d = await create(org.token, { status: 'draft' });
    expect(d.status).toBe('draft');
    const ids = async (token: string, path: string) => ((await call('GET', `${V1}${path}`, token)).body.items as Array<{ id: string }>).map((e) => e.id);
    expect(await ids(other.token, '/events')).not.toContain(d.id);
    expect((await call('GET', `${V1}/events/${d.id}`, other.token)).status).toBe(404);
    expect(await ids(org.token, '/me/events?scope=upcoming')).toContain(d.id); // the app's Drafts tab reads this
    expect(await ids(org.token, '/events')).not.toContain(d.id);
    expect((await call('POST', `${V1}/events/${d.id}/publish`, org.token)).status).toBeLessThan(300);
    expect(await ids(other.token, '/events')).toContain(d.id);
  });

  it('closing an event early reports phase "ended" to everyone', async () => {
    const org = await h.student();
    const guest = await h.student();
    const e = await create(org.token);
    await call('POST', `${V1}/events/${e.id}/register`, guest.token);
    await pool.query("update events set starts_at = now() - interval '1 hour', ends_at = now() + interval '1 hour' where id = $1", [e.id]);
    expect((await call('GET', `${V1}/events/${e.id}`, guest.token)).body.phase).toBe('live');
    expect((await call('POST', `${V1}/events/${e.id}/complete`, org.token)).status).toBeLessThan(300);
    expect((await call('GET', `${V1}/events/${e.id}`, guest.token)).body.phase).toBe('ended');
  });

  it('the guest list is for the organizer, or for guests when the organizer allows it', async () => {
    const org = await h.student();
    const guest = await h.student();
    const stranger = await h.student();
    const e = await create(org.token);
    await call('POST', `${V1}/events/${e.id}/register`, guest.token);
    expect((await call('GET', `${V1}/events/${e.id}/attendees`, org.token)).body.items.map((p: { id: string }) => p.id)).toContain(guest.id);
    expect((await call('GET', `${V1}/events/${e.id}/attendees`, guest.token)).status).toBe(200);
    expect((await call('GET', `${V1}/events/${e.id}/attendees`, stranger.token)).status).toBe(403);
    await call('PATCH', `${V1}/events/${e.id}`, org.token, { attendeesVisible: false });
    expect((await call('GET', `${V1}/events/${e.id}/attendees`, guest.token)).status).toBe(403);
  });

  it('a message that holds an event cannot be forwarded as plain text', async () => {
    const a = await h.student();
    const b = await h.student();
    const e = await create(a.token);
    const conv = (await call('POST', `${V1}/conversations`, a.token, { type: 'direct', userId: b.id })).body.id as string;
    const sent = await call('POST', `${V1}/events/${e.id}/share`, a.token, { target: 'chat', targetId: conv });
    expect(sent.status, JSON.stringify(sent.body)).toBe(201);
    const fwd = await call('POST', `${V1}/messages/${sent.body.messageId}/forward`, b.token, { conversationIds: [conv] });
    expect(fwd.status).toBe(422);
    expect(fwd.body.message).toContain('event page');
  });
});
