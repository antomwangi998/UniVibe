import { describe, expect, it } from 'vitest';
import {
  CAMPAIGN_STATUSES, allowedNext, assertTransition, canTransition, clockTarget, deliveryBlockers, mayDeliver, mayResume,
  refundEligibleOnCancel, resumeTarget, type DeliveryFacts,
} from '../src/modules/monetization/campaign_state';
import { formatMoney, newOrderReference, normalizeReceipt, parseMajor } from '../src/modules/monetization/money';

const t = (s: string) => new Date(s);
const facts = (o: Partial<DeliveryFacts> = {}): DeliveryFacts => ({
  status: 'active', reviewStatus: 'approved', orderStatus: 'paid',
  startsAt: t('2026-11-01T00:00:00Z'), endsAt: t('2026-11-08T00:00:00Z'), impressionCap: null, deliveredImpressions: 0, ...o,
});
const mid = t('2026-11-03T00:00:00Z');

describe('campaign transitions', () => {
  it('only an administrator can approve or reject', () => {
    expect(canTransition('pending_review', 'approved', 'admin')).toBe(true);
    expect(canTransition('pending_review', 'approved', 'advertiser')).toBe(false);
    expect(canTransition('pending_review', 'approved', 'system')).toBe(false);
    expect(canTransition('pending_review', 'rejected', 'advertiser')).toBe(false);
  });
  it('an advertiser cannot skip payment or review', () => {
    expect(canTransition('draft', 'active', 'advertiser')).toBe(false);
    expect(canTransition('draft', 'pending_review', 'advertiser')).toBe(false);
    expect(canTransition('pending_payment', 'pending_review', 'advertiser')).toBe(false);
    expect(canTransition('pending_payment', 'active', 'admin')).toBe(false);
  });
  it('a rejected campaign cannot be revived by anyone', () => {
    for (const to of CAMPAIGN_STATUSES) for (const a of ['advertiser', 'admin', 'system'] as const) {
      if (to !== 'refund_pending') expect(canTransition('rejected', to, a)).toBe(false);
    }
    expect(canTransition('rejected', 'refund_pending', 'advertiser')).toBe(false);
  });
  it('completed and refunded are final', () => {
    for (const to of CAMPAIGN_STATUSES) for (const a of ['advertiser', 'admin', 'system'] as const) {
      expect(canTransition('completed', to, a)).toBe(false);
      expect(canTransition('refunded', to, a)).toBe(false);
    }
  });
  it('only an administrator completes a refund', () => {
    expect(canTransition('refund_pending', 'refunded', 'admin')).toBe(true);
    expect(canTransition('refund_pending', 'refunded', 'advertiser')).toBe(false);
    expect(canTransition('refund_pending', 'refunded', 'system')).toBe(false);
  });
  it('assertTransition throws on a move that is not listed', () => {
    expect(() => assertTransition('draft', 'active', 'admin')).toThrow();
    expect(() => assertTransition('pending_review', 'approved', 'admin')).not.toThrow();
  });
  it('allowedNext lists only what the actor may do', () => {
    expect(allowedNext('pending_review', 'advertiser')).toEqual(['cancelled']);
    expect(allowedNext('pending_review', 'admin').sort()).toEqual(['approved', 'cancelled', 'rejected']);
  });
});

describe('delivery rules', () => {
  it('an approved, paid, in-window, active campaign may be shown', () => {
    expect(mayDeliver(facts(), mid)).toBe(true);
  });
  it('unpaid never delivers, even if approved and active', () => {
    for (const orderStatus of ['pending_payment', 'cancelled', 'expired', 'refunded', null] as const) {
      expect(deliveryBlockers(facts({ orderStatus }), mid)).toContain('not_paid');
    }
  });
  it('unapproved or rejected never delivers', () => {
    expect(deliveryBlockers(facts({ reviewStatus: 'pending' }), mid)).toContain('not_approved');
    expect(deliveryBlockers(facts({ reviewStatus: 'rejected' }), mid)).toContain('not_approved');
  });
  it('respects the schedule and the impression cap', () => {
    expect(deliveryBlockers(facts(), t('2026-10-31T23:59:59Z'))).toContain('not_started');
    expect(deliveryBlockers(facts(), t('2026-11-08T00:00:00Z'))).toContain('ended');
    expect(deliveryBlockers(facts({ impressionCap: 100, deliveredImpressions: 100 }), mid)).toContain('cap_reached');
    expect(mayDeliver(facts({ impressionCap: 100, deliveredImpressions: 99 }), mid)).toBe(true);
  });
  it('paused and scheduled campaigns are not shown', () => {
    expect(mayDeliver(facts({ status: 'paused' }), mid)).toBe(false);
    expect(mayDeliver(facts({ status: 'scheduled' }), mid)).toBe(false);
  });
});

describe('clock transitions', () => {
  it('approved + paid moves to scheduled before start and active after', () => {
    expect(clockTarget(facts({ status: 'approved' }), t('2026-10-30T00:00:00Z'))).toBe('scheduled');
    expect(clockTarget(facts({ status: 'approved' }), mid)).toBe('active');
  });
  it('approved without payment never starts', () => {
    expect(clockTarget(facts({ status: 'approved', orderStatus: 'pending_payment' }), mid)).toBeNull();
    expect(clockTarget(facts({ status: 'scheduled', orderStatus: 'refunded' }), mid)).toBeNull();
  });
  it('an active campaign completes at its end or its cap', () => {
    expect(clockTarget(facts(), t('2026-11-08T00:00:00Z'))).toBe('completed');
    expect(clockTarget(facts({ impressionCap: 5, deliveredImpressions: 5 }), mid)).toBe('completed');
    expect(clockTarget(facts(), mid)).toBeNull();
  });
  it('resume lands in the right place', () => {
    const w = { startsAt: t('2026-11-01T00:00:00Z'), endsAt: t('2026-11-08T00:00:00Z'), impressionCap: null, deliveredImpressions: 0 };
    expect(resumeTarget(w, t('2026-10-30T00:00:00Z'))).toBe('scheduled');
    expect(resumeTarget(w, mid)).toBe('active');
    expect(resumeTarget(w, t('2026-11-09T00:00:00Z'))).toBe('completed');
  });
  it('an advertiser cannot resume a staff pause', () => {
    expect(mayResume('admin', 'advertiser')).toBe(false);
    expect(mayResume('advertiser', 'advertiser')).toBe(true);
    expect(mayResume('admin', 'admin')).toBe(true);
  });
  it('refund eligibility on cancel', () => {
    expect(refundEligibleOnCancel('scheduled', 0)).toBe(true);
    expect(refundEligibleOnCancel('active', 0)).toBe(true);
    expect(refundEligibleOnCancel('active', 10)).toBe(false);
    expect(refundEligibleOnCancel('paused', 0)).toBe(false);
  });
});

describe('money', () => {
  it('formats and parses minor units without floats', () => {
    expect(formatMoney(150000)).toBe('KES 1,500.00');
    expect(formatMoney(5)).toBe('KES 0.05');
    expect(formatMoney(123456789n)).toBe('KES 1,234,567.89');
    expect(parseMajor('1500')).toBe(150000);
    expect(parseMajor('1500.5')).toBe(150050);
    expect(parseMajor('0.07')).toBe(7);
    for (const bad of ['', '-5', '1e3', '1.234', 'abc', '1,500']) expect(parseMajor(bad)).toBeNull();
  });
  it('normalises M-Pesa receipt codes', () => {
    expect(normalizeReceipt(' qgh7 abc123 ')).toBe('QGH7ABC123');
    for (const bad of ['', 'short', 'has-dash-12345', 'x'.repeat(21)]) expect(normalizeReceipt(bad)).toBeNull();
  });
  it('order references match the database check', () => {
    let i = 0;
    const ref = newOrderReference((n) => (i++ * 7) % n);
    expect(ref).toMatch(/^UV-[A-Z0-9]{8}$/);
    expect(ref).not.toMatch(/[01OI]/);
  });
});
