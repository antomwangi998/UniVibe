import { describe, expect, it } from 'vitest';
import { loadConfig, missingR2Settings, r2Settings } from '../src/config';

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

describe('R2 settings', () => {
  const r2 = {
    R2_ENDPOINT: 'https://abc123.r2.cloudflarestorage.com/univibedb',
    R2_ACCESS_KEY_ID: 'key',
    R2_SECRET_ACCESS_KEY: 'secret',
    R2_BUCKET: 'univibedb',
  };

  it('are complete when all four are set', () => {
    const config = loadConfig({ ...base, ...r2 });
    expect(missingR2Settings(config)).toEqual([]);
    expect(r2Settings(config)).not.toBeNull();
  });

  it('name exactly what is missing, never a value', () => {
    const config = loadConfig({ ...base, ...r2, R2_SECRET_ACCESS_KEY: '', R2_BUCKET: '   ' });
    expect(missingR2Settings(config)).toEqual(['R2_SECRET_ACCESS_KEY', 'R2_BUCKET']);
    expect(r2Settings(config)).toBeNull();
  });

  it('are all missing when none is set', () => {
    expect(missingR2Settings(loadConfig({ ...base }))).toHaveLength(4);
  });
});
