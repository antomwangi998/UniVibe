import { buildApp } from './app';
import { loadConfig, missingR2Settings, r2Settings } from './config';
import { createPool, migrate } from './db';
import { makeCtx } from './context';
import { createConsoleMailer, createResendMailer } from './email';
import { JobWorker, purgeOldRecords } from './jobs';
import { RateLimiter } from './limiter';
import { deliveryHandlers } from './modules/delivery';
import { createFcmSender, disabledPushSender } from './push';
import { Hub } from './realtime';
import { purgeExpiredEvents, sendEventReminders } from './modules/events';
import { cleanupPendingMedia } from './media';
import { createR2Storage } from './storage';

async function main() {
  const config = loadConfig();
  const pool = createPool(config.DATABASE_URL);
  await migrate(pool);
  const mailer = config.RESEND_API_KEY ? createResendMailer(config.RESEND_API_KEY, config.EMAIL_FROM) : createConsoleMailer();
  const r2 = r2Settings(config);
  const storage = r2 ? createR2Storage(r2) : undefined;
  if (!storage) console.warn(`R2 is not configured, so photo and video uploads are switched off. Missing: ${missingR2Settings(config).join(', ')}`);
  const hub = new Hub();
  const app = buildApp({ pool, mailer, config, storage, hub });
  await app.listen({ port: config.PORT, host: '0.0.0.0' });

  if (storage) {
    const sweep = () => cleanupPendingMedia(pool, storage).catch((e) => app.log.error({ err: e }, 'media cleanup failed'));
    void sweep();
    setInterval(sweep, 6 * 3600 * 1000).unref();
  }

  // Background jobs. Each is safe to run twice (reminders are guarded by a unique row; the purge just finds nothing).
  const jobs = makeCtx(pool, config, new RateLimiter(), storage ?? null);
  const every = (name: string, ms: number, job: () => Promise<unknown>) => {
    const run = () => job().catch((e) => app.log.error({ err: e, job: name }, 'background job failed'));
    void run();
    setInterval(run, ms).unref();
  };
  every('event-reminders', 5 * 60 * 1000, () => sendEventReminders(jobs));
  every('event-retention', 6 * 3600 * 1000, () => purgeExpiredEvents(jobs));

  // Queue worker: live notification events and push. Safe to run in this process or in several; rows are claimed with SKIP LOCKED.
  // NOTE: live events go to the sockets of the process that handles the job, so keep one web instance (see docs).
  const push = config.FCM_SERVICE_ACCOUNT_JSON ? createFcmSender(config.FCM_SERVICE_ACCOUNT_JSON) : disabledPushSender;
  if (!push.enabled) console.warn('FCM_SERVICE_ACCOUNT_JSON is not set, so push notifications are skipped (in-app notifications still work).');
  const worker = new JobWorker(pool, deliveryHandlers(jobs, hub, push), app.log);
  worker.start();
  every('purge-old-records', 6 * 3600 * 1000, async () => app.log.info(await purgeOldRecords(pool), 'purged old records'));

  const shutdown = async () => {
    await worker.stop(); // let running jobs finish before the database closes
    await app.close();
    await pool.end();
    process.exit(0);
  };
  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
