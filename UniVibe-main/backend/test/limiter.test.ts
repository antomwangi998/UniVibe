import { describe, expect, it } from 'vitest';
import { RateLimiter } from '../src/limiter';

describe('RateLimiter', () => {
  it('allows up to max, then says how long to wait', () => {
    let now = 0;
    const limiter = new RateLimiter(() => now);
    expect(limiter.hit('k', 2, 60)).toBe(0);
    now = 10_000;
    expect(limiter.hit('k', 2, 60)).toBe(0);
    now = 20_000;
    expect(limiter.hit('k', 2, 60)).toBe(40);
  });

  it('frees up once the window has passed and keeps keys separate', () => {
    let now = 0;
    const limiter = new RateLimiter(() => now);
    limiter.hit('a', 1, 60);
    expect(limiter.hit('a', 1, 60)).toBeGreaterThan(0);
    expect(limiter.hit('b', 1, 60)).toBe(0);
    now = 61_000;
    expect(limiter.hit('a', 1, 60)).toBe(0);
  });
});
