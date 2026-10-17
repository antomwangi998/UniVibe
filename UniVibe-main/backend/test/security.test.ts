import { describe, expect, it } from 'vitest';
import * as sec from '../src/security';

const SECRET = 'a'.repeat(40);

describe('codes', () => {
  it('are six digits', () => {
    for (let i = 0; i < 200; i++) expect(sec.newCode()).toMatch(/^\d{6}$/);
  });

  it('hash differently per user, purpose and pepper', () => {
    const base = sec.hashCode('123456', 'u1', 'verify', SECRET);
    expect(sec.hashCode('123456', 'u1', 'verify', SECRET)).toBe(base);
    expect(sec.hashCode('123456', 'u2', 'verify', SECRET)).not.toBe(base);
    expect(sec.hashCode('123456', 'u1', 'reset', SECRET)).not.toBe(base);
    expect(sec.hashCode('123456', 'u1', 'verify', 'b'.repeat(40))).not.toBe(base);
  });

  it('compare in constant time without throwing on bad input', () => {
    const h = sec.hashCode('123456', 'u1', 'verify', SECRET);
    expect(sec.safeEqualHex(h, h)).toBe(true);
    expect(sec.safeEqualHex(h, sec.hashCode('654321', 'u1', 'verify', SECRET))).toBe(false);
    expect(sec.safeEqualHex(h, 'zz')).toBe(false);
    expect(sec.safeEqualHex('', '')).toBe(false);
  });
});

describe('tokens', () => {
  it('access tokens round-trip and reject tampering or the wrong secret', async () => {
    const token = await sec.signAccessToken('user-1', SECRET);
    expect(await sec.verifyAccessToken(token, SECRET)).toBe('user-1');
    expect(await sec.verifyAccessToken(token, 'b'.repeat(40))).toBeNull();
    expect(await sec.verifyAccessToken(token.slice(0, -2) + 'xx', SECRET)).toBeNull();
    expect(await sec.verifyAccessToken('not-a-jwt', SECRET)).toBeNull();
  });

  it('refresh tokens are long, unique and stored hashed', () => {
    const a = sec.newRefreshToken();
    const b = sec.newRefreshToken();
    expect(a).not.toBe(b);
    expect(a.length).toBeGreaterThanOrEqual(40);
    expect(sec.hashToken(a)).toMatch(/^[0-9a-f]{64}$/);
    expect(sec.hashToken(a)).not.toContain(a);
  });
});

describe('passwords', () => {
  it('verify only the right password', async () => {
    const hash = await sec.hashPassword('correct horse battery');
    expect(hash.startsWith('$argon2id$')).toBe(true);
    expect(await sec.verifyPassword(hash, 'correct horse battery')).toBe(true);
    expect(await sec.verifyPassword(hash, 'wrong')).toBe(false);
    expect(await sec.verifyPassword('garbage', 'x')).toBe(false);
  });
});

describe('student email domains', () => {
  it('match the domain and its subdomains only', () => {
    const domains = ['lakeside.example'];
    expect(sec.emailMatchesDomains('a@lakeside.example', domains)).toBe(true);
    expect(sec.emailMatchesDomains('a@cs.lakeside.example', domains)).toBe(true);
    expect(sec.emailMatchesDomains('a@notlakeside.example', domains)).toBe(false);
    expect(sec.emailMatchesDomains('a@gmail.com', domains)).toBe(false);
  });
});
