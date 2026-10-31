import { describe, expect, it } from 'vitest';
import { shareInFlight, TtlCache } from '../src/previewcache';

describe('TtlCache', () => {
  it('returns what was stored and undefined for a miss, and can store null on purpose', () => {
    const c = new TtlCache<string | null>({ ttlMs: 1000, maxEntries: 10 });
    c.set('a', 'x');
    c.set('b', null);
    expect(c.get('a')).toBe('x');
    expect(c.get('b')).toBeNull();
    expect(c.get('missing')).toBeUndefined();
  });

  it('forgets an entry after its time, and a per-entry time can be shorter', () => {
    let now = 0;
    const c = new TtlCache<number>({ ttlMs: 1000, maxEntries: 10, now: () => now });
    c.set('long', 1);
    c.set('short', 2, 100);
    now = 150;
    expect(c.get('short')).toBeUndefined();
    expect(c.get('long')).toBe(1);
    now = 1000;
    expect(c.get('long')).toBeUndefined();
  });

  it('drops the entry used longest ago when full', () => {
    const c = new TtlCache<number>({ ttlMs: 1000, maxEntries: 2 });
    c.set('a', 1);
    c.set('b', 2);
    c.get('a'); // a was used, so b is now the oldest
    c.set('c', 3);
    expect(c.get('b')).toBeUndefined();
    expect(c.get('a')).toBe(1);
    expect(c.get('c')).toBe(3);
  });

  it('limits the total weight, and never keeps a value that could not fit alone', () => {
    const c = new TtlCache<string>({ ttlMs: 1000, maxEntries: 10, maxWeight: 10 }, (v) => v.length);
    c.set('a', '123456');
    c.set('b', '1234567');
    expect(c.get('a')).toBeUndefined();
    expect(c.get('b')).toBe('1234567');
    c.set('huge', 'x'.repeat(11));
    expect(c.get('huge')).toBeUndefined();
    expect(c.get('b')).toBe('1234567');
  });

  it('replacing a key does not double count its weight', () => {
    const c = new TtlCache<string>({ ttlMs: 1000, maxEntries: 10, maxWeight: 10 }, (v) => v.length);
    c.set('a', '123456');
    c.set('a', '1234567');
    c.set('b', '123');
    expect(c.get('a')).toBe('1234567');
    expect(c.get('b')).toBe('123');
  });
});

describe('shareInFlight', () => {
  it('runs once for callers asking at the same time, then again for a later call', async () => {
    const running = new Map<string, Promise<number>>();
    let runs = 0;
    const run = async () => { runs++; await Promise.resolve(); return runs; };
    const [x, y] = await Promise.all([shareInFlight(running, 'k', run), shareInFlight(running, 'k', run)]);
    expect(x).toBe(1);
    expect(y).toBe(1);
    expect(running.size).toBe(0);
    expect(await shareInFlight(running, 'k', run)).toBe(2);
  });

  it('lets the next caller try again after a failure', async () => {
    const running = new Map<string, Promise<number>>();
    await expect(shareInFlight(running, 'k', async () => { throw new Error('boom'); })).rejects.toThrow('boom');
    expect(await shareInFlight(running, 'k', async () => 5)).toBe(5);
  });
});
