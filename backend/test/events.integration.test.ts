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
    expect(titles).toEqual([ev.title, ev.title]); // event notifications carry the title so the app can name the event
    expect((await kinds(s2.token)).sort()).toEqual(['cancelled', 'confirmed']);
    expect((await kinds(s3.token)).sort()).toEqual(['cancelled', 'confirmed']);
    expect(await kinds(s1.token)).toEqual(['confirmed']); // had cancelled their place, so no cancellation notice
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
    await pool.query("update events set starts_at = now() + interval '50 minutes', ends_at = now() + interval '3 hours' where id = $1", [ev.id]);
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

  // ---- Events v2, stage 1 ----
  const makeCommunity = async (token: string, over: Record<string, unknown> = {}) => {
    const r = await call('POST', `${V1}/communities`, token, {
      name: `Events club ${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`, description: 'A community that hosts events.', category: 'clubs', ...over,
    });
    expect(r.status, JSON.stringify(r.body)).toBe(201);
    return r.body as { id: string };
  };
  const join = (communityId: string, userId: string, role = 'member') =>
    pool.query("insert into community_members (community_id, user_id, role) values ($1, $2, $3) on conflict (community_id, user_id) do update set role = $3, status = 'active'", [communityId, userId, role]);

  it('v2: online and hybrid events need a join link, only organizer and registrants see it, and the server derives registration state', async () => {
    const org = await h.student();
    const s1 = await h.student();
    expect((await call('POST', `${V1}/events`, org.token, body({ eventType: 'online' }))).status).toBe(422); // no link
    expect((await call('POST', `${V1}/events`, org.token, body({ eventType: 'online', onlineUrl: 'http://insecure.example' }))).status).toBe(400);
    expect((await call('POST', `${V1}/events`, org.token, body({ eventType: 'in_person', venueName: undefined }))).status).toBe(422); // no venue
    const e = await create(org.token, { eventType: 'online', venueName: undefined, onlineUrl: 'https://meet.example/abc', capacity: 1 });
    expect(e.venueName).toBe('Online');
    expect(e.onlineUrl).toBe('https://meet.example/abc');
    const seen = (await call('GET', `${V1}/events/${e.id}`, s1.token)).body;
    expect(seen.onlineUrl).toBeNull();
    expect(seen.hasOnlineLink).toBe(true);
    expect(seen.registration).toBe('open');
    expect((await call('POST', `${V1}/events/${e.id}/register`, s1.token)).status).toBe(201);
    const after = (await call('GET', `${V1}/events/${e.id}`, s1.token)).body;
    expect(after.onlineUrl).toBe('https://meet.example/abc');
    expect(after.registration).toBe('full');
  });

  it('v2: the registration deadline is enforced by the server and must precede the end', async () => {
    const org = await h.student();
    const s1 = await h.student();
    expect((await call('POST', `${V1}/events`, org.token, body({ registrationDeadline: inHours(40) }))).status).toBe(422); // after the end
    const e = await create(org.token, { registrationDeadline: inHours(26) });
    await pool.query("update events set registration_deadline = now() - interval '1 minute' where id = $1", [e.id]);
    const res = await call('POST', `${V1}/events/${e.id}/register`, s1.token);
    expect(res.status).toBe(409);
    expect(res.body.error?.code ?? res.body.code).toBe('registration_closed');
    expect((await call('GET', `${V1}/events/${e.id}`, s1.token)).body.registration).toBe('closed');
  });

  it('v2: drafts are private to the organizer, publish is idempotent, and a retried create returns the same event', async () => {
    const org = await h.student();
    const s1 = await h.student();
    const d = await create(org.token, { status: 'draft' });
    expect(d.status).toBe('draft');
    expect((await call('GET', `${V1}/events/${d.id}`, s1.token)).status).toBe(404);
    expect((await call('POST', `${V1}/events/${d.id}/register`, s1.token)).status).toBe(404);
    const list = await call('GET', `${V1}/events?limit=50`, org.token);
    expect(list.body.items.some((i: { id: string }) => i.id === d.id)).toBe(false);
    expect((await call('POST', `${V1}/events/${d.id}/publish`, s1.token)).status).toBe(404);
    expect((await call('POST', `${V1}/events/${d.id}/publish`, org.token)).body.status).toBe('published');
    expect((await call('POST', `${V1}/events/${d.id}/publish`, org.token)).status).toBe(200);
    expect((await call('GET', `${V1}/events/${d.id}`, s1.token)).status).toBe(200);
    const key = `key-${Date.now()}-abcdef`;
    const a = await call('POST', `${V1}/events`, org.token, body({ createKey: key }));
    const b = await call('POST', `${V1}/events`, org.token, body({ createKey: key }));
    expect([a.status, b.status]).toEqual([201, 200]);
    expect(b.body.id).toBe(a.body.id);
    const count = await pool.query('select count(*)::int as n from events where organizer_id = $1 and create_key = $2', [org.id, key]);
    expect(count.rows[0].n).toBe(1);
  });

  it('v2: only community owners and moderators host community events; private community events stay inside it', async () => {
    const owner = await h.student();
    const member = await h.student();
    const outsider = await h.student();
    const c = await makeCommunity(owner.token);
    await join(c.id, member.id);
    expect((await call('POST', `${V1}/events`, member.token, body({ communityId: c.id }))).status).toBe(403);
    expect((await call('POST', `${V1}/events`, outsider.token, body({ communityId: c.id }))).status).toBe(403);
    expect((await call('POST', `${V1}/events`, owner.token, body({ visibility: 'community' }))).status).toBe(422); // no community
    const pub = await create(owner.token, { communityId: c.id, visibility: 'public' });
    expect(pub.communityName).toBeTruthy();

    const secret = await makeCommunity(owner.token, { visibility: 'private', membershipPolicy: 'invite_only' });
    await join(secret.id, member.id);
    const ev = await create(owner.token, { communityId: secret.id, visibility: 'public' }); // forced inside the community
    expect(ev.visibility).toBe('community');
    expect((await call('GET', `${V1}/events/${ev.id}`, member.token)).status).toBe(200);
    expect((await call('GET', `${V1}/events/${ev.id}`, outsider.token)).status).toBe(404);
    expect((await call('POST', `${V1}/events/${ev.id}/register`, outsider.token)).status).toBe(404);
    const listed = await call('GET', `${V1}/events?limit=50`, outsider.token);
    expect(listed.body.items.some((i: { id: string }) => i.id === ev.id)).toBe(false);
    expect((await call('GET', `${V1}/search/events?q=Hack`, outsider.token)).body.items?.some?.((i: { id: string }) => i.id === ev.id) ?? false).toBe(false);
    // Removing the member removes access, including for events they registered for.
    expect((await call('POST', `${V1}/events/${ev.id}/register`, member.token)).status).toBe(201);
    await pool.query("update community_members set status = 'suspended' where community_id = $1 and user_id = $2", [secret.id, member.id]);
    expect((await call('GET', `${V1}/events/${ev.id}`, member.token)).status).toBe(404);
    const mine = await call('GET', `${V1}/me/events`, member.token);
    expect(mine.body.items.some((i: { id: string }) => i.id === ev.id)).toBe(false);
    // The visibility of a private community's event cannot be widened later.
    expect((await call('PATCH', `${V1}/events/${ev.id}`, owner.token, { visibility: 'public' })).status).toBe(422);
  });

  it('v2: complete needs a started event, completed events count as past, and organizer changes are audited', async () => {
    const org = await h.student();
    const e = await create(org.token);
    expect((await call('POST', `${V1}/events/${e.id}/complete`, org.token)).status).toBe(409); // not started
    await pool.query("update events set starts_at = now() - interval '10 minutes' where id = $1", [e.id]);
    expect((await call('POST', `${V1}/events/${e.id}/complete`, org.token)).status).toBe(204);
    const got = (await call('GET', `${V1}/events/${e.id}`, org.token)).body;
    expect(got.status).toBe('completed');
    expect(got.phase).toBe('ended');
    expect(got.registration).toBe('ended');
    expect((await call('PATCH', `${V1}/events/${e.id}`, org.token, { title: 'Renamed' }))).toMatchObject({ status: 409 });
    const past = await call('GET', `${V1}/events?scope=past&limit=50`, org.token);
    expect(past.body.items.some((i: { id: string }) => i.id === e.id)).toBe(true);
    const log = await pool.query('select action from event_audit where event_id = $1 order by id', [e.id]);
    expect(log.rows.map((r: { action: string }) => r.action)).toEqual(['create', 'publish', 'complete']);
  });

  it('v2: categories come from the table; unknown ones are refused and new ones work without a deploy', async () => {
    const org = await h.student();
    expect((await call('POST', `${V1}/events`, org.token, body({ category: 'not_a_category' }))).status).toBe(422);
    const cats = await call('GET', `${V1}/event-categories`, org.token);
    expect(cats.body.items.map((c: { code: string }) => c.code)).toEqual(expect.arrayContaining(['academic', 'career', 'study_groups', 'announcements']));
    await pool.query("insert into event_categories (code, label, sort_order) values ('robotics', 'Robotics', 150) on conflict do nothing");
    await create(org.token, { category: 'robotics' });
  });

  it('v2: wording edits do not notify registrants, time and venue changes do', async () => {
    const org = await h.student();
    const s1 = await h.student();
    const e = await create(org.token);
    await call('POST', `${V1}/events/${e.id}/register`, s1.token);
    await call('PATCH', `${V1}/events/${e.id}`, org.token, { description: 'A longer description.' });
    const n = () => pool.query("select count(*)::int as n from notifications where recipient_id = $1 and type = 'event_update' and entity_id = $2", [s1.id, e.id]);
    expect((await n()).rows[0].n).toBe(0);
    await call('PATCH', `${V1}/events/${e.id}`, org.token, { venueName: 'Main Hall' });
    expect((await n()).rows[0].n).toBe(1);
  });

  // ---- Events v2, stage 2: interested and saved ----
  it('v2: interested never takes a seat, going replaces it, and a response can always be withdrawn', async () => {
    const org = await h.student();
    const a = await h.student();
    const b = await h.student();
    const e = await create(org.token, { capacity: 1 });
    const rsvp = (token: string, state: string) => call('PUT', `${V1}/events/${e.id}/rsvp`, token, { state });
    expect((await rsvp(a.token, 'interested')).body).toMatchObject({ myRsvp: 'interested', interestedCount: 1, registeredCount: 0, spotsLeft: 1 });
    expect((await rsvp(a.token, 'interested')).body.interestedCount).toBe(1); // repeating changes nothing
    expect((await rsvp(b.token, 'going')).body).toMatchObject({ myRsvp: 'going', spotsLeft: 0 });
    // Interested did not reserve the only seat, and the full event now refuses A.
    const full = await rsvp(a.token, 'going');
    expect(full.status).toBe(409);
    expect((await call('GET', `${V1}/events/${e.id}`, a.token)).body.myRsvp).toBe('interested'); // a refused change leaves the old response
    // B swaps going for interested: the seat is released and A can take it.
    expect((await rsvp(b.token, 'interested')).body).toMatchObject({ myRsvp: 'interested', registeredCount: 0 });
    const took = (await rsvp(a.token, 'going')).body;
    expect(took).toMatchObject({ myRsvp: 'going', registeredCount: 1, interestedCount: 1 });
    expect((await rsvp(a.token, 'none')).body).toMatchObject({ myRsvp: null, registeredCount: 0 });
    const rows = await pool.query('select (select count(*)::int from event_interest where event_id = $1 and user_id = $2) as i, (select count(*)::int from event_registrations where event_id = $1 and user_id = $2 and status = \'registered\') as r', [e.id, a.id]);
    expect(rows.rows[0]).toEqual({ i: 0, r: 0 });
    expect((await rsvp(a.token, 'maybe')).status).toBe(400);
  });

  it('v2: interested is refused on cancelled or ended events, but withdrawing still works', async () => {
    const org = await h.student();
    const s1 = await h.student();
    const e = await create(org.token);
    await call('PUT', `${V1}/events/${e.id}/rsvp`, s1.token, { state: 'interested' });
    await call('POST', `${V1}/events/${e.id}/cancel`, org.token);
    expect((await call('PUT', `${V1}/events/${e.id}/rsvp`, s1.token, { state: 'interested' })).status).toBe(409);
    expect((await call('PUT', `${V1}/events/${e.id}/rsvp`, s1.token, { state: 'none' })).body.myRsvp).toBeNull();
  });

  it('v2: concurrent going and interested changes never leave someone in both states or overbook', async () => {
    const org = await h.student();
    const people = await Promise.all([h.student(), h.student(), h.student(), h.student()]);
    const e = await create(org.token, { capacity: 2 });
    await Promise.all(people.flatMap((p, i) => [
      call('PUT', `${V1}/events/${e.id}/rsvp`, p.token, { state: i % 2 ? 'interested' : 'going' }),
      call('PUT', `${V1}/events/${e.id}/rsvp`, p.token, { state: 'going' }),
    ]));
    const reg = await pool.query("select count(*)::int as n from event_registrations where event_id = $1 and status = 'registered'", [e.id]);
    expect(reg.rows[0].n).toBeLessThanOrEqual(2);
    const both = await pool.query("select count(*)::int as n from event_interest i join event_registrations r on r.event_id = i.event_id and r.user_id = i.user_id and r.status = 'registered' where i.event_id = $1", [e.id]);
    expect(both.rows[0].n).toBe(0);
  });

  it('v2: saved events are private, idempotent, paged newest first, and disappear when access is lost', async () => {
    const org = await h.student();
    const s1 = await h.student();
    const other = await h.student();
    const e1 = await create(org.token, { title: 'First saved' });
    const e2 = await create(org.token, { title: 'Second saved' });
    const e3 = await create(org.token, { title: 'Third saved' });
    for (const e of [e1, e2, e3]) {
      expect((await call('PUT', `${V1}/events/${e.id}/save`, s1.token)).status).toBe(204);
      expect((await call('PUT', `${V1}/events/${e.id}/save`, s1.token)).status).toBe(204); // repeat: still one row
    }
    expect((await pool.query('select count(*)::int as n from saved_events where user_id = $1', [s1.id])).rows[0].n).toBe(3);
    expect((await call('GET', `${V1}/events/${e1.id}`, s1.token)).body.saved).toBe(true);
    expect((await call('GET', `${V1}/events/${e1.id}`, other.token)).body.saved).toBe(false);
    const p1 = await call('GET', `${V1}/me/saved-events?limit=2`, s1.token);
    expect(p1.body.items.map((i: { id: string }) => i.id)).toEqual([e3.id, e2.id]);
    const p2 = await call('GET', `${V1}/me/saved-events?limit=2&cursor=${p1.body.nextCursor}`, s1.token);
    expect(p2.body.items.map((i: { id: string }) => i.id)).toEqual([e1.id]);
    expect(p2.body.nextCursor).toBeNull();
    expect((await call('GET', `${V1}/me/saved-events`, other.token)).body.items).toEqual([]);
    expect((await call('DELETE', `${V1}/events/${e2.id}/save`, s1.token)).status).toBe(204);
    expect((await call('GET', `${V1}/me/saved-events`, s1.token)).body.items.map((i: { id: string }) => i.id)).toEqual([e3.id, e1.id]);
    // A draft cannot be saved by someone else, and losing access hides a saved event.
    const draft = await create(org.token, { status: 'draft' });
    expect((await call('PUT', `${V1}/events/${draft.id}/save`, s1.token)).status).toBe(404);
    const owner = await h.student();
    const secret = await makeCommunity(owner.token, { visibility: 'private', membershipPolicy: 'invite_only' });
    await join(secret.id, s1.id);
    const hidden = await create(owner.token, { communityId: secret.id });
    expect((await call('PUT', `${V1}/events/${hidden.id}/save`, s1.token)).status).toBe(204);
    await pool.query("update community_members set status = 'suspended' where community_id = $1 and user_id = $2", [secret.id, s1.id]);
    expect((await call('GET', `${V1}/me/saved-events`, s1.token)).body.items.some((i: { id: string }) => i.id === hidden.id)).toBe(false);
  });

  it('requires authentication', async () => {
    for (const [m, p] of [['GET', '/events'], ['POST', '/events'], ['GET', '/me/events']] as const) expect((await call(m, `${V1}${p}`)).status).toBe(401);
  });
});
