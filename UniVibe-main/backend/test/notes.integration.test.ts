import type { Pool } from 'pg';
import WebSocket from 'ws';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { characterCount, cleanNoteText, purgeExpiredNotes } from '../src/modules/notes';
import { harness, url } from './helpers';

type Ev = { type: string; [k: string]: unknown };

describe('note text rules', () => {
  it('trims, drops control characters and counts emoji as one character', () => {
    expect(cleanNoteText('  hi\u0000 there \r\n\r\n\r\n x  ')).toBe('hi there \n x');
    expect(characterCount('😀😀a')).toBe(3);
    expect(characterCount('é')).toBe(1);
  });
});

describe.skipIf(!url)('notes (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';
  const sockets: WebSocket[] = [];
  let port = 0;

  beforeAll(async () => {
    pool = createPool(url as string);
    await migrate(pool);
    await pool.query("insert into universities (id, name, email_domains) values ('other', 'Other University', array['other.example']) on conflict do nothing");
  });
  afterAll(async () => { await pool.end(); });
  beforeEach(async () => {
    // The test database is shared by every student any test ever made, so start each test with no Notes at all.
    await pool.query('delete from notes');
    h.make();
    await h.app().listen({ port: 0, host: '127.0.0.1' });
    port = (h.app().server.address() as { port: number }).port;
  });
  afterEach(async () => {
    sockets.splice(0).forEach((s) => s.terminate());
    await h.close();
  });

  async function connect(token: string) {
    const ws = new WebSocket(`ws://127.0.0.1:${port}${V1}/ws`);
    sockets.push(ws);
    const events: Ev[] = [];
    ws.on('message', (d) => events.push(JSON.parse(d.toString())));
    await new Promise((resolve, reject) => { ws.on('open', resolve); ws.on('error', reject); });
    ws.send(JSON.stringify({ type: 'auth', token }));
    await waitFor(events, 'auth:ok');
    return events;
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
  const put = (token: string, body: unknown) => call('PUT', `${V1}/me/note`, token, body);
  const list = async (token: string) => (await call('GET', `${V1}/notes`, token)).body;

  it('requires sign-in', async () => {
    expect((await call('GET', `${V1}/notes`)).status).toBe(401);
    expect((await call('PUT', `${V1}/me/note`, undefined, { text: 'hi' })).status).toBe(401);
  });

  it('validates text on the server: blank, too long (never truncated), unknown fields, bad audience', async () => {
    const a = await h.student();
    expect((await put(a.token, { text: '   \n ' })).status).toBe(400);
    expect((await put(a.token, { text: '' })).status).toBe(400);
    const tooLong = await put(a.token, { text: 'x'.repeat(61) });
    expect(tooLong.status).toBe(400);
    expect(tooLong.body.message).toContain('60');
    expect((await put(a.token, { text: 'ok', audience: 'public' })).status).toBe(400);
    expect((await put(a.token, { text: 'ok', creatorId: a.id })).status).toBe(400);
    expect((await list(a.token)).mine).toBeNull(); // nothing was stored by the failed attempts
    // exactly 60 characters is fine, and so are 60 emoji (60 characters, 120 UTF-16 units)
    expect((await put(a.token, { text: 'y'.repeat(60) })).status).toBe(200);
    const emoji = await put(a.token, { text: '😀'.repeat(60) });
    expect(emoji.status).toBe(200);
    expect(emoji.body.text).toBe('😀'.repeat(60));
    expect((await put(a.token, { text: '😀'.repeat(61) })).status).toBe(400);
  });

  it('publish, replace, fetch and delete, with one active Note per student', async () => {
    const a = await h.student();
    const b = await h.student();
    const first = await put(a.token, { text: 'Exams next week 😮‍💨' });
    expect(first.status).toBe(200);
    expect(first.body.text).toBe('Exams next week 😮‍💨');
    expect(first.body.author.id).toBe(a.id);
    const seen = await list(b.token);
    expect(seen.items.map((n: { author: { id: string } }) => n.author.id)).toEqual([a.id]);
    expect(seen.mine).toBeNull();
    expect(seen.limits).toEqual({ maxChars: 60, ttlHours: 24 });
    expect(typeof seen.serverTime).toBe('string');

    const replaced = await put(a.token, { text: 'Busy today, catch you later' });
    expect(replaced.body.id).toBe(first.body.id); // replaced in place, not a second Note
    expect(replaced.body.version).toBe(first.body.version + 1);
    expect((await pool.query('select count(*)::int as n from notes where author_id = $1', [a.id])).rows[0].n).toBe(1);
    expect((await list(a.token)).mine.text).toBe('Busy today, catch you later');
    expect((await call('GET', `${V1}/users/${a.id}/note`, b.token)).body.text).toBe('Busy today, catch you later');

    expect((await call('DELETE', `${V1}/me/note`, a.token)).status).toBe(204);
    expect((await call('DELETE', `${V1}/me/note`, a.token)).status).toBe(204); // repeating is fine
    expect((await list(b.token)).items).toEqual([]);
    expect((await call('GET', `${V1}/users/${a.id}/note`, b.token)).status).toBe(404);
  });

  it('expiry is decided by the server clock and an edit restarts it', async () => {
    const a = await h.student();
    const b = await h.student();
    const n = await put(a.token, { text: 'soon gone' });
    const ttlMs = Date.parse(n.body.expiresAt) - Date.parse(n.body.createdAt);
    expect(Math.round(ttlMs / 3600000)).toBe(24);
    await pool.query("update notes set expires_at = now() - interval '1 second' where author_id = $1", [a.id]);
    expect((await list(b.token)).items).toEqual([]);
    expect((await list(a.token)).mine).toBeNull();
    expect((await call('GET', `${V1}/users/${a.id}/note`, b.token)).status).toBe(404);
    // a new publish after expiry is a fresh Note with a fresh 24 hours
    const again = await put(a.token, { text: 'back' });
    expect(again.status).toBe(200);
    expect(Date.parse(again.body.expiresAt)).toBeGreaterThan(Date.now() + 23 * 3600 * 1000);
    // the purge only removes rows that expired more than a day ago
    await pool.query("update notes set expires_at = now() - interval '2 days' where author_id = $1", [a.id]);
    expect((await purgeExpiredNotes(pool)).notes).toBeGreaterThanOrEqual(1);
    expect((await pool.query('select 1 from notes where author_id = $1', [a.id])).rowCount).toBe(0);
  });

  it('audience: followers-only is enforced by the server; campus-only stays inside the university', async () => {
    const author = await h.student();
    const follower = await h.student();
    const stranger = await h.student();
    const outsider = await h.student('other.example', 'other');
    await call('PUT', `/students/${author.id}/follow`, follower.token);
    await put(author.token, { text: 'friends only', audience: 'followers' });
    expect((await list(follower.token)).items).toHaveLength(1);
    expect((await list(stranger.token)).items).toEqual([]);
    expect((await call('GET', `${V1}/users/${author.id}/note`, stranger.token)).status).toBe(404);
    expect((await call('GET', `${V1}/users/${author.id}/note`, follower.token)).status).toBe(200);
    // changing the audience applies at once
    await put(author.token, { text: 'everyone now', audience: 'campus' });
    expect((await list(stranger.token)).items).toHaveLength(1);
    // another university never sees it
    expect((await list(outsider.token)).items).toEqual([]);
    expect((await call('GET', `${V1}/users/${author.id}/note`, outsider.token)).status).toBe(404);
  });

  it('blocks hide Notes both ways, and a suspended author disappears', async () => {
    const a = await h.student();
    const b = await h.student();
    const c = await h.student();
    await put(a.token, { text: 'from a' });
    await put(b.token, { text: 'from b' });
    expect((await list(a.token)).items.map((n: { author: { id: string } }) => n.author.id)).toEqual([b.id]);
    expect((await call('POST', `${V1}/users/${b.id}/block`, a.token)).status).toBe(204);
    expect((await list(a.token)).items).toEqual([]);
    expect((await list(b.token)).items).toEqual([]); // the block works in both directions
    expect((await call('GET', `${V1}/users/${a.id}/note`, b.token)).status).toBe(404);
    expect((await list(c.token)).items).toHaveLength(2); // an uninvolved student still sees both
    await pool.query("update users set suspended_until = now() + interval '1 day' where id = $1", [a.id]);
    expect((await list(c.token)).items.map((n: { author: { id: string } }) => n.author.id)).toEqual([b.id]);
  });

  it('muting hides one person’s Note for me only, and is undone by unmuting', async () => {
    const a = await h.student();
    const b = await h.student();
    const c = await h.student();
    await put(a.token, { text: 'loud' });
    expect((await call('PUT', `${V1}/users/${a.id}/note-mute`, b.token)).status).toBe(204);
    expect((await list(b.token)).items).toEqual([]);
    expect((await list(c.token)).items).toHaveLength(1);
    expect((await call('PUT', `${V1}/users/${b.id}/note-mute`, b.token)).status).toBe(400);
    expect((await call('PUT', `${V1}/users/00000000-0000-4000-8000-000000000000/note-mute`, b.token)).status).toBe(404);
    expect((await call('DELETE', `${V1}/users/${a.id}/note-mute`, b.token)).status).toBe(204);
    expect((await list(b.token)).items).toHaveLength(1);
  });

  it('nobody can change someone else’s Note: the author always comes from the token', async () => {
    const a = await h.student();
    const b = await h.student();
    await put(a.token, { text: 'mine' });
    await put(b.token, { text: 'theirs' });
    expect((await list(a.token)).mine.text).toBe('mine');
    expect((await call('DELETE', `${V1}/me/note`, b.token)).status).toBe(204);
    expect((await list(a.token)).mine.text).toBe('mine'); // b's delete only touched b's Note
    expect((await call('PUT', `${V1}/users/${a.id}/note`, b.token, { text: 'hijack' })).status).toBe(404);
  });

  it('live: viewers, and the author’s other devices, hear that a Note changed — without its text', async () => {
    const a = await h.student();
    const viewer = await h.student();
    const stranger = await h.student();
    const outsider = await h.student('other.example', 'other');
    await call('PUT', `/students/${a.id}/follow`, viewer.token);
    const viewerEvents = await connect(viewer.token);
    const otherDevice = await connect(a.token);
    const outsiderEvents = await connect(outsider.token);
    await put(a.token, { text: 'secret text', audience: 'followers' });
    const ev = await waitFor(viewerEvents, 'note:changed', (e) => e.userId === a.id);
    expect(ev).toEqual({ type: 'note:changed', userId: a.id, kind: 'updated' });
    expect(JSON.stringify(ev)).not.toContain('secret');
    await waitFor(otherDevice, 'note:changed');
    // a followers-only Note is not announced to a non-follower, and never to another university
    const strangerEvents = await connect(stranger.token);
    await put(a.token, { text: 'again', audience: 'followers' });
    await waitFor(viewerEvents, 'note:changed', (e) => e.userId === a.id && viewerEvents.filter((x) => x.type === 'note:changed').length >= 2);
    await quiet();
    expect(strangerEvents.some((e) => e.type === 'note:changed')).toBe(false);
    expect(outsiderEvents.some((e) => e.type === 'note:changed')).toBe(false);
    // delete is announced too
    await call('DELETE', `${V1}/me/note`, a.token);
    await waitFor(viewerEvents, 'note:changed', (e) => e.kind === 'deleted');
  });

  it('retrying the same publish leaves exactly one Note', async () => {
    const a = await h.student();
    await Promise.all([put(a.token, { text: 'same' }), put(a.token, { text: 'same' }), put(a.token, { text: 'same' })]);
    expect((await pool.query('select count(*)::int as n from notes where author_id = $1', [a.id])).rows[0].n).toBe(1);
  });
});
