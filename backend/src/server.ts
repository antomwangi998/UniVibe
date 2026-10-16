import { buildApp } from './app';
import { loadConfig, missingR2Settings, r2Settings } from './config';
import { createPool, migrate } from './db';
import { createConsoleMailer, createResendMailer } from './email';
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
  const app = buildApp({ pool, mailer, config, storage });
  await app.listen({ port: config.PORT, host: '0.0.0.0' });

  if (storage) {
    const sweep = () => cleanupPendingMedia(pool, storage).catch((e) => app.log.error({ err: e }, 'media cleanup failed'));
    void sweep();
    setInterval(sweep, 6 * 3600 * 1000).unref();
  }

  const shutdown = async () => {
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
