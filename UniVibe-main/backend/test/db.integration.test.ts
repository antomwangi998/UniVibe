import type { Pool } from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';

const url = process.env.TEST_DATABASE_URL;

describe.skipIf(!url)('migrations (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  beforeAll(() => { pool = createPool(url as string); });
  afterAll(async () => { await pool.end(); });

  it('can run several times at once without racing', async () => {
    await Promise.all([migrate(pool), migrate(pool), migrate(pool)]);
    const { rows } = await pool.query<{ n: number }>('select count(*)::int as n from schema_migrations');
    expect(rows[0].n).toBeGreaterThanOrEqual(4);
  });

  it('applies each migration only once', async () => {
    await migrate(pool);
    const { rows } = await pool.query<{ name: string; n: number }>(
      'select name, count(*)::int as n from schema_migrations group by name having count(*) > 1',
    );
    expect(rows).toEqual([]);
  });
});
