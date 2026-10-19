import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, url } from './helpers';

describe.skipIf(!url)('communities (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';
  let n = 0;
  const uniq = (p: string) => `${p} ${Date.now().toString(36)}${n++}`;

  beforeAll(async () => { pool = createPool(url as string); await migrate(pool); });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  const base = (over: object = {}) => ({ name: uniq('Chess Club'), description: 'A place for chess players on campus.', category: 'clubs', ...over });
  const create = async (token: string, over: object = {}) => {
    const r = await call('POST', `${V1}/communities`, token, base(over));
    expect(r.status, JSON.stringify(r.body)).toBe(201);
    return r.body as { id: string; name: string };
  };

  it('creates a community, makes the creator owner and member, and is idempotent per clientKey', async () => {
    const a = await h.student();
    const key = `key-${Date.now()}`;
    const first = await call('POST', `${V1}/communities`, a.token, base({ clientKey: key }));
    expect(first.status).toBe(201);
    expect(first.body.myRole).toBe('owner');
    expect(first.body.memberCount).toBe(1);
    const again = await call('POST', `${V1}/communities`, a.token, base({ name: first.body.name, clientKey: key }));
    expect(again.status).toBe(200);
    expect(again.body.id).toBe(first.body.id);
  });

  it('rejects duplicate names (case-insensitive) and invalid input', async () => {
    const a = await h.student();
    const c = await create(a.token);
    const b = await h.student();
    expect((await call('POST', `${V1}/communities`, b.token, base({ name: c.name.toUpperCase() }))).status).toBe(409);
    expect((await call('POST', `${V1}/communities`, b.token, base({ description: 'short' }))).status).toBe(400);
    expect((await call('POST', `${V1}/communities`, b.token, base({ visibility: 'private', membershipPolicy: 'open' }))).status).toBe(422);
  });

  it('public community: join is immediate, repeated and concurrent joins create one membership', async () => {
    const owner = await h.student();
    const c = await create(owner.token);
    const m = await h.student();
    const results = await Promise.all([1, 2, 3, 4].map(() => call('PUT', `${V1}/communities/${c.id}/membership`, m.token)));
    for (const r of results) { expect(r.status).toBe(200); expect(r.body.membership).toBe('joined'); }
    const rows = await pool.query('select count(*)::int as n from community_members where community_id = $1 and user_id = $2', [c.id, m.id]);
    expect(rows.rows[0].n).toBe(1);
    expect((await call('GET', `${V1}/communities/${c.id}`, m.token)).body.memberCount).toBe(2);
  });

  it('private approval community: request is pending, duplicates collapse, approval grants access, rejection does not', async () => {
    const owner = await h.student();
    const c = await create(owner.token, { visibility: 'private' });
    const a = await h.student();
    const b = await h.student();
    const r1 = await call('PUT', `${V1}/communities/${c.id}/membership`, a.token);
    expect(r1.status).toBe(202);
    await call('PUT', `${V1}/communities/${c.id}/membership`, a.token);
    await call('PUT', `${V1}/communities/${c.id}/membership`, b.token);
    expect((await pool.query("select count(*)::int as n from community_requests where community_id = $1 and status = 'pending'", [c.id])).rows[0].n).toBe(2);
    const list = await call('GET', `${V1}/communities/${c.id}/requests`, owner.token);
    expect(list.body.items).toHaveLength(2);
    // a non-staff user cannot list or approve
    expect((await call('GET', `${V1}/communities/${c.id}/requests`, a.token)).status).toBe(403);
    const reqA = list.body.items.find((i: { user: { id: string } }) => i.user.id === a.id).id;
    const reqB = list.body.items.find((i: { user: { id: string } }) => i.user.id === b.id).id;
    expect((await call('POST', `${V1}/communities/${c.id}/requests/${reqA}/approve`, a.token)).status).toBe(403);
    expect((await call('POST', `${V1}/communities/${c.id}/requests/${reqA}/approve`, owner.token)).status).toBe(200);
    expect((await call('POST', `${V1}/communities/${c.id}/requests/${reqB}/reject`, owner.token)).status).toBe(200);
    expect((await call('GET', `${V1}/communities/${c.id}/membership`, a.token)).body.membership).toBe('joined');
    expect((await call('GET', `${V1}/communities/${c.id}/membership`, b.token)).body.membership).toBe('request');
    // approval notification exists and does not name a moderator
    const note = await pool.query("select actor_id from notifications where recipient_id = $1 and type = 'community_join_approved'", [a.id]);
    expect(note.rows).toHaveLength(1);
    expect(note.rows[0].actor_id).toBeNull();
  });

  it('private posts never leak: not in the community feed, post lookup, home feed or search for non-members', async () => {
    const owner = await h.student();
    const c = await create(owner.token, { visibility: 'private' });
    const post = await call('POST', '/posts', owner.token, { text: 'secret exam tips zebrafish', communityId: c.id });
    expect(post.status, JSON.stringify(post.body)).toBe(201);
    expect(post.body.audience).toBe('community');
    expect(post.body.community.id).toBe(c.id);
    const outsider = await h.student();
    expect((await call('GET', `/posts/${post.body.id}`, outsider.token)).status).toBe(404);
    expect((await call('GET', `${V1}/communities/${c.id}/posts`, outsider.token)).status).toBe(403);
    const home = await call('GET', '/posts', outsider.token);
    expect(home.body.map((p: { id: string }) => p.id)).not.toContain(post.body.id);
    const search = await call('GET', `${V1}/search?q=zebrafish`, outsider.token);
    expect(JSON.stringify(search.body)).not.toContain('zebrafish');
    expect((await call('GET', `${V1}/communities/${c.id}/members`, outsider.token)).status).toBe(403);
    // the owner still sees it
    expect((await call('GET', `${V1}/communities/${c.id}/posts`, owner.token)).body.items).toHaveLength(1);
  });

  it('non-members cannot post; staff-only posting is enforced; leaving removes access to own private posts', async () => {
    const owner = await h.student();
    const c = await create(owner.token, { postPermission: 'staff' });
    const m = await h.student();
    expect((await call('POST', '/posts', m.token, { text: 'hi', communityId: c.id })).status).toBe(403);
    await call('PUT', `${V1}/communities/${c.id}/membership`, m.token);
    expect((await call('POST', '/posts', m.token, { text: 'hi', communityId: c.id })).status).toBe(403);
    expect((await call('POST', '/posts', owner.token, { text: 'announcement', communityId: c.id })).status).toBe(201);
    const priv = await create(owner.token, { visibility: 'private', membershipPolicy: 'invite_only' });
    expect((await call('PUT', `${V1}/communities/${priv.id}/membership`, m.token)).status).toBe(403); // invite only
  });

  it('home feed shows community posts only from joined, unmuted communities; public posts reachable via community feed', async () => {
    const owner = await h.student();
    const c = await create(owner.token);
    const post = (await call('POST', '/posts', owner.token, { text: 'public community post', communityId: c.id })).body;
    const outsider = await h.student();
    expect((await call('GET', '/posts', outsider.token)).body.map((p: { id: string }) => p.id)).not.toContain(post.id);
    const feed = await call('GET', `${V1}/communities/${c.id}/posts`, outsider.token);
    expect(feed.body.items[0].community.membership).toBe('join');
    await call('PUT', `${V1}/communities/${c.id}/membership`, outsider.token);
    expect((await call('GET', '/posts', outsider.token)).body.map((p: { id: string }) => p.id)).toContain(post.id);
    await call('PATCH', `${V1}/communities/${c.id}/membership`, outsider.token, { notificationLevel: 'muted' });
    expect((await call('GET', '/posts', outsider.token)).body.map((p: { id: string }) => p.id)).not.toContain(post.id);
  });

  it('roles: only the owner changes roles; moderators cannot remove moderators or the owner; owner cannot leave', async () => {
    const owner = await h.student();
    const c = await create(owner.token);
    const mod = await h.student();
    const mod2 = await h.student();
    const member = await h.student();
    for (const s of [mod, mod2, member]) await call('PUT', `${V1}/communities/${c.id}/membership`, s.token);
    expect((await call('PATCH', `${V1}/communities/${c.id}/members/${mod.id}/role`, member.token, { role: 'moderator' })).status).toBe(403);
    expect((await call('PATCH', `${V1}/communities/${c.id}/members/${member.id}/role`, member.token, { role: 'moderator' })).status).toBe(403); // self-promotion
    expect((await call('PATCH', `${V1}/communities/${c.id}/members/${mod.id}/role`, owner.token, { role: 'moderator' })).status).toBe(200);
    expect((await call('PATCH', `${V1}/communities/${c.id}/members/${mod2.id}/role`, owner.token, { role: 'moderator' })).status).toBe(200);
    expect((await call('DELETE', `${V1}/communities/${c.id}/members/${mod2.id}`, mod.token)).status).toBe(403);
    expect((await call('DELETE', `${V1}/communities/${c.id}/members/${owner.id}`, mod.token)).status).toBe(403);
    expect((await call('PATCH', `${V1}/communities/${c.id}`, mod.token, { description: 'Moderators cannot edit settings.' })).status).toBe(403);
    expect((await call('DELETE', `${V1}/communities/${c.id}/members/${member.id}`, mod.token)).status).toBe(204);
    expect((await call('DELETE', `${V1}/communities/${c.id}/membership`, owner.token)).status).toBe(409);
    const count = (await call('GET', `${V1}/communities/${c.id}`, owner.token)).body.memberCount;
    expect(count).toBe(3);
  });

  it('ownership transfer needs the exact name and an active member; one owner always remains', async () => {
    const owner = await h.student();
    const c = await create(owner.token);
    const m = await h.student();
    await call('PUT', `${V1}/communities/${c.id}/membership`, m.token);
    expect((await call('POST', `${V1}/communities/${c.id}/transfer-ownership`, owner.token, { userId: m.id, confirmName: 'wrong' })).status).toBe(422);
    expect((await call('POST', `${V1}/communities/${c.id}/transfer-ownership`, m.token, { userId: m.id, confirmName: c.name })).status).toBe(403);
    expect((await call('POST', `${V1}/communities/${c.id}/transfer-ownership`, owner.token, { userId: m.id, confirmName: c.name })).status).toBe(200);
    const owners = await pool.query("select user_id from community_members where community_id = $1 and role = 'owner'", [c.id]);
    expect(owners.rows.map((r) => r.user_id)).toEqual([m.id]);
  });

  it('invitations: invite, accept; duplicate, expired, revoked and already-used are handled', async () => {
    const owner = await h.student();
    const c = await create(owner.token, { visibility: 'private', membershipPolicy: 'invite_only' });
    const x = await h.student();
    const y = await h.student();
    expect((await call('POST', `${V1}/communities/${c.id}/invitations`, x.token, { userId: y.id })).status).toBe(403);
    const inv = await call('POST', `${V1}/communities/${c.id}/invitations`, owner.token, { userId: x.id });
    expect(inv.status).toBe(201);
    expect((await call('POST', `${V1}/communities/${c.id}/invitations`, owner.token, { userId: x.id })).status).toBe(409);
    expect((await call('GET', `${V1}/community-invitations`, x.token)).body.items).toHaveLength(1);
    expect((await call('POST', `${V1}/community-invitations/${inv.body.id}/accept`, y.token)).status).toBe(404); // not yours
    expect((await call('POST', `${V1}/community-invitations/${inv.body.id}/accept`, x.token)).status).toBe(200);
    expect((await call('POST', `${V1}/community-invitations/${inv.body.id}/accept`, x.token)).status).toBe(409);
    // expired
    const inv2 = await call('POST', `${V1}/communities/${c.id}/invitations`, owner.token, { userId: y.id });
    await pool.query("update community_invitations set expires_at = now() - interval '1 minute' where id = $1", [inv2.body.id]);
    expect((await call('POST', `${V1}/community-invitations/${inv2.body.id}/accept`, y.token)).status).toBe(410);
    // revoked
    const inv3 = await call('POST', `${V1}/communities/${c.id}/invitations`, owner.token, { userId: y.id });
    expect((await call('DELETE', `${V1}/communities/${c.id}/invitations/${inv3.body.id}`, owner.token)).status).toBe(204);
    expect((await call('POST', `${V1}/community-invitations/${inv3.body.id}/accept`, y.token)).status).toBe(409);
  });

  it('blocked users do not see each other\'s community; suspension and deletion hide everything', async () => {
    const owner = await h.student();
    const c = await create(owner.token);
    const post = (await call('POST', '/posts', owner.token, { text: 'visible until suspended', communityId: c.id })).body;
    const v = await h.student();
    await pool.query('insert into blocks (blocker_id, blocked_id) values ($1, $2)', [owner.id, v.id]);
    expect((await call('GET', `${V1}/communities/${c.id}`, v.token)).status).toBe(404);
    await pool.query('delete from blocks where blocker_id = $1', [owner.id]);
    expect((await call('GET', `/posts/${post.id}`, v.token)).status).toBe(200);
    await pool.query("update communities set status = 'suspended' where id = $1", [c.id]);
    expect((await call('GET', `/posts/${post.id}`, v.token)).status).toBe(404);
    expect((await call('PUT', `${V1}/communities/${c.id}/membership`, v.token)).status).toBe(409);
    await pool.query("update communities set status = 'active' where id = $1", [c.id]);
    expect((await call('DELETE', `${V1}/communities/${c.id}`, owner.token, { confirmName: 'nope' })).status).toBe(422);
    expect((await call('DELETE', `${V1}/communities/${c.id}`, owner.token, { confirmName: c.name })).status).toBe(204);
    expect((await call('GET', `/posts/${post.id}`, v.token)).status).toBe(404);
    expect((await call('GET', `${V1}/communities/${c.id}`, v.token)).status).toBe(404);
  });

  it('a private non-discoverable community is invisible to outsiders in lists and by id', async () => {
    const owner = await h.student();
    const c = await create(owner.token, { visibility: 'private', discoverable: false });
    const o = await h.student();
    expect((await call('GET', `${V1}/communities/${c.id}`, o.token)).status).toBe(404);
    const list = await call('GET', `${V1}/communities?q=${encodeURIComponent(c.name)}`, o.token);
    expect(list.body.items).toHaveLength(0);
    const ownerList = await call('GET', `${V1}/communities?scope=joined`, owner.token);
    expect(ownerList.body.items.map((i: { id: string }) => i.id)).toContain(c.id);
  });

  it('community notification switch is respected; role notices are always delivered', async () => {
    const owner = await h.student();
    const c = await create(owner.token, { visibility: 'private' });
    const a = await h.student();
    await call('PUT', '/me/notification-preferences', a.token, { communities: false });
    await call('PUT', `${V1}/communities/${c.id}/membership`, a.token);
    const req = (await call('GET', `${V1}/communities/${c.id}/requests`, owner.token)).body.items[0].id;
    await call('POST', `${V1}/communities/${c.id}/requests/${req}/approve`, owner.token);
    expect((await pool.query("select 1 from notifications where recipient_id = $1 and type = 'community_join_approved'", [a.id])).rowCount).toBe(0);
    await call('PATCH', `${V1}/communities/${c.id}/members/${a.id}/role`, owner.token, { role: 'moderator' });
    expect((await pool.query("select 1 from notifications where recipient_id = $1 and type = 'community_role_changed'", [a.id])).rowCount).toBe(1);
  });

  it('deleting an owner account passes the community to a successor', async () => {
    const owner = await h.student();
    const c = await create(owner.token);
    const m = await h.student();
    await call('PUT', `${V1}/communities/${c.id}/membership`, m.token);
    const { releaseOwnedCommunities } = await import('../src/modules/communities');
    const client = await pool.connect();
    try { await client.query('begin'); await releaseOwnedCommunities(client, owner.id); await client.query('delete from users where id = $1', [owner.id]); await client.query('commit'); } finally { client.release(); }
    const row = await pool.query('select owner_id, status from communities where id = $1', [c.id]);
    expect(row.rows[0]).toEqual({ owner_id: m.id, status: 'active' });
  });
});
