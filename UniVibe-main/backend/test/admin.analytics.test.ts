import { describe, expect, it } from 'vitest';
import { resolveRange } from '../src/modules/admin';

const now = new Date('2026-10-24T23:30:00Z');

describe('analytics date ranges', () => {
  it('defaults to the last 30 UTC days, ending today, with an equal period before it', () => {
    const r = resolveRange({}, now);
    expect(r).toMatchObject({ from: '2026-09-25', to: '2026-10-24', length: 30, prevFrom: '2026-08-26', prevTo: '2026-09-24' });
  });
  it('supports 7 and 90 days, and rejects other presets', () => {
    expect(resolveRange({ days: '7' }, now).from).toBe('2026-10-18');
    expect(resolveRange({ days: '90' }, now).length).toBe(90);
    expect(() => resolveRange({ days: '45' }, now)).toThrow();
  });
  it('accepts a custom range, caps the end at today and keeps the comparison the same length', () => {
    const r = resolveRange({ from: '2026-10-01', to: '2026-12-31' }, now);
    expect(r.to).toBe('2026-10-24');
    expect(r.length).toBe(24);
    expect(r.prevTo).toBe('2026-09-30');
  });
  it('rejects half a range, reversed dates, impossible dates, unknown keys and ranges over a year', () => {
    expect(() => resolveRange({ from: '2026-10-01' }, now)).toThrow();
    expect(() => resolveRange({ from: '2026-10-10', to: '2026-10-01' }, now)).toThrow();
    expect(() => resolveRange({ from: '2026-02-31', to: '2026-03-05' }, now)).toThrow(/valid from and to/);
    expect(() => resolveRange({ from: '2026-04-31', to: '2026-05-05' }, now)).toThrow();
    expect(() => resolveRange({ days: '7', sql: '1' }, now)).toThrow();
    expect(() => resolveRange({ from: '2024-01-01', to: '2026-10-01' }, now)).toThrow();
  });
  it('a single UTC day just before midnight still counts as that day', () => {
    const r = resolveRange({ from: '2026-10-24', to: '2026-10-24' }, now);
    expect(r.length).toBe(1);
    expect(r.prevFrom).toBe('2026-10-23');
  });
});
