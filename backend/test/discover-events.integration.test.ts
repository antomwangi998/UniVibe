import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, url } from './helpers';

describe.skipIf(!url)('event discovery (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';
  const D = `${V1}/discover/events`;
  let n = 0;
  const uniq = (p: string) => `${p}${Date.now().toString(36)}${n++}`;

  beforeAll(async () => { pool = createPool(url as string); await migrate(pool); });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  const inHours = (x: number) => new Date(Date.now() + x * 3600_000).toISOString();
  const create = async (token: string, over: Record<string, unknown> = {}) => {
    const r = await call('POST', `${V1}/events`, token, {
      title: uniq('Meetup '), description: 'Come along and meet people on campus tonight.', category: 'technology', venueName: 'Lab 3',
      startsAt: inHours(24), endsAt: inHours(27), ...over,
    });
    expect(r.status, JSON.stringify(r.body)).toBe(201);
    return r.body as { id: string; title: string };
  };
  const ids = (body: { items: Array<{ id: string }> }) => body.items.map((i) => i.id);
  const sectionsOf = async (token: string) => (await call('GET', `${D}/sections`, token)).body.sections as Array<{ key: string; title: string; items: Array<{ id: string; reason: string | null }> }>;
  const makeCommunity = async (token: string, over: Record<string, unknown> = {}) =>
    (await call('POST', `${V1}/communities`, token, { name: uniq('Disc club '), description: 'A community for event discovery tests.', category: 'clubs', ...over })).body as { id: string; name: string };

  it('sections never repeat an event, and each item says why it is shown', async () => {
    const viewer = await h.student();
    const host = await h.student();
    await call('PUT', `/students/${host.id}/follow`, viewer.token);
    const mk = await Promise.all([1, 2, 3].map(() => h.student()));
    const made = [await create(host.token), ...(await Promise.all(mk.map((s) => create(s.token))))];
    const sections = await sectionsOf(viewer.token);
    const all = sections.flatMap((s) => s.items.map((i) => i.id));
    expect(new Set(all).size).toBe(all.length); // no event twice
    for (const e of made) expect(all).toContain(e.id);
    const followed = sections.flatMap((s) => s.items).find((i) => i.id === made[0].id)!;
    expect(followed.reason).toBe('From someone you follow');
    expect(sections.every((s) => s.items.length > 0)).toBe(true);
  });

  it('events from joined communities come first, and private ones stay private', async () => {
    const owner = await h.student();
    const member = await h.student();
    const outsider = await h.student();
    const c = await makeCommunity(owner.token);
    await pool.query("insert into community_members (community_id, user_id, role) values ($1, $2, 'member')", [c.id, member.id]);
    const ev = await create(owner.token, { communityId: c.id, visibility: 'public' });
    const secret = await makeCommunity(owner.token, { visibility: 'private', membershipPolicy: 'invite_only' });
    await pool.query("insert into community_members (community_id, user_id, role) values ($1, $2, 'member')", [secret.id, member.id]);
    const hidden = await create(owner.token, { communityId: secret.id });
    const mine = await sectionsOf(member.token);
    expect(mine[0].key).toBe('communities');
    expect(mine[0].items.map((i) => i.id)).toEqual(expect.arrayContaining([ev.id, hidden.id]));
    expect(mine[0].items[0].reason).toBe('From a community you joined');
    const theirs = await sectionsOf(outsider.token);
    const seen = theirs.flatMap((s) => s.items.map((i) => i.id));
    expect(seen).toContain(ev.id);
    expect(seen).not.toContain(hidden.id);
    expect(ids((await call('GET', `${D}?limit=30`, outsider.token)).body)).not.toContain(hidden.id);
    // The community name is not revealed for the private one.
    const search = await call('GET', `${V1}/search/events?q=${encodeURIComponent(secret.name)}`, outsider.token);
    expect(ids(search.body)).not.toContain(hidden.id);
    expect(ids((await call('GET', `${V1}/search/events?q=${encodeURIComponent(secret.name)}`, member.token)).body)).toContain(hidden.id);
  });

  it('ranks by relevance, not by popularity alone', async () => {
    const viewer = await h.student();
    const friend = await h.student();
    const stranger = await h.student();
    const crowd = await Promise.all([1, 2, 3, 4].map(() => h.student()));
    await call('PUT', `/students/${friend.id}/follow`, viewer.token);
    const followed = await create(friend.token, { startsAt: inHours(30), endsAt: inHours(32) });
    const popular = await create(stranger.token, { startsAt: inHours(30), endsAt: inHours(32) });
    for (const c of crowd) await call('PUT', `${V1}/events/${popular.id}/rsvp`, c.token, { state: 'interested' });
    const list = ids((await call('GET', `${D}?limit=30`, viewer.token)).body);
    expect(list.indexOf(followed.id)).toBeGreaterThanOrEqual(0);
    expect(list.indexOf(followed.id)).toBeLessThan(list.indexOf(popular.id));
  });

  it('every filter is applied by the server', async () => {
    const viewer = await h.student();
    const [a, b, c, d, f] = await Promise.all([1, 2, 3, 4, 5].map(() => h.student()));
    const tech = await create(a.token, { category: 'technology', startsAt: inHours(24), endsAt: inHours(26) });
    const music = await create(b.token, { category: 'music', startsAt: inHours(100), endsAt: inHours(102) });
    const online = await create(c.token, { category: 'academic', eventType: 'online', onlineUrl: 'https://meet.example/x', venueName: undefined, startsAt: inHours(30), endsAt: inHours(32) });
    const small = await create(d.token, { capacity: 1, startsAt: inHours(40), endsAt: inHours(42) });
    const closed = await create(f.token, { startsAt: inHours(50), endsAt: inHours(52), registrationDeadline: inHours(49) });
    await call('POST', `${V1}/events/${small.id}/register`, a.token); // now full
    await pool.query("update events set registration_deadline = now() - interval '1 minute' where id = $1", [closed.id]);
    const get = async (qs: string) => ids((await call('GET', `${D}?limit=30&${qs}`, viewer.token)).body);
    expect(await get('category=music')).toEqual([music.id]);
    const mine = (e: { id: string; title: string }) => get(`category=music,technology&q=${encodeURIComponent(e.title)}`); // q keeps other tests' events out
    expect(await mine(tech)).toEqual([tech.id]);
    expect(await mine(music)).toEqual([music.id]);
    expect(await get('format=online')).toEqual([online.id]);
    const open = await get('registrationOpen=true');
    expect(open).toContain(tech.id);
    expect(open).not.toContain(small.id);
    expect(open).not.toContain(closed.id);
    const window = await get(`from=${encodeURIComponent(inHours(90))}&to=${encodeURIComponent(inHours(110))}`);
    expect(window).toEqual([music.id]);
    expect(await get('saved=true')).toEqual([]);
    await call('PUT', `${V1}/events/${music.id}/save`, viewer.token);
    expect(await get('saved=true')).toEqual([music.id]);
    expect(await get('category=nonsense_x')).toEqual([]);
    expect((await call('GET', `${D}?category=BAD CODE`, viewer.token)).status).toBe(400);
    expect((await call('GET', `${D}?format=hologram`, viewer.token)).status).toBe(400);
  });

  it('past events can be browsed newest first, and sort=time orders by start', async () => {
    const viewer = await h.student();
    const org = await h.student();
    const org2 = await h.student();
    const old = await create(org.token);
    await pool.query("update events set starts_at = now() - interval '5 hours', ends_at = now() - interval '4 hours' where id = $1", [old.id]);
    const soon = await create(org.token, { startsAt: inHours(10), endsAt: inHours(11) });
    const later = await create(org2.token, { startsAt: inHours(70), endsAt: inHours(71) });
    const pastRes = await call('GET', `${D}?scope=past&limit=30`, viewer.token);
    expect(pastRes.status, JSON.stringify(pastRes.body)).toBe(200);
    expect(ids(pastRes.body)).toContain(old.id);
    expect(ids((await call('GET', `${D}?limit=30`, viewer.token)).body)).not.toContain(old.id);
    const timed = ids((await call('GET', `${D}?sort=time&limit=50`, viewer.token)).body);
    expect(timed.indexOf(soon.id)).toBeLessThan(timed.indexOf(later.id));
  });

  it('pages without repeating events', async () => {
    const viewer = await h.student();
    const owners = await Promise.all([1, 2, 3, 4, 5].map(() => h.student()));
    const made = await Promise.all(owners.map((o) => create(o.token)));
    const seen: string[] = [];
    let cursor: string | null = null;
    for (let i = 0; i < 40; i++) {
      const res: { body: { items: Array<{ id: string }>; nextCursor: string | null } } = await call('GET', `${D}?limit=2${cursor ? `&cursor=${cursor}` : ''}`, viewer.token);
      seen.push(...ids(res.body));
      cursor = res.body.nextCursor;
      if (!cursor) break;
    }
    expect(new Set(seen).size).toBe(seen.length);
    for (const e of made) expect(seen).toContain(e.id);
    expect((await call('GET', `${D}?cursor=!!!`, viewer.token)).status).toBe(400);
  });

  it('not interested hides an event from Discover and Home recommendations only, and can be undone', async () => {
    const viewer = await h.student();
    const org = await h.student();
    await call('PUT', `/students/${org.id}/follow`, viewer.token);
    const e = await create(org.token);
    expect(ids((await call('GET', `${D}?limit=30`, viewer.token)).body)).toContain(e.id);
    expect((await call('PUT', `${D}/${e.id}/not-interested`, viewer.token)).status).toBe(204);
    expect((await call('PUT', `${D}/${e.id}/not-interested`, viewer.token)).status).toBe(204); // repeat is fine
    expect(ids((await call('GET', `${D}?limit=30`, viewer.token)).body)).not.toContain(e.id);
    expect((await sectionsOf(viewer.token)).flatMap((s) => s.items.map((i) => i.id))).not.toContain(e.id);
    const home = (await call('GET', '/posts?limit=50', viewer.token)).body as Array<{ eventId: string | null }>;
    expect(home.some((p) => p.eventId === e.id)).toBe(false);
    // Still reachable everywhere else.
    expect((await call('GET', `${V1}/events/${e.id}`, viewer.token)).status).toBe(200);
    expect(ids((await call('GET', `${V1}/events?limit=50`, viewer.token)).body)).toContain(e.id);
    expect((await call('DELETE', `${D}/${e.id}/not-interested`, viewer.token)).status).toBe(204);
    expect(ids((await call('GET', `${D}?limit=30`, viewer.token)).body)).toContain(e.id);
    // Not allowed for events the person cannot see.
    const draft = await create(org.token, { status: 'draft' });
    expect((await call('PUT', `${D}/${draft.id}/not-interested`, viewer.token)).status).toBe(404);
  });

  it('hiding an organizer removes all their events from Discover', async () => {
    const viewer = await h.student();
    const org = await h.student();
    const e = await create(org.token);
    expect((await call('POST', `${V1}/discover/feedback`, viewer.token, { kind: 'hide_author', authorId: org.id })).status).toBe(204);
    expect(ids((await call('GET', `${D}?limit=30`, viewer.token)).body)).not.toContain(e.id);
  });

  it('search finds events by organizer, category, description and community', async () => {
    const viewer = await h.student();
    const org = await h.student();
    const c = await makeCommunity(org.token);
    const word = `zebrafish${Date.now().toString(36)}`;
    const e = await create(org.token, { communityId: c.id, visibility: 'public', description: `A long enough description about ${word} for everyone to read.`, category: 'study_groups' });
    const find = async (q: string) => ids((await call('GET', `${V1}/search/events?q=${encodeURIComponent(q)}`, viewer.token)).body);
    expect(await find(word)).toContain(e.id);
    expect(await find('Study Groups')).toContain(e.id);
    expect(await find(c.name)).toContain(e.id);
    expect(await find(org.handle)).toContain(e.id);
    expect(ids((await call('GET', `${D}?q=${word}&limit=30`, viewer.token)).body)).toEqual([e.id]);
    expect(await find(`${word}nomatch`)).toEqual([]);
  });
});
