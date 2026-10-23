import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { makeCtx } from '../src/context';
import { createPool, migrate } from '../src/db';
import { RateLimiter } from '../src/limiter';
import { sendEventReminders } from '../src/modules/events';
import { harness, testConfig, url } from './helpers';

describe.skipIf(!url)('event notifications (needs TEST_DATABASE_URL)', () => {
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
      title: `Notify night ${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`, description: 'Come along', category: 'technology',
      venueName: 'Lab 3', startsAt: inHours(30), endsAt: inHours(33), ...over,
    });
    expect(r.status, JSON.stringify(r.body)).toBe(201);
    return r.body;
  };
  const kinds = async (token: string, eventId?: string) =>
    ((await call('GET', `${V1}/notifications?limit=50`, token)).body.items as Array<{ type: string; entityId: string | null; metadata: { kind?: string } }>)
      .filter((n) => n.type === 'event_update' && (!eventId || n.entityId === eventId)).map((n) => n.metadata.kind).sort();
  const jobsCtx = () => makeCtx(pool, testConfig(), new RateLimiter(), null);
  const startIn = (id: string, minutes: number) =>
    pool.query("update events set starts_at = now() + make_interval(mins => $2::int), ends_at = now() + make_interval(mins => $2::int + 180) where id = $1", [id, minutes]);

  it('registering sends one confirmation, however often the person toggles', async () => {
    const org = await h.student();
    const s1 = await h.student();
    const e = await create(org.token);
    for (let i = 0; i < 3; i++) {
      await call('POST', `${V1}/events/${e.id}/register`, s1.token);
      await call('DELETE', `${V1}/events/${e.id}/register`, s1.token);
    }
    await call('POST', `${V1}/events/${e.id}/register`, s1.token);
    expect(await kinds(s1.token, e.id)).toEqual(['confirmed']);
  });

  it('the organizer hears at milestones and when the event fills, not per person', async () => {
    const org = await h.student();
    const people = await Promise.all([1, 2, 3, 4].map(() => h.student()));
    const e = await create(org.token, { capacity: 3 });
    for (const p of people.slice(0, 3)) await call('POST', `${V1}/events/${e.id}/register`, p.token);
    // 1st registration (milestone) and the one that filled it (3 of 3): two notices, not three.
    expect(await kinds(org.token, e.id)).toEqual(['registrations', 'registrations']);
    expect((await call('POST', `${V1}/events/${e.id}/register`, people[3].token)).status).toBe(409);
    expect(await kinds(org.token, e.id)).toHaveLength(2);
    // The organizer's own registration does not notify them.
    const mine = await create(org.token);
    await call('POST', `${V1}/events/${mine.id}/register`, org.token);
    expect(await kinds(org.token, mine.id)).toEqual(['confirmed']);
  });

  it('a change then a cancellation both arrive, wording edits never do, and repeated changes become one', async () => {
    const org = await h.student();
    const going = await h.student();
    const interested = await h.student();
    const stranger = await h.student();
    const e = await create(org.token);
    await call('POST', `${V1}/events/${e.id}/register`, going.token);
    await call('PUT', `${V1}/events/${e.id}/rsvp`, interested.token, { state: 'interested' });
    await call('PATCH', `${V1}/events/${e.id}`, org.token, { title: 'A new title', description: 'More words.' });
    expect(await kinds(going.token, e.id)).toEqual(['confirmed']);
    // Re-sending the same venue or time is not a change.
    await call('PATCH', `${V1}/events/${e.id}`, org.token, { venueName: 'Lab 3', startsAt: e.startsAt });
    expect(await kinds(going.token, e.id)).toEqual(['confirmed']);
    await call('PATCH', `${V1}/events/${e.id}`, org.token, { venueName: 'Main Hall' });
    await call('PATCH', `${V1}/events/${e.id}`, org.token, { venueName: 'Library' });
    expect(await kinds(going.token, e.id)).toEqual(['changed', 'confirmed']);
    expect(await kinds(interested.token, e.id)).toEqual(['changed']);
    await call('POST', `${V1}/events/${e.id}/cancel`, org.token);
    expect(await kinds(going.token, e.id)).toEqual(['cancelled', 'changed', 'confirmed']); // the cancellation is not swallowed by the earlier change
    expect(await kinds(interested.token, e.id)).toEqual(['cancelled', 'changed']);
    expect(await kinds(stranger.token, e.id)).toEqual([]);
    expect(await kinds(org.token, e.id)).not.toContain('cancelled');
  });

  it('reminders: 24 hours and 1 hour, once each, with no late 24-hour reminder', async () => {
    const org = await h.student();
    const s1 = await h.student();
    const e = await create(org.token);
    await call('POST', `${V1}/events/${e.id}/register`, s1.token);
    const ctx = jobsCtx();
    const reminders = async () => (await call('GET', `${V1}/notifications?limit=50`, s1.token)).body.items
      .filter((n: { metadata: { kind?: string } }) => n.metadata.kind === 'reminder').map((n: { metadata: { offsetMinutes: number } }) => n.metadata.offsetMinutes).sort();
    // sendEventReminders works on every event in the database, and other test files run at the same time,
    // so count this event's own reminder rows rather than the function's return value.
    const mine = async () => (await pool.query('select offset_minutes from event_reminders where event_id = $1 order by 1', [e.id])).rows.map((r) => r.offset_minutes);
    await startIn(e.id, 1440 - 10); // the 24 hour mark was 10 minutes ago
    await sendEventReminders(ctx);
    expect(await mine()).toEqual([1440]);
    await sendEventReminders(ctx); // running again sends nothing new
    expect(await mine()).toEqual([1440]);
    await startIn(e.id, 50); // the 1 hour mark was 10 minutes ago
    await sendEventReminders(ctx);
    await sendEventReminders(ctx);
    expect(await mine()).toEqual([60, 1440]);
    expect(await reminders()).toEqual([60, 1440]);
  });

  it('a late registration gets no stale 24-hour reminder, only the 1-hour one', async () => {
    const org = await h.student();
    const s1 = await h.student();
    const e = await create(org.token);
    await startIn(e.id, 300); // 5 hours away
    await call('POST', `${V1}/events/${e.id}/register`, s1.token);
    const ctx = jobsCtx();
    expect(await sendEventReminders(ctx)).toBe(0);
    await startIn(e.id, 55);
    expect(await sendEventReminders(ctx)).toBe(1);
    const r = (await call('GET', `${V1}/notifications?limit=50`, s1.token)).body.items.filter((n: { metadata: { kind?: string } }) => n.metadata.kind === 'reminder');
    expect(r.map((n: { metadata: { offsetMinutes: number } }) => n.metadata.offsetMinutes)).toEqual([60]);
  });

  it('reminders respect cancellations, withdrawals, per-event and personal settings', async () => {
    const org = await h.student();
    const [a, b, c, d, f] = await Promise.all([1, 2, 3, 4, 5].map(() => h.student()));
    const e = await create(org.token);
    for (const p of [a, b, c, d, f]) await call('POST', `${V1}/events/${e.id}/register`, p.token);
    await call('DELETE', `${V1}/events/${e.id}/register`, b.token); // withdrew
    expect((await call('PUT', `${V1}/events/${e.id}/reminders`, c.token, { enabled: false })).status).toBe(204); // silenced this event
    expect((await call('PUT', `${V1}/events/${e.id}/reminders`, org.token, { enabled: false })).status).toBe(409); // not registered
    const prefs = await call('PUT', `${V1}/me/event-notification-preferences`, d.token, { reminderOffsets: [1440] }); // no 1-hour reminder
    expect(prefs.body.reminderOffsets).toEqual([1440]);
    await call('PUT', `${V1}/me/event-notification-preferences`, f.token, { reminders: false }); // reminders off entirely
    await startIn(e.id, 50);
    await sendEventReminders(jobsCtx());
    const got = async (t: string) => (await kinds(t, e.id)).includes('reminder');
    expect(await got(a.token)).toBe(true);
    expect(await got(b.token)).toBe(false);
    expect(await got(c.token)).toBe(false);
    expect(await got(d.token)).toBe(false);
    expect(await got(f.token)).toBe(false);
    // A cancelled event sends nothing.
    const e2 = await create(org.token);
    await call('POST', `${V1}/events/${e2.id}/register`, a.token);
    await call('POST', `${V1}/events/${e2.id}/cancel`, org.token);
    await startIn(e2.id, 50);
    await sendEventReminders(jobsCtx());
    expect(await kinds(a.token, e2.id)).not.toContain('reminder');
  });

  it('moving the start re-arms reminders for the new time', async () => {
    const org = await h.student();
    const s1 = await h.student();
    const e = await create(org.token);
    await call('POST', `${V1}/events/${e.id}/register`, s1.token);
    await startIn(e.id, 50);
    await sendEventReminders(jobsCtx());
    expect((await pool.query('select count(*)::int as n from event_reminders where event_id = $1', [e.id])).rows[0].n).toBe(1);
    const moved = await call('PATCH', `${V1}/events/${e.id}`, org.token, { startsAt: inHours(2), endsAt: inHours(4) });
    expect(moved.status, JSON.stringify(moved.body)).toBe(200);
    expect((await pool.query('select count(*)::int as n from event_reminders where event_id = $1', [e.id])).rows[0].n).toBe(0);
    await startIn(e.id, 55);
    expect(await sendEventReminders(jobsCtx())).toBe(1);
  });

  it('turning a notice type off stops it, and the master events switch still applies', async () => {
    const org = await h.student();
    const quiet = await h.student();
    const off = await h.student();
    const e = await create(org.token);
    await call('PUT', `${V1}/me/event-notification-preferences`, quiet.token, { confirmations: false, changes: false });
    await call('PUT', `${V1}/me/notification-preferences`, off.token, { events: false });
    for (const p of [quiet, off]) await call('POST', `${V1}/events/${e.id}/register`, p.token);
    await call('PATCH', `${V1}/events/${e.id}`, org.token, { venueName: 'Elsewhere' });
    await call('POST', `${V1}/events/${e.id}/cancel`, org.token);
    expect(await kinds(quiet.token, e.id)).toEqual(['cancelled']);
    expect(await kinds(off.token, e.id)).toEqual([]);
    const prefs = (await call('GET', `${V1}/me/event-notification-preferences`, quiet.token)).body;
    expect(prefs).toMatchObject({ confirmations: false, changes: false, cancellations: true, reminders: true, reminderOffsets: [1440, 60] });
    expect((await call('PUT', `${V1}/me/event-notification-preferences`, quiet.token, { reminderOffsets: [30] })).status).toBe(400);
  });

  it('community members get an in-app event notice only if they asked for more than mentions', async () => {
    const owner = await h.student();
    const keen = await h.student();
    const quietMember = await h.student();
    const muted = await h.student();
    const c = (await call('POST', `${V1}/communities`, owner.token, { name: `Notify club ${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`, description: 'A community that hosts events.', category: 'clubs' })).body;
    for (const [u, level] of [[keen, 'all'], [quietMember, 'mentions'], [muted, 'muted']] as const) {
      await pool.query("insert into community_members (community_id, user_id, role, notification_level) values ($1, $2, 'member', $3)", [c.id, u.id, level]);
    }
    const e = await create(owner.token, { communityId: c.id, visibility: 'public' });
    const { deliveryHandlers } = await import('../src/modules/delivery');
    const { Hub } = await import('../src/realtime');
    const handlers = deliveryHandlers(jobsCtx(), new Hub(), { send: async () => ({ status: 'accepted' }) } as never);
    const job = { id: 'x', kind: 'event.announce', payload: { eventId: e.id }, attempts: 1, max_attempts: 6 };
    await handlers['event.announce'](job);
    await handlers['event.announce'](job); // a retry adds nothing
    expect(await kinds(keen.token, e.id)).toEqual(['community_event']);
    expect(await kinds(quietMember.token, e.id)).toEqual([]);
    expect(await kinds(muted.token, e.id)).toEqual([]);
    expect(await kinds(owner.token, e.id)).toEqual([]);
    const row = (await pool.query("select push_status from notifications where recipient_id = $1 and entity_id = $2", [keen.id, e.id])).rows[0];
    expect(row).toBeTruthy();
  });
});
