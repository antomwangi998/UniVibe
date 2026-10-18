import type { Pool } from 'pg';
import WebSocket from 'ws';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, type Student, url } from './helpers';

type Ev = { type: string; [k: string]: unknown };

describe.skipIf(!url)('messaging (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  let port = 0;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';
  const sockets: WebSocket[] = [];

  beforeAll(async () => {
    pool = createPool(url as string);
    await migrate(pool);
    await pool.query("insert into universities (id, name, email_domains) values ('other', 'Other University', array['other.example']) on conflict do nothing");
  });
  afterAll(async () => { await pool.end(); });
  beforeEach(async () => {
    h.make();
    await h.app().listen({ port: 0, host: '127.0.0.1' });
    port = (h.app().server.address() as { port: number }).port;
  });
  afterEach(async () => {
    sockets.splice(0).forEach((s) => s.terminate());
    await h.close();
  });

  it('read receipts off: the other person is not told, in live events or in conversation data', async () => {
    const a = await h.student();
    const b = await h.student();
    expect((await call('GET', `${V1}/me/privacy`, b.token)).body.readReceipts).toBe(true);
    expect((await call('PUT', `${V1}/me/privacy`, b.token, { readReceipts: false })).body.readReceipts).toBe(false);
    expect((await call('PUT', `${V1}/me/privacy`, b.token, { readReceipts: 'no' })).status).toBe(400);
    const conv = await direct(a, b);
    await call('POST', `${V1}/conversations/${conv}/messages`, a.token, { content: 'hi' });
    await call('POST', `${V1}/conversations/${conv}/delivered`, b.token, { upToSeq: 1_000_000 });
    const aSocket = await connect(a.token);
    expect((await call('POST', `${V1}/conversations/${conv}/read`, b.token, {})).status).toBe(204);
    await quiet();
    expect(aSocket.events.some((e) => e.type === 'message:read')).toBe(false);
    const row = (await call('GET', `${V1}/conversations`, a.token)).body.items.find((x: { id: string }) => x.id === conv);
    expect(row.lastMessage.delivery).toBe('delivered');
    const members = (await call('GET', `${V1}/conversations/${conv}`, a.token)).body.members;
    expect(members.find((m: { id: string }) => m.id === b.id).lastReadSeq).toBe(0);
    // b still has it marked read for themselves
    expect((await call('GET', `${V1}/conversations`, b.token)).body.items.find((x: { id: string }) => x.id === conv).unreadCount).toBe(0);
    // turning it back on shows read state again from the next read
    await call('PUT', `${V1}/me/privacy`, b.token, { readReceipts: true });
    expect((await call('GET', `${V1}/conversations`, a.token)).body.items.find((x: { id: string }) => x.id === conv).lastMessage.delivery).toBe('read');
  });

  /** Opens a socket, authenticates, and records every event so tests can wait for specific ones. */
  async function connect(token: string | null, authenticate = true) {
    const ws = new WebSocket(`ws://127.0.0.1:${port}${V1}/ws`);
    sockets.push(ws);
    const events: Ev[] = [];
    const closed = new Promise<number>((resolve) => ws.on('close', (code) => resolve(code)));
    ws.on('message', (d) => events.push(JSON.parse(d.toString())));
    await new Promise((resolve, reject) => { ws.on('open', resolve); ws.on('error', reject); });
    if (authenticate && token) {
      ws.send(JSON.stringify({ type: 'auth', token }));
      await waitFor(events, 'auth:ok');
    }
    return { ws, events, closed, send: (o: unknown) => ws.send(JSON.stringify(o)) };
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
  const quiet = (ms = 250) => new Promise((r) => setTimeout(r, ms));

  async function direct(a: Student, b: Student) {
    const res = await call('POST', `${V1}/conversations`, a.token, { type: 'direct', userId: b.id });
    expect([200, 201]).toContain(res.status);
    return res.body.id as string;
  }

  it('scenario C: direct chat, persistence, live delivery, read state, outsiders locked out', async () => {
    const a = await h.student();
    const b = await h.student();
    const outsider = await h.student();
    const bSocket = await connect(b.token);
    // 1: two permitted users open one direct conversation, from either side
    const first = await call('POST', `${V1}/conversations`, a.token, { type: 'direct', userId: b.id });
    expect(first.status).toBe(201);
    const again = await call('POST', `${V1}/conversations`, b.token, { type: 'direct', userId: a.id });
    expect(again.status).toBe(200);
    expect(again.body.id).toBe(first.body.id);
    const conv = first.body.id;
    // 2-3: one sends; it is persisted
    const sent = await call('POST', `${V1}/conversations/${conv}/messages`, a.token, { content: 'hello b', clientId: 'c1' });
    expect(sent.status).toBe(201);
    expect((await pool.query('select content from messages where id = $1', [sent.body.id])).rows[0].content).toBe('hello b');
    // 4: the recipient receives it live, and the sender is told it was delivered
    const live = await waitFor(bSocket.events, 'message:new');
    expect((live.message as { content: string }).content).toBe('hello b');
    // 5: unread count, then read state
    const list = await call('GET', `${V1}/conversations`, b.token);
    expect(list.body.items.find((c: { id: string }) => c.id === conv).unreadCount).toBe(1);
    expect((await call('GET', `${V1}/notifications`, b.token)).body.items.map((n: { type: string }) => n.type)).toContain('message');
    const aSocket = await connect(a.token);
    expect((await call('POST', `${V1}/conversations/${conv}/read`, b.token, {})).status).toBe(204);
    await waitFor(aSocket.events, 'message:read', (e) => e.userId === b.id);
    expect((await call('GET', `${V1}/conversations`, b.token)).body.items.find((c: { id: string }) => c.id === conv).unreadCount).toBe(0);
    // notification carries no message text
    const note = JSON.stringify((await call('GET', `${V1}/notifications`, b.token)).body);
    expect(note).not.toContain('hello b');
    // 6: an unrelated user cannot see, read, send, or learn the conversation exists
    expect((await call('GET', `${V1}/conversations/${conv}`, outsider.token)).status).toBe(404);
    expect((await call('GET', `${V1}/conversations/${conv}/messages`, outsider.token)).status).toBe(404);
    expect((await call('POST', `${V1}/conversations/${conv}/messages`, outsider.token, { content: 'hi' })).status).toBe(404);
    expect((await call('POST', `${V1}/conversations/${conv}/read`, outsider.token, {})).status).toBe(404);
    expect((await call('GET', `${V1}/conversations`, outsider.token)).body.items).toHaveLength(0);
  });

  it('chat list carries group members, member count and how far my last message got', async () => {
    const a = await h.student();
    const b = await h.student();
    const c = await h.student();
    const conv = await direct(a, b);
    const sent = await call('POST', `${V1}/conversations/${conv}/messages`, a.token, { content: 'hi' });
    const row = async (who: Student, id: string) => (await call('GET', `${V1}/conversations`, who.token)).body.items.find((x: { id: string }) => x.id === id);
    expect((await row(a, conv)).lastMessage.delivery).toBe('sent');
    expect((await row(b, conv)).lastMessage.delivery).toBeNull(); // only the sender sees progress
    expect((await call('POST', `${V1}/conversations/${conv}/delivered`, b.token, { upToSeq: 1_000_000 })).status).toBeLessThan(300);
    expect((await row(a, conv)).lastMessage.delivery).toBe('delivered');
    await call('POST', `${V1}/conversations/${conv}/read`, b.token, {});
    expect((await row(a, conv)).lastMessage.delivery).toBe('read');
    expect(sent.status).toBe(201);
    const g = await call('POST', `${V1}/conversations`, a.token, { type: 'group', title: 'Study group', memberIds: [b.id, c.id] });
    const gr = await row(a, g.body.id);
    expect(gr.memberCount).toBe(3);
    expect(gr.members.map((m: { id: string }) => m.id).sort()).toEqual([b.id, c.id].sort()); // never includes me
    expect((await row(a, conv)).members).toEqual([]); // direct chats carry no member list
    const view = await call('GET', `${V1}/conversations/${g.body.id}`, a.token);
    expect(view.body.members.every((m: { lastDeliveredSeq: number }) => typeof m.lastDeliveredSeq === 'number')).toBe(true);
  });

  it('a retried send with the same clientId creates one message', async () => {
    const a = await h.student();
    const b = await h.student();
    const conv = await direct(a, b);
    const results = await Promise.all([1, 2, 3, 4].map(() => call('POST', `${V1}/conversations/${conv}/messages`, a.token, { content: 'once', clientId: 'retry-1' })));
    expect(new Set(results.map((r) => r.body.id)).size).toBe(1);
    expect(Number((await pool.query('select count(*) from messages where conversation_id = $1', [conv])).rows[0].count)).toBe(1);
  });

  it('paginates history newest-first without repeats and validates payloads', async () => {
    const a = await h.student();
    const b = await h.student();
    const conv = await direct(a, b);
    await pool.query("insert into privacy_settings (user_id) values ($1) on conflict do nothing", [a.id]);
    for (let i = 0; i < 7; i++) {
      await pool.query("insert into messages (conversation_id, sender_id, content) values ($1, $2, $3)", [conv, i % 2 ? a.id : b.id, `m${i}`]);
    }
    const seen: string[] = [];
    let before: number | null = null;
    do {
      const res: { body: { items: { id: string; content: string }[]; nextBefore: number | null } } =
        await call('GET', `${V1}/conversations/${conv}/messages?limit=3${before ? `&before=${before}` : ''}`, a.token);
      res.body.items.forEach((m) => seen.push(m.content));
      before = res.body.nextBefore;
    } while (before);
    expect(seen).toEqual(['m6', 'm5', 'm4', 'm3', 'm2', 'm1', 'm0']);
    expect((await call('POST', `${V1}/conversations/${conv}/messages`, a.token, { content: 'x'.repeat(4001) })).status).toBe(400);
    expect((await call('POST', `${V1}/conversations/${conv}/messages`, a.token, { content: '   ' })).status).toBe(400);
    expect((await call('POST', `${V1}/conversations/${conv}/messages`, a.token, { content: 'x', senderId: b.id })).status).toBe(400);
    expect((await call('POST', `${V1}/conversations/${conv}/messages`, a.token, { content: 'x', replyToId: '00000000-0000-4000-8000-000000000000' })).status).toBe(422);
  });

  it('applies blocking and messaging preferences, identically for both', async () => {
    const a = await h.student();
    const b = await h.student();
    const conv = await direct(a, b);
    expect((await call('POST', `${V1}/conversations/${conv}/messages`, a.token, { content: 'before block' })).status).toBe(201);
    await call('POST', `${V1}/users/${a.id}/block`, b.token);
    const blockedSend = await call('POST', `${V1}/conversations/${conv}/messages`, a.token, { content: 'after block' });
    expect(blockedSend.status).toBe(403);
    expect((await call('POST', `${V1}/conversations/${conv}/messages`, b.token, { content: 'blocker too' })).status).toBe(403);
    expect((await call('POST', `${V1}/conversations`, a.token, { type: 'direct', userId: b.id })).status).toBe(404);
    expect((await call('GET', `${V1}/conversations`, a.token)).body.items).toHaveLength(0);
    expect((await call('GET', `${V1}/conversations`, b.token)).body.items).toHaveLength(0);
    await call('DELETE', `${V1}/users/${a.id}/block`, b.token);
    // message_permission = nobody / followers
    const c = await h.student();
    await call('PUT', `${V1}/me/privacy`, c.token, { messagePermission: 'nobody' });
    const denied = await call('POST', `${V1}/conversations`, a.token, { type: 'direct', userId: c.id });
    expect(denied.status).toBe(403);
    expect(denied.body.error).toBe('cannot_message');
    await call('PUT', `${V1}/me/privacy`, c.token, { messagePermission: 'followers' });
    expect((await call('POST', `${V1}/conversations`, a.token, { type: 'direct', userId: c.id })).status).toBe(403);
    await call('PUT', `/students/${c.id}/follow`, a.token);
    expect((await call('POST', `${V1}/conversations`, a.token, { type: 'direct', userId: c.id })).status).toBe(201);
    // other universities cannot be messaged
    const out = await h.student('other.example', 'other');
    expect((await call('POST', `${V1}/conversations`, a.token, { type: 'direct', userId: out.id })).status).toBe(404);
  });

  it('edits within the window, unsends for everyone, and protects other people\'s messages', async () => {
    const a = await h.student();
    const b = await h.student();
    const conv = await direct(a, b);
    const bSocket = await connect(b.token);
    const m = (await call('POST', `${V1}/conversations/${conv}/messages`, a.token, { content: 'original' })).body.id;
    expect((await call('PATCH', `${V1}/messages/${m}`, b.token, { content: 'hijack' })).status).toBe(404);
    expect((await call('DELETE', `${V1}/messages/${m}`, b.token)).status).toBe(404);
    const edited = await call('PATCH', `${V1}/messages/${m}`, a.token, { content: 'fixed' });
    expect(edited.body.content).toBe('fixed');
    expect(edited.body.editedAt).not.toBeNull();
    await waitFor(bSocket.events, 'message:updated');
    await pool.query("update messages set created_at = now() - interval '16 minutes' where id = $1", [m]);
    expect((await call('PATCH', `${V1}/messages/${m}`, a.token, { content: 'too late' })).status).toBe(404);
    expect((await call('DELETE', `${V1}/messages/${m}`, a.token)).status).toBe(204);
    await waitFor(bSocket.events, 'message:deleted');
    const history = (await call('GET', `${V1}/conversations/${conv}/messages`, b.token)).body.items;
    expect(history[0].deleted).toBe(true);
    expect(history[0].content).toBeNull();
    expect((await pool.query('select content from messages where id = $1', [m])).rows[0].content).toBe('');
    expect((await call('DELETE', `${V1}/messages/${m}`, a.token)).status).toBe(404);
  });

  it('attaches only the sender\'s own message images', async () => {
    const a = await h.student();
    const b = await h.student();
    const conv = await direct(a, b);
    const img = await h.image(a.token, 'message');
    expect((await call('POST', `${V1}/conversations/${conv}/messages`, b.token, { mediaIds: [img.id] })).status).toBe(422);
    const ok = await call('POST', `${V1}/conversations/${conv}/messages`, a.token, { mediaIds: [img.id] });
    expect(ok.status).toBe(201);
    expect(ok.body.media[0].url).toContain('https://files.test/');
    expect((await call('POST', `${V1}/conversations/${conv}/messages`, a.token, { mediaIds: [img.id] })).status).toBe(422); // already used
    const post = await h.image(a.token, 'post');
    expect((await call('POST', `${V1}/conversations/${conv}/messages`, a.token, { mediaIds: [post.id] })).status).toBe(422); // wrong purpose
  });

  describe('groups', () => {
    it('creates groups, skips people who cannot be added, and enforces roles', async () => {
      const owner = await h.student();
      const m1 = await h.student();
      const m2 = await h.student();
      const blocker = await h.student();
      const outsider = await h.student('other.example', 'other');
      await call('POST', `${V1}/users/${owner.id}/block`, blocker.token);
      const res = await call('POST', `${V1}/conversations`, owner.token, { type: 'group', title: 'Study', memberIds: [m1.id, m2.id, blocker.id, outsider.id] });
      expect(res.status).toBe(201);
      expect(res.body.members.map((m: { id: string }) => m.id).sort()).toEqual([owner.id, m1.id, m2.id].sort());
      const conv = res.body.id;
      expect(res.body.members.find((m: { id: string }) => m.id === owner.id).role).toBe('owner');
      // invited members get a notification
      expect((await call('GET', `${V1}/notifications`, m1.token)).body.items.map((n: { type: string }) => n.type)).toContain('group_invite');
      // plain members cannot add or remove
      const extra = await h.student();
      expect((await call('POST', `${V1}/conversations/${conv}/members`, m1.token, { userIds: [extra.id] })).status).toBe(403);
      expect((await call('DELETE', `${V1}/conversations/${conv}/members/${m2.id}`, m1.token)).status).toBe(403);
      // owner adds; membership cap and duplicates are safe
      expect((await call('POST', `${V1}/conversations/${conv}/members`, owner.token, { userIds: [extra.id, m1.id] })).body.added).toBe(1);
      // everyone in the group can message, non-members cannot
      expect((await call('POST', `${V1}/conversations/${conv}/messages`, m2.token, { content: 'hi all' })).status).toBe(201);
      const nonMember = await h.student();
      expect((await call('POST', `${V1}/conversations/${conv}/messages`, nonMember.token, { content: 'let me in' })).status).toBe(404);
      // an admin can remove plain members but not the owner
      await pool.query("update conversation_members set role = 'admin' where conversation_id = $1 and user_id = $2", [conv, m1.id]);
      expect((await call('DELETE', `${V1}/conversations/${conv}/members/${owner.id}`, m1.token)).status).toBe(403);
      expect((await call('DELETE', `${V1}/conversations/${conv}/members/${extra.id}`, m1.token)).status).toBe(204);
      expect((await call('GET', `${V1}/conversations/${conv}`, extra.token)).status).toBe(404);
      // owner leaves: ownership passes to the admin
      expect((await call('DELETE', `${V1}/conversations/${conv}/members/${owner.id}`, owner.token)).status).toBe(204);
      const after = await call('GET', `${V1}/conversations/${conv}`, m1.token);
      expect(after.body.members.find((m: { id: string }) => m.id === m1.id).role).toBe('owner');
      // last person leaving deletes the group
      await call('DELETE', `${V1}/conversations/${conv}/members/${m2.id}`, m2.token);
      await call('DELETE', `${V1}/conversations/${conv}/members/${m1.id}`, m1.token);
      expect((await pool.query('select 1 from conversations where id = $1', [conv])).rowCount).toBe(0);
    });

    it('rejects bad group input and direct-chat member changes', async () => {
      const a = await h.student();
      const b = await h.student();
      expect((await call('POST', `${V1}/conversations`, a.token, { type: 'group', title: '', memberIds: [b.id] })).status).toBe(400);
      expect((await call('POST', `${V1}/conversations`, a.token, { type: 'group', title: 'x', memberIds: [] })).status).toBe(400);
      expect((await call('POST', `${V1}/conversations`, a.token, { type: 'group', title: 'x', memberIds: [a.id] })).status).toBe(422);
      const conv = await direct(a, b);
      expect((await call('POST', `${V1}/conversations/${conv}/members`, a.token, { userIds: [b.id] })).status).toBe(400);
    });
  });

  describe('websocket', () => {
    it('closes connections that never authenticate or send a bad token; refuses unauthenticated operations', async () => {
      const a = await h.student();
      const anon = await connect(null, false);
      anon.send({ type: 'typing:start', conversationId: '00000000-0000-4000-8000-000000000000' });
      expect(await anon.closed).toBe(4401);
      const bad = await connect(null, false);
      bad.send({ type: 'auth', token: 'not-a-real-token-at-all' });
      await waitFor(bad.events, 'error');
      expect(bad.events.find((e) => e.type === 'auth:ok')).toBeUndefined();
      void a;
    });

    it('authorizes every operation: non-members cannot send, read or type in a conversation', async () => {
      const a = await h.student();
      const b = await h.student();
      const intruder = await h.student();
      const conv = await direct(a, b);
      const aSocket = await connect(a.token);
      const intruderSocket = await connect(intruder.token);
      intruderSocket.send({ type: 'message:send', conversationId: conv, content: 'sneaky', clientId: 'x' });
      await waitFor(intruderSocket.events, 'error', (e) => e.code === 'not_found');
      intruderSocket.send({ type: 'typing:start', conversationId: conv });
      intruderSocket.send({ type: 'message:read', conversationId: conv });
      await quiet();
      expect(Number((await pool.query('select count(*) from messages where conversation_id = $1', [conv])).rows[0].count)).toBe(0);
      expect(aSocket.events.find((e) => e.type === 'typing:update')).toBeUndefined();
      expect(aSocket.events.find((e) => e.type === 'message:new')).toBeUndefined();
    });

    it('sends over the socket with ack, delivers to the other side only, and tells the sender it was delivered', async () => {
      const a = await h.student();
      const b = await h.student();
      const c = await h.student();
      const conv = await direct(a, b);
      const aSocket = await connect(a.token);
      const bSocket = await connect(b.token);
      const cSocket = await connect(c.token);
      aSocket.send({ type: 'message:send', conversationId: conv, content: 'over ws', clientId: 'ws-1' });
      const ack = await waitFor(aSocket.events, 'message:ack');
      expect(ack.clientId).toBe('ws-1');
      await waitFor(bSocket.events, 'message:new');
      await waitFor(aSocket.events, 'message:delivered', (e) => e.userId === b.id);
      await quiet();
      expect(cSocket.events.find((e) => e.type === 'message:new')).toBeUndefined();
      aSocket.send({ type: 'message:send', conversationId: conv, content: '' });
      await waitFor(aSocket.events, 'error', (e) => e.code === 'validation' || e.code === 'bad_request');
    });

    it('typing is temporary, rate limited, scoped to members, and cleared on disconnect', async () => {
      const a = await h.student();
      const b = await h.student();
      const conv = await direct(a, b);
      const aSocket = await connect(a.token);
      const bSocket = await connect(b.token);
      aSocket.send({ type: 'typing:start', conversationId: conv });
      await waitFor(bSocket.events, 'typing:update', (e) => e.typing === true);
      aSocket.send({ type: 'typing:start', conversationId: conv }); // inside the 2 s limit: ignored
      await quiet();
      expect(bSocket.events.filter((e) => e.type === 'typing:update' && e.typing === true)).toHaveLength(1);
      aSocket.ws.close();
      await waitFor(bSocket.events, 'typing:update', (e) => e.typing === false);
      expect((await pool.query("select count(*) from information_schema.columns where column_name like '%typing%'")).rows[0].count).toBe('0'); // never persisted
    });

    it('presence: multiple devices, offline only after the last one, and respects the privacy switch', async () => {
      const a = await h.student();
      const b = await h.student();
      await direct(a, b);
      const bSocket = await connect(b.token);
      const a1 = await connect(a.token);
      await waitFor(bSocket.events, 'presence:update', (e) => e.userId === a.id && e.online === true);
      const a2 = await connect(a.token);
      a1.ws.close();
      await quiet(400);
      expect(bSocket.events.find((e) => e.type === 'presence:update' && e.online === false)).toBeUndefined(); // a2 still connected
      a2.ws.close();
      await waitFor(bSocket.events, 'presence:update', (e) => e.userId === a.id && e.online === false);
      // an invisible user never produces presence events
      const c = await h.student();
      await direct(b, c);
      await call('PUT', `${V1}/me/privacy`, c.token, { showOnline: false });
      const before = bSocket.events.length;
      const cSocket = await connect(c.token);
      cSocket.ws.close();
      await quiet(400);
      expect(bSocket.events.slice(before).find((e) => e.type === 'presence:update' && e.userId === c.id)).toBeUndefined();
      const view = await call('GET', `${V1}/conversations/${(await direct(b, c))}`, b.token);
      expect(view.body.members.find((m: { id: string }) => m.id === c.id).online).toBeNull();
    });

    it('stops serving a suspended user mid-connection, and rejects oversized frames', async () => {
      const a = await h.student();
      const b = await h.student();
      const conv = await direct(a, b);
      const aSocket = await connect(a.token);
      await pool.query("update users set suspended_until = now() + interval '1 hour' where id = $1", [a.id]);
      aSocket.send({ type: 'message:send', conversationId: conv, content: 'still here?' });
      expect(await aSocket.closed).toBe(4401);
      expect(Number((await pool.query('select count(*) from messages where conversation_id = $1', [conv])).rows[0].count)).toBe(0);
      const big = await connect(b.token);
      big.send({ type: 'message:send', conversationId: conv, content: 'x'.repeat(20 * 1024) });
      expect(await big.closed).toBe(1009);
    });
  });

  it('requires authentication on every REST route', async () => {
    for (const [m, p] of [['GET', '/conversations'], ['POST', '/conversations'], ['GET', '/conversations/x'], ['POST', '/conversations/x/messages'], ['PATCH', '/messages/x'], ['DELETE', '/messages/x']] as const) {
      expect((await call(m, `${V1}${p}`)).status).toBe(401);
    }
  });
});
