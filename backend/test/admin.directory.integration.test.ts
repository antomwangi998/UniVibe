import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, url } from './helpers';

describe.skipIf(!url)('admin communities, content and events (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';
  beforeAll(async () => { pool = createPool(url as string); await migrate(pool); });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  const asRole = async (role: 'admin' | 'moderator') => { const u = await h.student(); await pool.query('update users set role = $2 where id = $1', [u.id, role]); return u; };
  const uni = async (id: string) => (await pool.query('select university_id as u from users where id = $1', [id])).rows[0].u as string;
  let seq = 0;
  const tag = Date.now().toString(36); // keeps each run's search words unique, so reruns on the same database stay clean
  const community = async (owner: { id: string }, o: { visibility?: string; status?: string; name?: string } = {}) => {
    const n = `${o.name ?? 'Club'} ${Date.now()}${seq++}`;
    const vis = o.visibility ?? 'public';
    const c = (await pool.query(
      `insert into communities (university_id, name, name_key, slug, description, category, visibility, membership_policy, owner_id, status, member_count)
       values ($1, $2, lower($2), $3, 'A place for testing things.', 'clubs', $4, $5, $6, $7, 1) returning id`,
      [await uni(owner.id), n, `c-${Date.now()}-${seq}`, vis, vis === 'public' ? 'open' : 'approval', owner.id, o.status ?? 'active'])).rows[0].id as string;
    await pool.query("insert into community_members (community_id, user_id, role, status) values ($1, $2, 'owner', 'active')", [c, owner.id]);
    return { id: c, name: n };
  };
  const event = async (organizer: { id: string }, o: { title?: string; starts?: string; ends?: string; status?: string; hidden?: boolean; type?: string; community?: string; visibility?: string } = {}) =>
    (await pool.query(
      `insert into events (organizer_id, university_id, title, category, venue_name, starts_at, ends_at, status, event_type, online_url, community_id, visibility, hidden_at)
       values ($1, $2, $3, 'social', 'Main hall', ${o.starts ?? "now() + interval '2 days'"}, ${o.ends ?? "now() + interval '2 days 2 hours'"}, $4, $5, $6, $7, $8, ${o.hidden ? 'now()' : 'null'}) returning id`,
      [organizer.id, await uni(organizer.id), o.title ?? `Event ${seq++}`, o.status ?? 'published', o.type ?? 'in_person', o.type === 'online' ? 'https://example.com/join' : null, o.community ?? null, o.visibility ?? 'university'])).rows[0].id as string;
  const report = async (reporter: { id: string }, type: string, target: string) =>
    (await pool.query("insert into reports (reporter_id, university_id, target_type, target_id, reason) select $1, university_id, $2, $3, 'spam' from users where id = $1 returning id", [reporter.id, type, target])).rows[0].id as string;
  const get = async (path: string, token: string) => call('GET', `${V1}${path}`, token);

  it('is invisible to everyone but administrators', async () => {
    const student = await h.student(); const mod = await asRole('moderator');
    for (const t of [student.token, mod.token]) {
      for (const path of ['/admin/communities', '/admin/events', '/admin/content', '/admin/communities/00000000-0000-4000-8000-000000000000']) expect((await get(path, t)).status, path).toBe(404);
      expect((await call('POST', `${V1}/admin/content/post/00000000-0000-4000-8000-000000000000/remove`, t, { note: 'spam' })).status).toBe(404);
    }
    const admin = await asRole('admin');
    for (const path of ['/admin/communities', '/admin/events', '/admin/content', '/admin/content?type=comment']) expect((await get(path, admin.token)).status, path).toBe(200);
  });

  it('communities: filter, sort and page on the server; details show who runs it, never the member list', async () => {
    const admin = await asRole('admin'); const o1 = await h.student(); const o2 = await h.student();
    const a = await community(o1, { name: `Chess${tag}` }); const b = await community(o2, { visibility: 'private', name: 'Secret' });
    const m1 = await h.student(); const m2 = await h.student();
    for (const m of [m1, m2]) await pool.query("insert into community_members (community_id, user_id, role, status) values ($1, $2, 'member', 'active')", [b.id, m.id]);
    const list = async (qs: string) => { const r = await get(`/admin/communities${qs}`, admin.token); expect(r.status, JSON.stringify(r.body)).toBe(200); return r.body; };
    const ids = (r: { items: { id: string }[] }) => r.items.map((x) => x.id);
    expect(ids(await list('?limit=50'))).toEqual(expect.arrayContaining([a.id, b.id]));
    expect(ids(await list('?visibility=private&limit=50'))).toContain(b.id);
    expect(ids(await list('?visibility=private&limit=50'))).not.toContain(a.id);
    expect(ids(await list(`?q=chess${tag}`))).toEqual([a.id]);
    const p1 = await list('?limit=1&page=1'); const p2 = await list('?limit=1&page=2');
    expect(p1.items).toHaveLength(1); expect(ids(p2)).not.toContain(p1.items[0].id); expect(p1.pages).toBe(p1.total);
    const names = (await list('?sort=name&limit=50')).items.map((c: { name: string }) => c.name.toLowerCase()); expect(names).toEqual([...names].sort());
    for (const bad of ['?status=gone', '?sort=best', '?q=a', '?page=0', '?evil=1']) expect((await get(`/admin/communities${bad}`, admin.token)).status, bad).toBe(400);

    const d = await get(`/admin/communities/${b.id}`, admin.token);
    expect(d.status, JSON.stringify(d.body)).toBe(200);
    expect(d.body.staff).toHaveLength(1); expect(d.body.staff[0]).toMatchObject({ id: o2.id, role: 'owner' });
    expect(JSON.stringify(d.body.memberCounts)).toContain('"n":2');
    expect(JSON.stringify(d.body)).not.toContain(m1.id);          // ordinary members are counted, not listed
    expect(d.body.activity).toEqual(expect.objectContaining({ posts: 0, channels: expect.any(Number), upcomingEvents: 0 }));
  });

  it('suspending a community needs a reason, settles its reports, tells the owner, and restoring brings back its earlier state', async () => {
    const admin = await asRole('admin'); const mod = await asRole('moderator'); const owner = await h.student(); const reporter = await h.student();
    const c = await community(owner, { status: 'archived' });
    const rid = await report(reporter, 'community', c.id);
    const url = (what: string) => `${V1}/admin/communities/${c.id}/${what}`;
    expect((await call('POST', url('suspend'), mod.token, { note: 'spam ring' })).status).toBe(404);
    expect((await call('POST', url('suspend'), admin.token, {})).status).toBe(400);
    expect((await call('POST', url('restore'), admin.token, { note: 'oops' })).status).toBe(409);
    expect((await call('POST', url('suspend'), admin.token, { note: 'spam ring', userMessage: 'Your community broke the spam rules.' })).status).toBe(200);
    expect((await call('POST', url('suspend'), admin.token, { note: 'again' })).status).toBe(409);
    expect((await pool.query('select status from communities where id = $1', [c.id])).rows[0].status).toBe('suspended');
    expect((await pool.query('select status, first_reviewed_at from reports where id = $1', [rid])).rows[0]).toMatchObject({ status: 'action_taken' });
    expect((await pool.query("select 1 from audit_logs where action = 'community.suspend' and target_id = $1", [c.id])).rowCount).toBe(1);
    const st = await call('GET', `${V1}/me/account-status`, owner.token); expect(st.status, JSON.stringify(st.body)).toBe(200);
    const notice = st.body.notices.find((n: { targetType: string }) => n.targetType === 'community');
    expect(notice).toMatchObject({ action: 'suspend_community', message: 'Your community broke the spam rules.' });
    expect((await get(`/admin/communities/${c.id}`, admin.token)).body.suspension).toMatchObject({ from: 'archived' });

    expect((await call('POST', url('restore'), admin.token, { note: 'appeal accepted' })).status).toBe(200);
    expect((await pool.query('select status, suspended_at from communities where id = $1', [c.id])).rows[0]).toEqual({ status: 'archived', suspended_at: null });
    const hist = (await get(`/admin/communities/${c.id}`, admin.token)).body.history.map((x: { action: string }) => x.action);
    expect(hist).toEqual(['restore_community', 'suspend_community']);
  });

  it('events: filters, hidden and private ones, and no join links', async () => {
    const admin = await asRole('admin'); const org = await h.student(); const owner = await h.student(); const joiner = await h.student();
    const up = await event(org, { title: `Upcoming gala${tag}` });
    const past = await event(org, { title: 'Old lecture', starts: "now() - interval '3 days'", ends: "now() - interval '3 days' + interval '2 hours'", status: 'completed' });
    const off = await event(org, { title: 'Cancelled picnic', status: 'cancelled' });
    const hid = await event(org, { title: 'Hidden mixer', hidden: true });
    const draft = await event(org, { title: 'Secret draft', status: 'draft' });
    const online = await event(org, { title: `Webinar night${tag}`, type: 'online' });
    const priv = await community(owner, { visibility: 'private' }); const pubc = await community(owner);
    const inPriv = await event(owner, { title: 'Private meetup', community: priv.id, visibility: 'community' });
    const inPub = await event(owner, { title: 'Public meetup', community: pubc.id });
    await pool.query("insert into event_registrations (event_id, user_id, status) values ($1, $2, 'registered')", [up, joiner.id]);
    const flagged = await event(org, { title: 'Flagged expo' }); await report(joiner, 'event', flagged);
    const list = async (qs: string) => { const r = await get(`/admin/events${qs}`, admin.token); expect(r.status, JSON.stringify(r.body)).toBe(200); return r.body; };
    const ids = (r: { items: { id: string }[] }) => r.items.map((x) => x.id);
    const all = ids(await list('?limit=50'));
    expect(all).toEqual(expect.arrayContaining([up, past, off, hid, online, inPub, flagged]));
    expect(all).not.toContain(draft); expect(all).not.toContain(inPriv);
    expect(ids(await list('?when=upcoming&limit=50'))).toContain(up); expect(ids(await list('?when=upcoming&limit=50'))).not.toContain(past);
    expect(ids(await list('?when=past&limit=50'))).toContain(past);
    expect(ids(await list('?status=cancelled&limit=50'))).toContain(off);
    expect(ids(await list('?hidden=1&limit=50'))).toEqual(expect.arrayContaining([hid])); expect(ids(await list('?hidden=1&limit=50'))).not.toContain(up);
    expect(ids(await list('?reported=1&limit=50'))).toContain(flagged); expect(ids(await list('?reported=1&limit=50'))).not.toContain(up);
    expect(ids(await list(`?community=${pubc.id}`))).toEqual([inPub]);
    expect(ids(await list(`?organizer=${org.id}&limit=50`))).not.toContain(inPub);
    expect(ids(await list(`?q=gala${tag}`))).toEqual([up]);
    const row = (await list(`?q=gala${tag}`)).items[0];
    expect(row.registered).toBe(1);
    const web = (await list(`?q=webinar night${tag}`)).items[0]; expect(web.venue).toBeNull();
    expect(JSON.stringify(await list('?limit=50'))).not.toContain('example.com');
    // once someone reports an event in a private community, moderators can see it
    await report(joiner, 'event', inPriv);
    expect(ids(await list('?limit=50'))).toContain(inPriv);
    expect((await get('/admin/events?when=sometime', admin.token)).status).toBe(400);
  });

  it('content: only what campus can read (or what was reported); removal needs a reason and is reversible', async () => {
    const admin = await asRole('admin'); const author = await h.student(); const other = await h.student();
    const campus = (await call('POST', '/posts', author.token, { text: `free pizza at the quad zq${tag}`, audience: 'campus' })).body.id as string;
    const followers = (await call('POST', '/posts', author.token, { text: 'only my followers see this', audience: 'followers' })).body.id as string;
    const priv = await community(other, { visibility: 'private' });
    const inPriv = (await pool.query("insert into posts (author_id, text, audience, community_id) values ($1, 'members only chatter', 'community', $2) returning id", [other.id, priv.id])).rows[0].id as string;
    const cm = (await call('POST', `/posts/${campus}/comments`, other.token, { text: `save me a slice zq${tag}` })).body.id as string;
    const list = async (qs: string) => { const r = await get(`/admin/content${qs}`, admin.token); expect(r.status, JSON.stringify(r.body)).toBe(200); return r.body; };
    const ids = (r: { items: { id: string }[] }) => r.items.map((x) => x.id);
    let r = await list('?limit=50');
    expect(ids(r)).toContain(campus); expect(ids(r)).not.toContain(followers); expect(ids(r)).not.toContain(inPriv);
    expect(ids(await list(`?q=pizza at the quad zq${tag}`))).toEqual([campus]);
    expect(ids(await list(`?type=comment&q=slice zq${tag}`))).toEqual([cm]);
    expect(ids(await list(`?author=${author.id}&limit=50`))).toEqual([campus]);
    await report(other, 'post', followers);
    expect(ids(await list('?limit=50'))).toContain(followers);              // reported, so staff can see it
    expect(ids(await list('?reported=1&limit=50'))).toContain(followers); expect(ids(await list('?reported=1&limit=50'))).not.toContain(campus);
    expect(ids(await list('?from=2001-01-01&to=2001-01-02'))).toEqual([]);
    for (const bad of ['?type=message', '?status=gone', '?from=2026-02-31', '?from=2026-05-01&to=2026-04-01', '?evil=1']) expect((await get(`/admin/content${bad}`, admin.token)).status, bad).toBe(400);

    const rid = await report(other, 'post', campus);
    const rm = `${V1}/admin/content/post/${campus}/remove`;
    expect((await call('POST', rm, admin.token, {})).status).toBe(400);
    expect((await call('POST', `${V1}/admin/content/post/${campus}/restore`, admin.token, { note: 'oops' })).status).toBe(409);
    expect((await call('POST', rm, admin.token, { note: 'spam', userMessage: 'Removed for spam.' })).status).toBe(200);
    expect((await call('POST', rm, admin.token, { note: 'again' })).status).toBe(409);
    expect((await pool.query('select status from posts where id = $1', [campus])).rows[0].status).toBe('removed');
    expect((await pool.query('select status from reports where id = $1', [rid])).rows[0].status).toBe('action_taken');
    expect(ids(await list('?status=removed&limit=50'))).toContain(campus);
    const st2 = await call('GET', `${V1}/me/account-status`, author.token); expect(st2.status, JSON.stringify(st2.body)).toBe(200);
    expect(st2.body.notices[0]).toMatchObject({ action: 'remove_content', message: 'Removed for spam.' });
    expect((await call('POST', `${V1}/admin/content/post/${campus}/restore`, admin.token, { note: 'mistake' })).status).toBe(200);
    expect((await pool.query('select status from posts where id = $1', [campus])).rows[0].status).toBe('active');
    expect((await pool.query("select count(*)::int as n from audit_logs where target_id = $1 and action in ('remove_content', 'restore_content')", [campus])).rows[0].n).toBe(2);
    expect((await call('POST', `${V1}/admin/content/comment/${cm}/remove`, admin.token, { note: 'rude' })).status).toBe(200);
    expect((await pool.query('select status from comments where id = $1', [cm])).rows[0].status).toBe('removed');
    expect((await call('POST', `${V1}/admin/content/message/${cm}/remove`, admin.token, { note: 'x' })).status).toBe(400);
  });
});
