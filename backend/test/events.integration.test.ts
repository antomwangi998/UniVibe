import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { makeCtx } from '../src/context';
import { createPool, migrate } from '../src/db';
import { RateLimiter } from '../src/limiter';
import { purgeExpiredEvents, sendEventReminders } from '../src/modules/events';
import { harness, testConfig, url } from './helpers';

describe.skipIf(!url)('events (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';

  beforeAll(async () => {
    pool = createPool(url as string);
    await migrate(pool);
    await pool.query("insert into universities (id, name, email_domains) values ('other', 'Other University', array['other.example']) on conflict do nothing");
  });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  const inHours = (n: number) => new Date(Date.now() + n * 3600_000).toISOString();
  const body = (over: Record<string, unknown> = {}) => ({
    title: 'Hack night', description: 'Build things', category: 'technology', venueName: 'Lab 3',
    startsAt: inHours(24), endsAt: inHours(27), ...over,
  });
  const create = async (token: string, over: Record<string, unknown> = {}) => {
    const res = await call('POST', `${V1}/events`, token, body(over));
    expect(res.status, JSON.stringify(res.body)).toBe(201);
    return res.body;
  };

  it('scenario D: organizer creates, students find it, register, capacity holds, cancel works, notifications follow', async () => {
    const org = await h.student();
    const s1 = await h.student();
    const s2 = await h.student();
    const s3 = await h.student();
    // 1: create
    const ev = await create(org.token, { capacity: 2, title: `Scenario ${Date.now()}` });
    expect(ev.isOrganizer).toBe(true);
    expect(ev.spotsLeft).toBe(2);
    // 2: students discover it
    const list = await call('GET', `${V1}/events?q=${encodeURIComponent(ev.title)}`, s1.token);
    expect(list.body.items.map((e: { id: string }) => e.id)).toEqual([ev.id]);
    // 3: register (idempotent)
    expect((await call('POST', `${V1}/events/${ev.id}/register`, s1.token)).status).toBe(201);
    expect((await call('POST', `${V1}/events/${ev.id}/register`, s1.token)).status).toBe(200);
    expect((await call('POST', `${V1}/events/${ev.id}/register`, s2.token)).status).toBe(201);
    // 4: capacity enforced
    const full = await call('POST', `${V1}/events/${ev.id}/register`, s3.token);
    expect(full.status).toBe(409);
    expect(full.body.error).toBe('event_full');
    expect((await call('GET', `${V1}/events/${ev.id}`, s3.token)).body.registeredCount).toBe(2);
    // 5: cancellation frees the place; registering again works
    expect((await call('DELETE', `${V1}/events/${ev.id}/register`, s1.token)).status).toBe(204);
    expect((await call('DELETE', `${V1}/events/${ev.id}/register`, s1.token)).status).toBe(204);
    expect((await call('GET', `${V1}/events/${ev.id}`, s3.token)).body.registeredCount).toBe(1);
    expect((await call('POST', `${V1}/events/${ev.id}/register`, s3.token)).status).toBe(201);
    expect((await call('POST', `${V1}/events/${ev.id}/register`, s1.token)).status).toBe(409);
    // 6: cancelling the event notifies the people going (and nobody else), then closes registration
    expect((await call('POST', `${V1}/events/${ev.id}/cancel`, org.token)).status).toBe(204);
    const kinds = async (t: string) => (await call('GET', `${V1}/notifications`, t)).body.items.filter((n: { type: string }) => n.type === 'event_update').map((n: { metadata: { kind: string } }) => n.metadata.kind);
    const titles = (await call('GET', `${V1}/notifications`, s2.token)).body.items.map((n: { metadata: { title: string } }) => n.metadata.title);
    expect(titles).toEqual([ev.title]); // event notifications carry the title so the app can name the event
    expect(await kinds(s2.token)).toEqual(['cancelled']);
    expect(await kinds(s3.token)).toEqual(['cancelled']);
    expect(await kinds(s1.token)).toEqual([]); // had cancelled their place
    const closed = await call('POST', `${V1}/events/${ev.id}/register`, s1.token);
    expect(closed.status).toBe(409);
    expect(closed.body.error).toBe('event_closed');
  });

  it('never exceeds capacity under concurrent registrations', async () => {
    const org = await h.student();
    const ev = await create(org.token, { capacity: 3 });
    // Registrations are rate limited per IP (10 per test app), so keep the crowd under that.
    const students = await Promise.all(Array.from({ length: 8 }, () => h.student()));
    const results = await Promise.all(students.map((s) => call('POST', `${V1}/events/${ev.id}/register`, s.token)));
    expect(results.filter((r) => r.status === 201)).toHaveLength(3);
    expect(results.filter((r) => r.status === 409)).toHaveLength(5);
    expect(Number((await pool.query("select count(*) from event_registrations where event_id = $1 and status = 'registered'", [ev.id])).rows[0].count)).toBe(3);
  });

  it('validates dates, sizes and categories', async () => {
    const org = await h.student();
    const bad = async (over: Record<string, unknown>, status = 422) =>
      expect((await call('POST', `${V1}/events`, org.token, body(over))).status, JSON.stringify(over)).toBe(status);
    await bad({ endsAt: inHours(23) });                                   // ends before it starts
    await bad({ startsAt: inHours(24), endsAt: inHours(24 + 49) });      // longer than 48 h
    await bad({ startsAt: new Date(Date.now() + 60_000).toISOString(), endsAt: inHours(2) }); // starts in 1 minute
    await bad({ startsAt: inHours(24 * 400), endsAt: inHours(24 * 400 + 2) }); // more than a year away
    await bad({ title: 'ab' }, 400);
    await bad({ category: 'parties' }, 400);
    await bad({ capacity: 0 }, 400);
    await bad({ capacity: 5001 }, 400);
    await bad({ startsAt: 'tomorrow' }, 400);
    await bad({ organizerId: 'someone-else' }, 400);
    expect((await call('GET', `${V1}/events?category=nonsense`, org.token)).status).toBe(400);
  });

  it('limits upcoming events per organizer', async () => {
    const org = await h.student();
    for (let i = 0; i < 5; i++) await create(org.token, { title: `Event number ${i}` });
    const sixth = await call('POST', `${V1}/events`, org.token, body({ title: 'One too many' }));
    expect(sixth.status).toBe(409);
    expect(sixth.body.error).toBe('too_many_events');
  });

  it('only the organizer (or a university admin) may edit, cancel, delete or check people in', async () => {
    const org = await h.student();
    const other = await h.student();
    const admin = await h.student();
    await pool.query("update users set role = 'admin' where id = $1", [admin.id]);
    const ev = await create(org.token);
    expect((await call('PATCH', `${V1}/events/${ev.id}`, other.token, { title: 'Hijacked' })).status).toBe(404);
    expect((await call('POST', `${V1}/events/${ev.id}/cancel`, other.token)).status).toBe(404);
    expect((await call('DELETE', `${V1}/events/${ev.id}`, other.token)).status).toBe(404);
    expect((await call('POST', `${V1}/events/${ev.id}/check-in`, other.token, { userId: other.id })).status).toBe(404);
    expect((await call('PATCH', `${V1}/events/${ev.id}`, admin.token, { title: 'Moderated title' })).body.title).toBe('Moderated title');
    expect((await call('PATCH', `${V1}/events/${ev.id}`, org.token, { organizer: 'x' })).status).toBe(400);
  });

  it('capacity cannot drop below attendance; changes notify people; delete only while nobody joined', async () => {
    const org = await h.student();
    const s1 = await h.student();
    const s2 = await h.student();
    const ev = await create(org.token, { capacity: 5 });
    await call('POST', `${V1}/events/${ev.id}/register`, s1.token);
    await call('POST', `${V1}/events/${ev.id}/register`, s2.token);
    const low = await call('PATCH', `${V1}/events/${ev.id}`, org.token, { capacity: 1 });
    expect(low.status).toBe(422);
    expect((await call('PATCH', `${V1}/events/${ev.id}`, org.token, { venueName: 'Main hall' })).status).toBe(200);
    expect((await call('GET', `${V1}/notifications`, s1.token)).body.items.some((n: { metadata: { kind: string } }) => n.metadata.kind === 'changed')).toBe(true);
    const del = await call('DELETE', `${V1}/events/${ev.id}`, org.token);
    expect(del.status).toBe(409);
    const solo = await create(org.token, { title: 'Solo event' });
    expect((await call('DELETE', `${V1}/events/${solo.id}`, org.token)).status).toBe(204);
    expect((await call('GET', `${V1}/events/${solo.id}`, org.token)).status).toBe(404);
  });

  it('attendee lists follow event privacy and blocking; check-in is organizer-only and time-boxed', async () => {
    const org = await h.student();
    const going = await h.student();
    const outsider = await h.student();
    const blocked = await h.student();
    const ev = await create(org.token, { attendeesVisible: false });
    await call('POST', `${V1}/events/${ev.id}/register`, going.token);
    expect((await call('GET', `${V1}/events/${ev.id}/attendees`, going.token)).status).toBe(403);
    expect((await call('GET', `${V1}/events/${ev.id}/attendees`, outsider.token)).status).toBe(403);
    expect((await call('GET', `${V1}/events/${ev.id}/attendees`, org.token)).body.items).toHaveLength(1);
    await call('PATCH', `${V1}/events/${ev.id}`, org.token, { attendeesVisible: true });
    expect((await call('GET', `${V1}/events/${ev.id}/attendees`, going.token)).status).toBe(200);
    expect((await call('GET', `${V1}/events/${ev.id}/attendees`, outsider.token)).status).toBe(403); // not going
    // blocking hides attendees from each other
    await call('POST', `${V1}/events/${ev.id}/register`, blocked.token);
    await call('POST', `${V1}/users/${blocked.id}/block`, going.token);
    expect((await call('GET', `${V1}/events/${ev.id}/attendees`, going.token)).body.items.map((a: { id: string }) => a.id)).toEqual([going.id]);
    // check-in only inside the window
    expect((await call('POST', `${V1}/events/${ev.id}/check-in`, org.token, { userId: going.id })).status).toBe(409);
    await pool.query("update events set starts_at = now() - interval '10 minutes', ends_at = now() + interval '2 hours' where id = $1", [ev.id]);
    expect((await call('POST', `${V1}/events/${ev.id}/check-in`, org.token, { userId: going.id })).status).toBe(204);
    expect((await call('POST', `${V1}/events/${ev.id}/check-in`, org.token, { userId: outsider.id })).status).toBe(404); // not registered
    expect((await call('GET', `${V1}/events/${ev.id}`, going.token)).body.myRegistration).toBe('checked_in');
    // ended events cannot be joined
    await pool.query("update events set starts_at = now() - interval '5 hours', ends_at = now() - interval '1 hour' where id = $1", [ev.id]);
    expect((await call('POST', `${V1}/events/${ev.id}/register`, outsider.token)).status).toBe(409);
  });

  it('respects university boundaries, visibility, blocking and filters', async () => {
    const org = await h.student();
    const mate = await h.student();
    const outsider = await h.student('other.example', 'other');
    const blockedOrg = await h.student();
    const priv = await create(org.token, { title: `Campus only ${Date.now()}` });
    const pub = await create(org.token, { title: `Open to all ${Date.now()}`, visibility: 'public', category: 'sports' });
    expect((await call('GET', `${V1}/events/${priv.id}`, outsider.token)).status).toBe(404);
    expect((await call('POST', `${V1}/events/${priv.id}/register`, outsider.token)).status).toBe(404);
    expect((await call('GET', `${V1}/events/${pub.id}`, outsider.token)).status).toBe(200);
    expect((await call('GET', `${V1}/events/${priv.id}`, mate.token)).status).toBe(200);
    const sports = await call('GET', `${V1}/events?category=sports&limit=50`, mate.token);
    expect(sports.body.items.every((e: { category: string }) => e.category === 'sports')).toBe(true);
    const range = await call('GET', `${V1}/events?from=${encodeURIComponent(inHours(100))}&limit=50`, mate.token);
    expect(range.body.items.find((e: { id: string }) => e.id === priv.id)).toBeUndefined();
    const hidden = await create(blockedOrg.token, { title: `Blocked host ${Date.now()}` });
    await call('POST', `${V1}/users/${blockedOrg.id}/block`, mate.token);
    expect((await call('GET', `${V1}/events/${hidden.id}`, mate.token)).status).toBe(404);
    expect((await call('GET', `${V1}/events/${hidden.id}`, blockedOrg.token)).status).toBe(200); // organizers always see their own event
    const matesEvent = await create(mate.token, { title: `Mate event ${Date.now()}` });
    expect((await call('GET', `${V1}/events/${matesEvent.id}`, blockedOrg.token)).status).toBe(404); // the block works both ways
  });

  it('paginates upcoming events in start order without repeats; /me/events lists mine', async () => {
    const org = await h.student();
    const fan = await h.student();
    const tag = `Page${Date.now()}`;
    const ids: string[] = [];
    for (let i = 0; i < 5; i++) ids.push((await create(org.token, { title: `${tag} ${i}`, startsAt: inHours(30 + i * 5), endsAt: inHours(32 + i * 5) })).id);
    const seen: string[] = [];
    let cursor: string | null = null;
    do {
      const res: { body: { items: { id: string }[]; nextCursor: string | null } } =
        await call('GET', `${V1}/events?q=${tag}&limit=2${cursor ? `&cursor=${cursor}` : ''}`, fan.token);
      res.body.items.forEach((e) => seen.push(e.id));
      cursor = res.body.nextCursor;
    } while (cursor);
    expect(seen).toEqual(ids);
    await call('POST', `${V1}/events/${ids[0]}/register`, fan.token);
    const mine = await call('GET', `${V1}/me/events`, fan.token);
    expect(mine.body.items.map((e: { id: string }) => e.id)).toEqual([ids[0]]);
    expect((await call('GET', `${V1}/me/events`, org.token)).body.items).toHaveLength(5);
  });

  it('reminders are sent once and only to people going; expired events are purged', async () => {
    const org = await h.student();
    const going = await h.student();
    const notGoing = await h.student();
    const ev = await create(org.token);
    await call('POST', `${V1}/events/${ev.id}/register`, going.token);
    await pool.query("update events set starts_at = now() + interval '30 minutes', ends_at = now() + interval '3 hours' where id = $1", [ev.id]);
    const ctx = makeCtx(pool, testConfig(), new RateLimiter(), null);
    expect(await sendEventReminders(ctx)).toBeGreaterThanOrEqual(1);
    expect(await sendEventReminders(ctx)).toBe(0); // running again sends nothing new
    const reminders = async (t: string) => (await call('GET', `${V1}/notifications`, t)).body.items.filter((n: { metadata: { kind: string } }) => n.metadata.kind === 'reminder');
    expect(await reminders(going.token)).toHaveLength(1);
    expect(await reminders(notGoing.token)).toHaveLength(0);
    // retention: 7 days after it ends
    await pool.query("update events set starts_at = now() - interval '9 days', ends_at = now() - interval '8 days' where id = $1", [ev.id]);
    expect(await purgeExpiredEvents(ctx)).toBeGreaterThanOrEqual(1);
    expect((await call('GET', `${V1}/events/${ev.id}`, going.token)).status).toBe(404);
    expect((await pool.query("select 1 from notifications where entity_type = 'event' and entity_id = $1", [ev.id])).rowCount).toBe(0);
  });

  it('requires authentication', async () => {
    for (const [m, p] of [['GET', '/events'], ['POST', '/events'], ['GET', '/me/events']] as const) expect((await call(m, `${V1}${p}`)).status).toBe(401);
  });
});
