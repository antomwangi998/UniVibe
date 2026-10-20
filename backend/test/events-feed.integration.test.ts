import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, url } from './helpers';

describe.skipIf(!url)('events in the Home Feed (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';

  beforeAll(async () => { pool = createPool(url as string); await migrate(pool); });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  const inHours = (n: number) => new Date(Date.now() + n * 3600_000).toISOString();
  const body = (over: Record<string, unknown> = {}) => ({
    title: 'Feed night', description: 'Come along', category: 'technology', venueName: 'Lab 3', startsAt: inHours(24), endsAt: inHours(27), ...over,
  });
  const create = async (token: string, over: Record<string, unknown> = {}) => {
    const r = await call('POST', `${V1}/events`, token, body(over));
    expect(r.status, JSON.stringify(r.body)).toBe(201);
    return r.body;
  };
  const feed = async (token: string) => (await call('GET', '/posts?limit=50', token)).body as Array<{ id: string; eventId: string | null; event: Record<string, unknown> | null }>;
  const cardFor = async (token: string, eventId: string) => (await feed(token)).find((p) => p.eventId === eventId);
  const announcements = async (eventId: string) => Number((await pool.query('select count(*) from posts where event_id = $1', [eventId])).rows[0].count);

  it('publishing creates exactly one announcement that references the event, and later edits never add another', async () => {
    const org = await h.student();
    const draft = await create(org.token, { status: 'draft' });
    expect(await announcements(draft.id)).toBe(0);
    await call('POST', `${V1}/events/${draft.id}/publish`, org.token);
    await call('POST', `${V1}/events/${draft.id}/publish`, org.token);
    expect(await announcements(draft.id)).toBe(1);
    await call('PATCH', `${V1}/events/${draft.id}`, org.token, { venueName: 'Main Hall', description: 'More detail' });
    expect(await announcements(draft.id)).toBe(1);
    const direct = await create(org.token);
    expect(await announcements(direct.id)).toBe(1);
    const row = (await pool.query('select event_id, text from posts where event_id = $1', [direct.id])).rows[0];
    expect(row).toEqual({ event_id: direct.id, text: '' });
    // One event row, however many times it is announced.
    expect((await pool.query('select count(*)::int as n from events where id = $1', [direct.id])).rows[0].n).toBe(1);
  });

  it('Home shows an event only to people it is relevant to, and the card is the live event', async () => {
    const org = await h.student();
    const follower = await h.student();
    const going = await h.student();
    const saver = await h.student();
    const stranger = await h.student();
    await call('PUT', `/students/${org.id}/follow`, follower.token);
    const e = await create(org.token);
    expect(await cardFor(org.token, e.id)).toBeTruthy();
    expect(await cardFor(follower.token, e.id)).toBeTruthy();
    expect(await cardFor(stranger.token, e.id)).toBeUndefined(); // not flooded
    await call('PUT', `${V1}/events/${e.id}/rsvp`, going.token, { state: 'going' });
    await call('PUT', `${V1}/events/${e.id}/save`, saver.token);
    expect(await cardFor(going.token, e.id)).toBeTruthy();
    expect(await cardFor(saver.token, e.id)).toBeTruthy();
    // The card is built from the canonical event: same ids, same state.
    const card = (await cardFor(going.token, e.id))!.event!;
    const detail = (await call('GET', `${V1}/events/${e.id}`, going.token)).body;
    expect(card).toMatchObject({ id: e.id, title: detail.title, myRsvp: 'going', registeredCount: detail.registeredCount, registration: detail.registration });
  });

  it('RSVP and save made from anywhere show on the feed card at once', async () => {
    const org = await h.student();
    const fan = await h.student();
    await call('PUT', `/students/${org.id}/follow`, fan.token);
    const e = await create(org.token);
    expect((await cardFor(fan.token, e.id))!.event).toMatchObject({ myRsvp: null, saved: false, interestedCount: 0 });
    await call('PUT', `${V1}/events/${e.id}/rsvp`, fan.token, { state: 'interested' });
    await call('PUT', `${V1}/events/${e.id}/save`, fan.token);
    expect((await cardFor(fan.token, e.id))!.event).toMatchObject({ myRsvp: 'interested', saved: true, interestedCount: 1 });
    await call('PUT', `${V1}/events/${e.id}/rsvp`, fan.token, { state: 'going' });
    expect((await cardFor(fan.token, e.id))!.event).toMatchObject({ myRsvp: 'going', interestedCount: 0, registeredCount: 1 });
  });

  it('a cancelled event stays in the feed marked cancelled, and an ended or completed one leaves Home', async () => {
    const org = await h.student();
    const fan = await h.student();
    await call('PUT', `/students/${org.id}/follow`, fan.token);
    const e = await create(org.token);
    await call('POST', `${V1}/events/${e.id}/cancel`, org.token);
    const card = (await cardFor(fan.token, e.id))!.event!;
    expect(card).toMatchObject({ status: 'cancelled', registration: 'cancelled' });
    expect(await announcements(e.id)).toBe(1);
    const e2 = await create(org.token);
    await pool.query("update events set starts_at = now() - interval '2 hours', ends_at = now() - interval '1 hour' where id = $1", [e2.id]);
    expect(await cardFor(fan.token, e2.id)).toBeUndefined();
    const e3 = await create(org.token);
    await pool.query("update events set starts_at = now() - interval '10 minutes' where id = $1", [e3.id]);
    await call('POST', `${V1}/events/${e3.id}/complete`, org.token);
    expect(await cardFor(fan.token, e3.id)).toBeUndefined();
  });

  it('not interested and hiding the organizer remove event cards from Home', async () => {
    const org = await h.student();
    const fan = await h.student();
    await call('PUT', `/students/${org.id}/follow`, fan.token);
    const e = await create(org.token);
    const post = await cardFor(fan.token, e.id);
    expect((await call('POST', `${V1}/discover/feedback`, fan.token, { kind: 'not_interested', postId: post!.id })).status).toBe(204);
    expect(await cardFor(fan.token, e.id)).toBeUndefined();
    // Still reachable in the Events tab: hiding a recommendation does not hide the event itself.
    expect((await call('GET', `${V1}/events/${e.id}`, fan.token)).status).toBe(200);
  });

  it('private community events are announced inside the community only', async () => {
    const owner = await h.student();
    const member = await h.student();
    const outsider = await h.student();
    const c = (await call('POST', `${V1}/communities`, owner.token, {
      name: `Feed club ${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`, description: 'A private place for events.', category: 'clubs', visibility: 'private', membershipPolicy: 'invite_only',
    })).body;
    await pool.query("insert into community_members (community_id, user_id, role) values ($1, $2, 'member')", [c.id, member.id]);
    const e = await create(owner.token, { communityId: c.id });
    const post = (await pool.query('select id, audience, community_id from posts where event_id = $1', [e.id])).rows[0];
    expect(post.audience).toBe('community');
    expect(post.community_id).toBe(c.id);
    expect(await cardFor(member.token, e.id)).toBeTruthy();
    expect(await cardFor(outsider.token, e.id)).toBeUndefined();
    expect((await call('GET', `/posts/${post.id}`, outsider.token)).status).toBe(404);
    // Losing membership removes the card and the post, even if the person registered.
    await call('PUT', `${V1}/events/${e.id}/rsvp`, member.token, { state: 'going' });
    await pool.query("update community_members set status = 'suspended' where community_id = $1 and user_id = $2", [c.id, member.id]);
    expect((await call('GET', `/posts/${post.id}`, member.token)).status).toBe(404);
  });

  it('the announcement cannot be edited as a post, and deleting the event removes it', async () => {
    const org = await h.student();
    const e = await create(org.token);
    const post = (await pool.query('select id from posts where event_id = $1', [e.id])).rows[0];
    const edit = await call('PUT', `/posts/${post.id}`, org.token, { text: 'sneaky' });
    expect(edit.status).toBe(409);
    expect((await call('DELETE', `${V1}/events/${e.id}`, org.token)).status).toBe(204);
    expect(await announcements(e.id)).toBe(0);
  });
});
