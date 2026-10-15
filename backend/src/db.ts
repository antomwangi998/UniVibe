import { readFileSync, readdirSync } from 'fs';
import { join } from 'path';
import { Pool, PoolClient } from 'pg';

export function createPool(connectionString: string): Pool {
  return new Pool({ connectionString, max: 10 });
}

export async function withTx<T>(pool: Pool, fn: (client: PoolClient) => Promise<T>): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query('begin');
    const result = await fn(client);
    await client.query('commit');
    return result;
  } catch (error) {
    await client.query('rollback');
    throw error;
  } finally {
    client.release();
  }
}

/** Applies every migrations/*.sql file once, in name order. */
export async function migrate(pool: Pool, dir: string = join(__dirname, '..', 'migrations')): Promise<void> {
  await pool.query(
    'create table if not exists schema_migrations (name text primary key, applied_at timestamptz not null default now())',
  );
  const files = readdirSync(dir).filter((f) => f.endsWith('.sql')).sort();
  for (const file of files) {
    const done = await pool.query('select 1 from schema_migrations where name = $1', [file]);
    if (done.rowCount) continue;
    await withTx(pool, async (client) => {
      await client.query(readFileSync(join(dir, file), 'utf8'));
      await client.query('insert into schema_migrations (name) values ($1)', [file]);
    });
  }
}
