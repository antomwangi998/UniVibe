/**
 * The campaign lifecycle, in one place. No database and no HTTP in this file, so every rule can be tested alone and
 * every route asks the same questions.
 *
 * Three things are tracked independently and ALL must hold before a campaign is shown:
 *   payment  - the order is paid (orders.status = 'paid'), confirmed by staff or a verified provider, never by the app
 *   review   - staff approved the content (campaigns.review_status = 'approved')
 *   schedule - now is inside [starts_at, ends_at) and no cap has been reached
 */

export const CAMPAIGN_STATUSES = [
  'draft', 'pending_payment', 'pending_review', 'approved', 'scheduled', 'active', 'paused',
  'completed', 'rejected', 'cancelled', 'refund_pending', 'refunded',
] as const;
export type CampaignStatus = (typeof CAMPAIGN_STATUSES)[number];

export type Actor = 'advertiser' | 'admin' | 'system';

/** from -> to -> who may make that move. A move that is not listed here does not exist. */
const TRANSITIONS: Record<CampaignStatus, Partial<Record<CampaignStatus, readonly Actor[]>>> = {
  draft: { pending_payment: ['advertiser'], pending_review: ['system'], cancelled: ['advertiser', 'admin'] },
  // Payment confirmed -> review. An unpaid order that is abandoned or expires is cancelled by the system.
  pending_payment: { pending_review: ['system'], cancelled: ['advertiser', 'admin', 'system'] },
  // 'system' may cancel when the thing being promoted stops being eligible (for example the event was cancelled).
  pending_review: { approved: ['admin'], rejected: ['admin'], cancelled: ['advertiser', 'admin', 'system'] },
  // Approval never starts a campaign by itself: the system moves it on once payment and schedule are checked again.
  approved: { scheduled: ['system'], active: ['system'], completed: ['system'], cancelled: ['advertiser', 'admin', 'system'] },
  scheduled: { active: ['system'], paused: ['advertiser', 'admin'], completed: ['system'], cancelled: ['advertiser', 'admin', 'system'] },
  active: { paused: ['advertiser', 'admin'], completed: ['system', 'admin'], cancelled: ['advertiser', 'admin', 'system'] },
  // Resume goes to scheduled, active or completed depending on the clock (see resumeTarget).
  paused: { scheduled: ['advertiser', 'admin'], active: ['advertiser', 'admin'], completed: ['system', 'admin'], cancelled: ['advertiser', 'admin', 'system'] },
  // Rejected and cancelled campaigns that were paid for move to refund_pending; unpaid ones simply stay put.
  rejected: { refund_pending: ['system', 'admin'] },
  cancelled: { refund_pending: ['system', 'admin'] },
  refund_pending: { refunded: ['admin'], cancelled: ['admin'] },
  completed: {},
  refunded: {},
};

export const TERMINAL: readonly CampaignStatus[] = ['completed', 'refunded'];

export function canTransition(from: CampaignStatus, to: CampaignStatus, actor: Actor): boolean {
  return !!TRANSITIONS[from]?.[to]?.includes(actor);
}

export function allowedNext(from: CampaignStatus, actor: Actor): CampaignStatus[] {
  const row = TRANSITIONS[from] ?? {};
  return (Object.keys(row) as CampaignStatus[]).filter((to) => row[to]!.includes(actor));
}

export class TransitionError extends Error {
  constructor(public readonly from: CampaignStatus, public readonly to: CampaignStatus, public readonly actor: Actor) {
    super(`A ${actor} cannot move a campaign from ${from} to ${to}.`);
  }
}

export function assertTransition(from: CampaignStatus, to: CampaignStatus, actor: Actor): void {
  if (!canTransition(from, to, actor)) throw new TransitionError(from, to, actor);
}

export interface DeliveryFacts {
  status: CampaignStatus;
  reviewStatus: 'pending' | 'approved' | 'rejected';
  /** orders.status of the campaign's order; null when it has no order. */
  orderStatus: 'pending_payment' | 'paid' | 'cancelled' | 'expired' | 'refunded' | null;
  startsAt: Date;
  endsAt: Date;
  impressionCap: number | null;
  deliveredImpressions: number;
}

export type Blocker = 'not_paid' | 'not_approved' | 'wrong_status' | 'not_started' | 'ended' | 'cap_reached';

/** Why a campaign may not be shown right now. An empty list means it may. */
export function deliveryBlockers(f: DeliveryFacts, now: Date): Blocker[] {
  const out: Blocker[] = [];
  if (f.orderStatus !== 'paid') out.push('not_paid');
  if (f.reviewStatus !== 'approved') out.push('not_approved');
  if (f.status !== 'active') out.push('wrong_status');
  if (now < f.startsAt) out.push('not_started');
  if (now >= f.endsAt) out.push('ended');
  if (f.impressionCap !== null && f.deliveredImpressions >= f.impressionCap) out.push('cap_reached');
  return out;
}

export const mayDeliver = (f: DeliveryFacts, now: Date): boolean => deliveryBlockers(f, now).length === 0;

/**
 * What the system should do with a campaign that is waiting on the clock. Returns the next status, or null when
 * nothing changes. Payment and review are re-checked here, so an approved campaign without a paid order never starts.
 */
export function clockTarget(f: DeliveryFacts, now: Date): CampaignStatus | null {
  const ready = f.orderStatus === 'paid' && f.reviewStatus === 'approved';
  const capped = f.impressionCap !== null && f.deliveredImpressions >= f.impressionCap;
  switch (f.status) {
    case 'approved':
      if (!ready) return null;
      if (now >= f.endsAt) return 'completed'; // approved too late: nothing was delivered
      return now >= f.startsAt ? 'active' : 'scheduled';
    case 'scheduled':
      if (!ready) return null;
      if (now >= f.endsAt) return 'completed';
      return now >= f.startsAt ? 'active' : null;
    case 'active':
      return now >= f.endsAt || capped ? 'completed' : null;
    default:
      return null;
  }
}

/** Where a paused campaign goes when it resumes. */
export function resumeTarget(f: Pick<DeliveryFacts, 'startsAt' | 'endsAt' | 'impressionCap' | 'deliveredImpressions'>, now: Date): CampaignStatus {
  if (now >= f.endsAt || (f.impressionCap !== null && f.deliveredImpressions >= f.impressionCap)) return 'completed';
  return now >= f.startsAt ? 'active' : 'scheduled';
}

/** An advertiser may resume only what they paused themselves; staff pauses stay with staff. */
export const mayResume = (pausedBy: 'advertiser' | 'admin' | null, actor: Actor): boolean =>
  actor !== 'advertiser' || pausedBy === 'advertiser';

/** Campaign statuses for which money is owed back if the campaign stops (it was paid and nothing was delivered or it was refused). */
export const refundEligibleOnCancel = (from: CampaignStatus, delivered: number): boolean =>
  ['pending_review', 'approved', 'scheduled'].includes(from) || (from === 'active' && delivered === 0);
