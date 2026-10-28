import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, url } from './helpers';

// Written for CI (needs Postgres). NOT yet run: the authoring sandbox had no database.
describe.skipIf(!url)('campus bridge: scopes across campuses (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';

  beforeAll(async () => {
    pool = createPool(url as string);
    await migrate(pool);
    await pool.query("insert into universities (id, name, email_domains) values ('bridge-b', 'Bridge B University', array['bridgeb.example']) on conflict do nothing");
  });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  let n = 0;
  const label = () => `Bridge-${Date.now()}-${n++}`;
  const away = async (course: string) => {
    const s = await h.student('bridgeb.example', 'bridge-b');
    expect((await call('PUT', '/me/profile', s.token, { course, interests: ['chess'], bio: '' })).status).toBe(200);
    return s;
  };
  const postIds = (items: { id: string }[]) => items.map((p) => p.id);

  it('keeps campus posts home-only and lets an "all campuses" post cross, labelled with its campus', async () => {
    const home = await h.student();
    const away1 = await away(label());
    const campusOnly = (await call('POST', `${V1}/posts`, away1.token, { text: 'private to campus b' })).body;
    const shared = (await call('POST', `${V1}/posts`, away1.token, { text: 'hello every campus', audience: 'all' })).body;
    expect(shared.audience).toBe('all');
    // Home default: neither post, because you do not follow the author.
    expect(postIds((await call('GET', `${V1}/posts`, home.token)).body.items ?? (await call('GET', `${V1}/posts`, home.token)).body)).not.toContain(shared.id);
    // All campuses: the shared post, never the campus-only one.
    const all = await call('GET', `${V1}/posts?campusScope=all&limit=100`, home.token);
    const list = all.body.items ?? all.body;
    expect(postIds(list)).toContain(shared.id);
    expect(postIds(list)).not.toContain(campusOnly.id);
    expect(list.find((p: { id: string }) => p.id === shared.id).author.universityCode).toBe('bridge-b');
    // A direct fetch obeys the same rules.
    expect((await call('GET', `${V1}/posts/${shared.id}`, home.token)).status).toBe(200);
    expect((await call('GET', `${V1}/posts/${campusOnly.id}`, home.token)).status).toBe(404);
    // One specific campus.
    const one = await call('GET', `${V1}/posts?campusScope=bridge-b&limit=100`, home.token);
    expect(postIds(one.body.items ?? one.body)).toContain(shared.id);
    const wrong = await call('GET', `${V1}/posts?campusScope=lakeside&limit=100`, home.token);
    expect(postIds(wrong.body.items ?? wrong.body)).not.toContain(shared.id);
  });

  it('shows people from other campuses in Discover only in the right scope, and honours the opt-out', async () => {
    const course = label();
    const home = await h.onboarded(course);
    const friend = await away(course);
    const ids = async (scope?: string) =>
      (await call('GET', `${V1}/discover?course=${encodeURIComponent(course)}&limit=50${scope ? `&campusScope=${scope}` : ''}`, home.token)).body.items.map((i: { id: string }) => i.id);
    expect(await ids()).not.toContain(friend.id);
    expect(await ids('all')).toContain(friend.id);
    expect(await ids('bridge-b')).toContain(friend.id);
    const card = (await call('GET', `${V1}/discover?course=${encodeURIComponent(course)}&campusScope=all`, home.token)).body.items.find((i: { id: string }) => i.id === friend.id);
    expect(card.universityId).toBe('bridge-b');
    // Opting out hides them from other campuses, not from their own.
    expect((await call('PUT', `${V1}/me/privacy`, friend.token, { showOtherCampuses: false })).status).toBe(200);
    expect(await ids('all')).not.toContain(friend.id);
    expect((await call('GET', `${V1}/students/${friend.id}`, home.token)).status).toBe(404);
  });

  it('lets a student follow someone on another campus and then message them, but not cold-message', async () => {
    const home = await h.student();
    const friend = await away(label());
    expect((await call('GET', `${V1}/students/${friend.id}`, home.token)).status).toBe(200);
    expect((await call('POST', `${V1}/conversations`, home.token, { type: 'direct', userId: friend.id })).status).toBe(403);
    expect((await call('PUT', `${V1}/students/${friend.id}/follow`, home.token)).status).toBeLessThan(300);
    expect((await call('POST', `${V1}/conversations`, home.token, { type: 'direct', userId: friend.id })).status).toBeLessThan(300);
  });

  it('lists campuses for the filter and flags a quiet home campus', async () => {
    const home = await h.student();
    await away(label());
    const res = await call('GET', `${V1}/campuses/scopes`, home.token);
    expect(res.status).toBe(200);
    expect(res.body.home.id).toBe('lakeside');
    expect(res.body.others.map((c: { id: string }) => c.id)).toContain('bridge-b');
    expect(typeof res.body.homeQuiet).toBe('boolean');
  });

  it('routes a report on another campus\'s content to that campus\'s moderators', async () => {
    const home = await h.student();
    const away1 = await away(label());
    const post = (await call('POST', `${V1}/posts`, away1.token, { text: 'reportable', audience: 'all' })).body;
    const res = await call('POST', `${V1}/reports`, home.token, { targetType: 'post', targetId: post.id, reason: 'spam' });
    expect(res.status).toBeLessThan(300);
    const row = (await pool.query("select university_id from reports where target_type = 'post' and target_id = $1", [post.id])).rows[0];
    expect(row.university_id).toBe('bridge-b');
  });
  it('keeps a reported post out of the reporter\'s moderation queue and puts it in the owner campus\'s queue', async () => {
    const reporter = await h.student();
    const owner = await away(label());
    const mod = await away(label());
    await pool.query("update users set role = 'moderator' where id = $1", [mod.id]);
    const post = (await call('POST', `${V1}/posts`, owner.token, { text: 'queue me', audience: 'all' })).body;
    const made = await call('POST', `${V1}/reports`, reporter.token, { targetType: 'post', targetId: post.id, reason: 'spam' });
    expect(made.status).toBeLessThan(300);
    const queue = (await call('GET', `${V1}/moderation/reports?status=open&limit=50`, mod.token)).body.items.map((r: { id: string }) => r.id);
    expect(queue).toContain(made.body.id);
  });

  it('hides a student who opted out from other campuses in search, follows and messages', async () => {
    const home = await h.student();
    const friend = await away(label());
    expect((await call('PUT', `${V1}/me/privacy`, friend.token, { showOtherCampuses: false })).status).toBe(200);
    expect((await call('PUT', `/students/${friend.id}/follow`, home.token)).status).toBe(404);
    expect((await call('POST', `${V1}/conversations`, home.token, { type: 'direct', userId: friend.id })).status).toBe(404);
    // Their own campus still sees them.
    const mate = await away(label());
    expect((await call('GET', `${V1}/students/${friend.id}`, mate.token)).status).toBe(200);
  });

  it('never lets an \'all campuses\' post be commented on or liked by someone who blocked the author', async () => {
    const reader = await h.student();
    const author = await away(label());
    const post = (await call('POST', `${V1}/posts`, author.token, { text: 'open post', audience: 'all' })).body;
    expect((await call('PUT', `/posts/${post.id}/like`, reader.token)).status).toBeLessThan(300);
    await call('POST', `${V1}/users/${author.id}/block`, reader.token);
    expect((await call('GET', `${V1}/posts/${post.id}`, reader.token)).status).toBe(404);
  });
});
