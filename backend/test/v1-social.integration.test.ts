import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, url } from './helpers';

describe.skipIf(!url)('blocking, notifications and privacy on top of the existing social routes (needs TEST_DATABASE_URL)', () => {
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

  const types = async (token: string) => (await call('GET', `${V1}/notifications?limit=50`, token)).body.items.map((n: { type: string }) => n.type);
  const post = async (token: string, text: string, audience = 'campus') => (await call('POST', '/posts', token, { text, audience })).body.id as string;

  it('scenario A: post, feed, like, comment, notifications, restricted content stays restricted', async () => {
    const author = await h.student();
    const fan = await h.student();
    const stranger = await h.student();
    const outsider = await h.student('other.example', 'other');
    const id = await post(author.token, 'hello campus');
    expect((await call('GET', '/posts', fan.token)).body.map((p: { id: string }) => p.id)).toContain(id);
    expect((await call('PUT', `/posts/${id}/like`, fan.token)).status).toBe(204);
    expect((await call('PUT', `/posts/${id}/like`, fan.token)).status).toBe(204); // repeat is harmless
    const c = await call('POST', `/posts/${id}/comments`, fan.token, { text: 'nice one' });
    expect(c.status).toBe(201);
    expect(await types(author.token)).toEqual(expect.arrayContaining(['like', 'comment']));
    expect((await types(author.token)).filter((t: string) => t === 'like')).toHaveLength(1);
    const listed = (await call('GET', `${V1}/notifications`, author.token)).body.items[0];
    expect(typeof listed.ageSeconds).toBe('number'); // the app formats "2m / 3h" from this
    expect(listed.ageSeconds).toBeGreaterThanOrEqual(0);
    // the notification never carries the comment text
    expect(JSON.stringify((await call('GET', `${V1}/notifications`, author.token)).body)).not.toContain('nice one');
    // a reply notifies the person replied to
    await call('POST', `/posts/${id}/comments`, author.token, { text: 'thanks!', replyToId: c.body.id });
    expect(await types(fan.token)).toContain('reply');
    // like -> unlike -> like still yields one notification
    await call('DELETE', `/posts/${id}/like`, fan.token);
    expect(await types(author.token)).not.toContain('like');
    await call('PUT', `/posts/${id}/like`, fan.token);
    expect((await types(author.token)).filter((t: string) => t === 'like')).toHaveLength(1);
    // content restricted to followers or other universities stays out of reach
    const followersOnly = await post(author.token, 'followers only', 'followers');
    expect((await call('GET', `/posts/${followersOnly}`, stranger.token)).status).toBe(404);
    expect((await call('GET', `/posts/${id}`, outsider.token)).status).toBe(404);
    expect((await call('PUT', `/posts/${id}/like`, outsider.token)).status).toBe(404);
    // deleting the post removes the notifications about it
    expect((await call('DELETE', `/posts/${id}`, author.token)).status).toBe(204);
    expect(await types(author.token)).toEqual([]);
  });

  it('own actions never notify yourself', async () => {
    const a = await h.student();
    const id = await post(a.token, 'me myself');
    await call('PUT', `/posts/${id}/like`, a.token);
    await call('POST', `/posts/${id}/comments`, a.token, { text: 'self comment' });
    expect(await types(a.token)).toEqual([]);
  });

  it('a follow notifies once and unfollowing retracts it', async () => {
    const a = await h.student();
    const b = await h.student();
    await call('PUT', `/students/${b.id}/follow`, a.token);
    await call('PUT', `/students/${b.id}/follow`, a.token);
    expect(await types(b.token)).toEqual(['follow']);
    await call('DELETE', `/students/${b.id}/follow`, a.token);
    expect(await types(b.token)).toEqual([]);
  });

  it('scenario E: a block hides both people from each other and direct API calls cannot bypass it', async () => {
    const a = await h.student();
    const b = await h.student();
    const bystander = await h.student();
    await call('PUT', `/students/${b.id}/follow`, a.token);
    await call('PUT', `/students/${a.id}/follow`, b.token);
    const bPost = await post(b.token, 'from b');
    const aPost = await post(a.token, 'from a');
    const bComment = (await call('POST', `/posts/${aPost}/comments`, b.token, { text: 'b was here' })).body.id;
    const open = await post(bystander.token, 'bystander post');
    const aOnOpen = (await call('POST', `/posts/${open}/comments`, a.token, { text: 'a on open' })).body.id;
    expect((await call('POST', `${V1}/users/${b.id}/block`, a.token)).status).toBe(204);
    expect((await call('POST', `${V1}/users/${b.id}/block`, a.token)).status).toBe(204); // repeat is fine
    // profiles, in both directions, look like "not found"
    expect((await call('GET', `/students/${b.id}`, a.token)).status).toBe(404);
    expect((await call('GET', `/students/${a.id}`, b.token)).status).toBe(404);
    // posts and everything on them
    expect((await call('GET', `/posts/${bPost}`, a.token)).status).toBe(404);
    expect((await call('GET', `/posts/${aPost}`, b.token)).status).toBe(404);
    expect((await call('PUT', `/posts/${bPost}/like`, a.token)).status).toBe(404);
    expect((await call('POST', `/posts/${bPost}/comments`, a.token, { text: 'hi' })).status).toBe(404);
    expect((await call('PUT', `/posts/${bPost}/save`, a.token)).status).toBe(404);
    for (const filter of ['campus', 'following']) {
      expect((await call('GET', `/posts?filter=${filter}`, a.token)).body.map((p: { id: string }) => p.id)).not.toContain(bPost);
      expect((await call('GET', `/posts?filter=${filter}`, b.token)).body.map((p: { id: string }) => p.id)).not.toContain(aPost);
    }
    // follows were removed and cannot be recreated
    expect((await call('PUT', `/students/${b.id}/follow`, a.token)).status).toBe(404);
    expect((await call('PUT', `/students/${a.id}/follow`, b.token)).status).toBe(404);
    expect((await call('GET', '/me/followers', a.token)).body).toEqual([]);
    expect((await call('GET', '/me/following', a.token)).body).toEqual([]);
    // comments by a blocked person vanish from shared threads, and cannot be replied to or liked
    expect((await call('GET', `/posts/${open}/comments`, bystander.token)).body).toHaveLength(1);
    await call('POST', `/posts/${open}/comments`, b.token, { text: 'b on open' });
    const seenByA = (await call('GET', `/posts/${open}/comments`, a.token)).body.map((c: { text: string }) => c.text);
    expect(seenByA).not.toContain('b on open');
    expect((await call('GET', `/posts/${open}`, a.token)).body.commentCount).toBe(1);
    expect((await call('GET', `/posts/${open}`, bystander.token)).body.commentCount).toBe(2);
    expect((await call('PUT', `/comments/${bComment}/like`, a.token)).status).toBe(404);
    const bOnOpen = (await call('GET', `/posts/${open}/comments`, bystander.token)).body.find((c: { text: string }) => c.text === 'b on open').id;
    expect((await call('POST', `/posts/${open}/comments`, a.token, { text: 'reply', replyToId: bOnOpen })).status).toBe(404);
    expect(aOnOpen).toBeTruthy();
    // v1: messaging, discovery and notifications between them are gone too
    expect((await call('POST', `${V1}/conversations`, a.token, { type: 'direct', userId: b.id })).status).toBe(404);
    expect((await call('POST', `${V1}/conversations`, b.token, { type: 'direct', userId: a.id })).status).toBe(404);
    expect(await types(a.token)).toEqual([]);
    expect(await types(b.token)).toEqual([]);
    // blocked list shows only the blocker's own list
    expect((await call('GET', `${V1}/me/blocked-users`, a.token)).body.items.map((u: { id: string }) => u.id)).toEqual([b.id]);
    expect((await call('GET', `${V1}/me/blocked-users`, b.token)).body.items).toEqual([]);
    // unblocking restores visibility but not the follows
    expect((await call('DELETE', `${V1}/users/${b.id}/block`, a.token)).status).toBe(204);
    expect((await call('GET', `/students/${b.id}`, a.token)).body.followedByMe).toBe(false);
    expect((await call('GET', `/posts/${bPost}`, a.token)).status).toBe(200);
  });

  it('blocking validates its input', async () => {
    const a = await h.student();
    const outsider = await h.student('other.example', 'other');
    expect((await call('POST', `${V1}/users/${a.id}/block`, a.token)).status).toBe(400);
    expect((await call('POST', `${V1}/users/${outsider.id}/block`, a.token)).status).toBeLessThan(300); // another campus can be blocked too
    expect((await call('POST', `${V1}/users/nobody/block`, a.token)).status).toBe(404);
    expect((await call('POST', `${V1}/users/${a.id}/block`)).status).toBe(401);
  });

  it('suspended accounts are locked out of old and new routes, and vanish for others', async () => {
    const a = await h.student();
    const b = await h.student();
    const id = await post(a.token, 'before suspension');
    await pool.query("update users set suspended_until = now() + interval '1 day' where id = $1", [a.id]);
    for (const [m, p] of [['GET', '/posts'], ['GET', '/me/stats'], ['GET', `${V1}/notifications`]] as const) {
      const res = await call(m, p, a.token);
      expect(res.status).toBe(403);
      expect(res.body.error).toBe('account_suspended');
    }
    expect((await call('GET', `/posts/${id}`, b.token)).status).toBe(404);
    expect((await call('GET', `/students/${a.id}`, b.token)).status).toBe(404);
    expect((await call('PUT', `/students/${a.id}/follow`, b.token)).status).toBe(404);
    await pool.query('update users set suspended_until = null where id = $1', [a.id]);
    expect((await call('GET', `/posts/${id}`, b.token)).status).toBe(200);
  });

  it('removed posts and comments disappear for everyone, including counts', async () => {
    const a = await h.student();
    const b = await h.student();
    const id = await post(a.token, 'will be removed');
    const c = await call('POST', `/posts/${id}/comments`, b.token, { text: 'will be removed too' });
    expect((await call('GET', `/posts/${id}`, b.token)).body.commentCount).toBe(1);
    await pool.query("update comments set status = 'removed' where id = $1", [c.body.id]);
    expect((await call('GET', `/posts/${id}`, b.token)).body.commentCount).toBe(0);
    expect((await call('GET', `/posts/${id}/comments`, b.token)).body).toEqual([]);
    expect((await call('PUT', `/comments/${c.body.id}/like`, a.token)).status).toBe(404);
    await pool.query("update posts set status = 'removed' where id = $1", [id]);
    expect((await call('GET', `/posts/${id}`, b.token)).status).toBe(404);
    expect((await call('GET', `/posts/${id}`, a.token)).status).toBe(404);
    expect((await call('GET', '/posts', b.token)).body.map((p: { id: string }) => p.id)).not.toContain(id);
    expect((await call('GET', `/students/${a.id}`, b.token)).body.posts).toBe(0);
  });

  describe('privacy settings', () => {
    it('defaults, partial updates, validation, and effects', async () => {
      const a = await h.student();
      expect((await call('GET', `${V1}/me/privacy`, a.token)).body).toEqual({ discoverable: true, showOnline: true, messagePermission: 'everyone', readReceipts: true, typingIndicators: true });
      const res = await call('PUT', `${V1}/me/privacy`, a.token, { messagePermission: 'nobody' });
      expect(res.body).toEqual({ discoverable: true, showOnline: true, messagePermission: 'nobody', readReceipts: true, typingIndicators: true });
      expect((await call('PUT', `${V1}/me/privacy`, a.token, { discoverable: false })).body.messagePermission).toBe('nobody'); // earlier choice kept
      expect((await call('PUT', `${V1}/me/privacy`, a.token, { messagePermission: 'friends' })).status).toBe(400);
      expect((await call('PUT', `${V1}/me/privacy`, a.token, { role: 'admin' })).status).toBe(400);
      expect((await call('GET', `${V1}/me/privacy`)).status).toBe(401);
      const b = await h.student();
      expect((await call('GET', `${V1}/me/privacy`, b.token)).body.messagePermission).toBe('everyone'); // not shared between users
    });
  });

  describe('notifications API', () => {
    it('lists newest first with pagination, counts, marks read and deletes only your own, and honours preferences', async () => {
      const a = await h.student();
      const fans = [await h.student(), await h.student(), await h.student()];
      const ids = [await post(a.token, 'one'), await post(a.token, 'two'), await post(a.token, 'three')];
      for (let i = 0; i < 3; i++) await call('PUT', `/posts/${ids[i]}/like`, fans[i].token);
      expect((await call('GET', `${V1}/notifications/unread-count`, a.token)).body.count).toBe(3);
      const seen: string[] = [];
      let cursor: string | null = null;
      do {
        const res: { body: { items: { id: string; actor: { id: string } }[]; nextCursor: string | null } } =
          await call('GET', `${V1}/notifications?limit=2${cursor ? `&cursor=${cursor}` : ''}`, a.token);
        res.body.items.forEach((n) => seen.push(n.id));
        cursor = res.body.nextCursor;
      } while (cursor);
      expect(new Set(seen).size).toBe(3);
      const first = (await call('GET', `${V1}/notifications?limit=1`, a.token)).body.items[0];
      expect(first.actor.id).toBe(fans[2].id); // newest first
      expect((await call('POST', `${V1}/notifications/${first.id}/read`, fans[0].token)).status).toBe(404);
      expect((await call('DELETE', `${V1}/notifications/${first.id}`, fans[0].token)).status).toBe(404);
      expect((await call('POST', `${V1}/notifications/${first.id}/read`, a.token)).status).toBe(204);
      expect((await call('POST', `${V1}/notifications/${first.id}/read`, a.token)).status).toBe(204);
      expect((await call('GET', `${V1}/notifications/unread-count`, a.token)).body.count).toBe(2);
      expect((await call('GET', `${V1}/notifications?unread=true`, a.token)).body.items).toHaveLength(2);
      expect((await call('POST', `${V1}/notifications/read-all`, a.token)).status).toBe(204);
      expect((await call('GET', `${V1}/notifications/unread-count`, a.token)).body.count).toBe(0);
      expect((await call('DELETE', `${V1}/notifications/${first.id}`, a.token)).status).toBe(204);
      // preferences switch a type off for new events
      expect((await call('PUT', `${V1}/me/notification-preferences`, a.token, { likes: false })).body.likes).toBe(false);
      const extra = await post(a.token, 'four');
      await call('PUT', `/posts/${extra}/like`, fans[0].token);
      expect((await types(a.token)).filter((t: string) => t === 'like')).toHaveLength(2);
      expect((await call('PUT', `${V1}/me/notification-preferences`, a.token, { push: true })).status).toBe(400);
      expect((await call('POST', `${V1}/notifications`, a.token, { recipientId: fans[0].id })).status).toBe(404); // clients cannot create notifications
      expect((await call('GET', `${V1}/notifications?cursor=zzz`, a.token)).status).toBe(400);
      expect((await call('GET', `${V1}/notifications?limit=500`, a.token)).status).toBe(400);
    });
  });

  it('exposes health, readiness and a request id without leaking details', async () => {
    const ready = await h.app().inject({ method: 'GET', url: '/ready' });
    expect(ready.statusCode).toBe(200);
    expect(JSON.parse(ready.body)).toEqual({ status: 'ready' });
    expect(ready.headers['x-request-id']).toBeTruthy();
    expect((await h.app().inject({ method: 'GET', url: '/health' })).statusCode).toBe(200);
  });
});
