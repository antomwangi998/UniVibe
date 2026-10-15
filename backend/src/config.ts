import { z } from 'zod';

const schema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().int().positive().default(3000),
  DATABASE_URL: z.string().min(1),
  JWT_SECRET: z.string().min(32, 'JWT_SECRET must be at least 32 characters'),
  CODE_PEPPER: z.string().min(32, 'CODE_PEPPER must be at least 32 characters'),
  RESEND_API_KEY: z.string().optional(),
  EMAIL_FROM: z.string().default('UniVibe <onboarding@resend.dev>'),
});

export type Config = z.infer<typeof schema>;

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  // A blank or quoted EMAIL_FROM (an easy slip in a dashboard) would make Resend answer 422, so tidy it up.
  const from = env.EMAIL_FROM?.trim().replace(/^["']+|["']+$/g, '');
  const config = schema.parse({ ...env, RESEND_API_KEY: env.RESEND_API_KEY || undefined, EMAIL_FROM: from || undefined });
  if (config.NODE_ENV === 'production' && !config.RESEND_API_KEY) {
    throw new Error('RESEND_API_KEY is required in production');
  }
  return config;
}
