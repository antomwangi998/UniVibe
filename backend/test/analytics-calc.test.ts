import { describe, expect, it } from 'vitest';
import {
  type DayRow, countRsvpsAfterClick, ctr, dailyTotals, integrityFlags, mergeDaily, parseRange, placementTotals, shiftDay,
} from '../src/modules/monetization/analytics_calc';

const row = (day: string, placement: string, impressions: number, clicks: number, reach = impressions): DayRow => ({ day, placement, impressions, clicks, reach });
const NOW = new Date('2026-11-10T12:00:00Z');
const win = { startsAt: new Date('2026-11-01T00:00:00Z'), endsAt: new Date('2026-11-20T00:00:00Z') };

describe('ctr', () => {
  it('is clicks over impressions, and empty when nothing was shown', () => {
    expect(ctr(5, 100)).toBe(0.05);
    expect(ctr(0, 100)).toBe(0);
    expect(ctr(0, 0)).toBeNull();
    expect(ctr(3, 0)).toBeNull();
  });
});

describe('merging roll-ups with raw events', () => {
  it('uses raw rows for recent days and roll-ups for older ones', () => {
    const raw = [row('2026-11-09', 'home_feed', 10, 1), row('2026-11-02', 'home_feed', 3, 0)];
    const rolled = [row('2026-11-02', 'home_feed', 50, 5), row('2026-11-09', 'home_feed', 999, 99)];
    const m = mergeDaily(rolled, raw, '2026-11-10');
    expect(m.find((r) => r.day === '2026-11-09')!.impressions).toBe(10); // recent: raw wins
    expect(m.find((r) => r.day === '2026-11-02')!.impressions).toBe(50); // old: roll-up wins
  });
  it('keeps raw rows when an old day was never rolled up', () => {
    expect(mergeDaily([], [row('2026-10-01', 'home_feed', 7, 1)], '2026-11-10')[0].impressions).toBe(7);
  });
  it('keeps a roll-up when the raw rows are gone', () => {
    expect(mergeDaily([row('2026-08-01', 'discover_feed', 20, 2)], [], '2026-11-10')).toHaveLength(1);
  });
});

describe('daily totals and placements', () => {
  it('fills days with no activity so charts have no gaps', () => {
    const d = dailyTotals([row('2026-11-02', 'home_feed', 10, 2), row('2026-11-02', 'discover_feed', 10, 0)], '2026-11-01', '2026-11-04');
    expect(d.map((x) => x.day)).toEqual(['2026-11-01', '2026-11-02', '2026-11-03', '2026-11-04']);
    expect(d[1].impressions).toBe(20);
    expect(d[1].clicks).toBe(2);
    expect(d[1].ctr).toBe(0.1);
    expect(d[0].ctr).toBeNull();
  });
  it('splits by placement', () => {
    const p = placementTotals([row('2026-11-02', 'home_feed', 10, 1), row('2026-11-03', 'home_feed', 10, 1), row('2026-11-03', 'discover_feed', 5, 0)]);
    expect(p).toEqual([
      { placement: 'discover_feed', impressions: 5, clicks: 0, ctr: 0 },
      { placement: 'home_feed', impressions: 20, clicks: 2, ctr: 0.1 },
    ]);
  });
  it('shifts days across month ends', () => {
    expect(shiftDay('2026-03-01', -1)).toBe('2026-02-28');
    expect(shiftDay('2026-12-31', 1)).toBe('2027-01-01');
  });
});

describe('date filtering', () => {
  it('defaults to the campaign dates, capped at today', () => {
    const r = parseRange(undefined, undefined, NOW, win);
    expect(r.fromDay).toBe('2026-11-01');
    expect(r.toDay).toBe('2026-11-10');
    expect(r.toExclusive.toISOString()).toBe('2026-11-11T00:00:00.000Z');
  });
  it('includes the whole of the last day', () => {
    expect(parseRange('2026-11-03', '2026-11-03', NOW, win).toExclusive.toISOString()).toBe('2026-11-04T00:00:00.000Z');
  });
  it('rejects reversed, oversized and impossible dates', () => {
    expect(() => parseRange('2026-11-05', '2026-11-01', NOW, win)).toThrow();
    expect(() => parseRange('2026-01-01', '2026-11-01', NOW, win)).toThrow();
    expect(() => parseRange('2026-02-30', undefined, NOW, win)).toThrow();
    expect(() => parseRange('nonsense', undefined, NOW, win)).toThrow();
  });
});

describe('RSVP after click', () => {
  const T = Date.UTC(2026, 10, 5, 12);
  const H = 3_600_000;
  it('counts a sign-up that follows a tap within a week, once per student', () => {
    const clicks = new Map([['a', T], ['b', T], ['c', T]]);
    const n = countRsvpsAfterClick(clicks, [
      { key: 'a', at: T + H }, { key: 'a', at: T + 2 * H }, // twice: counts once
      { key: 'b', at: T - H },                               // signed up BEFORE the tap: not counted
      { key: 'c', at: T + 8 * 24 * H },                      // eight days later: too late
      { key: 'd', at: T + H },                               // never tapped
    ]);
    expect(n).toBe(1);
  });
});

describe('integrity signals', () => {
  const base = { impressions: 1000, clicks: 30, reach: 400, clickViewers: 25, topViewerClickShare: 0.05, peakHourShare: 0.1, frequencyCapPerDay: 3, days: 7 };
  it('stay quiet for ordinary numbers', () => { expect(integrityFlags(base)).toEqual([]); });
  it('stay quiet when there is too little data to judge', () => {
    expect(integrityFlags({ ...base, impressions: 20, clicks: 8, reach: 10, clickViewers: 1, topViewerClickShare: 1 })).toEqual([]);
  });
  it('flag each pattern', () => {
    const codes = (o: object) => integrityFlags({ ...base, ...o }).map((f) => f.code);
    expect(codes({ clicks: 400 })).toContain('unusually_high_ctr');
    expect(codes({ topViewerClickShare: 0.5 })).toContain('concentrated_clicks');
    expect(codes({ clicks: 40, clickViewers: 2 })).toContain('few_clickers');
    expect(codes({ reach: 10 })).toContain('frequency_exceeded');
    expect(codes({ peakHourShare: 0.9 })).toContain('single_hour_burst');
  });
});
