import { describe, expect, it } from 'vitest';
import { coverageEnd, daysLeft, effectiveEntitlements, isEntitlementKey, nextPeriod, type PeriodRow } from '../src/modules/monetization/entitlements';

const D = (s: string) => new Date(`${s}T00:00:00Z`);
const row = (over: Partial<PeriodRow> = {}): PeriodRow => ({ status: 'active', period_start: D('2026-10-01'), period_end: D('2026-11-01'), entitlements: ['premium.chat_themes'], ...over });
const now = D('2026-10-15');

describe('effectiveEntitlements', () => {
  it('grants the plan keys during a live period', () => expect(effectiveEntitlements([row()], now)).toEqual(['premium.chat_themes']));
  it('grants nothing after the period ends (expired)', () => expect(effectiveEntitlements([row()], D('2026-11-01'))).toEqual([]));
  it('grants nothing before the period starts', () => expect(effectiveEntitlements([row({ period_start: D('2026-11-01'), period_end: D('2026-12-01') })], now)).toEqual([]));
  it('a refunded period never counts', () => expect(effectiveEntitlements([row({ status: 'refunded' })], now)).toEqual([]));
  it('unknown keys grant nothing', () => expect(effectiveEntitlements([row({ entitlements: ['premium.everything', 'admin'] })], now)).toEqual([]));
  it('merges and de-duplicates across periods', () => {
    const r = effectiveEntitlements([row(), row({ entitlements: ['premium.chat_themes', 'premium.profile_themes'] })], now);
    expect(r).toEqual(['premium.chat_themes', 'premium.profile_themes']);
  });
  it('no rows means no entitlements', () => expect(effectiveEntitlements([], now)).toEqual([]));
});

describe('nextPeriod', () => {
  it('starts now when nothing is running', () => {
    const [s, e] = nextPeriod([], now, 30);
    expect(s).toEqual(now); expect(e).toEqual(D('2026-11-14'));
  });
  it('starts now after a lapse', () => expect(nextPeriod([D('2026-09-01')], now, 30)[0]).toEqual(now));
  it('extends from the end of a running period, losing no days', () => {
    const [s, e] = nextPeriod([D('2026-11-01'), D('2026-10-20')], now, 30);
    expect(s).toEqual(D('2026-11-01')); expect(e).toEqual(D('2026-12-01'));
  });
});

describe('helpers', () => {
  it('daysLeft rounds up and never goes negative', () => { expect(daysLeft(D('2026-10-16'), now)).toBe(1); expect(daysLeft(D('2026-10-01'), now)).toBe(0); });
  it('recognises only known keys', () => { expect(isEntitlementKey('premium.chat_themes')).toBe(true); expect(isEntitlementKey('x')).toBe(false); });
});

import { applySwitches } from '../src/modules/monetization/entitlements';
describe('applySwitches (staff kill switches)', () => {
  it('removes only the switched-off keys', () => expect(applySwitches(['premium.chat_themes', 'premium.profile_themes'], ['premium.chat_themes'])).toEqual(['premium.profile_themes']));
  it('leaves everything when nothing is off', () => expect(applySwitches(['premium.chat_themes'], [])).toEqual(['premium.chat_themes']));
  it('switching off a key nobody has changes nothing', () => expect(applySwitches(['premium.chat_themes'], ['business.offers'])).toEqual(['premium.chat_themes']));
});

describe('coverageEnd (days already paid for, including queued periods)', () => {
  it('counts a queued period behind the running one', () => expect(coverageEnd([row(), row({ period_start: D('2026-11-01'), period_end: D('2026-12-01') })], now)).toEqual(D('2026-12-01')));
  it('is null when nothing is live', () => expect(coverageEnd([row({ period_end: D('2026-10-10') })], now)).toBe(null));
  it('is null when only a future period exists', () => expect(coverageEnd([row({ period_start: D('2026-11-01'), period_end: D('2026-12-01') })], now)).toBe(null));
  it('ignores a refunded period', () => expect(coverageEnd([row({ status: 'refunded' })], now)).toBe(null));
});
