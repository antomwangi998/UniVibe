import { HttpError } from '../../errors';
import { audit } from '../moderation';
import { daysLeft, effectiveEntitlements, nextPeriod, type EntitlementKey, type PeriodRow } from './entitlements';
import type { Q } from './service';

type DbPeriod = { status: 'active' | 'refunded'; period_start: Date; period_end: Date; entitlements: string[]; business_id: string | null; plan_code: string; plan_name: string; audience: string };

async function periodsOf(q: Q, where: string, id: string): Promise<DbPeriod[]> {
  return (await q.query<DbPeriod>(
    `select s.status, s.period_start, s.period_end, s.business_id, p.entitlements, p.code as plan_code, p.name as plan_name, p.audience
       from subscriptions s join subscription_plans p on p.id = s.plan_id
      where ${where} and s.status = 'active' and s.period_end > now() order by s.period_end desc`, [id])).rows;
}

/** A person's own (non-business) entitlements, decided by the server clock. */
export async function userEntitlements(q: Q, userId: string, now = new Date()): Promise<EntitlementKey[]> {
  const rows = await periodsOf(q, 's.user_id = $1 and s.business_id is null', userId);
  return effectiveEntitlements(rows as PeriodRow[], now);
}

/** A business's entitlements. Paid status only; verification is a separate, explicit staff decision. */
export async function businessEntitlements(q: Q, businessId: string, now = new Date()): Promise<EntitlementKey[]> {
  const rows = await periodsOf(q, 's.business_id = $1', businessId);
  return effectiveEntitlements(rows as PeriodRow[], now);
}

/** Route guard: call at the top of any route that serves a Premium feature. */
export async function requireEntitlement(q: Q, userId: string, key: EntitlementKey): Promise<void> {
  if (!(await userEntitlements(q, userId)).includes(key)) throw new HttpError(403, 'premium_required', 'This needs UniVibe Premium.');
}
export async function requireBusinessEntitlement(q: Q, businessId: string, key: EntitlementKey): Promise<void> {
  if (!(await businessEntitlements(q, businessId)).includes(key)) throw new HttpError(403, 'premium_required', 'This needs Premium Business.');
}

/** Status shown on the Premium screen. `renews` is always false: manual payments never renew by themselves. */
export async function subscriptionStatus(q: Q, userId: string, now = new Date()) {
  const rows = await periodsOf(q, 's.user_id = $1 and s.business_id is null', userId);
  const live = rows.filter((r) => r.period_start.getTime() <= now.getTime());
  const cur = live[0];
  if (!cur) return { active: false as const, renews: false as const };
  return { active: true as const, renews: false as const, plan: cur.plan_code, planName: cur.plan_name, endsAt: cur.period_end, daysLeft: daysLeft(cur.period_end, now) };
}

/**
 * Called inside the transaction that marks an order paid. Idempotent: `subscriptions.order_id` is unique, so a
 * repeated call, a double click or a replayed confirmation can never add a second period.
 */
export async function activateSubscriptionForOrder(q: Q, orderId: string, actorId: string | null): Promise<boolean> {
  const o = (await q.query<{ status: string; advertiser_id: string | null; plan_id: string | null; business_id: string | null }>(
    'select status, advertiser_id, plan_id, business_id from orders where id = $1 for update', [orderId])).rows[0];
  if (!o || !o.plan_id || !o.advertiser_id || o.status !== 'paid') return false;
  const plan = (await q.query<{ period_days: number }>('select period_days from subscription_plans where id = $1', [o.plan_id])).rows[0];
  if (!plan) return false;
  const ends = (await q.query<{ period_end: Date }>(
    `select period_end from subscriptions where status = 'active' and ${o.business_id ? 'business_id = $1' : 'user_id = $1 and business_id is null'}`,
    [o.business_id ?? o.advertiser_id])).rows.map((r) => r.period_end);
  const [start, end] = nextPeriod(ends, new Date(), plan.period_days);
  const ins = await q.query(
    `insert into subscriptions (user_id, business_id, plan_id, order_id, period_start, period_end) values ($1, $2, $3, $4, $5, $6) on conflict (order_id) do nothing`,
    [o.advertiser_id, o.business_id, o.plan_id, orderId, start, end]);
  if ((ins.rowCount ?? 0) > 0) await audit(q, actorId, 'subscription.activate', 'order', orderId, { periodEnd: end.toISOString() });
  return (ins.rowCount ?? 0) > 0;
}

/** Called when a full refund is paid out: that period stops counting at once. Other periods are untouched. */
export async function endSubscriptionForOrder(q: Q, orderId: string, actorId: string | null): Promise<void> {
  const r = await q.query("update subscriptions set status = 'refunded' where order_id = $1 and status = 'active'", [orderId]);
  if ((r.rowCount ?? 0) > 0) await audit(q, actorId, 'subscription.refunded', 'order', orderId, {});
}
