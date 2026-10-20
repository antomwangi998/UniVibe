import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, url } from './helpers';

describe.skipIf(!url)('event categories (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  beforeAll(async () => { pool = createPool(url as string); await migrate(pool); });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  it('accepts career and party events, filters by them, and still rejects unknown categories', async () => {
    const s = await h.student();
    const starts = new Date(Date.now() + 3 * 3600_000).toISOString();
    const ends = new Date(Date.now() + 5 * 3600_000).toISOString();
    const make = (category: string, title: string) =>
      call('POST', '/api/v1/events', s.token, { title, category, venueName: 'Main hall', startsAt: starts, endsAt: ends });
    const career = await make('career', 'Career fair');
    expect(career.status, JSON.stringify(career.body)).toBe(201);
    expect(career.body.category).toBe('career');
    expect((await make('party', 'Rooftop party')).status).toBe(201);
    expect((await make('banquet', 'Not a category')).status).toBe(422);
    const list = await call('GET', '/api/v1/events?category=career&limit=50', s.token);
    expect(list.body.items.some((e: { id: string }) => e.id === career.body.id)).toBe(true);
    expect(list.body.items.every((e: { category: string }) => e.category === 'career')).toBe(true);
  });
});
