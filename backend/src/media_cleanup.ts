import type { Pool, PoolClient } from 'pg';
import { withTx } from './db';
import { cleanupStorageGarbage } from './media';
import type { Storage } from './storage';

/** How long a finished upload may sit unused before it is treated as abandoned. Override with MEDIA_ORPHAN_GRACE_HOURS. */
export const DEFAULT_ORPHAN_GRACE_HOURS = 24;

/** Held for the length of one batch so two server instances never clean the same rows at once. */
const CLEANUP_LOCK = 7_201_501;

/**
 * Every column in the database that points at media(id), read from the schema itself. A feature added later that
 * references media is protected automatically, without anyone remembering to edit this file.
 * (Quoted by Postgres, so the result is safe to put in a query.)
 */
export async function mediaReferences(q: Pick<PoolClient, 'query'>): Promise<{ table: string; column: string }[]> {
  const { rows } = await q.query<{ tbl: string; col: string }>(
    `select format('%I.%I', n.nspname, c.relname) as tbl, format('%I', a.attname) as col
       from pg_constraint k
       join pg_class c on c.oid = k.conrelid
       join pg_namespace n on n.oid = c.relnamespace
       cross join lateral unnest(k.conkey) as u(attnum)
       join pg_attribute a on a.attrelid = k.conrelid and a.attnum = u.attnum
      where k.contype = 'f' and k.confrelid = 'media'::regclass`,
  );
  return rows.map((r) => ({ table: r.tbl, column: r.col }));
}

/**
 * Takes one batch of unused media out of the database and queues its files for deletion (storage_garbage, the same
 * queue link-card pictures use), in one transaction. The files are removed afterwards and retried until storage accepts
 * the removal, so a storage outage can never leave a file with no record of it.
 *
 * Unused means all of these:
 *  - the upload finished (unfinished ones are handled by cleanupPendingMedia),
 *  - it is older than the grace period, so a person still composing a post, message or Story is never caught,
 *  - it is not attached to a post or a message,
 *  - nothing else points at it: avatar, cover, event cover, community or group photo, a Story, or anything added later.
 */
async function queueUnusedMedia(pool: Pool, graceHours: number, batch: number): Promise<number> {
  return withTx(pool, async (client) => {
    const lock = await client.query<{ ok: boolean }>('select pg_try_advisory_xact_lock($1) as ok', [CLEANUP_LOCK]);
    if (!lock.rows[0].ok) return 0; // another instance is already cleaning
    const notUsed = (await mediaReferences(client))
      .map((r) => `and not exists (select 1 from ${r.table} ref where ref.${r.column} = m.id)`)
      .join('\n          ');
    const { rows } = await client.query<{ id: string; object_key: string; thumb_key: string | null }>(
      `select m.id, m.object_key, m.thumb_key from media m
        where m.status = 'ready'
          and m.created_at < now() - make_interval(hours => $1::int)
          and m.post_id is null and m.message_id is null
          ${notUsed}
        order by m.created_at
        limit $2
        for update of m skip locked`,
      [graceHours, batch],
    );
    if (rows.length === 0) return 0;
    await client.query('delete from media where id = any($1::uuid[])', [rows.map((r) => r.id)]);
    const keys = rows.flatMap((r) => [r.object_key, r.thumb_key]).filter((k): k is string => Boolean(k));
    await client.query('insert into storage_garbage (object_key) select unnest($1::text[]) on conflict do nothing', [keys]);
    return rows.length;
  });
}

export interface MediaCleanupOptions {
  graceHours?: number;
  /** Rows taken per batch. */
  batch?: number;
  /** Safety cap on batches per run, so one run can never hold the database for long. */
  maxBatches?: number;
}

/**
 * Removes uploaded files that nothing uses (a composer closed after uploading, a failed or retried post, a replaced
 * cover) and then deletes their files from storage. Safe to run often and in several instances; repeating it finds
 * nothing new. Returns how many media rows were removed and how many files were deleted from storage this run.
 */
export async function purgeUnusedMedia(pool: Pool, storage: Storage | null, opts: MediaCleanupOptions = {}): Promise<{ media: number; files: number }> {
  const graceHours = opts.graceHours ?? DEFAULT_ORPHAN_GRACE_HOURS;
  const batch = opts.batch ?? 200;
  const maxBatches = opts.maxBatches ?? 10;
  let media = 0;
  for (let i = 0; i < maxBatches; i++) {
    const n = await queueUnusedMedia(pool, graceHours, batch);
    media += n;
    if (n < batch) break;
  }
  let files = 0;
  if (storage) {
    for (let i = 0; i < maxBatches; i++) {
      const n = await cleanupStorageGarbage(pool, storage, batch * 2);
      files += n;
      if (n < batch * 2) break;
    }
  }
  return { media, files };
}
