import { buildApp } from './app';
import { loadConfig } from './config';
import { createPool, migrate } from './db';
import { createConsoleMailer, createResendMailer } from './email';

async function main() {
  const config = loadConfig();
  const pool = createPool(config.DATABASE_URL);
  await migrate(pool);
  const mailer = config.RESEND_API_KEY ? createResendMailer(config.RESEND_API_KEY, config.EMAIL_FROM) : createConsoleMailer();
  const app = buildApp({ pool, mailer, config });
  await app.listen({ port: config.PORT, host: '0.0.0.0' });

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
