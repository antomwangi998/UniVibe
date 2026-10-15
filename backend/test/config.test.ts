import { describe, expect, it } from 'vitest';
import { loadConfig } from '../src/config';

const base = {
  DATABASE_URL: 'postgres://x',
  JWT_SECRET: 'j'.repeat(40),
  CODE_PEPPER: 'p'.repeat(40),
  RESEND_API_KEY: 're_test',
  NODE_ENV: 'production',
};

describe('loadConfig EMAIL_FROM', () => {
  it('falls back to the default when blank', () => {
    expect(loadConfig({ ...base, EMAIL_FROM: '' }).EMAIL_FROM).toBe('UniVibe <onboarding@resend.dev>');
    expect(loadConfig({ ...base, EMAIL_FROM: '   ' }).EMAIL_FROM).toBe('UniVibe <onboarding@resend.dev>');
  });

  it('strips accidental quotes', () => {
    expect(loadConfig({ ...base, EMAIL_FROM: '"UniVibe <hello@example.com>"' }).EMAIL_FROM).toBe('UniVibe <hello@example.com>');
  });

  it('keeps a good value', () => {
    expect(loadConfig({ ...base, EMAIL_FROM: 'UniVibe <hello@example.com>' }).EMAIL_FROM).toBe('UniVibe <hello@example.com>');
  });

  it('still requires the email key in production', () => {
    expect(() => loadConfig({ ...base, RESEND_API_KEY: '' })).toThrow();
  });
});
