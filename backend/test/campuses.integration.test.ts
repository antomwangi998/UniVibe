import type { Pool } from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, PASSWORD, url } from './helpers';

// Written for CI (needs Postgres). NOT yet run: the authoring sandbox had no database.
describe.skipIf(!url)('multi-campus universities (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const email = (tag: string) => `${tag}${Date.now()}${Math.random().toString(36).slice(2, 7)}@campus.example`;

  beforeAll(async () => {
    pool = createPool(url as string);
    await migrate(pool);
    await pool.query("insert into universities (id, name, email_domains) values ('campus-test', 'Campus Test University', array['campus.example']) on conflict do nothing");
    await pool.query("insert into campuses (id, university_id, name, sort_order) values ('campus-test-a', 'campus-test', 'Alpha Campus', 1), ('campus-test-b', 'campus-test', 'Beta Campus', 2) on conflict do nothing");
    h.make();
    await h.app().ready();
  });
  afterAll(async () => { await h.close(); await pool.end(); });

  it('lists campuses with each university, and Kenyatta University is seeded with its student email domain', async () => {
    const res = await call('GET', '/universities');
    expect(res.status).toBe(200);
    const test = res.body.find((u: { id: string }) => u.id === 'campus-test');
    expect(test.campuses.map((c: { id: string }) => c.id)).toEqual(['campus-test-a', 'campus-test-b']);
    const ku = res.body.find((u: { id: string }) => u.id === 'ku');
    expect(ku.emailDomains).toEqual(['students.ku.ac.ke']);
    expect(ku.campuses.length).toBeGreaterThan(1);
    const lakeside = res.body.find((u: { id: string }) => u.id === 'lakeside');
    expect(lakeside?.campuses ?? []).toEqual([]);
  });

  it('requires a campus of the same university when the university has campuses', async () => {
    const base = { fullName: 'Test Student', universityId: 'campus-test', password: PASSWORD, acceptedTerms: true };
    expect((await call('POST', '/auth/register', undefined, { ...base, email: email('a') })).status).toBe(400);
    expect((await call('POST', '/auth/register', undefined, { ...base, email: email('b'), campusId: 'ku-main' })).status).toBe(400);
    expect((await call('POST', '/auth/register', undefined, { ...base, email: email('c'), campusId: 'campus-test-a' })).status).toBe(202);
  });

  it('ignores campuses for a university that has none, and shows the campus on the profile', async () => {
    const plain = await h.student();
    expect((await call('GET', '/me', plain.token)).body.campusId).toBeNull();
    const s = await h.student('campus.example', 'campus-test', 'campus-test-b');
    const me = await call('GET', '/me', s.token);
    expect(me.body.campusId).toBe('campus-test-b');
    expect(me.body.campusName).toBe('Beta Campus');
  });

  it('lets a student switch campus, but only to one of their own university', async () => {
    const s = await h.student('campus.example', 'campus-test', 'campus-test-a');
    const moved = await call('PUT', '/me/campus', s.token, { campusId: 'campus-test-b' });
    expect(moved.status, JSON.stringify(moved.body)).toBe(200);
    expect(moved.body.campusName).toBe('Beta Campus');
    expect((await call('PUT', '/me/campus', s.token, { campusId: 'ku-main' })).status).toBe(400);
    expect((await call('PUT', '/me/campus', s.token, {})).status).toBe(400);
    expect((await call('PUT', '/me/campus', undefined, { campusId: 'campus-test-a' })).status).toBe(401);
    const plain = await h.student();
    expect((await call('PUT', '/me/campus', plain.token, { campusId: 'campus-test-a' })).status).toBe(400);
  });

  it('tags events with a campus, defaults to the organizer\'s campus, and filters Discover by my campus', async () => {
    const V1 = '/api/v1';
    const inHours = (n: number) => new Date(Date.now() + n * 3600_000).toISOString();
    const onboard = async (s: { token: string }) => { expect((await call('PUT', '/me/profile', s.token, { course: 'Law', interests: ['chess'], bio: '' })).status).toBe(200); };
    const a = await h.student('campus.example', 'campus-test', 'campus-test-a');
    const b = await h.student('campus.example', 'campus-test', 'campus-test-b');
    await onboard(a); await onboard(b);
    const make = (token: string, title: string, over: Record<string, unknown> = {}) =>
      call('POST', `${V1}/events`, token, { title, description: 'Campus event', category: 'technology', venueName: 'Hall', startsAt: inHours(24), endsAt: inHours(27), ...over });

    const own = await make(a.token, 'Alpha own campus');
    expect(own.status, JSON.stringify(own.body)).toBe(201);
    expect(own.body.campusId).toBe('campus-test-a');
    expect(own.body.campusName).toBe('Alpha Campus');
    const all = await make(a.token, 'Alpha for everyone', { campusId: null });
    expect(all.status, JSON.stringify(all.body)).toBe(201);
    expect(all.body.campusId).toBeNull();
    expect((await make(a.token, 'Wrong university campus', { campusId: 'ku-main' })).status).toBe(422);

    const ids = async (query: string) => (await call('GET', `${V1}/discover/events?${query}`, b.token)).body.items.map((e: { id: string }) => e.id);
    const everything = await ids('limit=30');
    expect(everything).toEqual(expect.arrayContaining([own.body.id, all.body.id]));
    const mine = await ids('limit=30&myCampus=true');
    expect(mine).toContain(all.body.id);
    expect(mine).not.toContain(own.body.id);

    const moved = await call('PATCH', `${V1}/events/${own.body.id}`, a.token, { campusId: 'campus-test-b' });
    expect(moved.status, JSON.stringify(moved.body)).toBe(200);
    expect(await ids('limit=30&myCampus=true')).toContain(own.body.id);
  });

  it('puts the author\'s university code on a post, for the badge next to the name', async () => {
    const s = await h.student('campus.example', 'campus-test', 'campus-test-a');
    const post = await call('POST', '/posts', s.token, { text: 'hello campus', audience: 'campus' });
    expect(post.status, JSON.stringify(post.body)).toBe(201);
    expect(post.body.author.universityCode).toBe('campus-test');
  });
});
