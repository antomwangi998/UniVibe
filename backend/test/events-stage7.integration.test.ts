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

  it('moderator tools: access probe, report list filter, hide and restore without a report, public cover stays closed', async () => {
    const org = await h.student();
    const viewer = await h.student();
    const mod = await h.student();
    await pool.query("update users set role = 'moderator' where id = $1", [mod.id]);
    expect((await call('GET', `${V1}/moderation/access`, viewer.token)).status).toBe(403);
    expect((await call('GET', `${V1}/moderation/access`, mod.token)).status).toBe(200);

    const e = await create(org.token);
    await call('POST', `${V1}/reports`, viewer.token, { targetType: 'event', targetId: e.id, reason: 'spam', details: '' });
    const queue = await call('GET', `${V1}/moderation/reports?targetType=event`, mod.token);
    expect(queue.status).toBe(200);
    expect(queue.body.items.every((r: { targetType: string }) => r.targetType === 'event')).toBe(true);
    expect(queue.body.items.some((r: { targetId: string }) => r.targetId === e.id)).toBe(true);

    expect((await call('POST', `${V1}/moderation/events/${e.id}/hide`, viewer.token, {})).status).toBe(403);
    const hide = await call('POST', `${V1}/moderation/events/${e.id}/hide`, mod.token, { note: 'spam', userMessage: 'Your event was hidden.' });
    expect(hide.status, JSON.stringify(hide.body)).toBe(200);
    expect((await call('GET', `${V1}/events/${e.id}`, viewer.token)).status).toBe(404);
    expect((await call('GET', `${V1}/moderation/reports?targetType=event`, mod.token)).body.items.some((r: { targetId: string }) => r.targetId === e.id)).toBe(false); // settled by the hide
    expect((await call('GET', `/public/events/${e.id}/cover`, undefined)).status).toBe(404);
    expect((await call('POST', `${V1}/moderation/events/${e.id}/restore`, mod.token, {})).status).toBe(200);
    expect((await call('GET', `${V1}/events/${e.id}`, viewer.token)).status).toBe(200);
  });

  it('the public cover address answers 404 for events without a cover, private events and unknown ids', async () => {
    const org = await h.student();
    const noCover = await create(org.token);
    const priv = await create(org.token, { visibility: 'university' });
    expect((await call('GET', `/public/events/${noCover.id}/cover`, undefined)).status).toBe(404);
    expect((await call('GET', `/public/events/${priv.id}/cover`, undefined)).status).toBe(404);
    expect((await call('GET', `/public/events/not-an-id/cover`, undefined)).status).toBe(404);
    const preview = await call('GET', `/public/events/${noCover.id}`, undefined);
    expect(preview.body.hasCover).toBe(false);
    expect(JSON.stringify(preview.body)).not.toMatch(/coverKey|object_key/);
  });

  it('the guest list pages with a cursor', async () => {
    const org = await h.student();
    const e = await create(org.token);
    for (let i = 0; i < 3; i++) await call('POST', `${V1}/events/${e.id}/register`, (await h.student()).token);
    const first = await call('GET', `${V1}/events/${e.id}/attendees?limit=2`, org.token);
    expect(first.body.items).toHaveLength(2);
    expect(first.body.nextCursor).toBeTruthy();
    const second = await call('GET', `${V1}/events/${e.id}/attendees?limit=2&cursor=${encodeURIComponent(first.body.nextCursor)}`, org.token);
    expect(second.body.items).toHaveLength(1);
    expect(second.body.nextCursor).toBeNull();
  });
});
