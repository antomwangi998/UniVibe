import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { Hub } from '../src/realtime';
import { harness, url } from './helpers';

/** Stands in for a connected WebSocket: records every event the server pushes to this person. */
function fakeSocket() {
  const events: { type: string; [k: string]: unknown }[] = [];
  const ws = { OPEN: 1, readyState: 1, send: (p: string) => { events.push(JSON.parse(p)); }, close: () => {} };
  return { ws: ws as unknown as import('ws').WebSocket, events };
}

describe.skipIf(!url)('community channels (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  let hub: Hub;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';
  let n = 0;
  const uniq = (p: string) => `${p} ${Date.now().toString(36)}${n++}`;

  beforeAll(async () => { pool = createPool(url as string); await migrate(pool); });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { hub = new Hub(); h.make(hub); });
  afterEach(async () => { await h.close(); });

  const base = (over: object = {}) => ({ name: uniq('Chess Club'), description: 'A place for chess players on campus.', category: 'clubs', ...over });
  const createCommunity = async (token: string, over: object = {}) => {
    const r = await call('POST', `${V1}/communities`, token, base(over));
    expect(r.status, JSON.stringify(r.body)).toBe(201);
    return r.body as { id: string };
  };
  const join = async (communityId: string, token: string) => expect((await call('PUT', `${V1}/communities/${communityId}/membership`, token)).status).toBe(200);

  /** A public community with an owner, a moderator and an ordinary member, plus its two starter channels. */
  async function world() {
    const owner = await h.student();
    const mod = await h.student();
    const member = await h.student();
    const outsider = await h.student();
    const c = await createCommunity(owner.token);
    await join(c.id, mod.token);
    await join(c.id, member.token);
    expect((await call('PATCH', `${V1}/communities/${c.id}/members/${mod.id}/role`, owner.token, { role: 'moderator' })).status).toBe(200);
    const list = await call('GET', `${V1}/communities/${c.id}/channels`, owner.token);
    expect(list.status, JSON.stringify(list.body)).toBe(200);
    const general = list.body.channels.find((x: { name: string }) => x.name === 'general');
    const announcements = list.body.channels.find((x: { name: string }) => x.name === 'announcements');
    const chan = (id: string) => `${V1}/communities/${c.id}/channels/${id}`;
    return { owner, mod, member, outsider, c, general, announcements, chan };
  }

  it('new communities get announcements and general; members see them, outsiders and non-members do not', async () => {
    const w = await world();
    expect(w.general.type).toBe('text');
    expect(w.announcements.type).toBe('announcement');
    expect(w.announcements.postRole).toBe('staff');
    expect((await call('GET', `${V1}/communities/${w.c.id}/channels`, w.member.token)).status).toBe(200);
    // Public community, but its discussions are members-only.
    const pub = await call('GET', `${V1}/communities/${w.c.id}/channels`, w.outsider.token);
    expect(pub.status).toBe(403);
    expect(pub.body.error).toBe('not_a_member');
    // A private community does not even confirm itself to outsiders.
    const priv = await createCommunity(w.owner.token, { visibility: 'private', membershipPolicy: 'approval' });
    expect((await call('GET', `${V1}/communities/${priv.id}/channels`, w.outsider.token)).status).toBe(404);
  });

  it('messages: send is idempotent per clientId, pages newest-first, and catches up after a reconnect', async () => {
    const w = await world();
    const post = (text: string, clientId?: string) => call('POST', `${w.chan(w.general.id)}/messages`, w.member.token, { content: text, clientId });
    const first = await post('hello', 'k1');
    expect(first.status).toBe(201);
    const retry = await post('hello', 'k1');
    expect(retry.status).toBe(200);
    expect(retry.body.id).toBe(first.body.id);
    for (let i = 2; i <= 5; i++) expect((await post(`m${i}`)).status).toBe(201);
    expect((await pool.query('select count(*)::int as n from community_channel_messages where channel_id = $1', [w.general.id])).rows[0].n).toBe(5);

    const page1 = await call('GET', `${w.chan(w.general.id)}/messages?limit=2`, w.owner.token);
    expect(page1.body.items.map((m: { content: string }) => m.content)).toEqual(['m5', 'm4']);
    expect(page1.body.nextCursor).toBeTruthy();
    const page2 = await call('GET', `${w.chan(w.general.id)}/messages?limit=2&cursor=${page1.body.nextCursor}`, w.owner.token);
    expect(page2.body.items.map((m: { content: string }) => m.content)).toEqual(['m3', 'm2']);
    const page3 = await call('GET', `${w.chan(w.general.id)}/messages?limit=2&cursor=${page2.body.nextCursor}`, w.owner.token);
    expect(page3.body.items.map((m: { content: string }) => m.content)).toEqual(['hello']);
    expect(page3.body.nextCursor).toBeNull();

    const seqOfM3 = page2.body.items[0].seq as number;
    const missed = await call('GET', `${w.chan(w.general.id)}/messages?afterSeq=${seqOfM3}`, w.owner.token);
    expect(missed.body.items.map((m: { content: string }) => m.content)).toEqual(['m4', 'm5']); // oldest first
    expect(missed.body.hasMore).toBe(false);
  });

  it('concurrent sends get distinct, gap-free message numbers', async () => {
    const w = await world();
    const results = await Promise.all(Array.from({ length: 8 }, (_, i) => call('POST', `${w.chan(w.general.id)}/messages`, w.member.token, { content: `c${i}`, clientId: `c${i}` })));
    for (const r of results) expect(r.status, JSON.stringify(r.body)).toBe(201);
    const seqs = (await pool.query('select seq::int as s from community_channel_messages where channel_id = $1 order by seq', [w.general.id])).rows.map((r) => r.s);
    expect(seqs).toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
  });

  it('unread counts follow the read marker and never count your own messages', async () => {
    const w = await world();
    await call('POST', `${w.chan(w.general.id)}/messages`, w.member.token, { content: 'one' });
    await call('POST', `${w.chan(w.general.id)}/messages`, w.member.token, { content: 'two' });
    const unread = async (token: string) => (await call('GET', `${V1}/communities/${w.c.id}/channels`, token)).body.channels.find((x: { id: string }) => x.id === w.general.id).unreadCount;
    expect(await unread(w.owner.token)).toBe(2);
    expect(await unread(w.member.token)).toBe(0);
    expect((await call('POST', `${w.chan(w.general.id)}/read`, w.owner.token, {})).status).toBe(200);
    expect(await unread(w.owner.token)).toBe(0);
  });

  it('channel management: members cannot, moderators can; only the owner deletes; the type is fixed; names are unique', async () => {
    const w = await world();
    const body = { name: 'Programming Help', description: 'Ask coding questions.', type: 'qa' };
    expect((await call('POST', `${V1}/communities/${w.c.id}/channels`, w.member.token, body)).status).toBe(403);
    const made = await call('POST', `${V1}/communities/${w.c.id}/channels`, w.mod.token, body);
    expect(made.status, JSON.stringify(made.body)).toBe(201);
    expect(made.body.name).toBe('programming-help');
    expect((await call('POST', `${V1}/communities/${w.c.id}/channels`, w.mod.token, body)).status).toBe(409);
    expect((await call('PATCH', `${w.chan(made.body.id)}`, w.mod.token, { type: 'text' })).status).toBe(400); // type is not editable (strict schema)
    expect((await call('PATCH', `${w.chan(made.body.id)}`, w.member.token, { locked: true })).status).toBe(403);
    expect((await call('DELETE', `${w.chan(made.body.id)}`, w.mod.token)).status).toBe(403);
    expect((await call('DELETE', `${w.chan(made.body.id)}`, w.owner.token)).status).toBe(204);
    expect((await call('GET', `${w.chan(made.body.id)}/messages`, w.owner.token)).status).toBe(404);
  });

  it('announcement channel: only staff announce (and need a title); members can react; direct API cannot bypass', async () => {
    const w = await world();
    const url = `${w.chan(w.announcements.id)}/messages`;
    const denied = await call('POST', url, w.member.token, { title: 'Hi', content: 'sneaky' });
    expect(denied.status).toBe(403);
    expect(denied.body.error).toBe('staff_only');
    expect((await call('POST', url, w.mod.token, { content: 'no title' })).status).toBe(400);
    const ok = await call('POST', url, w.mod.token, { title: 'Exam week', content: 'Rooms are posted.' });
    expect(ok.status, JSON.stringify(ok.body)).toBe(201);
    expect((await call('PUT', `${w.chan(w.announcements.id)}/messages/${ok.body.id}/reactions/like`, w.member.token)).status).toBe(200);
    // Replies are off by default in announcement channels.
    expect((await call('POST', url, w.member.token, { content: 'thanks', replyToId: ok.body.id })).status).toBe(403);
    // The announcement queues one notification job; members (not the author) are notified when it runs.
    const job = await pool.query("select 1 from jobs where kind = 'channel.announce' and payload->>'messageId' = $1", [ok.body.id]);
    expect(job.rowCount).toBe(1);
  });

  it('locked channel: read-only for members, still writable for staff; reactions stay open', async () => {
    const w = await world();
    const m = await call('POST', `${w.chan(w.general.id)}/messages`, w.mod.token, { content: 'rules' });
    expect((await call('PATCH', w.chan(w.general.id), w.mod.token, { locked: true })).status).toBe(200);
    const denied = await call('POST', `${w.chan(w.general.id)}/messages`, w.member.token, { content: 'hi' });
    expect(denied.status).toBe(403);
    expect(denied.body.error).toBe('channel_locked');
    expect((await call('POST', `${w.chan(w.general.id)}/messages`, w.mod.token, { content: 'still here' })).status).toBe(201);
    expect((await call('PUT', `${w.chan(w.general.id)}/messages/${m.body.id}/reactions/heart`, w.member.token)).status).toBe(200);
  });

  it('replies, threads and deletion: tombstone keeps replies attached; members cannot delete others', async () => {
    const w = await world();
    const root = await call('POST', `${w.chan(w.general.id)}/messages`, w.member.token, { content: 'who has notes?' });
    const r1 = await call('POST', `${w.chan(w.general.id)}/messages`, w.owner.token, { content: 'me', replyToId: root.body.id });
    expect(r1.status).toBe(201);
    expect(r1.body.replyTo.id).toBe(root.body.id);
    expect(r1.body.replyTo.preview).toBe('who has notes?');
    // The reply header needs the original author's name without a second request.
    expect(typeof r1.body.replyTo.authorName).toBe('string');
    expect(r1.body.replyTo.authorName.length).toBeGreaterThan(0);
    // A reply cannot point at a message in another channel.
    const other = await call('POST', `${w.chan(w.announcements.id)}/messages`, w.mod.token, { title: 'x', content: 'y' });
    expect((await call('POST', `${w.chan(w.general.id)}/messages`, w.owner.token, { content: 'bad', replyToId: other.body.id })).status).toBe(422);

    const thread = await call('GET', `${w.chan(w.general.id)}/messages/${root.body.id}/thread`, w.owner.token);
    expect(thread.body.root.replyCount).toBe(1);
    expect(thread.body.replies.map((m: { content: string }) => m.content)).toEqual(['me']);

    expect((await call('DELETE', `${w.chan(w.general.id)}/messages/${r1.body.id}`, w.member.token)).status).toBe(403); // not theirs, not staff
    expect((await call('DELETE', `${w.chan(w.general.id)}/messages/${root.body.id}`, w.member.token)).status).toBe(204); // their own
    const after = await call('GET', `${w.chan(w.general.id)}/messages/${root.body.id}/thread`, w.owner.token);
    expect(after.body.root.deleted).toBe(true);
    expect(after.body.root.content).toBeNull();
    expect(after.body.replies).toHaveLength(1); // the reply survives
    const refreshed = await call('GET', `${w.chan(w.general.id)}/messages?limit=10`, w.owner.token);
    const reply = refreshed.body.items.find((m: { id: string }) => m.id === r1.body.id);
    expect(reply.replyTo.deleted).toBe(true);
    expect(reply.replyTo.preview).toBeNull();
    expect((await call('POST', `${w.chan(w.general.id)}/messages`, w.owner.token, { content: 'late', replyToId: root.body.id })).status).toBe(422);
    // Staff can delete anyone's message.
    expect((await call('DELETE', `${w.chan(w.general.id)}/messages/${r1.body.id}`, w.mod.token)).status).toBe(204);
  });

  it('editing: only the author, shows an edited marker', async () => {
    const w = await world();
    const m = await call('POST', `${w.chan(w.general.id)}/messages`, w.member.token, { content: 'typo' });
    expect((await call('PATCH', `${w.chan(w.general.id)}/messages/${m.body.id}`, w.mod.token, { content: 'hax' })).status).toBe(404);
    const edited = await call('PATCH', `${w.chan(w.general.id)}/messages/${m.body.id}`, w.member.token, { content: 'fixed' });
    expect(edited.status).toBe(200);
    expect(edited.body.content).toBe('fixed');
    expect(edited.body.editedAt).toBeTruthy();
  });

  it('reactions: the same reaction twice is stored once, different ones coexist, removal works', async () => {
    const w = await world();
    const m = await call('POST', `${w.chan(w.general.id)}/messages`, w.owner.token, { content: 'react to me' });
    const put = (r: string, t: string) => call('PUT', `${w.chan(w.general.id)}/messages/${m.body.id}/reactions/${r}`, t);
    await Promise.all([put('heart', w.member.token), put('heart', w.member.token), put('heart', w.member.token)]);
    expect((await put('fire', w.member.token)).status).toBe(200);
    const both = await put('heart', w.mod.token);
    expect(both.body.reactions).toEqual({ heart: 2, fire: 1 });
    expect((await pool.query('select count(*)::int as n from community_channel_reactions where message_id = $1 and user_id = $2', [m.body.id, w.member.id])).rows[0].n).toBe(2);
    const gone = await call('DELETE', `${w.chan(w.general.id)}/messages/${m.body.id}/reactions/heart`, w.member.token);
    expect(gone.body.reactions).toEqual({ heart: 1, fire: 1 });
    expect(gone.body.myReactions).toEqual(['fire']);
    expect((await put('thumbsup', w.member.token)).status).toBe(400);
  });

  it('pins: members cannot pin (even by calling the API directly), staff can, the pin list works', async () => {
    const w = await world();
    const m = await call('POST', `${w.chan(w.general.id)}/messages`, w.member.token, { content: 'useful link' });
    const pinUrl = `${w.chan(w.general.id)}/messages/${m.body.id}/pin`;
    expect((await call('PUT', pinUrl, w.member.token)).status).toBe(403);
    const pinned = await call('PUT', pinUrl, w.mod.token);
    expect(pinned.status).toBe(200);
    expect(pinned.body.pinned).toBe(true);
    expect((await call('GET', `${w.chan(w.general.id)}/pins`, w.member.token)).body.items.map((x: { id: string }) => x.id)).toEqual([m.body.id]);
    expect((await call('DELETE', pinUrl, w.member.token)).status).toBe(403);
    expect((await call('DELETE', pinUrl, w.owner.token)).body.pinned).toBe(false);
    // Deleting a pinned message unpins it.
    await call('PUT', pinUrl, w.mod.token);
    await call('DELETE', `${w.chan(w.general.id)}/messages/${m.body.id}`, w.member.token);
    expect((await call('GET', `${w.chan(w.general.id)}/pins`, w.member.token)).body.items).toHaveLength(0);
  });

  it('question and answer: titled questions, answers in the thread, accepting by author or staff only', async () => {
    const w = await world();
    const qa = await call('POST', `${V1}/communities/${w.c.id}/channels`, w.owner.token, { name: 'help', type: 'qa' });
    expect(qa.status).toBe(201);
    const u = `${w.chan(qa.body.id)}/messages`;
    expect((await call('POST', u, w.member.token, { content: 'no title' })).status).toBe(400);
    const q = await call('POST', u, w.member.token, { title: 'How do coroutines work?', content: 'Basics please.' });
    expect(q.status, JSON.stringify(q.body)).toBe(201);
    const a1 = await call('POST', u, w.owner.token, { content: 'They are lightweight threads.', replyToId: q.body.id });
    expect(a1.status).toBe(201);
    // An answer to an answer is refused: answers belong to the question.
    expect((await call('POST', u, w.mod.token, { content: 'nested', replyToId: a1.body.id })).status).toBe(422);
    // The timeline shows questions only; the thread shows answers.
    expect((await call('GET', u, w.member.token)).body.items.map((m: { id: string }) => m.id)).toEqual([q.body.id]);
    const acceptUrl = `${w.chan(qa.body.id)}/messages/${q.body.id}/accepted`;
    expect((await call('PUT', acceptUrl, w.outsider.token, { answerId: a1.body.id })).status).toBe(404); // not a member: the channel does not exist for them
    expect((await call('PUT', acceptUrl, w.mod.token, { answerId: q.body.id })).status).toBe(422); // not an answer
    const accepted = await call('PUT', acceptUrl, w.member.token, { answerId: a1.body.id }); // the asker
    expect(accepted.status, JSON.stringify(accepted.body)).toBe(200);
    expect(accepted.body.acceptedAnswerId).toBe(a1.body.id);
    // Another ordinary member cannot change it.
    const other = await h.student();
    await join(w.c.id, other.token);
    expect((await call('DELETE', acceptUrl, other.token)).status).toBe(403);
    // Deleting the accepted answer makes the question unresolved again.
    await call('DELETE', `${w.chan(qa.body.id)}/messages/${a1.body.id}`, w.owner.token);
    const thread = await call('GET', `${w.chan(qa.body.id)}/messages/${q.body.id}/thread`, w.member.token);
    expect(thread.body.root.acceptedAnswerId).toBeNull();
  });

  it('losing access closes the channel at once: leaving, suspension and staff-only channels', async () => {
    const w = await world();
    const m = await h.student();
    await join(w.c.id, m.token);
    expect((await call('POST', `${w.chan(w.general.id)}/messages`, m.token, { content: 'before' })).status).toBe(201);
    expect((await call('PATCH', `${V1}/communities/${w.c.id}/members/${m.id}/status`, w.owner.token, { status: 'suspended' })).status).toBe(200);
    expect((await call('GET', `${w.chan(w.general.id)}/messages`, m.token)).status).toBe(404);
    expect((await call('POST', `${w.chan(w.general.id)}/messages`, m.token, { content: 'after' })).status).toBe(404);
    expect((await call('GET', `${w.chan(w.general.id)}/search?q=before`, m.token)).status).toBe(404);
    // A staff-only channel is invisible to members, in the list and by id.
    const staffOnly = await call('POST', `${V1}/communities/${w.c.id}/channels`, w.owner.token, { name: 'mods', viewRole: 'staff' });
    expect(staffOnly.status).toBe(201);
    const list = await call('GET', `${V1}/communities/${w.c.id}/channels`, w.member.token);
    expect(list.body.channels.some((c: { name: string }) => c.name === 'mods')).toBe(false);
    expect((await call('GET', `${w.chan(staffOnly.body.id)}/messages`, w.member.token)).status).toBe(404);
    expect((await call('GET', `${w.chan(staffOnly.body.id)}/messages`, w.mod.token)).status).toBe(200);
    // Archiving hides a channel from members and makes it read-only for staff.
    const gen = await call('PATCH', w.chan(w.general.id), w.mod.token, { archived: true });
    expect(gen.status).toBe(200);
    expect((await call('GET', `${w.chan(w.general.id)}/messages`, w.member.token)).status).toBe(404);
    expect((await call('POST', `${w.chan(w.general.id)}/messages`, w.mod.token, { content: 'x' })).status).toBe(403);
  });

  it('real time: only people who may read the channel are told, and only after the message is stored', async () => {
    const w = await world();
    const memberSock = fakeSocket();
    const outsiderSock = fakeSocket();
    const modSock = fakeSocket();
    hub.add(w.member.id, memberSock.ws);
    hub.add(w.outsider.id, outsiderSock.ws);
    hub.add(w.mod.id, modSock.ws);
    const sent = await call('POST', `${w.chan(w.general.id)}/messages`, w.owner.token, { content: 'live' });
    expect(sent.status).toBe(201);
    const got = memberSock.events.find((e) => e.type === 'channel:message') as { channelId: string; message: { id: string; content: string } } | undefined;
    expect(got?.message.id).toBe(sent.body.id);
    expect(got?.message.content).toBe('live');
    expect(modSock.events.some((e) => e.type === 'channel:message')).toBe(true);
    expect(outsiderSock.events.filter((e) => e.type.startsWith('channel:'))).toHaveLength(0);
    // A rejected send broadcasts nothing.
    const before = memberSock.events.length;
    expect((await call('POST', `${w.chan(w.announcements.id)}/messages`, w.member.token, { title: 't', content: 'c' })).status).toBe(403);
    expect(memberSock.events.length).toBe(before);
    // Reactions, pins and deletions are announced too.
    await call('PUT', `${w.chan(w.general.id)}/messages/${sent.body.id}/reactions/heart`, w.member.token);
    await call('PUT', `${w.chan(w.general.id)}/messages/${sent.body.id}/pin`, w.mod.token);
    await call('DELETE', `${w.chan(w.general.id)}/messages/${sent.body.id}`, w.owner.token);
    const kinds = new Set(memberSock.events.map((e) => e.type));
    for (const k of ['channel:reaction', 'channel:message:updated', 'channel:message:deleted']) expect(kinds.has(k)).toBe(true);
    // Someone removed from the community stops receiving events.
    await call('DELETE', `${V1}/communities/${w.c.id}/members/${w.member.id}`, w.owner.token);
    const mark = memberSock.events.length;
    await call('POST', `${w.chan(w.general.id)}/messages`, w.owner.token, { content: 'after removal' });
    expect(memberSock.events.length).toBe(mark);
  });

  it('search: respects filters and never reaches channels the person cannot open', async () => {
    const w = await world();
    const a = await call('POST', `${w.chan(w.general.id)}/messages`, w.member.token, { content: 'Kotlin coroutines guide' });
    await call('POST', `${w.chan(w.general.id)}/messages`, w.owner.token, { content: 'lunch at noon' });
    await call('PUT', `${w.chan(w.general.id)}/messages/${a.body.id}/pin`, w.mod.token);
    const s = (qs: string, t = w.owner.token) => call('GET', `${w.chan(w.general.id)}/search?${qs}`, t);
    expect((await s('q=kotlin')).body.items.map((m: { id: string }) => m.id)).toEqual([a.body.id]);
    expect((await s(`authorId=${w.owner.id}`)).body.items).toHaveLength(1);
    expect((await s('pinned=true')).body.items.map((m: { id: string }) => m.id)).toEqual([a.body.id]);
    expect((await s('q=100%25')).body.items).toHaveLength(0); // wildcard characters are literal
    expect((await s('')).status).toBe(400); // nothing to search for
    expect((await s('q=kotlin', w.outsider.token)).status).toBe(404);
    const staffOnly = await call('POST', `${V1}/communities/${w.c.id}/channels`, w.owner.token, { name: 'mods', viewRole: 'staff' });
    await call('POST', `${w.chan(staffOnly.body.id)}/messages`, w.owner.token, { content: 'secret plan' });
    expect((await call('GET', `${w.chan(staffOnly.body.id)}/search?q=secret`, w.member.token)).status).toBe(404);
  });

  it('attachments: an uploaded photo attaches once, only for its owner, and is erased with the message', async () => {
    const w = await world();
    const img = await h.image(w.member.token, 'message');
    const sent = await call('POST', `${w.chan(w.general.id)}/messages`, w.member.token, { content: 'look', mediaIds: [img.id] });
    expect(sent.status, JSON.stringify(sent.body)).toBe(201);
    expect(sent.body.media).toHaveLength(1);
    const again = await call('POST', `${w.chan(w.general.id)}/messages`, w.member.token, { content: 'twice', mediaIds: [img.id] });
    expect(again.status).toBe(422);
    const stolen = await h.image(w.member.token, 'message');
    expect((await call('POST', `${w.chan(w.general.id)}/messages`, w.mod.token, { content: 'not mine', mediaIds: [stolen.id] })).status).toBe(422);
    await call('DELETE', `${w.chan(w.general.id)}/messages/${sent.body.id}`, w.member.token);
    expect((await pool.query('select count(*)::int as n from media where id = $1', [img.id])).rows[0].n).toBe(0);
    expect((await pool.query('select count(*)::int as n from storage_garbage where object_key = $1', [img.key])).rows[0].n).toBe(1);
    // Attachments can be switched off per channel.
    await call('PATCH', w.chan(w.general.id), w.mod.token, { allowAttachments: false });
    const img2 = await h.image(w.member.token, 'message');
    expect((await call('POST', `${w.chan(w.general.id)}/messages`, w.member.token, { content: 'no files', mediaIds: [img2.id] })).status).toBe(403);
  });

  it('reports: a member can report a channel message; an outsider cannot see it exists', async () => {
    const w = await world();
    const m = await call('POST', `${w.chan(w.general.id)}/messages`, w.owner.token, { content: 'rude words' });
    const report = { targetType: 'channel_message', targetId: m.body.id, reason: 'harassment', details: '' };
    expect((await call('POST', `${V1}/reports`, w.outsider.token, report)).status).toBe(404);
    expect((await call('POST', `${V1}/reports`, w.owner.token, report)).status).toBe(404); // your own message
    expect((await call('POST', `${V1}/reports`, w.member.token, report)).status).toBe(201);
  });

  it('polls: members vote once, counts are anonymous, closed and edited polls are refused, and who may post follows the channel', async () => {
    const w = await world();
    const url = `${w.chan(w.general.id)}/messages`;
    const poll = { options: ['Friday', 'Saturday'], durationHours: 24 };
    // Same rules as feed polls: 2 to 4 distinct options, at most 3 days.
    expect((await call('POST', url, w.member.token, { content: 'Which day?', poll: { options: ['Only one'], durationHours: 24 } })).status).toBe(400);
    expect((await call('POST', url, w.member.token, { content: 'Which day?', poll: { options: ['A', 'a'], durationHours: 24 } })).status).toBe(400);
    expect((await call('POST', url, w.member.token, { content: 'Which day?', poll: { options: ['A', 'B'], durationHours: 100 } })).status).toBe(400);
    expect((await call('POST', url, w.member.token, { content: '', poll })).status).toBe(400);
    const made = await call('POST', url, w.member.token, { content: 'Which day?', poll });
    expect(made.status, JSON.stringify(made.body)).toBe(201);
    expect(made.body.poll.options.map((o: { text: string }) => o.text)).toEqual(['Friday', 'Saturday']);
    expect(made.body.poll.myVote).toBeNull();
    expect(made.body.poll.ended).toBe(false);

    const vote = (t: string, i: number) => call('PUT', `${w.chan(w.general.id)}/messages/${made.body.id}/vote`, t, { optionIndex: i });
    const first = await vote(w.owner.token, 1);
    expect(first.status, JSON.stringify(first.body)).toBe(200);
    expect(first.body.poll.myVote).toBe(1);
    expect(first.body.poll.options[1].votes).toBe(1);
    const again = await vote(w.owner.token, 0); // a vote cannot be changed
    expect(again.status).toBe(409);
    expect(again.body.error).toBe('already_voted');
    // Two simultaneous taps from one person count once.
    const results = await Promise.all([vote(w.mod.token, 0), vote(w.mod.token, 1)]);
    expect(results.map((r) => r.status).sort()).toEqual([200, 409]);
    expect((await vote(w.member.token, 7)).status).toBe(400);
    expect((await vote(w.outsider.token, 0)).status).toBe(404); // not a member: the channel does not exist for them
    // Nobody's choice is visible to anyone else.
    const viewed = await call('GET', `${w.chan(w.general.id)}/messages?limit=5`, w.member.token);
    const seen = viewed.body.items.find((m: { id: string }) => m.id === made.body.id);
    expect(seen.poll.myVote).toBeNull();
    expect(seen.poll.options.reduce((n: number, o: { votes: number }) => n + o.votes, 0)).toBe(2);
    expect(seen.poll).not.toHaveProperty('voters'); // only counts and your own choice are ever sent

    // A poll cannot be edited once posted, and a closed poll takes no more votes.
    expect((await call('PATCH', `${w.chan(w.general.id)}/messages/${made.body.id}`, w.member.token, { content: 'Changed question' })).status).toBe(400);
    await pool.query("update community_channel_messages set poll_ends_at = now() - interval '1 minute' where id = $1", [made.body.id]);
    const late = await vote(w.member.token, 0);
    expect(late.status).toBe(409);
    expect(late.body.error).toBe('poll_ended');
    const closed = await call('GET', `${w.chan(w.general.id)}/messages?limit=5`, w.member.token);
    expect(closed.body.items.find((m: { id: string }) => m.id === made.body.id).poll.ended).toBe(true);

    // Polls follow the channel's posting rules: announcement polls are staff only and need a title; question channels take none.
    const ann = `${w.chan(w.announcements.id)}/messages`;
    expect((await call('POST', ann, w.member.token, { title: 'Venue', content: 'Where?', poll })).status).toBe(403);
    expect((await call('POST', ann, w.mod.token, { content: 'Where?', poll })).status).toBe(400); // title missing
    expect((await call('POST', ann, w.mod.token, { title: 'Venue', content: 'Where?', poll })).status).toBe(201);
    const qa = await call('POST', `${V1}/communities/${w.c.id}/channels`, w.owner.token, { name: 'ask', type: 'qa' });
    expect((await call('POST', `${w.chan(qa.body.id)}/messages`, w.owner.token, { title: 'A question?', content: 'x', poll })).status).toBe(400);
    // A deleted poll takes no votes and shows no results.
    const gone = await call('POST', url, w.member.token, { content: 'Pizza?', poll });
    await call('DELETE', `${w.chan(w.general.id)}/messages/${gone.body.id}`, w.member.token);
    expect((await call('PUT', `${w.chan(w.general.id)}/messages/${gone.body.id}/vote`, w.owner.token, { optionIndex: 0 })).status).toBe(404);
  });

  it('ordering and groups: groups are created, channels move between them, deleting a group keeps its channels', async () => {
    const w = await world();
    const g = await call('POST', `${V1}/communities/${w.c.id}/channel-groups`, w.mod.token, { name: 'Academics' });
    expect(g.status, JSON.stringify(g.body)).toBe(201);
    expect((await call('POST', `${V1}/communities/${w.c.id}/channel-groups`, w.member.token, { name: 'Nope' })).status).toBe(403);
    const order = await call('PUT', `${V1}/communities/${w.c.id}/channel-order`, w.mod.token, {
      groups: [g.body.id],
      channels: [{ id: w.general.id, groupId: g.body.id }, { id: w.announcements.id, groupId: null }],
    });
    expect(order.status, JSON.stringify(order.body)).toBe(200);
    const list = await call('GET', `${V1}/communities/${w.c.id}/channels`, w.member.token);
    expect(list.body.channels.map((c: { name: string }) => c.name)).toEqual(['general', 'announcements']);
    expect(list.body.channels[0].groupId).toBe(g.body.id);
    expect((await call('DELETE', `${V1}/communities/${w.c.id}/channel-groups/${g.body.id}`, w.mod.token)).status).toBe(204);
    const after = await call('GET', `${V1}/communities/${w.c.id}/channels`, w.member.token);
    expect(after.body.channels).toHaveLength(2);
    expect(after.body.channels[0].groupId).toBeNull();
    // A group from another community cannot be used.
    const other = await createCommunity(w.owner.token);
    const foreign = await call('POST', `${V1}/communities/${other.id}/channel-groups`, w.owner.token, { name: 'Elsewhere' });
    expect((await call('PATCH', w.chan(w.general.id), w.mod.token, { groupId: foreign.body.id })).status).toBe(422);
  });
});
