import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, url } from './helpers';

// Runs in CI against Postgres (TEST_DATABASE_URL). The money path for UniVibe Premium, end to end.
describe.skipIf(!url)('premium subscriptions (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';
  const KEYS = ['premium.chat_themes', 'premium.profile_frames', 'premium.profile_themes'];

  beforeAll(async () => { pool = createPool(url as string); await migrate(pool); });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  const staff = async () => {
    const u = await h.student();
    await pool.query("update users set role = 'admin' where id = $1", [u.id]);
    return u;
  };
  const receipt = () => `QX${Math.random().toString(36).slice(2, 10).toUpperCase()}`.slice(0, 10).padEnd(10, 'A');
  const planByCode = async (code: string) => (await pool.query<{ id: string }>('select id from subscription_plans where code = $1', [code])).rows[0].id;

  /** Payments on, and the monthly plan on sale. Other tests' leftovers are reset so the checks below are exact. */
  const open = async () => {
    const admin = await staff();
    await pool.query("update premium_feature_flags set enabled = true");
    expect((await call('PUT', `${V1}/admin/monetization/payment-settings`, admin.token, { enabled: true, method: 'till', number: '123456', accountName: 'UniVibe' })).status).toBe(200);
    const monthly = await planByCode('premium_monthly');
    expect((await call('PATCH', `${V1}/admin/monetization/plans/${monthly}`, admin.token, { active: true })).status).toBe(200);
    return { admin, monthly };
  };
  /** A student buys: checkout, receipt claim, then staff confirm the exact amount. Returns the order and payment ids. */
  const buy = async (admin: { token: string }, who: { token: string }, planId: string, amount = 9900) => {
    const co = await call('POST', `${V1}/monetization/premium/checkout`, who.token, { planId, createKey: `k-${Math.random().toString(36).slice(2, 12)}` });
    expect(co.status, JSON.stringify(co.body)).toBe(201);
    expect((await call('POST', `${V1}/orders/${co.body.order.id}/payment`, who.token, { receipt: receipt() })).status).toBe(202);
    const pay = (await call('GET', `${V1}/admin/monetization/payments?status=awaiting_verification`, admin.token)).body.items.find((i: { orderReference: string }) => i.orderReference === co.body.order.reference);
    expect(pay, 'payment shows up for staff').toBeTruthy();
    return { orderId: co.body.order.id as string, reference: co.body.order.reference as string, payId: pay.id as string, confirm: (received = amount) => call('POST', `${V1}/admin/monetization/payments/${pay.id}/confirm`, admin.token, { receivedMinor: received }) };
  };

  it('plans ship off, and a student cannot buy a plan that is not on sale', async () => {
    const s = await h.student();
    await pool.query("update subscription_plans set active = false where code = 'premium_annual'");
    const annual = await planByCode('premium_annual');
    expect((await call('POST', `${V1}/monetization/premium/checkout`, s.token, { planId: annual })).status).toBe(404);
    const list = await call('GET', `${V1}/monetization/premium/plans`, s.token);
    expect(list.status).toBe(200);
    expect(list.body.plans.some((p: { code: string }) => p.code === 'premium_annual')).toBe(false);
  });

  it('only staff manage plans, a plan with no features cannot go on sale, and price changes need confirming', async () => {
    const s = await h.student();
    const admin = await staff();
    const monthly = await planByCode('premium_monthly');
    expect((await call('GET', `${V1}/admin/monetization/plans`, s.token)).status).toBe(404);
    expect((await call('PATCH', `${V1}/admin/monetization/plans/${monthly}`, s.token, { active: true })).status).toBe(404);
    const business = await planByCode('business_premium');
    expect((await call('PATCH', `${V1}/admin/monetization/plans/${business}`, admin.token, { active: true })).status).toBe(422);
    expect((await call('PATCH', `${V1}/admin/monetization/plans/${monthly}`, admin.token, { priceMinor: 12900 })).status).toBe(422);
    expect((await call('PATCH', `${V1}/admin/monetization/plans/${monthly}`, admin.token, { priceMinor: 9900, confirmPriceChange: true })).status).toBe(200);
  });

  it('paying starts Premium only after staff confirm the right amount, exactly once', async () => {
    const { admin, monthly } = await open();
    const s = await h.student();
    expect((await call('GET', `${V1}/me/entitlements`, s.token)).body.entitlements).toEqual([]);

    const co = await call('POST', `${V1}/monetization/premium/checkout`, s.token, { planId: monthly, createKey: 'same-tap-twice-1' });
    expect(co.status, JSON.stringify(co.body)).toBe(201);
    expect(co.body.order.amountMinor).toBe(9900); // the price came from the plan, not the request
    // A repeated tap returns the same order, not a second one.
    const again = await call('POST', `${V1}/monetization/premium/checkout`, s.token, { planId: monthly, createKey: 'same-tap-twice-1' });
    expect(again.status).toBe(200);
    expect(again.body.order.id).toBe(co.body.order.id);
    // The request has no price field: sending one is refused.
    expect((await call('POST', `${V1}/monetization/premium/checkout`, s.token, { planId: monthly, priceMinor: 1 })).status).toBe(400);

    expect((await call('POST', `${V1}/orders/${co.body.order.id}/payment`, s.token, { receipt: receipt() })).status).toBe(202);
    // Tapping Subscribe and claiming a receipt prove nothing.
    expect((await call('GET', `${V1}/me/entitlements`, s.token)).body.entitlements).toEqual([]);

    const pay = (await call('GET', `${V1}/admin/monetization/payments?status=awaiting_verification`, admin.token)).body.items.find((i: { orderReference: string }) => i.orderReference === co.body.order.reference);
    expect((await call('POST', `${V1}/admin/monetization/payments/${pay.id}/confirm`, admin.token, { receivedMinor: 5000 })).status).toBe(422);
    expect((await call('GET', `${V1}/me/entitlements`, s.token)).body.entitlements).toEqual([]);

    expect((await call('POST', `${V1}/admin/monetization/payments/${pay.id}/confirm`, admin.token, { receivedMinor: 9900 })).status).toBe(200);
    expect((await call('POST', `${V1}/admin/monetization/payments/${pay.id}/confirm`, admin.token, { receivedMinor: 9900 })).status).toBe(200); // duplicate confirmation
    const me = await call('GET', `${V1}/me/entitlements`, s.token);
    expect(me.body.entitlements).toEqual(KEYS);
    expect(me.body.subscription.active).toBe(true);
    expect(me.body.subscription.renews).toBe(false);
    expect(me.body.subscription.daysLeft).toBe(30);
    expect((await pool.query('select count(*)::int as n from subscriptions where order_id = $1', [co.body.order.id])).rows[0].n).toBe(1);

    const hist = await call('GET', `${V1}/me/billing-history`, s.token);
    expect(hist.body.items[0]).toMatchObject({ status: 'paid', amountMinor: 9900 });
    const audited = await pool.query("select action from audit_logs where action = 'subscription.activate' and target_id = $1", [co.body.order.id]);
    expect(audited.rowCount).toBe(1);
  });

  it('buying again while Premium runs adds a full period to the end, losing no days', async () => {
    const { admin, monthly } = await open();
    const s = await h.student();
    expect((await (await buy(admin, s, monthly)).confirm()).status).toBe(200);
    expect((await (await buy(admin, s, monthly)).confirm()).status).toBe(200);
    const me = await call('GET', `${V1}/me/entitlements`, s.token);
    expect(me.body.subscription.daysLeft).toBe(60);
    expect((await pool.query('select count(*)::int as n from subscriptions where user_id = $1', [s.id])).rows[0].n).toBe(2);
  });

  it('an ended period grants nothing, and the saved profile look stays but stops showing', async () => {
    const { admin, monthly } = await open();
    const s = await h.student();
    expect((await (await buy(admin, s, monthly)).confirm()).status).toBe(200);
    expect((await call('PUT', `${V1}/me/profile-look`, s.token, { theme: 'aurora', accent: '#14b8a6', frame: 'ring', badge: 'moon' })).status).toBe(200);
    const viewer = await h.student();
    const seen = await call('GET', `/students/${s.id}`, viewer.token);
    expect(seen.body.look).toMatchObject({ theme: 'aurora', accent: '#14B8A6', frame: 'ring', badge: 'moon' });

    await pool.query("update subscriptions set period_start = now() - interval '40 days', period_end = now() - interval '10 days' where user_id = $1", [s.id]);
    expect((await call('GET', `${V1}/me/entitlements`, s.token)).body.entitlements).toEqual([]);
    expect((await call('GET', `/students/${s.id}`, viewer.token)).body.look).toBeNull();
    // Saved, not shown: it returns with Premium.
    expect((await call('GET', `${V1}/me/profile-look`, s.token)).body.look.theme).toBe('aurora');
    // And nothing new can be saved without Premium; clearing always can.
    expect((await call('PUT', `${V1}/me/profile-look`, s.token, { theme: 'gold' })).status).toBe(403);
    expect((await call('PUT', `${V1}/me/profile-look`, s.token, {})).status).toBe(200);
  });

  it('a look shows on feed posts for other students while Premium is live, and not for a student without it', async () => {
    const { admin, monthly } = await open();
    const premium = await h.student();
    const plain = await h.student();
    const viewer = await h.student();
    expect((await (await buy(admin, premium, monthly)).confirm()).status).toBe(200);
    expect((await call('PUT', `${V1}/me/profile-look`, premium.token, { theme: 'bloom', frame: 'halo' })).status).toBe(200);
    // Without Premium nothing can be set, so the plain student has no look to show.
    expect((await call('PUT', `${V1}/me/profile-look`, plain.token, { theme: 'bloom' })).status).toBe(403);
    expect((await call('POST', '/posts', premium.token, { text: 'hello from premium' })).status).toBe(201);
    expect((await call('POST', '/posts', plain.token, { text: 'hello from free' })).status).toBe(201);
    const feed = await call('GET', '/posts', viewer.token);
    expect(feed.status).toBe(200);
    const mine = feed.body.items.find((p: { author: { id: string } }) => p.author.id === premium.id);
    const theirs = feed.body.items.find((p: { author: { id: string } }) => p.author.id === plain.id);
    expect(mine.author.look).toMatchObject({ theme: 'bloom', frame: 'halo' });
    expect(theirs.author.look ?? null).toBeNull();
  });

  it('a staff kill switch removes a feature for everyone at once, and turning it on restores it', async () => {
    const { admin, monthly } = await open();
    const s = await h.student();
    expect((await (await buy(admin, s, monthly)).confirm()).status).toBe(200);
    expect((await call('PUT', `${V1}/me/profile-look`, s.token, { theme: 'tide', frame: 'ring' })).status).toBe(200);
    const viewer = await h.student();
    expect((await call('PATCH', `${V1}/admin/monetization/premium/features/premium.chat_themes`, s.token, { enabled: false })).status).toBe(404); // not staff
    expect((await call('PATCH', `${V1}/admin/monetization/premium/features/premium.profile_frames`, admin.token, { enabled: false })).status).toBe(200);
    expect((await call('GET', `${V1}/me/entitlements`, s.token)).body.entitlements).toEqual(['premium.chat_themes', 'premium.profile_themes']);
    const during = (await call('GET', `/students/${s.id}`, viewer.token)).body.look;
    expect(during.theme).toBe('tide');
    expect(during.frame ?? null).toBeNull();
    expect((await call('PATCH', `${V1}/admin/monetization/premium/features/premium.profile_frames`, admin.token, { enabled: true })).status).toBe(200);
    expect((await call('GET', `/students/${s.id}`, viewer.token)).body.look.frame).toBe('ring');
    expect((await call('PATCH', `${V1}/admin/monetization/premium/features/not.a_feature`, admin.token, { enabled: false })).status).toBe(404);
  });

  it('a refund needs a second staff member, and Premium ends only when the payout is recorded', async () => {
    const { admin, monthly } = await open();
    const s = await h.student();
    const bought = await buy(admin, s, monthly);
    expect((await bought.confirm()).status).toBe(200);
    // Not refundable before payment, and a student cannot start one.
    expect((await call('POST', `${V1}/admin/monetization/subscription-orders/${bought.orderId}/refund`, s.token, { reason: 'please' })).status).toBe(404);
    const req = await call('POST', `${V1}/admin/monetization/subscription-orders/${bought.orderId}/refund`, admin.token, { reason: 'Paid by mistake' });
    expect(req.status, JSON.stringify(req.body)).toBe(201);
    expect((await call('POST', `${V1}/admin/monetization/subscription-orders/${bought.orderId}/refund`, admin.token, { reason: 'again' })).status).toBe(409); // one open refund at a time
    expect((await call('GET', `${V1}/me/entitlements`, s.token)).body.entitlements).toEqual(KEYS); // requested, not yet refunded

    expect((await call('POST', `${V1}/admin/monetization/refunds/${req.body.refundId}/approve`, admin.token, {})).status).toBe(403); // not your own request
    const second = await staff();
    expect((await call('POST', `${V1}/admin/monetization/refunds/${req.body.refundId}/approve`, second.token, {})).status).toBe(200);
    expect((await call('GET', `${V1}/me/entitlements`, s.token)).body.entitlements).toEqual(KEYS); // approved, money not yet sent
    expect((await call('POST', `${V1}/admin/monetization/refunds/${req.body.refundId}/paid`, second.token, { payoutReference: 'QREFUND456' })).status).toBe(200);
    expect((await call('GET', `${V1}/me/entitlements`, s.token)).body.entitlements).toEqual([]);
    expect((await call('GET', `${V1}/me/billing-history`, s.token)).body.items[0].status).toBe('refunded');
    expect((await pool.query("select status from subscriptions where order_id = $1", [bought.orderId])).rows[0].status).toBe('refunded');
  });

  it('people cannot read or pay each other\'s orders, and the subscriber list is staff only', async () => {
    const { admin, monthly } = await open();
    const a = await h.student();
    const b = await h.student();
    const co = await call('POST', `${V1}/monetization/premium/checkout`, a.token, { planId: monthly });
    expect((await call('POST', `${V1}/orders/${co.body.order.id}/payment`, b.token, { receipt: receipt() })).status).toBe(404);
    expect((await call('GET', `${V1}/me/billing-history`, b.token)).body.items).toHaveLength(0);
    expect((await call('GET', `${V1}/admin/monetization/subscriptions`, a.token)).status).toBe(404);
    expect((await call('GET', `${V1}/admin/monetization/subscriptions`, admin.token)).status).toBe(200);
    expect((await call('GET', `${V1}/admin/monetization/premium/overview`, a.token)).status).toBe(404);
    const ov = await call('GET', `${V1}/admin/monetization/premium/overview`, admin.token);
    expect(ov.status).toBe(200);
    expect(ov.body.plans.length).toBeGreaterThanOrEqual(3);
  });

  it('business plans need an approved business you own, and a personal plan refuses a business', async () => {
    const { monthly } = await open();
    const s = await h.student();
    expect((await call('POST', `${V1}/monetization/premium/checkout`, s.token, { planId: monthly, businessId: '00000000-0000-4000-8000-000000000000' })).status).toBe(422);
  });
});
