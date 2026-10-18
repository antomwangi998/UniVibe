import { randomUUID } from 'node:crypto';
import type { Pool } from 'pg';
import WebSocket from 'ws';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { makeCtx } from '../src/context';
import { createPool, migrate } from '../src/db';
import { enqueue, runDueJobs, type JobLogger } from '../src/jobs';
import { RateLimiter } from '../src/limiter';
import { deliveryHandlers } from '../src/modules/delivery';
import type { PushMessage, PushResult, PushSender } from '../src/push';
import { Hub } from '../src/realtime';
import { harness, testConfig, type Student, url } from './helpers';

type Ev = { type: string; [k: string]: unknown };
const quiet: JobLogger = { info() {}, warn() {}, error() {} };
const TOKEN = (n: string) => `fcm-token-${n}-`.padEnd(40, 'x');

describe.skipIf(!url)('push, jobs, reactions, settings, sync, search (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  let hub: Hub;
  let port = 0;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';
  const sockets: WebSocket[] = [];

  /** A push sender the tests control. `script` decides the answer for each token. */
  const sent: { token: string; message: PushMessage }[] = [];
  let script: (token: string) => PushResult = () => ({ ok: true });
  const sender: PushSender = {
    enabled: true,
    send: async (token, message) => { sent.push({ token, message }); return script(token); },
  };
  const work = () => runDueJobs(pool, deliveryHandlers(makeCtx(pool, testConfig(), new RateLimiter(), null), hub, sender), quiet);
  const dueNow = () => pool.query("update jobs set run_at = now() where status = 'pending'");

  beforeAll(async () => { pool = createPool(url as string); await migrate(pool); });
  afterAll(async () => { await pool.end(); });
  beforeEach(async () => {
    hub = new Hub();
    // Jobs left over from earlier tests must not be processed (and counted) by this one.
    await pool.query("update jobs set status = 'done', finished_at = now(), locked_until = null where status in ('pending', 'active')");
    sent.length = 0;
    script = () => ({ ok: true });
    h.make(hub);
    await h.app().listen({ port: 0, host: '127.0.0.1' });
    port = (h.app().server.address() as { port: number }).port;
  });
  afterEach(async () => { sockets.splice(0).forEach((s) => s.terminate()); await h.close(); });

  async function connect(token: string) {
    const ws = new WebSocket(`ws://127.0.0.1:${port}${V1}/ws`);
    sockets.push(ws);
    const events: Ev[] = [];
    ws.on('message', (d) => events.push(JSON.parse(d.toString())));
    await new Promise((resolve, reject) => { ws.on('open', resolve); ws.on('error', reject); });
    ws.send(JSON.stringify({ type: 'auth', token }));
    await waitFor(events, 'auth:ok');
    return { events };
  }
  async function waitFor(events: Ev[], type: string, match: (e: Ev) => boolean = () => true, ms = 3000): Promise<Ev> {
    const start = Date.now();
    while (Date.now() - start < ms) {
      const found = events.find((e) => e.type === type && match(e));
      if (found) return found;
      await new Promise((r) => setTimeout(r, 20));
    }
    throw new Error(`timed out waiting for ${type}; saw ${JSON.stringify(events.map((e) => e.type))}`);
  }

  async function pair() {
    const a = await h.student();
    const b = await h.student();
    const res = await call('POST', `${V1}/conversations`, a.token, { type: 'direct', userId: b.id });
    expect([200, 201]).toContain(res.status);
    return { a, b, conv: res.body.id as string };
  }
  const say = async (s: Student, conv: string, content: string) => {
    const res = await call('POST', `${V1}/conversations/${conv}/messages`, s.token, { content });
    expect(res.status, JSON.stringify(res.body)).toBe(201);
    return res.body as { id: string; seq: number };
  };
  const register = async (s: Student, install: string, token: string) =>
    call('PUT', `${V1}/me/devices`, s.token, { installationId: install, fcmToken: token });

  // ---------------------------------------------------------------- devices
  describe('device tokens', () => {
    it('registers several devices, and re-registering the same one does not duplicate it', async () => {
      const s = await h.student();
      expect((await register(s, 'install-phone-1', TOKEN('a'))).status).toBe(204);
      expect((await register(s, 'install-phone-1', TOKEN('a'))).status).toBe(204);
      expect((await register(s, 'install-tablet-2', TOKEN('b'))).status).toBe(204);
      const rows = (await pool.query('select * from device_tokens where user_id = $1 and revoked_at is null', [s.id])).rows;
      expect(rows).toHaveLength(2);
    });
    it('a token that moves to another account stops serving the old one', async () => {
      const [a, b] = [await h.student(), await h.student()];
      await register(a, 'install-shared-1', TOKEN('shared'));
      await register(b, 'install-shared-1', TOKEN('shared'));
      const live = (await pool.query('select user_id from device_tokens where fcm_token = $1 and revoked_at is null', [TOKEN('shared')])).rows;
      expect(live.map((r) => r.user_id)).toEqual([b.id]);
    });
    it('can be revoked, idempotently, and validates input', async () => {
      const s = await h.student();
      await register(s, 'install-gone-001', TOKEN('c'));
      expect((await call('DELETE', `${V1}/me/devices/install-gone-001`, s.token)).status).toBe(204);
      expect((await call('DELETE', `${V1}/me/devices/install-gone-001`, s.token)).status).toBe(204);
      expect((await pool.query('select 1 from device_tokens where user_id = $1 and revoked_at is null', [s.id])).rowCount).toBe(0);
      expect((await register(s, 'short', TOKEN('d'))).status).toBe(400);
      expect((await call('PUT', `${V1}/me/devices`, undefined, { installationId: 'install-anon-001', fcmToken: TOKEN('e') })).status).toBe(401);
    });
  });

  // ---------------------------------------------------------------- message push
  describe('message push', () => {
    it('pushes a generic data message to the recipient, with no message text or tokens in it', async () => {
      const { a, b, conv } = await pair();
      await register(b, 'install-b-phone-1', TOKEN('b1'));
      const m = await say(a, conv, 'super secret plans');
      await work();
      expect(sent).toHaveLength(1);
      expect(sent[0].token).toBe(TOKEN('b1'));
      expect(sent[0].message.data).toMatchObject({ kind: 'message', messageId: m.id, conversationId: conv, group: '0' });
      const payload = JSON.stringify(sent[0].message);
      expect(payload).not.toContain('super secret');
      expect(payload).not.toContain(b.token);
    });
    it('sends nothing to the sender, to a muted recipient, or when push is switched off', async () => {
      const { a, b, conv } = await pair();
      await register(a, 'install-a-phone-1', TOKEN('a1'));
      await register(b, 'install-b-phone-1', TOKEN('b1'));
      // Muted: nothing, but the message is still there.
      const until = new Date(Date.now() + 3600_000).toISOString();
      expect((await call('PATCH', `${V1}/conversations/${conv}/settings`, b.token, { mutedUntil: until })).status).toBe(200);
      await say(a, conv, 'one');
      await work();
      expect(sent).toHaveLength(0);
      const history = await call('GET', `${V1}/conversations/${conv}/messages`, b.token);
      expect(history.body.items.map((i: { content: string }) => i.content)).toContain('one');
      // Unmuted but push switched off.
      await call('PATCH', `${V1}/conversations/${conv}/settings`, b.token, { mutedUntil: null });
      await call('PUT', `${V1}/me/notification-preferences`, b.token, { pushMessages: false });
      await say(a, conv, 'two');
      await work();
      expect(sent).toHaveLength(0);
    });
    it('sends nothing for a message that was already read or unsent before the job ran', async () => {
      const { a, b, conv } = await pair();
      await register(b, 'install-b-phone-1', TOKEN('b1'));
      const read = await say(a, conv, 'read me');
      await call('POST', `${V1}/conversations/${conv}/read`, b.token, { upToSeq: read.seq });
      const gone = await say(a, conv, 'oops');
      await call('DELETE', `${V1}/messages/${gone.id}`, a.token);
      await work();
      expect(sent).toHaveLength(0);
    });
    it('does not push for a blocked pair', async () => {
      const { a, b, conv } = await pair();
      await register(b, 'install-b-phone-1', TOKEN('b1'));
      await say(a, conv, 'before the block');
      await call('POST', `${V1}/users/${a.id}/block`, b.token);
      await work();
      expect(sent).toHaveLength(0);
    });
    it('revokes a token FCM says is dead, and keeps the other devices working', async () => {
      const { a, b, conv } = await pair();
      await register(b, 'install-b-phone-1', TOKEN('dead'));
      await register(b, 'install-b-tablet-2', TOKEN('live'));
      script = (t) => t === TOKEN('dead') ? { ok: false, retryable: false, invalidToken: true, reason: 'UNREGISTERED' } : { ok: true };
      const m = await say(a, conv, 'hello');
      await work();
      const rows = (await pool.query('select fcm_token, revoked_at, last_success_at from device_tokens where user_id = $1', [b.id])).rows;
      expect(rows.find((r) => r.fcm_token === TOKEN('dead')).revoked_at).not.toBeNull();
      expect(rows.find((r) => r.fcm_token === TOKEN('live')).last_success_at).not.toBeNull();
      expect((await pool.query("select status from jobs where kind = 'push.message' and payload->>'messageId' = $1", [m.id])).rows[0].status).toBe('done');
    });
    it('retries a transient provider failure with backoff, then succeeds without a duplicate job', async () => {
      const { a, b, conv } = await pair();
      await register(b, 'install-b-phone-1', TOKEN('b1'));
      let failures = 1;
      script = () => failures-- > 0 ? { ok: false, retryable: true, invalidToken: false, reason: 'UNAVAILABLE' } : { ok: true };
      const m = await say(a, conv, 'retry me');
      await work();
      const job = (await pool.query("select status, attempts, run_at > now() as later from jobs where kind = 'push.message' and payload->>'messageId' = $1", [m.id])).rows[0];
      expect(job).toMatchObject({ status: 'pending', attempts: 1, later: true });
      await dueNow();
      await work();
      const after = (await pool.query("select status, attempts from jobs where kind = 'push.message' and payload->>'messageId' = $1", [m.id])).rows;
      expect(after).toEqual([{ status: 'done', attempts: 2 }]);
    });
    it('marks a job failed for good after its attempts are used up', async () => {
      const { a, b, conv } = await pair();
      await register(b, 'install-b-phone-1', TOKEN('b1'));
      script = () => ({ ok: false, retryable: true, invalidToken: false, reason: 'UNAVAILABLE' });
      const m = await say(a, conv, 'never works');
      await pool.query("update jobs set max_attempts = 2 where payload->>'messageId' = $1", [m.id]);
      await work();
      await dueNow();
      await work();
      const job = (await pool.query("select status, attempts, last_error from jobs where payload->>'messageId' = $1", [m.id])).rows[0];
      expect(job.status).toBe('failed');
      expect(job.attempts).toBe(2);
      expect(job.last_error).toBeTruthy();
    });
  });

  // ---------------------------------------------------------------- outbox / queue
  describe('outbox and queue', () => {
    it('writes the message and its push job together, and a replayed send creates no second job', async () => {
      const { a, conv, b } = await pair();
      const body = { content: 'once', clientId: 'client-1234' };
      const first = await call('POST', `${V1}/conversations/${conv}/messages`, a.token, body);
      const again = await call('POST', `${V1}/conversations/${conv}/messages`, a.token, body);
      expect([first.status, again.status]).toEqual([201, 200]);
      const jobs = await pool.query("select 1 from jobs where kind = 'push.message' and payload->>'messageId' = $1 and payload->>'recipientId' = $2", [first.body.id, b.id]);
      expect(jobs.rowCount).toBe(1);
    });
    it('recovers a job whose worker crashed mid-run (lock expired)', async () => {
      const { a, b, conv } = await pair();
      await register(b, 'install-b-phone-1', TOKEN('b1'));
      const m = await say(a, conv, 'crash');
      // Simulate a worker that claimed the job and died: active, with a lock in the past.
      await pool.query("update jobs set status = 'active', attempts = 1, locked_until = now() - interval '1 minute' where payload->>'messageId' = $1", [m.id]);
      await work();
      expect(sent).toHaveLength(1);
      expect((await pool.query("select status from jobs where payload->>'messageId' = $1", [m.id])).rows[0].status).toBe('done');
    });
    it('does not enqueue the same notification twice and fails unknown job kinds permanently', async () => {
      const id = randomUUID();
      expect(await enqueue(pool, 'notification.deliver', { notificationId: id }, { dedupeKey: id })).toBe(true);
      expect(await enqueue(pool, 'notification.deliver', { notificationId: id }, { dedupeKey: id })).toBe(false);
      await enqueue(pool, 'no.such.kind', {});
      await work();
      expect((await pool.query("select status from jobs where kind = 'no.such.kind'")).rows[0].status).toBe('failed');
      // The deliver job for a notification that no longer exists is a harmless success.
      expect((await pool.query("select status from jobs where dedupe_key = $1", [id])).rows[0].status).toBe('done');
    });
    it('only admins can see queue stats', async () => {
      const s = await h.student();
      expect((await call('GET', `${V1}/admin/jobs`, s.token)).status).toBe(404);
      await pool.query("update users set role = 'admin' where id = $1", [s.id]);
      const res = await call('GET', `${V1}/admin/jobs`, s.token);
      expect(res.status).toBe(200);
      expect(res.body).toHaveProperty('byStatus');
    });
  });

  // ---------------------------------------------------------------- realtime notifications
  describe('live notifications', () => {
    it('a follow is stored, pushed to the open app with the server unread count, and not duplicated on replay', async () => {
      const [alice, bob] = [await h.student(), await h.student()];
      const live = await connect(alice.token);
      expect((await call('PUT', `/students/${alice.id}/follow`, bob.token)).status).toBe(204);
      await work();
      const ev = await waitFor(live.events, 'notification:created', (e) => (e.notification as { type: string }).type === 'follow');
      expect(ev.unreadCount).toBe(1);
      // Same job replayed: nothing new for the app.
      await call('PUT', `/students/${alice.id}/follow`, bob.token);
      await work();
      expect(live.events.filter((e) => e.type === 'notification:created')).toHaveLength(1);
      expect((await call('GET', `${V1}/notifications/unread-count`, alice.token)).body.count).toBe(1);
    });
    it('reading a notification updates the count on the person\'s other devices', async () => {
      const [alice, bob] = [await h.student(), await h.student()];
      await call('PUT', `/students/${alice.id}/follow`, bob.token);
      await work();
      const live = await connect(alice.token);
      const list = await call('GET', `${V1}/notifications`, alice.token);
      await call('POST', `${V1}/notifications/${list.body.items[0].id}/read`, alice.token);
      const ev = await waitFor(live.events, 'notification:unread');
      expect(ev.unreadCount).toBe(0);
    });
    it('push for a follow respects the push switch but the feed entry stays', async () => {
      const [alice, bob] = [await h.student(), await h.student()];
      await register(alice, 'install-alice-001', TOKEN('al'));
      await call('PUT', `${V1}/me/notification-preferences`, alice.token, { pushSocial: false });
      await call('PUT', `/students/${alice.id}/follow`, bob.token);
      await work();
      expect(sent).toHaveLength(0);
      expect((await call('GET', `${V1}/notifications`, alice.token)).body.items).toHaveLength(1);
      expect((await pool.query("select push_status from notifications where recipient_id = $1", [alice.id])).rows[0].push_status).toBe('skipped');
    });
    it('records "accepted" only when FCM accepted the request', async () => {
      const [alice, bob] = [await h.student(), await h.student()];
      await register(alice, 'install-alice-001', TOKEN('al'));
      await call('PUT', `/students/${alice.id}/follow`, bob.token);
      await work();
      expect(sent).toHaveLength(1);
      expect((await pool.query("select push_status from notifications where recipient_id = $1", [alice.id])).rows[0].push_status).toBe('accepted');
    });
  });

  // ---------------------------------------------------------------- reactions
  describe('reactions', () => {
    it('adds, replaces and removes one reaction per person and tells the other side', async () => {
      const { a, b, conv } = await pair();
      const live = await connect(a.token);
      const m = await say(a, conv, 'react to me');
      const put = await call('PUT', `${V1}/messages/${m.id}/reaction`, b.token, { reaction: 'heart' });
      expect(put.body.reactions).toEqual({ heart: 1 });
      const ev = await waitFor(live.events, 'message:reaction', (e) => e.reaction === 'heart');
      expect(ev).toMatchObject({ messageId: m.id, userId: b.id });
      expect((await call('PUT', `${V1}/messages/${m.id}/reaction`, b.token, { reaction: 'fire' })).body.reactions).toEqual({ fire: 1 });
      expect((await call('PUT', `${V1}/messages/${m.id}/reaction`, a.token, { reaction: 'fire' })).body.reactions).toEqual({ fire: 2 });
      const history = await call('GET', `${V1}/conversations/${conv}/messages`, b.token);
      expect(history.body.items[0]).toMatchObject({ reactions: { fire: 2 }, myReaction: 'fire' });
      expect((await call('DELETE', `${V1}/messages/${m.id}/reaction`, b.token)).body.reactions).toEqual({ fire: 1 });
    });
    it('rejects non-members, unknown reactions and reactions to unsent messages', async () => {
      const { a, conv } = await pair();
      const outsider = await h.student();
      const m = await say(a, conv, 'private');
      expect((await call('PUT', `${V1}/messages/${m.id}/reaction`, outsider.token, { reaction: 'like' })).status).toBe(404);
      expect((await call('PUT', `${V1}/messages/${m.id}/reaction`, a.token, { reaction: 'poop' })).status).toBe(400);
      await call('DELETE', `${V1}/messages/${m.id}`, a.token);
      expect((await call('PUT', `${V1}/messages/${m.id}/reaction`, a.token, { reaction: 'like' })).status).toBe(404);
    });
  });

  // ---------------------------------------------------------------- settings
  describe('conversation settings', () => {
    it('pins, archives and mutes per person, and a new message brings an archived chat back', async () => {
      const { a, b, conv } = await pair();
      await say(a, conv, 'hi');
      const until = new Date(Date.now() + 3600_000).toISOString();
      const res = await call('PATCH', `${V1}/conversations/${conv}/settings`, b.token, { archived: true, pinned: true, mutedUntil: until });
      expect(res.body).toMatchObject({ archived: true, pinned: true });
      expect((await call('GET', `${V1}/conversations`, b.token)).body.items).toHaveLength(0);
      expect((await call('GET', `${V1}/conversations?archived=true`, b.token)).body.items).toHaveLength(1);
      // A's own view is untouched.
      expect((await call('GET', `${V1}/conversations`, a.token)).body.items[0]).toMatchObject({ archived: false, pinned: false, mutedUntil: null });
      await say(a, conv, 'are you there');
      const back = (await call('GET', `${V1}/conversations`, b.token)).body.items;
      expect(back).toHaveLength(1);
      expect(back[0]).toMatchObject({ archived: false, pinned: true });
    });
    it('rejects a mute time in the past and settings for conversations you are not in', async () => {
      const { b, conv } = await pair();
      const outsider = await h.student();
      expect((await call('PATCH', `${V1}/conversations/${conv}/settings`, b.token, { mutedUntil: new Date(Date.now() - 1000).toISOString() })).status).toBe(400);
      expect((await call('PATCH', `${V1}/conversations/${conv}/settings`, outsider.token, { pinned: true })).status).toBe(404);
    });
    it('marks a conversation unread until it is next read', async () => {
      const { a, b, conv } = await pair();
      await say(a, conv, 'hi');
      await call('POST', `${V1}/conversations/${conv}/read`, b.token, {});
      expect((await call('POST', `${V1}/conversations/${conv}/mark-unread`, b.token)).status).toBe(204);
      expect((await call('GET', `${V1}/conversations`, b.token)).body.items[0].markedUnread).toBe(true);
      await call('POST', `${V1}/conversations/${conv}/read`, b.token, {});
      expect((await call('GET', `${V1}/conversations`, b.token)).body.items[0].markedUnread).toBe(false);
    });
  });

  // ---------------------------------------------------------------- clear, pin, forward
  describe('clear chat, pinned message, forwarding', () => {
    it('clearing hides history for you only, including search and the list preview', async () => {
      const { a, b, conv } = await pair();
      await say(a, conv, 'old one');
      await say(b, conv, 'old two');
      expect((await call('POST', `${V1}/conversations/${conv}/clear`, b.token)).status).toBe(204);
      expect((await call('GET', `${V1}/conversations/${conv}/messages`, b.token)).body.items).toHaveLength(0);
      expect((await call('GET', `${V1}/messages/search?q=old`, b.token)).body.items).toHaveLength(0);
      expect((await call('GET', `${V1}/conversations`, b.token)).body.items[0].lastMessage).toBeNull();
      // A still has everything, and new messages reach B again.
      expect((await call('GET', `${V1}/conversations/${conv}/messages`, a.token)).body.items).toHaveLength(2);
      await say(a, conv, 'fresh');
      const after = await call('GET', `${V1}/conversations/${conv}/messages`, b.token);
      expect(after.body.items.map((i: { content: string }) => i.content)).toEqual(['fresh']);
      expect((await call('POST', `${V1}/conversations/${conv}/clear`, (await h.student()).token)).status).toBe(404);
    });

    it('anyone can pin in a direct chat; the pin shows up in the window and detail, and unsending drops it', async () => {
      const { a, b, conv } = await pair();
      const m = await say(a, conv, 'important');
      expect((await call('PUT', `${V1}/conversations/${conv}/pin`, b.token, { messageId: m.id })).status).toBe(204);
      expect((await call('GET', `${V1}/conversations/${conv}/messages`, a.token)).body.pinnedMessageId).toBe(m.id);
      expect((await call('GET', `${V1}/conversations/${conv}`, a.token)).body.pinnedMessageId).toBe(m.id);
      await call('DELETE', `${V1}/messages/${m.id}`, a.token);
      expect((await call('GET', `${V1}/conversations/${conv}/messages`, a.token)).body.pinnedMessageId).toBeNull();
      const m2 = await say(a, conv, 'second');
      await call('PUT', `${V1}/conversations/${conv}/pin`, a.token, { messageId: m2.id });
      expect((await call('DELETE', `${V1}/conversations/${conv}/pin`, b.token)).status).toBe(204);
      expect((await call('GET', `${V1}/conversations/${conv}`, a.token)).body.pinnedMessageId).toBeNull();
    });

    it('only owner and admins pin in a group, and only messages of that conversation', async () => {
      const [owner, member] = [await h.student(), await h.student()];
      const g = await call('POST', `${V1}/conversations`, owner.token, { type: 'group', title: 'Study', memberIds: [member.id] });
      expect([200, 201]).toContain(g.status);
      const m = await say(owner, g.body.id, 'rules');
      expect((await call('PUT', `${V1}/conversations/${g.body.id}/pin`, member.token, { messageId: m.id })).status).toBe(403);
      expect((await call('PUT', `${V1}/conversations/${g.body.id}/pin`, owner.token, { messageId: m.id })).status).toBe(204);
      const { a, conv: other } = await pair();
      const foreign = await say(a, other, 'elsewhere');
      expect((await call('PUT', `${V1}/conversations/${g.body.id}/pin`, owner.token, { messageId: foreign.id })).status).toBe(404);
    });

    it('forwards text as a new flagged message with no reply quote or reactions', async () => {
      const { a, b, conv } = await pair();
      const c = await h.student();
      const other = await call('POST', `${V1}/conversations`, b.token, { type: 'direct', userId: c.id });
      const m = await say(a, conv, 'share this');
      await call('PUT', `${V1}/messages/${m.id}/reaction`, b.token, { reaction: 'heart' });
      const res = await call('POST', `${V1}/messages/${m.id}/forward`, b.token, { conversationIds: [other.body.id] });
      expect(res.status).toBe(200);
      expect(res.body.forwarded).toBe(1);
      const history = await call('GET', `${V1}/conversations/${other.body.id}/messages`, c.token);
      expect(history.body.items).toHaveLength(1);
      expect(history.body.items[0]).toMatchObject({ content: 'share this', senderId: b.id, forwarded: true, replyTo: null, reactions: {} });
    });

    it('sends nothing when any target is not yours, and refuses messages you cannot see', async () => {
      const { a, b, conv } = await pair();
      const { conv: stranger } = await pair();
      const mine = await call('POST', `${V1}/conversations`, b.token, { type: 'direct', userId: (await h.student()).id });
      const m = await say(a, conv, 'hello');
      const res = await call('POST', `${V1}/messages/${m.id}/forward`, b.token, { conversationIds: [mine.body.id, stranger] });
      expect(res.status).toBe(404);
      expect((await call('GET', `${V1}/conversations/${mine.body.id}/messages`, b.token)).body.items).toHaveLength(0);
      const outsider = await h.student();
      expect((await call('POST', `${V1}/messages/${m.id}/forward`, outsider.token, { conversationIds: [mine.body.id] })).status).toBe(404);
      expect((await call('POST', `${V1}/messages/${m.id}/forward`, b.token, { conversationIds: [] })).status).toBe(400);
    });
  });

  // ---------------------------------------------------------------- Android-friendly routes
  describe('routes the Android app relies on', () => {
    it('accepts PUT as well as PATCH for editing a message and for conversation settings', async () => {
      const { a, b, conv } = await pair();
      const m = await say(a, conv, 'typo');
      const edited = await call('PUT', `${V1}/messages/${m.id}`, a.token, { content: 'fixed' });
      expect(edited.status).toBe(200);
      expect(edited.body.content).toBe('fixed');
      expect((await call('PATCH', `${V1}/messages/${m.id}`, a.token, { content: 'fixed again' })).status).toBe(200);
      expect((await call('PUT', `${V1}/conversations/${conv}/settings`, b.token, { pinned: true })).body.pinned).toBe(true);
    });
    it('the conversation detail shows how far each member has read', async () => {
      const { a, b, conv } = await pair();
      const m = await say(a, conv, 'seen?');
      const before = await call('GET', `${V1}/conversations/${conv}`, a.token);
      expect(before.body.members.find((x: { id: string }) => x.id === b.id).lastReadSeq).toBe(0);
      await call('POST', `${V1}/conversations/${conv}/read`, b.token, { upToSeq: m.seq });
      const after = await call('GET', `${V1}/conversations/${conv}`, a.token);
      expect(after.body.members.find((x: { id: string }) => x.id === b.id).lastReadSeq).toBe(m.seq);
      expect((await call('GET', `${V1}/conversations/${conv}`, (await h.student()).token)).status).toBe(404);
    });
    it('the conversation detail carries the viewer\'s own settings', async () => {
      const { b, conv } = await pair();
      await call('PATCH', `${V1}/conversations/${conv}/settings`, b.token, { pinned: true });
      const detail = await call('GET', `${V1}/conversations/${conv}`, b.token);
      expect(detail.body).toMatchObject({ pinned: true, archived: false, mutedUntil: null });
    });
  });

  // ---------------------------------------------------------------- reconnect sync and delivery
  describe('sync and delivery', () => {
    it('returns everything after a seq, oldest first, in pages', async () => {
      const { a, b, conv } = await pair();
      const first = await say(a, conv, 'm1');
      await say(a, conv, 'm2');
      await say(a, conv, 'm3');
      const page = await call('GET', `${V1}/conversations/${conv}/messages?after=${first.seq}&limit=1`, b.token);
      expect(page.body.items.map((i: { content: string }) => i.content)).toEqual(['m2']);
      expect(page.body.nextAfter).toBe(page.body.items[0].seq);
      const rest = await call('GET', `${V1}/conversations/${conv}/messages?after=${page.body.nextAfter}`, b.token);
      expect(rest.body.items.map((i: { content: string }) => i.content)).toEqual(['m3']);
      expect(rest.body.nextAfter).toBeNull();
      expect((await call('GET', `${V1}/conversations/${conv}/messages?after=0`, (await h.student()).token)).status).toBe(404);
    });
    it('messages sent while offline are marked delivered, and the sender told, when the recipient connects', async () => {
      const { a, b, conv } = await pair();
      const liveA = await connect(a.token);
      const m = await say(a, conv, 'while you were away');
      await connect(b.token);
      const ev = await waitFor(liveA.events, 'message:delivered', (e) => e.userId === b.id && Number(e.upToSeq) >= m.seq);
      expect(ev.conversationId).toBe(conv);
    });
    it('a device can acknowledge delivery over HTTP, but never beyond what exists', async () => {
      const { a, b, conv } = await pair();
      const m = await say(a, conv, 'hi');
      expect((await call('POST', `${V1}/conversations/${conv}/delivered`, b.token, { upToSeq: m.seq + 1000 })).status).toBe(204);
      const row = (await pool.query('select last_delivered_seq from conversation_members where conversation_id = $1 and user_id = $2', [conv, b.id])).rows[0];
      expect(Number(row.last_delivered_seq)).toBe(m.seq);
    });
  });

  // ---------------------------------------------------------------- search
  describe('message search', () => {
    it('finds messages only in conversations you belong to and treats % literally', async () => {
      const { a, b, conv } = await pair();
      const outsider = await h.student();
      await say(a, conv, 'lunch at noon');
      await say(a, conv, 'it is 100% done');
      const found = await call('GET', `${V1}/messages/search?q=lunch`, b.token);
      expect(found.body.items).toHaveLength(1);
      expect(found.body.items[0].content).toBe('lunch at noon');
      expect((await call('GET', `${V1}/messages/search?q=${encodeURIComponent('100%')}`, b.token)).body.items).toHaveLength(1);
      expect((await call('GET', `${V1}/messages/search?q=${encodeURIComponent('%%')}`, b.token)).body.items).toHaveLength(0);
      expect((await call('GET', `${V1}/messages/search?q=lunch`, outsider.token)).body.items).toHaveLength(0);
      expect((await call('GET', `${V1}/messages/search?q=lunch&conversationId=${conv}`, outsider.token)).status).toBe(404);
    });
    it('hides unsent messages and results from blocked people, and paginates', async () => {
      const { a, b, conv } = await pair();
      const gone = await say(a, conv, 'needle one');
      await say(a, conv, 'needle two');
      await say(a, conv, 'needle three');
      await call('DELETE', `${V1}/messages/${gone.id}`, a.token);
      const p1 = await call('GET', `${V1}/messages/search?q=needle&limit=1`, b.token);
      expect(p1.body.items).toHaveLength(1);
      const p2 = await call('GET', `${V1}/messages/search?q=needle&limit=1&before=${p1.body.nextBefore}`, b.token);
      expect(p2.body.items[0].content).toBe('needle two');
      expect(p2.body.nextBefore).toBeNull();
      await call('POST', `${V1}/users/${a.id}/block`, b.token);
      expect((await call('GET', `${V1}/messages/search?q=needle`, b.token)).body.items).toHaveLength(0);
    });
  });
});
