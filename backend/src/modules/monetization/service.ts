import { randomInt, randomUUID } from 'crypto';
import type { Pool, PoolClient } from 'pg';
import { notSuspended } from '../../access';
import { HttpError } from '../../errors';
import { audit } from '../moderation';
import { notify } from '../notify';
import {
  type Actor, type CampaignStatus, TransitionError, assertTransition, clockTarget, refundEligibleOnCancel,
  type DeliveryFacts,
} from './campaign_state';
import { newOrderReference } from './money';

export type Q = Pick<PoolClient, 'query'>;

export type PromotionNotice =
  | 'receipt_submitted' | 'payment_confirmed' | 'payment_rejected' | 'campaign_approved' | 'campaign_rejected' | 'campaign_paused'
  | 'campaign_stopped' | 'refund_approved' | 'refund_declined' | 'refund_paid';

/**
 * Tells a person something happened to their promotion. The text lives in the app, keyed by `kind`, so the server never
 * sends free text and nothing private travels in a notification. Each notice gets its own id because notifications are
 * de-duplicated per entity; the campaign id travels in the metadata.
 */
export async function notifyPromotion(q: Q, recipientId: string | null, kind: PromotionNotice, campaignId: string | null): Promise<void> {
  if (!recipientId) return;
  await notify(q, { recipientId, actorId: null, type: 'promotion', entityType: 'campaign', entityId: randomUUID(), metadata: { kind, ...(campaignId ? { campaignId } : {}) } });
}

/** Every staff member who can confirm payments (the platform has few). */
export async function notifyStaff(q: Q, kind: PromotionNotice, campaignId: string | null): Promise<void> {
  const staff = await q.query<{ id: string }>(`select u.id from users u where u.role = 'admin' and ${notSuspended('u')} limit 20`);
  for (const s of staff.rows) await notifyPromotion(q, s.id, kind, campaignId);
}

export const isUnique = (e: unknown) => (e as { code?: string } | null)?.code === '23505';

/** Which package kind may back which campaign kind, and which placements each kind may use. */
export const PACKAGE_FOR_CAMPAIGN = {
  event_promotion: 'event_promotion', business_listing: 'featured_business', sponsored_post: 'sponsored_post', discover_feature: 'featured_discover',
} as const;
export type CampaignKind = keyof typeof PACKAGE_FOR_CAMPAIGN;
export const PLACEMENTS_FOR_PACKAGE: Record<string, readonly string[]> = {
  event_promotion: ['home_feed', 'discover_feed', 'discover_events'],
  featured_business: ['business_directory', 'discover_feed'],
  sponsored_post: ['home_feed', 'discover_feed'],
  featured_discover: ['discover_feed', 'discover_events'],
  business_plan: ['business_directory'],
};

export interface CampaignRow {
  id: string; advertiser_id: string | null; kind: CampaignKind; business_id: string | null; event_id: string | null; post_id: string | null;
  package_id: string | null; order_id: string | null; status: CampaignStatus; review_status: 'pending' | 'approved' | 'rejected';
  paused_by: 'advertiser' | 'admin' | null; starts_at: Date; ends_at: Date; impression_cap: number | null; delivered_impressions: string | number;
  headline: string;
}

/** Locks the campaign row so two requests (or a request and the sweeper) cannot change it at the same time. */
export async function lockCampaign(q: Q, id: string): Promise<CampaignRow> {
  const r = (await q.query<CampaignRow>('select * from campaigns where id = $1 for update', [id])).rows[0];
  if (!r) throw new HttpError(404, 'not_found', 'Not found.');
  return r;
}

/**
 * The only way a campaign's status changes. Checks the move against the state machine for this actor, writes the
 * new status and a history row, in the caller's transaction. A refused move is a 409, never a silent no-op.
 */
export async function moveCampaign(
  q: Q, c: CampaignRow, to: CampaignStatus, actor: Actor, actorId: string | null, note = '', set: Record<string, string | null> = {},
): Promise<void> {
  try { assertTransition(c.status, to, actor); } catch (e) {
    if (e instanceof TransitionError) throw new HttpError(409, 'invalid_transition', `This campaign cannot move from ${c.status} to ${to}.`);
    throw e;
  }
  // Column names come from fixed call sites in this module, never from a request.
  const cols = Object.keys(set);
  const sets = cols.map((k, i) => `${k} = $${i + 3}`).join(', ');
  await q.query(
    `update campaigns set status = $2, status_changed_at = now(), updated_at = now()${cols.length ? ', ' + sets : ''} where id = $1`,
    [c.id, to, ...cols.map((k) => set[k])],
  );
  await q.query(
    'insert into campaign_status_history (campaign_id, from_status, to_status, actor_id, actor_kind, note) values ($1, $2, $3, $4, $5, $6)',
    [c.id, c.status, to, actorId, actor, note.slice(0, 500)],
  );
  c.status = to;
  // Keep the in-memory row in step with what was written, so a follow-up check in the same request sees it.
  Object.assign(c, set);
}

export const factsOf = (c: CampaignRow, orderStatus: DeliveryFacts['orderStatus']): DeliveryFacts => ({
  status: c.status, reviewStatus: c.review_status, orderStatus, startsAt: c.starts_at, endsAt: c.ends_at,
  impressionCap: c.impression_cap, deliveredImpressions: Number(c.delivered_impressions),
});

export async function orderStatusOf(q: Q, orderId: string | null): Promise<DeliveryFacts['orderStatus']> {
  if (!orderId) return null;
  return (await q.query<{ status: DeliveryFacts['orderStatus'] }>('select status from orders where id = $1', [orderId])).rows[0]?.status ?? null;
}

/** Applies the clock rules (start, finish, cap) to one locked campaign. Payment and review are re-checked inside. */
export async function advanceByClock(q: Q, c: CampaignRow, now = new Date()): Promise<CampaignStatus | null> {
  const target = clockTarget(factsOf(c, await orderStatusOf(q, c.order_id)), now);
  if (!target) return null;
  await moveCampaign(q, c, target, 'system', null, 'clock');
  // approved -> scheduled can immediately become active when the start has passed; clockTarget already chose the right one.
  return target;
}

/** The refund request for a paid order, created at most once (the unique index on open refunds backs this up). */
export async function requestRefundIfPaid(q: Q, c: CampaignRow, reason: string, requestedBy: string | null): Promise<string | null> {
  if (!c.order_id) return null;
  const p = (await q.query<{ id: string; amount_minor: string; order_id: string }>(
    "select id, amount_minor, order_id from payments where order_id = $1 and status = 'confirmed' limit 1", [c.order_id])).rows[0];
  if (!p || Number(p.amount_minor) <= 0) return null;
  const r = await q.query<{ id: string }>(
    `insert into refunds (payment_id, order_id, amount_minor, reason, requested_by) values ($1, $2, $3, $4, $5)
       on conflict do nothing returning id`, [p.id, p.order_id, p.amount_minor, reason.slice(0, 500), requestedBy]);
  return r.rows[0]?.id ?? null;
}

/** Stops a campaign for good and, when money is owed back, opens a refund request for staff to decide. */
export async function stopCampaign(q: Q, c: CampaignRow, actor: Actor, actorId: string | null, reason: string, wantRefund?: boolean): Promise<void> {
  const delivered = Number(c.delivered_impressions);
  const paid = (await orderStatusOf(q, c.order_id)) === 'paid';
  const refund = paid && (wantRefund ?? refundEligibleOnCancel(c.status, delivered));
  if (c.status !== 'rejected' && c.status !== 'cancelled') await moveCampaign(q, c, 'cancelled', actor, actorId, reason);
  if (c.order_id && !paid) await q.query("update orders set status = 'cancelled', updated_at = now() where id = $1 and status = 'pending_payment'", [c.order_id]);
  if (refund && (await requestRefundIfPaid(q, c, reason, actorId))) await moveCampaign(q, c, 'refund_pending', 'system', actorId, 'refund requested');
}

/** True when the event can still be promoted: published, not hidden, not over, and its organizer is the advertiser. */
export async function eventPromotable(q: Q, eventId: string, organizerId: string | null): Promise<{ ok: boolean; endsAt?: Date }> {
  const e = (await q.query<{ organizer_id: string; ends_at: Date }>(
    "select organizer_id, ends_at from events where id = $1 and status = 'published' and hidden_at is null and ends_at > now()", [eventId])).rows[0];
  if (!e || (organizerId !== null && e.organizer_id !== organizerId)) return { ok: false };
  return { ok: true, endsAt: e.ends_at };
}

export async function newUniqueReference(q: Q): Promise<string> {
  for (let i = 0; i < 6; i++) {
    const ref = newOrderReference((n) => randomInt(n));
    if (!(await q.query('select 1 from orders where reference = $1', [ref])).rowCount) return ref;
  }
  throw new HttpError(503, 'try_again', 'Please try again.');
}

export interface SweepResult { advanced: number; stopped: number; expiredOrders: number }

/**
 * Runs every minute or so. Safe to run twice and from several processes: each campaign is locked and re-read before
 * it is touched. Handles three things nobody else would: campaigns whose time came, promotions whose event stopped
 * being promotable (cancelled, hidden, finished or deleted), and orders nobody paid in time.
 */
export async function sweepCampaigns(pool: Pool): Promise<SweepResult> {
  const out: SweepResult = { advanced: 0, stopped: 0, expiredOrders: 0 };
  const run = async (fn: (client: PoolClient) => Promise<void>) => {
    const client = await pool.connect();
    try { await client.query('begin'); await fn(client); await client.query('commit'); }
    catch (e) { await client.query('rollback').catch(() => {}); throw e; }
    finally { client.release(); }
  };

  const due = await pool.query<{ id: string }>(
    "select id from campaigns where status in ('approved', 'scheduled', 'active') order by status_changed_at limit 200");
  for (const { id } of due.rows) {
    await run(async (client) => {
      const c = await lockCampaign(client, id);
      let moved = await advanceByClock(client, c);
      // approved -> scheduled leaves nothing more to do; an active campaign past its end completes in the same pass.
      if (moved === 'scheduled') moved = (await advanceByClock(client, c)) ?? moved;
      if (moved) out.advanced++;
    });
  }

  const gone = await pool.query<{ id: string }>(
    `select c.id from campaigns c left join events e on e.id = c.event_id
      where c.kind = 'event_promotion' and c.status in ('pending_review', 'approved', 'scheduled', 'active', 'paused')
        and (e.id is null or e.status <> 'published' or e.hidden_at is not null or e.ends_at <= now()) limit 200`);
  for (const { id } of gone.rows) {
    await run(async (client) => {
      const c = await lockCampaign(client, id);
      if (!['pending_review', 'approved', 'scheduled', 'active', 'paused'].includes(c.status)) return;
      const still = c.event_id ? await eventPromotable(client, c.event_id, null) : { ok: false };
      if (still.ok) return;
      // Policy: an event that stops being promotable before it ran ends the promotion with a refund; one that already
      // delivered ends it without (see docs/MONETIZATION.md). An event that simply finished is completed, not refunded.
      const finished = c.event_id ? (await client.query("select 1 from events where id = $1 and status in ('published','completed') and ends_at <= now()", [c.event_id])).rowCount : 0;
      if (finished && c.status === 'active') await moveCampaign(client, c, 'completed', 'system', null, 'event ended');
      else await stopCampaign(client, c, 'system', null, 'The event is no longer available to promote.');
      await notifyPromotion(client, c.advertiser_id, 'campaign_stopped', c.id);
      await audit(client, null, 'campaign.event_ineligible', 'campaign', c.id, { to: c.status });
      out.stopped++;
    });
  }

  const stale = await pool.query<{ id: string }>("select id from orders where status = 'pending_payment' and expires_at <= now() limit 200");
  for (const { id } of stale.rows) {
    await run(async (client) => {
      const o = (await client.query<{ status: string }>('select status from orders where id = $1 for update', [id])).rows[0];
      if (!o || o.status !== 'pending_payment') return;
      // A receipt waiting for staff keeps its order alive: expiring it could cancel something the advertiser did pay for.
      if ((await client.query("select 1 from payments where order_id = $1 and status = 'awaiting_verification'", [id])).rowCount) return;
      await client.query("update orders set status = 'expired', updated_at = now() where id = $1", [id]);
      const c = (await client.query<{ id: string }>('select id from campaigns where order_id = $1', [id])).rows[0];
      if (c) {
        const locked = await lockCampaign(client, c.id);
        if (locked.status === 'pending_payment') await moveCampaign(client, locked, 'cancelled', 'system', null, 'payment window expired');
      }
      out.expiredOrders++;
    });
  }
  return out;
}

/**
 * Housekeeping for ad events. Re-computes the last three full UTC days of totals from the raw rows (safe to run any
 * number of times), then deletes raw rows older than 90 days. Totals in `campaign_daily` are what long-range reports read.
 */
export async function rollupCampaignEvents(pool: Pool): Promise<{ days: number; purged: number }> {
  const today = "(date_trunc('day', now() at time zone 'UTC') at time zone 'UTC')";
  const up = await pool.query(
    `insert into campaign_daily (campaign_id, day, placement, impressions, clicks, reach)
     select campaign_id, (created_at at time zone 'UTC')::date, placement,
            count(*) filter (where kind = 'impression'), count(*) filter (where kind = 'click'),
            count(distinct viewer_key) filter (where kind = 'impression')
       from campaign_events where created_at >= ${today} - interval '3 days' and created_at < ${today}
      group by 1, 2, 3
     on conflict (campaign_id, day, placement) do update set impressions = excluded.impressions, clicks = excluded.clicks, reach = excluded.reach`);
  // Never delete a day that has not been rolled up: only rows older than the roll-up window and the 90-day retention.
  const gone = await pool.query("delete from campaign_events where created_at < now() - interval '90 days'");
  return { days: up.rowCount ?? 0, purged: gone.rowCount ?? 0 };
}
