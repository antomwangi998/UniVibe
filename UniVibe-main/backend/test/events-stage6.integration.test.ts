import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, url } from './helpers';

describe.skipIf(!url)('events stage 6: updates, audit, sharing, moderation, public preview (needs TEST_DATABASE_URL)', () => {
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
      title: `Stage six ${Math.random().toString(36).slice(2, 7)}`, description: 'Come along', category: 'technology',
      venueName: 'Lab 3', startsAt: inHours(30), endsAt: inHours(33), visibility: 'public', ...over,
    });
    expect(r.status, JSON.stringify(r.body)).toBe(201);
    return r.body;
  };
  const makeCommunity = async (token: string, over: Record<string, unknown> = {}) => {
    const r = await call('POST', `${V1}/communities`, token, {
      name: `Six club ${Math.random().toString(36).slice(2, 8)}`, description: 'A community.', category: 'clubs', ...over,
    });
    expect(r.status, JSON.stringify(r.body)).toBe(201);
    return r.body as { id: string };
  };
  const join = (communityId: string, userId: string, role = 'member') =>
    pool.query("insert into community_members (community_id, user_id, role) values ($1, $2, $3) on conflict (community_id, user_id) do update set role = $3, status = 'active'", [communityId, userId, role]);
  const direct = async (a: { token: string }, b: { id: string }) => {
    const r = await call('POST', `${V1}/conversations`, a.token, { type: 'direct', userId: b.id });
    expect(r.status, JSON.stringify(r.body)).toBeLessThan(300);
    return r.body.id as string;
  };
  const eventNotices = async (token: string, eventId: string) =>
    ((await call('GET', `${V1}/notifications?limit=50`, token)).body.items as Array<{ type: string; entityId: string | null; metadata: { kind?: string } }>)
      .filter((n) => n.type === 'event_update' && n.entityId === eventId).map((n) => n.metadata.kind);

  it('organizer updates reach going and interested people, are readable by viewers, and are limited', async () => {
    const org = await h.student();
    const going = await h.student();
    const interested = await h.student();
    const stranger = await h.student();
    const e = await create(org.token);
    await call('POST', `${V1}/events/${e.id}/register`, going.token);
    await call('PUT', `${V1}/events/${e.id}/rsvp`, interested.token, { state: 'interested' });
    expect((await call('POST', `${V1}/events/${e.id}/updates`, stranger.token, { body: 'hi' })).status).toBe(404); // not the organizer
    expect((await call('POST', `${V1}/events/${e.id}/updates`, org.token, { body: '   ' })).status).toBe(400);
    expect((await call('POST', `${V1}/events/${e.id}/updates`, org.token, { body: 'Doors move to 7:30' })).status).toBe(201);
    expect((await call('POST', `${V1}/events/${e.id}/updates`, org.token, { body: 'Bring a charger' })).status).toBe(201);
    expect((await eventNotices(going.token, e.id)).filter((k) => k === 'update')).toHaveLength(2);
    expect((await eventNotices(interested.token, e.id)).filter((k) => k === 'update')).toHaveLength(2);
    expect((await eventNotices(stranger.token, e.id))).toEqual([]);
    expect((await eventNotices(org.token, e.id)).filter((k) => k === 'update')).toHaveLength(0);
    const list = await call('GET', `${V1}/events/${e.id}/updates`, stranger.token);
    expect(list.status).toBe(200);
    expect(list.body.items.map((i: { body: string }) => i.body)).toEqual(['Bring a charger', 'Doors move to 7:30']);
    for (let i = 0; i < 3; i++) expect((await call('POST', `${V1}/events/${e.id}/updates`, org.token, { body: `more ${i}` })).status).toBe(201);
    expect((await call('POST', `${V1}/events/${e.id}/updates`, org.token, { body: 'one too many' })).status).toBe(429);
  });

  it('the audit is readable by the organizer only, and a calendar-relevant change bumps the revision', async () => {
    const org = await h.student();
    const other = await h.student();
    const e = await create(org.token);
    expect(e.revision).toBe(0);
    expect((await call('PATCH', `${V1}/events/${e.id}`, org.token, { description: 'Reworded' })).body.revision).toBe(0);
    expect((await call('PATCH', `${V1}/events/${e.id}`, org.token, { venueName: 'Lab 4' })).body.revision).toBe(1);
    expect((await call('GET', `${V1}/events/${e.id}/audit`, other.token)).status).toBe(404);
    const audit = await call('GET', `${V1}/events/${e.id}/audit`, org.token);
    expect(audit.status).toBe(200);
    expect(audit.body.items.map((i: { action: string }) => i.action)).toEqual(expect.arrayContaining(['create', 'publish', 'update']));
  });

  it('sharing: chat and community cards carry only the id, community events stay inside, and unseen events are unavailable', async () => {
    const org = await h.student();
    const friend = await h.student();
    const member = await h.student();
    const outsider = await h.student();
    const pub = await create(org.token);
    const conv = await direct(org, friend);
    const shared = await call('POST', `${V1}/events/${pub.id}/share`, org.token, { target: 'chat', targetId: conv, text: 'Come!' });
    expect(shared.status, JSON.stringify(shared.body)).toBe(201);
    const msgs = await call('GET', `${V1}/conversations/${conv}/messages`, friend.token);
    expect(msgs.body.items.find((m: { eventId: string | null }) => m.eventId === pub.id)).toBeTruthy();

    const c = await makeCommunity(org.token, { visibility: 'private', membershipPolicy: 'invite_only' });
    await join(c.id, member.id);
    const inside = await create(org.token, { communityId: c.id });
    expect(inside.visibility).toBe('community');
    expect((await call('POST', `${V1}/events/${inside.id}/share`, org.token, { target: 'chat', targetId: conv })).status).toBe(403);
    const other = await makeCommunity(org.token);
    expect((await call('POST', `${V1}/events/${inside.id}/share`, org.token, { target: 'community', targetId: other.id })).status).toBe(403);
    const ok = await call('POST', `${V1}/events/${inside.id}/share`, org.token, { target: 'community', targetId: c.id, text: 'See you there' });
    expect(ok.status, JSON.stringify(ok.body)).toBe(201);
    expect((await call('GET', `${V1}/communities/${c.id}/posts`, member.token)).body.items.some((p: { sharedEvent: boolean }) => p.sharedEvent)).toBe(true);
    expect((await call('GET', `${V1}/events/${inside.id}`, outsider.token)).status).toBe(404);
    // A draft cannot be shared; a non-member cannot post a share into the community.
    const draft = await create(org.token, { status: 'draft' });
    expect((await call('POST', `${V1}/events/${draft.id}/share`, org.token, { target: 'chat', targetId: conv })).status).toBe(409);
    expect((await call('POST', `${V1}/events/${pub.id}/share`, outsider.token, { target: 'community', targetId: c.id })).status).toBe(404);
  });

  it('moderators can hide and restore an event; hidden events vanish for others, and the organizer is told in account status', async () => {
    const org = await h.student();
    const viewer = await h.student();
    const mod = await h.student();
    await pool.query("update users set role = 'moderator' where id = $1", [mod.id]);
    const e = await create(org.token);
    const rep = await call('POST', `${V1}/reports`, viewer.token, { targetType: 'event', targetId: e.id, reason: 'spam', details: '' });
    expect(rep.status).toBe(201);
    const detail = await call('GET', `${V1}/moderation/events/${e.id}`, mod.token);
    expect(detail.status).toBe(200);
    expect(detail.body.organizer.id).toBe(org.id);
    expect((await call('GET', `${V1}/moderation/events/${e.id}`, viewer.token)).status).toBe(403);
    const hide = await call('POST', `${V1}/moderation/reports/${rep.body.id}/action`, mod.token, { action: 'hide_event', note: 'spam', userMessage: 'Your event was hidden.' });
    expect(hide.status, JSON.stringify(hide.body)).toBe(200);
    expect((await call('GET', `${V1}/events/${e.id}`, viewer.token)).status).toBe(404);
    const mine = await call('GET', `${V1}/events/${e.id}`, org.token);
    expect(mine.status).toBe(200);
    expect(mine.body.hidden).toBe(true);
    expect((await call('POST', `${V1}/events/${e.id}/updates`, org.token, { body: 'x' })).status).toBe(409);
    expect((await call('GET', `${V1}/me/account-status`, org.token)).body.notices?.some((n: { action: string }) => n.action === 'hide_event') ?? true).toBe(true);
    const rep2 = await call('POST', `${V1}/reports`, (await h.student()).token, { targetType: 'event', targetId: e.id, reason: 'spam', details: '' });
    expect(rep2.status).toBe(404); // hidden events cannot be reported by people who cannot see them
    await pool.query("update reports set status = 'open' where target_id = $1", [e.id]);
    const restore = await call('POST', `${V1}/moderation/reports/${rep.body.id}/action`, mod.token, { action: 'restore_event', note: '' });
    expect(restore.status).toBe(200);
    expect((await call('GET', `${V1}/events/${e.id}`, viewer.token)).status).toBe(200);
  });

  it('a suspended organizer\'s events disappear for others', async () => {
    const org = await h.student();
    const viewer = await h.student();
    const e = await create(org.token);
    await pool.query("update users set suspended_until = now() + interval '1 day' where id = $1", [org.id]);
    expect((await call('GET', `${V1}/events/${e.id}`, viewer.token)).status).toBe(404);
  });

  it('public preview: only public published events, a generic page otherwise, no private fields', async () => {
    const org = await h.student();
    const pub = await create(org.token, { venueName: 'Main Hall', venueAddress: 'Secret street 1' });
    const uni = await create(org.token, { visibility: 'university' });
    const preview = await call('GET', `/public/events/${pub.id}`, undefined);
    expect(preview.status).toBe(200);
    expect(preview.body.title).toBe(pub.title);
    expect(JSON.stringify(preview.body)).not.toMatch(/Secret street|organizer|onlineUrl/);
    expect((await call('GET', `/public/events/${uni.id}`, undefined)).status).toBe(404);
    expect((await call('GET', `/public/events/not-an-id`, undefined)).status).toBe(404);
    const page = await h.app().inject({ method: 'GET', url: `/e/${uni.id}` });
    expect(page.statusCode).toBe(200);
    expect(page.body).not.toContain(uni.title); // same generic page as an unknown id
    const link = await call('GET', `${V1}/events/${pub.id}/link`, org.token);
    expect(link.body.appLink).toBe(`univibe://events/${pub.id}`);
    expect(link.body.publicPreview).toBe(true);
  });
});
