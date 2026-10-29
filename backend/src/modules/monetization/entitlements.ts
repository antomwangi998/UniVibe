/**
 * Entitlements, in one place. The server decides; the app only displays what the server says.
 * Pure functions here (no database, no HTTP) so every rule is testable alone.
 */

export const ENTITLEMENT_KEYS = [
  'premium.profile_themes', 'premium.profile_frames', 'premium.chat_themes', 'premium.sticker_packs',
  'premium.creator_analytics', 'premium.post_scheduling', 'premium.advanced_feed_controls',
  'business.analytics', 'business.offers', 'business.scheduled_posts',
] as const;
export type EntitlementKey = (typeof ENTITLEMENT_KEYS)[number];

export const isEntitlementKey = (k: string): k is EntitlementKey => (ENTITLEMENT_KEYS as readonly string[]).includes(k);

export interface PeriodRow {
  status: 'active' | 'refunded';
  period_start: Date;
  period_end: Date;
  entitlements: string[];
  business_id?: string | null;
}

/** A period counts only while it is active, has started and has not ended. A refunded period never counts. */
export function isLive(p: Pick<PeriodRow, 'status' | 'period_start' | 'period_end'>, now: Date): boolean {
  return p.status === 'active' && p.period_start.getTime() <= now.getTime() && now.getTime() < p.period_end.getTime();
}

/** Union of the entitlements of every live period. Unknown keys are dropped, so a bad row can grant nothing. */
export function effectiveEntitlements(rows: readonly PeriodRow[], now: Date): EntitlementKey[] {
  const out = new Set<EntitlementKey>();
  for (const r of rows) if (isLive(r, now)) for (const k of r.entitlements) if (isEntitlementKey(k)) out.add(k);
  return [...out].sort();
}

/**
 * Where a purchase starts. Buying while a period is still running extends from its end (no days are lost);
 * buying after a lapse starts now. Returns [start, end].
 */
export function nextPeriod(existingEnds: readonly Date[], now: Date, periodDays: number): [Date, Date] {
  const latest = existingEnds.reduce<Date | null>((m, d) => (!m || d.getTime() > m.getTime() ? d : m), null);
  const start = latest && latest.getTime() > now.getTime() ? latest : now;
  return [start, new Date(start.getTime() + periodDays * 86_400_000)];
}

/** Whole days left, rounded up, never negative. */
export const daysLeft = (end: Date, now: Date): number => Math.max(0, Math.ceil((end.getTime() - now.getTime()) / 86_400_000));
