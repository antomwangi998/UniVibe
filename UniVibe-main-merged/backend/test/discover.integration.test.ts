import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, url } from './helpers';

describe.skipIf(!url)('discover (needs TEST_DATABASE_URL)', () => {
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

  // Each test uses its own course label so results from other tests in the shared database never interfere.
  let n = 0;
  const label = () => `Course-${Date.now()}-${n++}`;
  const onboarded = (course: string, interests: string[] = ['chess']) => h.onboarded(course, interests);
  const ids = async (token: string, course: string) =>
    (await call('GET', `${V1}/discover?course=${encodeURIComponent(course)}&limit=50`, token)).body.items.map((i: { id: string }) => i.id);

  it('scenario B: eligibility, interaction, privacy, opt-out', async () => {
    const course = label();
    const a = await onboarded(course);
    const b = await onboarded(course);
    // 1-2: discoverable by default and eligible
    expect(await ids(a.token, course)).toEqual([b.id]);
    // never shows the requester themselves
    expect(await ids(b.token, course)).toEqual([a.id]);
    // 3-4: a connect is recorded and becomes an ordinary follow
    const res = await call('POST', `${V1}/discover/${b.id}/like`, a.token);
    expect(res.body.status).toBe('accepted');
    expect(await ids(a.token, course)).toEqual([]); // already processed
    // repeated requests change nothing
    expect((await call('POST', `${V1}/discover/${b.id}/like`, a.token)).status).toBe(200);
    expect(Number((await pool.query('select count(*) from discover_actions where user_id = $1', [a.id])).rows[0].count)).toBe(1);
    expect(Number((await pool.query('select count(*) from follows where follower_id = $1 and followee_id = $2', [a.id, b.id])).rows[0].count)).toBe(1);
    // 5: the target sees a normal follow notification and no trace of Discover
    const notes = (await call('GET', `${V1}/notifications`, b.token)).body.items;
    expect(notes).toHaveLength(1);
    expect(notes[0].type).toBe('follow');
    expect(JSON.stringify(notes)).not.toMatch(/discover/i);
    // passes are invisible to the person passed on and to everyone else
    const c = await onboarded(course);
    await call('POST', `${V1}/discover/${c.id}/pass`, a.token);
    expect((await call('POST', `${V1}/discover/${c.id}/pass`, a.token)).status).toBe(204);
    expect(await ids(a.token, course)).toEqual([]);
    expect((await call('GET', `${V1}/notifications`, c.token)).body.items).toHaveLength(0);
    // 6: opting out removes the profile from everyone else's results
    const d = await onboarded(course);
    expect(await ids(d.token, course)).toContain(c.id);
    await call('PUT', `${V1}/me/privacy`, c.token, { discoverable: false });
    expect(await ids(d.token, course)).not.toContain(c.id);
    // and cannot be acted on directly either
    expect((await call('POST', `${V1}/discover/${c.id}/like`, d.token)).status).toBe(404);
  });

  it('excludes blocked, suspended, other-university and not-onboarded students', async () => {
    const course = label();
    const a = await onboarded(course);
    const blocked = await onboarded(course);
    const blocker = await onboarded(course);
    const suspended = await onboarded(course);
    const fresh = await h.student(); // never completed onboarding
    const outsider = await h.student('other.example', 'other');
    await call('PUT', '/me/profile', outsider.token, { course, interests: ['chess'], bio: '' });
    await call('POST', `${V1}/users/${blocked.id}/block`, a.token);
    await call('POST', `${V1}/users/${a.id}/block`, blocker.token);
    await pool.query("update users set suspended_until = now() + interval '1 day' where id = $1", [suspended.id]);
    expect(await ids(a.token, course)).toEqual([]);
    expect([fresh, outsider].length).toBe(2);
    expect((await call('POST', `${V1}/discover/${blocked.id}/like`, a.token)).status).toBe(404);
    expect((await call('POST', `${V1}/discover/${blocker.id}/like`, a.token)).status).toBe(404);
    expect((await call('POST', `${V1}/discover/${outsider.id}/pass`, a.token)).status).toBe(404);
    expect((await call('POST', `${V1}/discover/${a.id}/pass`, a.token)).status).toBe(404);
  });

  it('filters and saved preferences work', async () => {
    const course = label();
    const a = await onboarded(course, ['chess', 'music']);
    await onboarded(course, ['music']);
    const c = await onboarded(course, ['music', 'art']);
    const byInterest = (await call('GET', `${V1}/discover?course=${encodeURIComponent(course)}&interest=art`, a.token)).body.items;
    expect(byInterest.map((i: { id: string }) => i.id)).toEqual([c.id]);
    expect(byInterest[0].sharedInterests).toBe(1);
    expect((await call('PUT', `${V1}/me/discover/preferences`, a.token, { courses: ['no-such-course'] })).status).toBe(200);
    expect((await call('GET', `${V1}/discover`, a.token)).body.items).toEqual([]);
    expect((await call('GET', `${V1}/me/discover/preferences`, a.token)).body.courses).toEqual(['no-such-course']);
    expect((await call('PUT', `${V1}/me/discover/preferences`, a.token, { role: 'x' })).status).toBe(400);
  });

  it('paginates and requires auth', async () => {
    const course = label();
    const a = await onboarded(course);
    for (let i = 0; i < 5; i++) await onboarded(course);
    const seen = new Set<string>();
    let cursor: string | null = null;
    do {
      const res: { body: { items: { id: string }[]; nextCursor: string | null } } =
        await call('GET', `${V1}/discover?course=${encodeURIComponent(course)}&limit=2${cursor ? `&cursor=${cursor}` : ''}`, a.token);
      res.body.items.forEach((i) => seen.add(i.id));
      cursor = res.body.nextCursor;
    } while (cursor);
    expect(seen.size).toBe(5);
    expect((await call('GET', `${V1}/discover`)).status).toBe(401);
  });
});
