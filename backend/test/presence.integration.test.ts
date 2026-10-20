import type { Pool } from 'pg';
import WebSocket from 'ws';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, url } from './helpers';

describe.skipIf(!url)('presence: online dot and last active (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  let port = 0;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';
  const sockets: WebSocket[] = [];

  beforeAll(async () => { pool = createPool(url as string); await migrate(pool); });
  afterAll(async () => { await pool.end(); });
  beforeEach(async () => {
    await pool.query('delete from notes');
    h.make();
    await h.app().listen({ port: 0, host: '127.0.0.1' });
    port = (h.app().server.address() as { port: number }).port;
  });
  afterEach(async () => { sockets.splice(0).forEach((s) => s.terminate()); await h.close(); });

  async function connect(token: string) {
    const ws = new WebSocket(`ws://127.0.0.1:${port}${V1}/ws`);
    sockets.push(ws);
    let ok = false;
    ws.on('message', (d) => { if (JSON.parse(d.toString()).type === 'auth:ok') ok = true; });
    await new Promise((resolve, reject) => { ws.on('open', resolve); ws.on('error', reject); });
    ws.send(JSON.stringify({ type: 'auth', token }));
    for (let i = 0; i < 150 && !ok; i++) await new Promise((r) => setTimeout(r, 20));
    expect(ok).toBe(true);
    return ws;
  }
  const until = async (check: () => Promise<boolean>, ms = 3000) => {
    const start = Date.now();
    while (Date.now() - start < ms) { if (await check()) return; await new Promise((r) => setTimeout(r, 40)); }
    throw new Error('timed out');
  };
  async function direct(a: { token: string }, b: { id: string }) {
    const res = await call('POST', `${V1}/conversations`, a.token, { type: 'direct', userId: b.id });
    expect([200, 201]).toContain(res.status);
    return res.body.id as string;
  }
  const rowFor = async (viewer: { token: string }, conv: string) =>
    (await call('GET', `${V1}/conversations`, viewer.token)).body.items.find((c: { id: string }) => c.id === conv);

  it('shows online while connected, then the time they were last active; never "offline" without a time', async () => {
    const a = await h.student();
    const b = await h.student();
    const conv = await direct(a, b);
    // Never connected: no dot and no time at all.
    expect((await rowFor(a, conv)).presence).toEqual({ online: false, lastActiveAt: null });
    const ws = await connect(b.token);
    const live = (await rowFor(a, conv)).presence;
    expect(live.online).toBe(true);
    expect(Math.abs(Date.now() - Date.parse(live.lastActiveAt))).toBeLessThan(5000);
    ws.close();
    await until(async () => (await rowFor(a, conv)).presence.online === false);
    const after = (await rowFor(a, conv)).presence;
    expect(after.online).toBe(false);
    expect(Math.abs(Date.now() - Date.parse(after.lastActiveAt))).toBeLessThan(10000);
    // The conversation view carries the same last-active time for the other member.
    const member = (await call('GET', `${V1}/conversations/${conv}`, a.token)).body.members.find((m: { id: string }) => m.id === b.id);
    expect(member.lastActiveAt).toBe(after.lastActiveAt);
  });

  it('turning "Show online status" off hides both the dot and the time, everywhere', async () => {
    const a = await h.student();
    const b = await h.student();
    const conv = await direct(a, b);
    await call('PUT', `${V1}/me/note`, b.token, { text: 'hi there' });
    await connect(b.token);
    expect((await rowFor(a, conv)).presence.online).toBe(true);
    expect((await call('GET', `${V1}/notes`, a.token)).body.items[0].presence.online).toBe(true);
    await call('PUT', `${V1}/me/privacy`, b.token, { showOnline: false });
    expect((await rowFor(a, conv)).presence).toBeNull();
    const member = (await call('GET', `${V1}/conversations/${conv}`, a.token)).body.members.find((m: { id: string }) => m.id === b.id);
    expect(member.online).toBeNull();
    expect(member.lastActiveAt).toBeNull();
    expect((await call('GET', `${V1}/notes`, a.token)).body.items[0].presence).toBeNull();
    expect((await call('GET', `${V1}/users/${b.id}/note`, a.token)).body.presence).toBeNull();
    // They still see themselves.
    expect((await call('GET', `${V1}/conversations/${conv}`, b.token)).body.members.find((m: { id: string }) => m.id === b.id).online).toBe(true);
  });
});
