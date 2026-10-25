import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, url } from './helpers';

describe.skipIf(!url)('shared places: one-time and live (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';

  beforeAll(async () => { pool = createPool(url as string); await migrate(pool); });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  const direct = async (a: { token: string }, b: { id: string }) => {
    const r = await call('POST', `${V1}/conversations`, a.token, { type: 'direct', userId: b.id });
    expect(r.status, JSON.stringify(r.body)).toBeLessThan(300);
    return r.body.id as string;
  };
  const send = (token: string, conv: string, body: Record<string, unknown>) =>
    call('POST', `${V1}/conversations/${conv}/messages`, token, { clientId: crypto.randomUUID(), ...body });

  it('a one-time place is rounded to about 10 m before it is stored', async () => {
    const a = await h.student(); const b = await h.student();
    const conv = await direct(a, b);
    const r = await send(a.token, conv, { location: { latitude: -1.286389123, longitude: 36.817223987 } });
    expect(r.status, JSON.stringify(r.body)).toBeLessThan(300);
    expect(r.body.location).toEqual({ latitude: -1.2864, longitude: 36.8172, label: null });
    expect(r.body.live).toBeNull();
  });

  it('rejects out-of-range positions and unsupported live durations', async () => {
    const a = await h.student(); const b = await h.student();
    const conv = await direct(a, b);
    expect((await send(a.token, conv, { location: { latitude: 91, longitude: 0 } })).status).toBe(400);
    expect((await send(a.token, conv, { location: { latitude: 1, longitude: 1, liveMinutes: 7 } })).status).toBe(400);
  });

  it('live: the other person sees it move, only the sender can update it, and stopping wipes the position', async () => {
    const a = await h.student(); const b = await h.student();
    const conv = await direct(a, b);
    const sent = await send(a.token, conv, { location: { latitude: -1.2864, longitude: 36.8172, liveMinutes: 15 } });
    expect(sent.status, JSON.stringify(sent.body)).toBeLessThan(300);
    const id = sent.body.id as string;
    expect(sent.body.live.active).toBe(true);
    expect(sent.body.location).toEqual({ latitude: -1.2864, longitude: 36.8172, label: null });

    expect((await call('PUT', `${V1}/messages/${id}/live`, b.token, { latitude: 1, longitude: 1 })).status).toBe(409);
    expect((await call('PUT', `${V1}/messages/${id}/live`, a.token, { latitude: -1.29001, longitude: 36.82004 })).status).toBe(200);
    const seen = (await call('GET', `${V1}/conversations/${conv}/messages`, b.token)).body.items.find((m: { id: string }) => m.id === id);
    expect(seen.location).toEqual({ latitude: -1.29, longitude: 36.82, label: null });

    expect((await call('DELETE', `${V1}/messages/${id}/live`, a.token)).status).toBe(204);
    const ended = (await call('GET', `${V1}/conversations/${conv}/messages`, b.token)).body.items.find((m: { id: string }) => m.id === id);
    expect(ended.live.active).toBe(false);
    expect(ended.location).toBeNull();
    const row = await pool.query('select lat, lng from live_locations where message_id = $1', [id]);
    expect(row.rows[0]).toEqual({ lat: null, lng: null });
    expect((await call('PUT', `${V1}/messages/${id}/live`, a.token, { latitude: 1, longitude: 1 })).status).toBe(409);
  });

  it('starting a new live share ends the old one, and a share past its time is wiped by the purge', async () => {
    const a = await h.student(); const b = await h.student();
    const conv = await direct(a, b);
    const first = await send(a.token, conv, { location: { latitude: 1, longitude: 1, liveMinutes: 60 } });
    const second = await send(a.token, conv, { location: { latitude: 2, longitude: 2, liveMinutes: 15 } });
    expect(second.status).toBeLessThan(300);
    const old = await pool.query('select ended_at, lat from live_locations where message_id = $1', [first.body.id]);
    expect(old.rows[0].ended_at).not.toBeNull();
    expect(old.rows[0].lat).toBeNull();

    await pool.query("update live_locations set expires_at = now() - interval '1 minute' where message_id = $1", [second.body.id]);
    const { purgeLiveLocations } = await import('../src/modules/messaging');
    expect((await purgeLiveLocations(pool)).cleared).toBeGreaterThanOrEqual(1);
    expect((await pool.query('select lat from live_locations where message_id = $1', [second.body.id])).rows[0].lat).toBeNull();
  });
});
