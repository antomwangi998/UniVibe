/**
 * The arithmetic behind advertiser analytics, with no database in it so every rule can be tested alone.
 *
 * Definitions (also returned by the API, so the app can show them):
 *  - impression: a sponsored card was on screen for about a second. Counted once per card served.
 *  - click: a tap on the card or its button. A second tap by the same student within 30 minutes is not counted.
 *  - click-through rate (CTR): clicks divided by impressions. Not defined (null) when there were no impressions.
 *  - reach: different students who saw the ad (counted by a keyed hash, never by name).
 *  - RSVP after click: a student tapped the ad and then RSVPed to the promoted event within 7 days. It shows the ad
 *    and the sign-up are connected; it does not prove the ad caused the sign-up. There is no purchase conversion
 *    metric because the app receives no evidence of purchases.
 */

export interface DayRow { day: string; placement: string; impressions: number; clicks: number; reach: number }
export interface Flag { code: string; message: string }

export const MAX_RANGE_DAYS = 92;
export const RSVP_WINDOW_MS = 7 * 86_400_000;
const DAY_MS = 86_400_000;

/** Clicks per impression, or null when nothing was shown (a rate of 0 would claim the ad was shown and ignored). */
export const ctr = (clicks: number, impressions: number): number | null => (impressions > 0 ? clicks / impressions : null);

export const dayString = (d: Date): string => d.toISOString().slice(0, 10);
const dayStart = (s: string): Date => new Date(`${s}T00:00:00.000Z`);
export const shiftDay = (s: string, n: number): string => dayString(new Date(dayStart(s).getTime() + n * DAY_MS));

/**
 * Raw events are the truth while they exist; the daily roll-up replaces them once they may have been deleted.
 * For days at least `rolledAfterDays` old a roll-up row wins when there is one; for newer days, or when the roll-up
 * is missing, the raw row stays.
 */
export function mergeDaily(rolled: DayRow[], raw: DayRow[], todayUtc: string, rolledAfterDays = 3): DayRow[] {
  const cutoff = shiftDay(todayUtc, -rolledAfterDays);
  const out = new Map<string, DayRow>();
  const key = (r: DayRow) => `${r.day}|${r.placement}`;
  for (const r of raw) out.set(key(r), r);
  for (const r of rolled) if (r.day < cutoff) out.set(key(r), r);
  return [...out.values()].sort((a, b) => (a.day === b.day ? a.placement.localeCompare(b.placement) : a.day < b.day ? -1 : 1));
}

export interface DayTotal { day: string; impressions: number; clicks: number; ctr: number | null }

/** One row per calendar day in [fromDay, toDay], zero-filled so a chart has no gaps. */
export function dailyTotals(rows: DayRow[], fromDay: string, toDay: string): DayTotal[] {
  const by = new Map<string, { i: number; c: number }>();
  for (const r of rows) {
    const t = by.get(r.day) ?? { i: 0, c: 0 };
    t.i += r.impressions; t.c += r.clicks;
    by.set(r.day, t);
  }
  const out: DayTotal[] = [];
  for (let d = fromDay; d <= toDay; d = shiftDay(d, 1)) {
    const t = by.get(d) ?? { i: 0, c: 0 };
    out.push({ day: d, impressions: t.i, clicks: t.c, ctr: ctr(t.c, t.i) });
  }
  return out;
}

export function placementTotals(rows: DayRow[]): { placement: string; impressions: number; clicks: number; ctr: number | null }[] {
  const by = new Map<string, { i: number; c: number }>();
  for (const r of rows) {
    const t = by.get(r.placement) ?? { i: 0, c: 0 };
    t.i += r.impressions; t.c += r.clicks;
    by.set(r.placement, t);
  }
  return [...by.entries()].map(([placement, t]) => ({ placement, impressions: t.i, clicks: t.c, ctr: ctr(t.c, t.i) })).sort((a, b) => a.placement.localeCompare(b.placement));
}

export interface Range { fromDay: string; toDay: string; from: Date; toExclusive: Date }

/**
 * The reporting window, in whole UTC days. Defaults to the campaign's own dates up to today. Rejects reversed or
 * oversized ranges and anything that is not a real calendar date.
 */
export function parseRange(fromIn: string | undefined, toIn: string | undefined, now: Date, campaign: { startsAt: Date; endsAt: Date }): Range {
  const valid = (s: string) => dayString(dayStart(s)) === s;
  if ((fromIn && !valid(fromIn)) || (toIn && !valid(toIn))) throw new RangeError('Use real dates in the form YYYY-MM-DD.');
  const fromDay = fromIn ?? dayString(campaign.startsAt);
  const toDay = toIn ?? dayString(new Date(Math.min(now.getTime(), campaign.endsAt.getTime())));
  if (fromDay > toDay) throw new RangeError('The start date must not be after the end date.');
  const from = dayStart(fromDay);
  const toExclusive = new Date(dayStart(toDay).getTime() + DAY_MS);
  if ((toExclusive.getTime() - from.getTime()) / DAY_MS > MAX_RANGE_DAYS) throw new RangeError(`Choose at most ${MAX_RANGE_DAYS} days at a time.`);
  return { fromDay, toDay, from, toExclusive };
}

/**
 * RSVPs that followed a tap on the ad. `firstClicks`: viewer key -> time of that student's first tap.
 * A sign-up counts when it came after that tap and within the window. Each student counts once.
 */
export function countRsvpsAfterClick(firstClicks: Map<string, number>, signups: { key: string; at: number }[], windowMs = RSVP_WINDOW_MS): number {
  const seen = new Set<string>();
  for (const s of signups) {
    const clicked = firstClicks.get(s.key);
    if (clicked !== undefined && s.at >= clicked && s.at - clicked <= windowMs) seen.add(s.key);
  }
  return seen.size;
}

export interface IntegrityStats {
  impressions: number; clicks: number; reach: number; clickViewers: number;
  /** Largest number of clicks from one student, as a share of all clicks. */
  topViewerClickShare: number;
  /** Busiest single hour's impressions as a share of all impressions. */
  peakHourShare: number;
  frequencyCapPerDay: number; days: number;
}

/**
 * Signals that engagement may not be genuine. They are prompts for a person to look, never a verdict, and they only
 * fire once there is enough data for the numbers to mean something.
 */
export function integrityFlags(s: IntegrityStats): Flag[] {
  const flags: Flag[] = [];
  const rate = ctr(s.clicks, s.impressions);
  if (s.impressions >= 100 && rate !== null && rate > 0.25) flags.push({ code: 'unusually_high_ctr', message: 'More than a quarter of impressions were clicked, far above what is normal.' });
  if (s.clicks >= 10 && s.topViewerClickShare > 0.2) flags.push({ code: 'concentrated_clicks', message: 'One student accounts for over a fifth of all clicks.' });
  if (s.clicks >= 20 && s.clickViewers <= 2) flags.push({ code: 'few_clickers', message: 'Many clicks came from only one or two students.' });
  if (s.reach > 0 && s.impressions / s.reach > s.frequencyCapPerDay * Math.max(1, s.days) + 0.5) flags.push({ code: 'frequency_exceeded', message: 'Students saw the ad more often than the daily limit allows. Check delivery.' });
  if (s.impressions >= 200 && s.peakHourShare > 0.6) flags.push({ code: 'single_hour_burst', message: 'Most impressions arrived in a single hour.' });
  return flags;
}
