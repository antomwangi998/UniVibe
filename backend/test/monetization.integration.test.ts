import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { sweepCampaigns } from '../src/modules/monetization/service';
import { harness, url } from './helpers';

// Written to run in CI against Postgres (TEST_DATABASE_URL). NOT yet run: the authoring sandbox had no database.
describe.skipIf(!url)('monetization (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';
  const hours = (n: number) => new Date(Date.now() + n * 3600_000).toISOString();

  beforeAll(async () => { pool = createPool(url as string); await migrate(pool); });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  const staff = async () => {
    const u = await h.student();
    await pool.query("update users set role = 'admin' where id = $1", [u.id]);
    return u;
  };
  const setup = async (priceMinor = 50000) => {
    const admin = await staff();
    expect((await call('PUT', `${V1}/admin/monetization/payment-settings`, admin.token, { enabled: true, method: 'till', number: '123456', accountName: 'UniVibe' })).status).toBe(200);
    const code = `pkg-${Math.random().toString(36).slice(2, 10)}`;
    const p = await call('POST', `${V1}/admin/monetization/packages`, admin.token, {
      code, kind: 'event_promotion', name: 'Event boost', priceMinor, durationDays: 7, placements: ['home_feed', 'discover_events'],
    });
    expect(p.status, JSON.stringify(p.body)).toBe(201);
    return { admin, packageId: p.body.id as string };
  };
  const event = async (token: string) => {
    const r = await call('POST', `${V1}/events`, token, { title: 'Hack night', description: 'x', category: 'technology', venueName: 'Lab 3', startsAt: hours(24), endsAt: hours(27) });
    expect(r.status, JSON.stringify(r.body)).toBe(201);
    await pool.query("update events set status = 'published' where id = $1", [r.body.id]);
    return r.body.id as string;
  };
  const campaign = async (token: string, packageId: string, eventId: string) => {
    const r = await call('POST', `${V1}/campaigns`, token, {
      kind: 'event_promotion', packageId, eventId, headline: 'Come to hack night', placements: ['home_feed'], startsAt: hours(2), endsAt: hours(26),
    });
    expect(r.status, JSON.stringify(r.body)).toBe(201);
    return r.body.id as string;
  };
  const receipt = () => `QX${Math.random().toString(36).slice(2, 10).toUpperCase()}`.slice(0, 10).padEnd(10, 'A');

  it('only staff can manage packages and payments', async () => {
    const s = await h.student();
    for (const [m, path] of [['GET', 'packages'], ['GET', 'payments'], ['GET', 'campaigns'], ['GET', 'refunds']] as const) {
      expect((await call(m, `${V1}/admin/monetization/${path}`, s.token)).status).toBe(404);
    }
    expect((await call('POST', `${V1}/admin/monetization/packages`, s.token, {})).status).toBe(404);
  });

  it('paid checkout is refused until a payment destination is switched on', async () => {
    const admin = await staff();
    await pool.query("update monetization_settings set value = '{\"enabled\": false}' where key = 'manual_payment'");
    const p = await call('POST', `${V1}/admin/monetization/packages`, admin.token, { code: `off-${Date.now()}`, kind: 'event_promotion', name: 'Paid', priceMinor: 10000, durationDays: 7, placements: ['home_feed'] });
    const org = await h.student();
    const c = await campaign(org.token, p.body.id, await event(org.token));
    expect((await call('POST', `${V1}/campaigns/${c}/checkout`, org.token)).status).toBe(503);
    expect((await call('GET', `${V1}/monetization/payment-info`, org.token)).body.enabled).toBe(false);
  });

  it('a campaign only runs after payment is confirmed by staff and the content is approved', async () => {
    const { admin, packageId } = await setup(50000);
    const org = await h.student();
    const c = await campaign(org.token, packageId, await event(org.token));
    const co = await call('POST', `${V1}/campaigns/${c}/checkout`, org.token);
    expect(co.status, JSON.stringify(co.body)).toBe(201);
    expect(co.body.order.amountMinor).toBe(50000);
    // Repeating checkout returns the same order.
    expect((await call('POST', `${V1}/campaigns/${c}/checkout`, org.token)).body.order.id).toBe(co.body.order.id);

    const rc = receipt();
    expect((await call('POST', `${V1}/orders/${co.body.order.id}/payment`, org.token, { receipt: rc })).status).toBe(202);
    // The claim alone changes nothing.
    expect((await call('GET', `${V1}/campaigns/${c}`, org.token)).body.status).toBe('pending_payment');
    // The same receipt cannot be used again.
    const org2 = await h.student();
    const c2 = await campaign(org2.token, packageId, await event(org2.token));
    const co2 = await call('POST', `${V1}/campaigns/${c2}/checkout`, org2.token);
    expect((await call('POST', `${V1}/orders/${co2.body.order.id}/payment`, org2.token, { receipt: rc })).status).toBe(409);

    const pay = (await call('GET', `${V1}/admin/monetization/payments?status=awaiting_verification`, admin.token)).body.items.find((i: { orderReference: string }) => i.orderReference === co.body.order.reference);
    expect(pay.receipt).toBe(rc.toUpperCase());
    // Wrong amount: nothing is confirmed.
    expect((await call('POST', `${V1}/admin/monetization/payments/${pay.id}/confirm`, admin.token, { receivedMinor: 40000 })).status).toBe(422);
    expect((await call('GET', `${V1}/campaigns/${c}`, org.token)).body.status).toBe('pending_payment');
    // Right amount, twice: one confirmation.
    expect((await call('POST', `${V1}/admin/monetization/payments/${pay.id}/confirm`, admin.token, { receivedMinor: 50000 })).status).toBe(200);
    expect((await call('POST', `${V1}/admin/monetization/payments/${pay.id}/confirm`, admin.token, { receivedMinor: 50000 })).status).toBe(200);
    expect((await pool.query("select count(*)::int as n from payment_events where payment_id = $1 and kind = 'confirmed'", [pay.id])).rows[0].n).toBe(1);
    const after = await call('GET', `${V1}/campaigns/${c}`, org.token);
    expect(after.body.status).toBe('pending_review');
    expect(after.body.payment.status).toBe('confirmed');

    // Paid is not approved: it still cannot be delivered. Only staff approve.
    expect((await call('POST', `${V1}/admin/monetization/campaigns/${c}/approve`, org.token, {})).status).toBe(404);
    expect((await call('POST', `${V1}/admin/monetization/campaigns/${c}/approve`, admin.token, {})).status).toBe(200);
    expect((await call('GET', `${V1}/campaigns/${c}`, org.token)).body.status).toBe('scheduled'); // starts in 2 hours
    const audited = await pool.query("select action from audit_logs where actor_id = $1 and action in ('payment.confirm', 'campaign.approve')", [admin.id]);
    expect(audited.rows.map((r) => r.action).sort()).toEqual(['campaign.approve', 'payment.confirm']);
  });

  it('a later price change never alters an existing order', async () => {
    const { admin, packageId } = await setup(50000);
    const org = await h.student();
    const c = await campaign(org.token, packageId, await event(org.token));
    const co = await call('POST', `${V1}/campaigns/${c}/checkout`, org.token);
    // The change must be confirmed.
    expect((await call('PATCH', `${V1}/admin/monetization/packages/${packageId}`, admin.token, { priceMinor: 90000 })).status).toBe(422);
    expect((await call('PATCH', `${V1}/admin/monetization/packages/${packageId}`, admin.token, { priceMinor: 90000, confirmPriceChange: true })).status).toBe(200);
    expect((await call('GET', `${V1}/campaigns/${c}`, org.token)).body.order.amountMinor).toBe(50000);
    expect(co.body.order.amountMinor).toBe(50000);
  });

  it('other students cannot see, pay for or change someone else\'s campaign', async () => {
    const { packageId } = await setup();
    const org = await h.student();
    const other = await h.student();
    const c = await campaign(org.token, packageId, await event(org.token));
    const co = await call('POST', `${V1}/campaigns/${c}/checkout`, org.token);
    expect((await call('GET', `${V1}/campaigns/${c}`, other.token)).status).toBe(404);
    expect((await call('POST', `${V1}/campaigns/${c}/cancel`, other.token)).status).toBe(404);
    expect((await call('POST', `${V1}/orders/${co.body.order.id}/payment`, other.token, { receipt: receipt() })).status).toBe(404);
    expect((await call('GET', `${V1}/me/campaigns`, other.token)).body.items).toHaveLength(0);
  });

  it('only the organizer can promote their event, and only once at a time', async () => {
    const { packageId } = await setup();
    const org = await h.student();
    const other = await h.student();
    const ev = await event(org.token);
    const body = { kind: 'event_promotion', packageId, eventId: ev, headline: 'Hack night', placements: ['home_feed'], startsAt: hours(2), endsAt: hours(26) };
    expect((await call('POST', `${V1}/campaigns`, other.token, body)).status).toBe(422);
    expect((await call('POST', `${V1}/campaigns`, org.token, body)).status).toBe(201);
    expect((await call('POST', `${V1}/campaigns`, org.token, body)).status).toBe(409);
    // The promotion cannot outlast the event.
    expect((await call('POST', `${V1}/campaigns`, org.token, { ...body, eventId: await event(org.token), endsAt: hours(80) })).status).toBe(422);
  });

  it('rejecting a paid campaign queues a refund that a different staff member decides', async () => {
    const { admin, packageId } = await setup(20000);
    const org = await h.student();
    const c = await campaign(org.token, packageId, await event(org.token));
    const co = await call('POST', `${V1}/campaigns/${c}/checkout`, org.token);
    await call('POST', `${V1}/orders/${co.body.order.id}/payment`, org.token, { receipt: receipt() });
    const pay = (await call('GET', `${V1}/admin/monetization/payments?status=awaiting_verification`, admin.token)).body.items.find((i: { orderReference: string }) => i.orderReference === co.body.order.reference);
    await call('POST', `${V1}/admin/monetization/payments/${pay.id}/confirm`, admin.token, { receivedMinor: 20000 });
    expect((await call('POST', `${V1}/admin/monetization/campaigns/${c}/reject`, admin.token, { note: 'Misleading wording' })).status).toBe(200);
    const view = await call('GET', `${V1}/campaigns/${c}`, org.token);
    expect(view.body.status).toBe('refund_pending');
    expect(view.body.reviewNote).toBe('Misleading wording');
    const refund = (await call('GET', `${V1}/admin/monetization/refunds?status=requested`, admin.token)).body.items.find((r: { order_reference: string }) => r.order_reference === co.body.order.reference);
    // The requester (the admin who rejected) cannot approve their own request.
    expect((await call('POST', `${V1}/admin/monetization/refunds/${refund.id}/approve`, admin.token, {})).status).toBe(403);
    const second = await staff();
    expect((await call('POST', `${V1}/admin/monetization/refunds/${refund.id}/paid`, second.token, { payoutReference: 'QREFUND123' })).status).toBe(409); // must approve first
    expect((await call('POST', `${V1}/admin/monetization/refunds/${refund.id}/approve`, second.token, {})).status).toBe(200);
    expect((await call('POST', `${V1}/admin/monetization/refunds/${refund.id}/paid`, second.token, { payoutReference: 'QREFUND123' })).status).toBe(200);
    expect((await call('GET', `${V1}/campaigns/${c}`, org.token)).body.status).toBe('refunded');
  });

  it('a promotion ends when its event is cancelled', async () => {
    const { packageId } = await setup(0); // a free package skips payment, so the test needs no staff step
    const org = await h.student();
    const ev = await event(org.token);
    const c = await campaign(org.token, packageId, ev);
    expect((await call('POST', `${V1}/campaigns/${c}/checkout`, org.token)).status).toBe(201);
    expect((await call('GET', `${V1}/campaigns/${c}`, org.token)).body.status).toBe('pending_review');
    await pool.query("update events set status = 'cancelled' where id = $1", [ev]);
    await sweepCampaigns(pool);
    expect((await call('GET', `${V1}/campaigns/${c}`, org.token)).body.status).toBe('cancelled');
  });

  it('an overpayment confirms the order and queues only the excess for refund; the campaign carries on', async () => {
    const { admin, packageId } = await setup(20000);
    const org = await h.student();
    const c = await campaign(org.token, packageId, await event(org.token));
    const co = await call('POST', `${V1}/campaigns/${c}/checkout`, org.token);
    await call('POST', `${V1}/orders/${co.body.order.id}/payment`, org.token, { receipt: receipt() });
    const pay = (await call('GET', `${V1}/admin/monetization/payments?status=awaiting_verification`, admin.token)).body.items.find((i: { orderReference: string }) => i.orderReference === co.body.order.reference);
    // Too little never confirms; too much needs the explicit refundExcess flag.
    expect((await call('POST', `${V1}/admin/monetization/payments/${pay.id}/confirm`, admin.token, { receivedMinor: 15000 })).status).toBe(422);
    expect((await call('POST', `${V1}/admin/monetization/payments/${pay.id}/confirm`, admin.token, { receivedMinor: 25000 })).status).toBe(422);
    expect((await call('POST', `${V1}/admin/monetization/payments/${pay.id}/confirm`, admin.token, { receivedMinor: 25000, refundExcess: true })).status).toBe(200);
    expect((await call('GET', `${V1}/campaigns/${c}`, org.token)).body.status).toBe('pending_review');
    const refund = (await call('GET', `${V1}/admin/monetization/refunds?status=requested`, admin.token)).body.items.find((r: { order_reference: string }) => r.order_reference === co.body.order.reference);
    expect(refund.amountMinor).toBe(5000);
    // The shared test database holds many admins, so the sole-administrator exception does not apply here:
    // a second administrator decides the refund instead.
    const decider = await staff();
    expect((await call('POST', `${V1}/admin/monetization/refunds/${refund.id}/approve`, decider.token, {})).status).toBe(200);
    expect((await call('POST', `${V1}/admin/monetization/refunds/${refund.id}/paid`, decider.token, { payoutReference: 'QEXCESS123' })).status).toBe(200);
    // Paying back the excess leaves the order paid and the campaign untouched.
    expect((await pool.query('select status from orders where id = $1', [co.body.order.id])).rows[0].status).toBe('paid');
    expect((await call('GET', `${V1}/campaigns/${c}`, org.token)).body.status).toBe('pending_review');
  });

  it('a draft can be edited, a submitted campaign cannot', async () => {
    const { packageId } = await setup(0);
    const org = await h.student();
    const ev = await event(org.token);
    const c = await campaign(org.token, packageId, ev);
    const body = { kind: 'event_promotion', packageId, eventId: ev, headline: 'New headline', placements: ['home_feed'], startsAt: hours(3), endsAt: hours(20) };
    expect((await call('PUT', `${V1}/campaigns/${c}`, org.token, body)).status).toBe(200);
    expect((await call('GET', `${V1}/campaigns/${c}`, org.token)).body.headline).toBe('New headline');
    expect((await call('PUT', `${V1}/campaigns/${c}`, (await h.student()).token, body)).status).toBe(404);
    await call('POST', `${V1}/campaigns/${c}/checkout`, org.token);
    expect((await call('PUT', `${V1}/campaigns/${c}`, org.token, body)).status).toBe(409);
  });

  it('advertisers and staff are notified, and ad reports can be resolved', async () => {
    const { admin, packageId } = await setup(0);
    const org = await h.student();
    const c = await campaign(org.token, packageId, await event(org.token));
    await call('POST', `${V1}/campaigns/${c}/checkout`, org.token);
    await call('POST', `${V1}/admin/monetization/campaigns/${c}/approve`, admin.token, {});
    const inbox = await pool.query("select metadata->>'kind' as kind from notifications where recipient_id = $1 and type = 'promotion'", [org.id]);
    expect(inbox.rows.map((r) => r.kind)).toContain('campaign_approved');
    expect((await call('GET', `${V1}/admin/monetization/ad-reports`, org.token)).status).toBe(404);
    expect((await call('GET', `${V1}/admin/monetization/ad-reports`, admin.token)).status).toBe(200);
  });

  it('the revenue overview keeps payment volume, refunds and earned revenue apart', async () => {
    const { admin, packageId } = await setup(40000);
    const org = await h.student();
    const c = await campaign(org.token, packageId, await event(org.token));
    const co = await call('POST', `${V1}/campaigns/${c}/checkout`, org.token);
    await call('POST', `${V1}/orders/${co.body.order.id}/payment`, org.token, { receipt: receipt() });
    const before = (await call('GET', `${V1}/admin/monetization/overview`, admin.token)).body;
    const pay = (await call('GET', `${V1}/admin/monetization/payments?status=awaiting_verification`, admin.token)).body.items.find((i: { orderReference: string }) => i.orderReference === co.body.order.reference);
    await call('POST', `${V1}/admin/monetization/payments/${pay.id}/confirm`, admin.token, { receivedMinor: 40000 });
    const after = (await call('GET', `${V1}/admin/monetization/overview`, admin.token)).body;
    expect(after.payments.receivedMinor - before.payments.receivedMinor).toBe(40000);
    // Paid but not yet reviewed or started: held as deferred, nothing recognised from it.
    expect(after.revenue.deferredMinor - before.revenue.deferredMinor).toBe(40000);
    expect(after.revenue.recognisedMinor).toBe(before.revenue.recognisedMinor);
    expect(after.costs).toBeNull();
    // Only staff with the finance permission can see it.
    expect((await call('GET', `${V1}/admin/monetization/overview`, org.token)).status).toBe(404);
  });
});
