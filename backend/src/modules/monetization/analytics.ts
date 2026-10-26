import type { Pool } from 'pg';
import { HttpError } from '../../errors';
import { formatMoney } from './money';
import { type Blocker, deliveryBlockers } from './campaign_state';
import {
  type DayRow, type Flag, type Range, countRsvpsAfterClick, ctr, dailyTotals, dayString, integrityFlags, mergeDaily, parseRange, placementTotals,
} from './analytics_calc';
import { factsOf, orderStatusOf, type CampaignRow } from './service';
import { viewerKey } from './sponsored_token';

export type AnalyticsCampaign = CampaignRow & { frequency_cap_per_day: number; delivered_clicks: string | number };

const REACH_EXACT_DAYS = 85; // raw events are kept 90 days; stay inside that so a distinct count is exact
const MAX_CLICKERS = 20_000;
const MAX_SIGNUPS = 5_000;
const REPORT_HOLD = 3;

export const DEFINITIONS = {
  impression: 'A sponsored card was on screen for about a second. Counted once per card served.',
  click: 'A tap on the card or its button. A second tap by the same student within 30 minutes is not counted.',
  ctr: 'Clicks divided by impressions. Shown as empty when there were no impressions.',
  reach: 'Different students who saw the ad. Counted without names.',
  rsvpAfterClick: 'Students who tapped the ad and then RSVPed to the event within 7 days. It shows the two are connected, not that the ad caused the RSVP.',
  purchases: 'Not measured. UniVibe receives no evidence of purchases, so none are reported.',
} as const;

const BLOCKER_TEXT: Record<Blocker, string> = {
  not_paid: 'Payment has not been confirmed.',
  not_approved: 'The ad has not been approved.',
  wrong_status: 'The campaign is not running right now.',
  not_started: 'The start date has not arrived yet.',
  ended: 'The campaign has ended.',
  cap_reached: 'The impression limit for this package has been reached.',
};

export interface AnalyticsOptions { integrity?: boolean }

/** The reporting window from the request's optional `from` and `to`, or a 422 that says what is wrong. */
export function rangeFor(from: string | undefined, to: string | undefined, c: Pick<CampaignRow, 'starts_at' | 'ends_at'>, now = new Date()): Range {
  try { return parseRange(from, to, now, { startsAt: c.starts_at, endsAt: c.ends_at }); } catch (e) {
    if (e instanceof RangeError) throw new HttpError(422, 'validation', e.message);
    throw e;
  }
}

/** Everything an advertiser (or staff) sees about one campaign's performance, computed from recorded events only. */
export async function loadAnalytics(pool: Pool, secret: string, c: AnalyticsCampaign, range: Range, opts: AnalyticsOptions = {}, now = new Date()) {
  const q = pool.query.bind(pool);
  const dayExpr = "to_char((created_at at time zone 'UTC')::date, 'YYYY-MM-DD')";
  const [rolled, raw] = await Promise.all([
    q(`select to_char(day, 'YYYY-MM-DD') as day, placement, impressions, clicks, reach from campaign_daily where campaign_id = $1 and day >= $2::date and day < $3::date`,
      [c.id, range.fromDay, dayString(range.toExclusive)]),
    q(`select ${dayExpr} as day, placement, (count(*) filter (where kind = 'impression'))::int as impressions, (count(*) filter (where kind = 'click'))::int as clicks,
              (count(distinct viewer_key) filter (where kind = 'impression'))::int as reach
         from campaign_events where campaign_id = $1 and created_at >= $2 and created_at < $3 group by 1, 2`, [c.id, range.from, range.toExclusive]),
  ]);
  const rows: DayRow[] = mergeDaily(rolled.rows as DayRow[], raw.rows as DayRow[], dayString(now));
  const impressions = rows.reduce((n, r) => n + r.impressions, 0);
  const clicks = rows.reduce((n, r) => n + r.clicks, 0);

  // Reach over the whole window is a distinct count, which can be exact only while raw events exist.
  const exact = range.from.getTime() >= now.getTime() - REACH_EXACT_DAYS * 86_400_000;
  const reach = exact
    ? Number((await q("select count(distinct viewer_key) as n from campaign_events where campaign_id = $1 and kind = 'impression' and created_at >= $2 and created_at < $3", [c.id, range.from, range.toExclusive])).rows[0].n)
    : rows.reduce((n, r) => n + r.reach, 0);

  let conversions: { kind: 'rsvp_after_click'; count: number } | null = null;
  if (c.kind === 'event_promotion' && c.event_id) {
    const first = await q(
      "select viewer_key, min(created_at) as first_click from campaign_events where campaign_id = $1 and kind = 'click' group by viewer_key limit $2", [c.id, MAX_CLICKERS]);
    const firstClicks = new Map<string, number>((first.rows as { viewer_key: string; first_click: Date }[]).map((r) => [r.viewer_key, r.first_click.getTime()]));
    if (firstClicks.size) {
      const regs = await q(
        "select user_id, registered_at from event_registrations where event_id = $1 and status = 'registered' and registered_at >= $2 and registered_at < $3 limit $4",
        [c.event_id, range.from, range.toExclusive, MAX_SIGNUPS]);
      const signups = (regs.rows as { user_id: string; registered_at: Date }[]).map((r) => ({ key: viewerKey(secret, r.user_id), at: r.registered_at.getTime() }));
      conversions = { kind: 'rsvp_after_click', count: countRsvpsAfterClick(firstClicks, signups) };
    } else conversions = { kind: 'rsvp_after_click', count: 0 };
  }

  const orderStatus = await orderStatusOf(pool, c.order_id);
  const order = c.order_id ? (await q('select amount_minor, currency, status from orders where id = $1', [c.order_id])).rows[0] : null;
  const refunded = c.order_id ? Number((await q("select coalesce(sum(amount_minor), 0) as n from refunds where order_id = $1 and status = 'paid_out'", [c.order_id])).rows[0].n) : 0;
  const held = Number((await q("select count(*) as n from campaign_reports where campaign_id = $1 and status = 'open'", [c.id])).rows[0].n) >= REPORT_HOLD;
  const blockers = deliveryBlockers(factsOf(c, orderStatus), now);
  const cap = c.impression_cap;
  const delivered = Number(c.delivered_impressions);

  const out = {
    campaign: { id: c.id, kind: c.kind, headline: c.headline, status: c.status, startsAt: c.starts_at, endsAt: c.ends_at },
    range: { from: range.fromDay, to: range.toDay },
    totals: { impressions, clicks, ctr: ctr(clicks, impressions), reach, reachExact: exact },
    conversions,
    byPlacement: placementTotals(rows),
    daily: dailyTotals(rows, range.fromDay, range.toDay),
    delivery: {
      status: c.status, running: blockers.length === 0 && !held, heldForReview: held,
      reasonsNotRunning: blockers.map((b) => BLOCKER_TEXT[b]),
      impressionCap: cap, impressionsDelivered: delivered, percentOfCapUsed: cap ? Math.min(100, Math.round((delivered / cap) * 100)) : null,
    },
    payment: order && {
      status: order.status, amountMinor: Number(order.amount_minor), amount: formatMoney(Number(order.amount_minor), order.currency),
      refundedMinor: refunded, refunded: formatMoney(refunded, order.currency),
    },
    definitions: DEFINITIONS,
  };
  if (!opts.integrity) return out;

  const top = await q(
    "select coalesce(max(n), 0)::int as top, count(*)::int as viewers from (select count(*) as n from campaign_events where campaign_id = $1 and kind = 'click' and created_at >= $2 and created_at < $3 group by viewer_key) t",
    [c.id, range.from, range.toExclusive]);
  const peak = await q(
    "select coalesce(max(n), 0)::int as peak from (select count(*) as n from campaign_events where campaign_id = $1 and kind = 'impression' and created_at >= $2 and created_at < $3 group by date_trunc('hour', created_at)) t",
    [c.id, range.from, range.toExclusive]);
  const days = Math.max(1, Math.round((range.toExclusive.getTime() - range.from.getTime()) / 86_400_000));
  const flags: Flag[] = integrityFlags({
    impressions, clicks, reach, clickViewers: top.rows[0].viewers,
    topViewerClickShare: clicks > 0 ? top.rows[0].top / clicks : 0, peakHourShare: impressions > 0 ? peak.rows[0].peak / impressions : 0,
    frequencyCapPerDay: c.frequency_cap_per_day, days,
  });
  return { ...out, integrity: { flags, note: 'Signals for a person to review. They are not proof of fraud.' } };
}
