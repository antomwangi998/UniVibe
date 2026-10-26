import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { sweepCampaigns } from '../src/modules/monetization/service';
import { harness, url } from './helpers';

// Written for CI (needs Postgres). NOT yet run: the authoring sandbox had no database.
describe.skipIf(!url)('sponsored delivery (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';
  const hours = (n: number) => new Date(Date.now() + n * 3600_000).toISOString();

  beforeAll(async () => { pool = createPool(url as string); await migrate(pool); });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  /** A live, paid, approved event promotion (free package, so no payment step), plus the organizer and a staff account. */
  async function liveCampaign(over: { cap?: number | null; frequency?: number } = {}) {
    const admin = await h.student();
    await pool.query("update users set role = 'admin' where id = $1", [admin.id]);
    const pkg = await call('POST', `${V1}/admin/monetization/packages`, admin.token, {
      code: `free-${Math.random().toString(36).slice(2, 10)}`, kind: 'event_promotion', name: 'Free boost', priceMinor: 0, durationDays: 7,
      placements: ['home_feed', 'discover_events'], impressionCap: over.cap ?? null, frequencyCapPerDay: over.frequency ?? 3,
    });
    expect(pkg.status, JSON.stringify(pkg.body)).toBe(201);
    const org = await h.student();
    const ev = await call('POST', `${V1}/events`, org.token, { title: 'Hack night', description: 'x', category: 'technology', venueName: 'Lab 3', startsAt: hours(24), endsAt: hours(27) });
    await pool.query("update events set status = 'published' where id = $1", [ev.body.id]);
    const c = await call('POST', `${V1}/campaigns`, org.token, { kind: 'event_promotion', packageId: pkg.body.id, eventId: ev.body.id, headline: 'Hack night', placements: ['home_feed'], startsAt: hours(2), endsAt: hours(26) });
    await call('POST', `${V1}/campaigns/${c.body.id}/checkout`, org.token);
    expect((await call('POST', `${V1}/admin/monetization/campaigns/${c.body.id}/approve`, admin.token, {})).status).toBe(200);
    await pool.query("update campaigns set starts_at = now() - interval '1 minute' where id = $1", [c.body.id]);
    await sweepCampaigns(pool);
    expect((await call('GET', `${V1}/campaigns/${c.body.id}`, org.token)).body.status).toBe('active');
    return { admin, org, campaignId: c.body.id as string, eventId: ev.body.id as string };
  }
  const feed = (token: string, placement = 'home_feed') => call('GET', `${V1}/sponsored?placement=${placement}`, token);

  it('shows a labelled card to a student, at most one, and nothing when no campaign qualifies', async () => {
    const viewer = await h.student();
    expect((await feed(viewer.token)).body.items).toHaveLength(0);
    const { campaignId } = await liveCampaign();
    const r = await feed(viewer.token);
    expect(r.body.items).toHaveLength(1);
    const card = r.body.items[0];
    expect(card.campaignId).toBe(campaignId);
    expect(card.label).toBe('Sponsored');
    expect(card.advertiser.name).toBeTruthy();
    // A placement the package does not include gets nothing.
    expect((await feed(viewer.token, 'discover_feed')).body.items).toHaveLength(0);
  });

  it('counts an impression once per card and ignores replays, forgeries and other people\'s tokens', async () => {
    const { campaignId } = await liveCampaign();
    const a = await h.student();
    const b = await h.student();
    const token = (await feed(a.token)).body.items[0].impressionToken as string;
    expect((await call('POST', `${V1}/sponsored/impression`, a.token, { token })).body.counted).toBe(true);
    expect((await call('POST', `${V1}/sponsored/impression`, a.token, { token })).body.counted).toBe(false);
    expect((await call('POST', `${V1}/sponsored/impression`, b.token, { token })).status).toBe(400);
    expect((await call('POST', `${V1}/sponsored/impression`, a.token, { token: `${token}x` })).status).toBe(400);
    expect((await pool.query('select delivered_impressions from campaigns where id = $1', [campaignId])).rows[0].delivered_impressions).toBe('1');
  });

  it('counts a click only after that card was shown, and only once in a short while', async () => {
    const { campaignId } = await liveCampaign({ frequency: 5 });
    const a = await h.student();
    const t1 = (await feed(a.token)).body.items[0].impressionToken as string;
    expect((await call('POST', `${V1}/sponsored/click`, a.token, { token: t1 })).status).toBe(400); // never reported as shown
    await call('POST', `${V1}/sponsored/impression`, a.token, { token: t1 });
    expect((await call('POST', `${V1}/sponsored/click`, a.token, { token: t1 })).body.counted).toBe(true);
    expect((await call('POST', `${V1}/sponsored/click`, a.token, { token: t1 })).body.counted).toBe(false);
    const t2 = (await feed(a.token)).body.items[0].impressionToken as string;
    await call('POST', `${V1}/sponsored/impression`, a.token, { token: t2 });
    expect((await call('POST', `${V1}/sponsored/click`, a.token, { token: t2 })).body.counted).toBe(false); // second tap within 30 minutes
    expect((await pool.query('select delivered_clicks from campaigns where id = $1', [campaignId])).rows[0].delivered_clicks).toBe('1');
  });

  it('stops after the daily frequency cap and after the package impression cap', async () => {
    await liveCampaign({ frequency: 2 });
    const a = await h.student();
    for (let i = 0; i < 2; i++) {
      const it = (await feed(a.token)).body.items[0];
      expect(it).toBeTruthy();
      await call('POST', `${V1}/sponsored/impression`, a.token, { token: it.impressionToken });
    }
    expect((await feed(a.token)).body.items).toHaveLength(0);

    const { campaignId } = await liveCampaign({ cap: 1 });
    const b = await h.student();
    const only = (await feed(b.token)).body.items.find((x: { campaignId: string }) => x.campaignId === campaignId);
    await call('POST', `${V1}/sponsored/impression`, b.token, { token: only.impressionToken });
    const c = await h.student();
    expect((await feed(c.token)).body.items.some((x: { campaignId: string }) => x.campaignId === campaignId)).toBe(false);
  });

  it('never delivers an unpaid, unapproved, paused or ended campaign', async () => {
    const { campaignId, org } = await liveCampaign();
    const v = await h.student();
    const live = async () => (await feed(v.token)).body.items.some((x: { campaignId: string }) => x.campaignId === campaignId);
    expect(await live()).toBe(true);
    await pool.query("update orders set status = 'pending_payment' where id = (select order_id from campaigns where id = $1)", [campaignId]);
    expect(await live()).toBe(false);
    await pool.query("update orders set status = 'paid' where id = (select order_id from campaigns where id = $1)", [campaignId]);
    await pool.query("update campaigns set review_status = 'pending' where id = $1", [campaignId]);
    expect(await live()).toBe(false);
    await pool.query("update campaigns set review_status = 'approved' where id = $1", [campaignId]);
    expect(await live()).toBe(true);
    expect((await call('POST', `${V1}/campaigns/${campaignId}/pause`, org.token)).status).toBe(200);
    expect(await live()).toBe(false);
    expect((await call('POST', `${V1}/campaigns/${campaignId}/resume`, org.token)).status).toBe(200);
    expect(await live()).toBe(true);
    await pool.query("update campaigns set ends_at = now() - interval '1 second' where id = $1", [campaignId]);
    expect(await live()).toBe(false);
  });

  it('respects blocks, "not interested" and existing sign-ups', async () => {
    const { campaignId, org, eventId } = await liveCampaign();
    const blocker = await h.student();
    const hider = await h.student();
    const going = await h.student();
    await pool.query('insert into blocks (blocker_id, blocked_id) values ($1, $2)', [blocker.id, org.id]);
    await pool.query('insert into event_feedback (user_id, event_id) values ($1, $2)', [hider.id, eventId]);
    await pool.query("insert into event_registrations (event_id, user_id, status) values ($1, $2, 'registered')", [eventId, going.id]);
    for (const s of [blocker, hider, going]) {
      expect((await feed(s.token)).body.items.some((x: { campaignId: string }) => x.campaignId === campaignId)).toBe(false);
    }
  });

  it('students at another university see nothing', async () => {
    await pool.query("insert into universities (id, name, email_domains) values ('elsewhere', 'Elsewhere University', array['elsewhere.example']) on conflict do nothing");
    const { campaignId } = await liveCampaign();
    const outsider = await h.student('elsewhere.example', 'elsewhere');
    expect((await feed(outsider.token)).body.items.some((x: { campaignId: string }) => x.campaignId === campaignId)).toBe(false);
  });

  it('three reports hold an ad back; reporting needs a card the person was shown', async () => {
    const { campaignId } = await liveCampaign({ frequency: 5 });
    const reporters = [await h.student(), await h.student(), await h.student()];
    const stranger = await h.student();
    expect((await call('POST', `${V1}/sponsored/${campaignId}/report`, stranger.token, { reason: 'scam' })).status).toBe(404);
    for (const r of reporters) {
      const t = (await feed(r.token)).body.items[0].impressionToken;
      await call('POST', `${V1}/sponsored/impression`, r.token, { token: t });
      expect((await call('POST', `${V1}/sponsored/${campaignId}/report`, r.token, { reason: 'misleading' })).status).toBe(201);
    }
    const fresh = await h.student();
    expect((await feed(fresh.token)).body.items.some((x: { campaignId: string }) => x.campaignId === campaignId)).toBe(false);
  });

  it('advertisers see their own recorded performance; nobody else does; staff also get integrity signals', async () => {
    const { admin, org, campaignId, eventId } = await liveCampaign({ frequency: 5 });
    const a = await h.student();
    const b = await h.student();
    for (const v of [a, b]) {
      const t = (await feed(v.token)).body.items[0].impressionToken as string;
      await call('POST', `${V1}/sponsored/impression`, v.token, { token: t });
      if (v === a) await call('POST', `${V1}/sponsored/click`, v.token, { token: t });
    }
    await pool.query("insert into event_registrations (event_id, user_id, status) values ($1, $2, 'registered')", [eventId, a.id]);
    const r = await call('GET', `${V1}/campaigns/${campaignId}/analytics`, org.token);
    expect(r.status, JSON.stringify(r.body)).toBe(200);
    expect(r.body.totals).toMatchObject({ impressions: 2, clicks: 1, ctr: 0.5, reach: 2 });
    expect(r.body.conversions).toEqual({ kind: 'rsvp_after_click', count: 1 });
    expect(r.body.delivery.running).toBe(true);
    expect(r.body.integrity).toBeUndefined(); // staff-only
    expect(JSON.stringify(r.body)).not.toContain(a.id); // no student is named
    // Someone else's campaign is not found; students cannot reach the staff view.
    expect((await call('GET', `${V1}/campaigns/${campaignId}/analytics`, a.token)).status).toBe(404);
    expect((await call('GET', `${V1}/admin/monetization/campaigns/${campaignId}/analytics`, org.token)).status).toBe(404);
    expect((await call('GET', `${V1}/admin/monetization/campaigns/${campaignId}/analytics`, admin.token)).body.integrity.flags).toEqual([]);
    // Date filtering: a window before the campaign shows nothing; bad windows are refused.
    const past = await call('GET', `${V1}/campaigns/${campaignId}/analytics?from=2020-01-01&to=2020-01-10`, org.token);
    expect(past.body.totals).toMatchObject({ impressions: 0, clicks: 0, ctr: null });
    expect(past.body.daily).toHaveLength(10);
    for (const bad of ['from=2026-02-30', 'from=2026-11-05&to=2026-11-01', 'from=2020-01-01&to=2026-01-01', 'from=nope']) {
      expect((await call('GET', `${V1}/campaigns/${campaignId}/analytics?${bad}`, org.token)).status).toBe(bad === 'from=nope' ? 400 : 422);
    }
    const sum = await call('GET', `${V1}/me/advertising/summary`, org.token);
    expect(sum.body).toMatchObject({ impressions: 2, clicks: 1, campaigns: { active: 1, total: 1 } });
  });

  it('a campaign that is not running says why', async () => {
    const { org, campaignId } = await liveCampaign();
    await pool.query("update campaigns set review_status = 'pending' where id = $1", [campaignId]);
    const r = await call('GET', `${V1}/campaigns/${campaignId}/analytics`, org.token);
    expect(r.body.delivery.running).toBe(false);
    expect(r.body.delivery.reasonsNotRunning).toContain('The ad has not been approved.');
  });
});
