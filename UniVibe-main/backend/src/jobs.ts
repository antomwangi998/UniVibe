import type { Pool, PoolClient } from 'pg';

type Q = Pick<PoolClient, 'query'>;

export interface JobLogger {
  info(obj: object, msg?: string): void;
  warn(obj: object, msg?: string): void;
  error(obj: object, msg?: string): void;
}

export interface JobRow {
  id: string;
  kind: string;
  payload: Record<string, unknown>;
  attempts: number;
  max_attempts: number;
}

export type Handler = (job: JobRow) => Promise<void>;

/** Throw this when retrying cannot help (bad payload, rejected request). The job is marked failed at once. */
export class PermanentJobError extends Error {}

/**
 * Adds a job. Call it with the same transaction client as the change it follows, so the job exists if and only if
 * that change committed (a crash between "saved" and "announced" can then never lose the announcement).
 * Returns false when a job with the same (kind, dedupeKey) already exists.
 */
export async function enqueue(
  q: Q, kind: string, payload: Record<string, string | number | boolean | null>,
  opts: { dedupeKey?: string; delaySeconds?: number; maxAttempts?: number } = {},
): Promise<boolean> {
  const res = await q.query(
    `insert into jobs (kind, payload, dedupe_key, run_at, max_attempts)
     values ($1, $2::jsonb, $3, now() + make_interval(secs => $4::int), $5)
     on conflict do nothing`,
    [kind, JSON.stringify(payload), opts.dedupeKey ?? null, opts.delaySeconds ?? 0, opts.maxAttempts ?? 6],
  );
  return (res.rowCount ?? 0) > 0;
}

/** Exponential backoff with "equal jitter": between half and all of 5s, 10s, 20s, ... capped at 15 minutes. */
export function backoffSeconds(attempt: number, rand: () => number = Math.random): number {
  const base = Math.min(15 * 60, 5 * 2 ** Math.max(0, attempt - 1));
  return Math.max(1, Math.round(base / 2 + rand() * (base / 2)));
}

/**
 * Takes up to `limit` due jobs. A job whose worker crashed is still 'active' with an expired lock, so it is
 * claimed again here. Every claim counts as an attempt.
 */
export async function claimJobs(pool: Pool, limit: number, lockSeconds = 60): Promise<JobRow[]> {
  const { rows } = await pool.query<JobRow>(
    `update jobs set status = 'active', attempts = attempts + 1, locked_until = now() + make_interval(secs => $2::int)
      where id in (
        select id from jobs
         where (status = 'pending' and run_at <= now()) or (status = 'active' and locked_until < now())
         order by run_at limit $1 for update skip locked)
      returning id, kind, payload, attempts, max_attempts`,
    [limit, lockSeconds],
  );
  return rows;
}

const withTimeout = <T>(p: Promise<T>, ms: number): Promise<T> =>
  new Promise<T>((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`job timed out after ${ms} ms`)), ms);
    p.then((v) => { clearTimeout(t); resolve(v); }, (e) => { clearTimeout(t); reject(e); });
  });

/** Runs one claimed job and records the outcome. Never throws. */
export async function processJob(
  pool: Pool, job: JobRow, handlers: Record<string, Handler>, log: JobLogger,
  opts: { timeoutMs?: number; rand?: () => number } = {},
): Promise<'done' | 'retry' | 'failed'> {
  const fail = async (error: string): Promise<'failed'> => {
    await pool.query("update jobs set status = 'failed', last_error = $2, finished_at = now(), locked_until = null where id = $1", [job.id, error.slice(0, 500)]);
    log.error({ jobId: job.id, kind: job.kind, attempts: job.attempts }, `job failed permanently: ${error.slice(0, 200)}`);
    return 'failed';
  };
  // A job that keeps crashing its worker is claimed again and again; stop it once it has used all its attempts.
  if (job.attempts > job.max_attempts) return fail('attempts exhausted (worker crashed or timed out)');
  const handler = handlers[job.kind];
  if (!handler) return fail(`no handler for job kind ${job.kind}`);
  const started = Date.now();
  try {
    await withTimeout(handler(job), opts.timeoutMs ?? 30_000);
    await pool.query("update jobs set status = 'done', finished_at = now(), locked_until = null, last_error = null where id = $1", [job.id]);
    log.info({ jobId: job.id, kind: job.kind, attempts: job.attempts, ms: Date.now() - started }, 'job done');
    return 'done';
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    if (e instanceof PermanentJobError || job.attempts >= job.max_attempts) return fail(message);
    const wait = backoffSeconds(job.attempts, opts.rand);
    await pool.query(
      "update jobs set status = 'pending', last_error = $2, locked_until = null, run_at = now() + make_interval(secs => $3::int) where id = $1",
      [job.id, message.slice(0, 500), wait],
    );
    log.warn({ jobId: job.id, kind: job.kind, attempts: job.attempts, retryInSeconds: wait }, `job will retry: ${message.slice(0, 200)}`);
    return 'retry';
  }
}

/** Processes every currently due job, one batch at a time. Tests use this instead of waiting for the poll timer. */
export async function runDueJobs(pool: Pool, handlers: Record<string, Handler>, log: JobLogger, opts: { timeoutMs?: number; rand?: () => number } = {}): Promise<number> {
  let total = 0;
  for (;;) {
    const jobs = await claimJobs(pool, 20);
    if (!jobs.length) return total;
    await Promise.all(jobs.map((j) => processJob(pool, j, handlers, log, opts)));
    total += jobs.length;
  }
}

/** Polls the queue with bounded concurrency and drains in-flight jobs on shutdown. */
export class JobWorker {
  private timer: NodeJS.Timeout | null = null;
  private running = 0;
  private stopped = true;

  constructor(
    private readonly pool: Pool,
    private readonly handlers: Record<string, Handler>,
    private readonly log: JobLogger,
    private readonly opts: { concurrency?: number; pollMs?: number } = {},
  ) {}

  start(): void {
    if (!this.stopped) return;
    this.stopped = false;
    this.timer = setInterval(() => void this.tick(), this.opts.pollMs ?? 2000);
    this.timer.unref();
    void this.tick();
  }

  private async tick(): Promise<void> {
    const room = (this.opts.concurrency ?? 4) - this.running;
    if (this.stopped || room <= 0) return;
    let jobs: JobRow[] = [];
    try { jobs = await claimJobs(this.pool, room); } catch (e) { this.log.error({ err: e }, 'claiming jobs failed'); return; }
    for (const job of jobs) {
      this.running += 1;
      void processJob(this.pool, job, this.handlers, this.log)
        .catch((e) => this.log.error({ err: e, jobId: job.id }, 'recording a job result failed'))
        .finally(() => { this.running -= 1; });
    }
  }

  /** Stops claiming and waits (at most `graceMs`) for jobs already running. */
  async stop(graceMs = 10_000): Promise<void> {
    this.stopped = true;
    if (this.timer) clearInterval(this.timer);
    const deadline = Date.now() + graceMs;
    while (this.running > 0 && Date.now() < deadline) await new Promise((r) => setTimeout(r, 50));
  }
}

export interface JobStats {
  byStatus: Record<string, number>;
  failedByKind: Record<string, number>;
  oldestPendingSeconds: number | null;
  retrying: number;
}

/** Queue depth, failures and lag, for the admin endpoint and logs. */
export async function jobStats(pool: Pool): Promise<JobStats> {
  const status = await pool.query<{ status: string; n: string }>('select status, count(*) as n from jobs group by status');
  const failed = await pool.query<{ kind: string; n: string }>("select kind, count(*) as n from jobs where status = 'failed' group by kind");
  const lag = await pool.query<{ oldest: number | null; retrying: string }>(
    `select floor(extract(epoch from (now() - min(run_at))))::int as oldest, count(*) filter (where attempts > 0) as retrying
       from jobs where status = 'pending' and run_at <= now()`);
  return {
    byStatus: Object.fromEntries(status.rows.map((r) => [r.status, Number(r.n)])),
    failedByKind: Object.fromEntries(failed.rows.map((r) => [r.kind, Number(r.n)])),
    oldestPendingSeconds: lag.rows[0].oldest,
    retrying: Number(lag.rows[0].retrying),
  };
}

/** Housekeeping: finished jobs, dead device tokens and old read notifications. Safe to run repeatedly. */
export async function purgeOldRecords(pool: Pool): Promise<{ jobs: number; tokens: number; notifications: number }> {
  const jobs = await pool.query("delete from jobs where (status = 'done' and finished_at < now() - interval '7 days') or (status = 'failed' and finished_at < now() - interval '30 days')");
  // A token nobody has used or refreshed for 90 days is retired; revoked tokens are deleted after 30 more days.
  await pool.query(
    `update device_tokens set revoked_at = now(), revoked_reason = 'stale'
      where revoked_at is null and greatest(registered_at, coalesce(last_success_at, registered_at)) < now() - interval '90 days'`);
  const tokens = await pool.query("delete from device_tokens where revoked_at is not null and revoked_at < now() - interval '30 days'");
  const notifications = await pool.query("delete from notifications where read_at is not null and read_at < now() - interval '180 days'");
  return { jobs: jobs.rowCount ?? 0, tokens: tokens.rowCount ?? 0, notifications: notifications.rowCount ?? 0 };
}
