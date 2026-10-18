import { z } from 'zod';

const schema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().int().positive().default(3000),
  DATABASE_URL: z.string().min(1),
  JWT_SECRET: z.string().min(32, 'JWT_SECRET must be at least 32 characters'),
  CODE_PEPPER: z.string().min(32, 'CODE_PEPPER must be at least 32 characters'),
  RESEND_API_KEY: z.string().optional(),
  EMAIL_FROM: z.string().default('UniVibe <onboarding@resend.dev>'),
  // Cloudflare R2 (private bucket). All four are needed for photo and video uploads.
  R2_ENDPOINT: z.string().url().optional(),
  R2_ACCESS_KEY_ID: z.string().optional(),
  R2_SECRET_ACCESS_KEY: z.string().optional(),
  R2_BUCKET: z.string().optional(),
  // Firebase service account (JSON, or the same JSON base64-encoded). Server-side only; without it push is skipped.
  FCM_SERVICE_ACCOUNT_JSON: z.string().optional(),
});

export type Config = z.infer<typeof schema>;

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  // A blank or quoted EMAIL_FROM (an easy slip in a dashboard) would make Resend answer 422, so tidy it up.
  const from = env.EMAIL_FROM?.trim().replace(/^["']+|["']+$/g, '');
  const config = schema.parse({
    ...env,
    RESEND_API_KEY: env.RESEND_API_KEY || undefined,
    EMAIL_FROM: from || undefined,
    R2_ENDPOINT: env.R2_ENDPOINT?.trim() || undefined,
    R2_ACCESS_KEY_ID: env.R2_ACCESS_KEY_ID?.trim() || undefined,
    R2_SECRET_ACCESS_KEY: env.R2_SECRET_ACCESS_KEY?.trim() || undefined,
    R2_BUCKET: env.R2_BUCKET?.trim() || undefined,
    FCM_SERVICE_ACCOUNT_JSON: env.FCM_SERVICE_ACCOUNT_JSON?.trim() || undefined,
  });
  if (config.NODE_ENV === 'production' && !config.RESEND_API_KEY) {
    throw new Error('RESEND_API_KEY is required in production');
  }
  return config;
}

/** The R2 settings, or null when any of the four is missing (uploads are then switched off). */
export function r2Settings(config: Config) {
  const { R2_ENDPOINT, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET } = config;
  if (!R2_ENDPOINT || !R2_ACCESS_KEY_ID || !R2_SECRET_ACCESS_KEY || !R2_BUCKET) return null;
  return { endpoint: R2_ENDPOINT, accessKeyId: R2_ACCESS_KEY_ID, secretAccessKey: R2_SECRET_ACCESS_KEY, bucket: R2_BUCKET };
}

/** Names (never values) of the R2 settings that are missing, so a misconfigured server can say exactly what to fix. */
export function missingR2Settings(config: Config): string[] {
  const wanted = ['R2_ENDPOINT', 'R2_ACCESS_KEY_ID', 'R2_SECRET_ACCESS_KEY', 'R2_BUCKET'] as const;
  return wanted.filter((name) => !config[name]);
}
