import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { makeNeed } from '../../admin_guard';
import type { Ctx } from '../../context';
import { withTx } from '../../db';
import { HttpError } from '../../errors';
import { decodeCursor, idParam, pageSchema, toPage, tsText } from '../../http';
import { audit } from '../moderation';
import { manualPaymentSettings } from './advertiser_routes';
import { formatMoney } from './money';
import { ENTITLEMENT_KEYS, isEntitlementKey } from './entitlements';
import { newUniqueReference } from './service';
import { subscriptionStatus, userEntitlements } from './subscriptions';

const ORDER_HOURS = 48;
const MAX_OPEN_PLAN_ORDERS = 3;
const MAX_PRICE_MINOR = 100_000_00; // KES 100,000: refuses a fat-fingered price

type PlanRow = { id: string; code: string; audience: string; name: string; description: string; currency: string; price_minor: string; period_days: number; entitlements: string[]; active: boolean };
const planView = (p: PlanRow) => ({
  id: p.id, code: p.code, audience: p.audience, name: p.name, description: p.description, currency: p.currency,
  priceMinor: Number(p.price_minor), price: formatMoney(Number(p.price_minor), p.currency), periodDays: p.period_days,
  entitlements: p.entitlements, renews: false,
});

export function premiumRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;
    const need = makeNeed(ctx);

    /** Plans on sale. Empty until staff switch a plan on, so the app shows "not available yet" instead of selling unbuilt features. */
    app.get('/monetization/premium/plans', async (req) => {
      await ctx.auth(req);
      const { audience } = z.object({ audience: z.enum(['user', 'business']).default('user') }).strict().parse(req.query ?? {});
      const { rows } = await pool.query<PlanRow>('select * from subscription_plans where active and audience = $1 order by sort_order', [audience]);
      return { plans: rows.map(planView), payment: await manualPaymentSettings(pool) };
    });

    /** What this person may use right now. The app may cache this briefly; it cannot grant anything by editing local data. */
    app.get('/me/entitlements', async (req, reply) => {
      const me = await ctx.auth(req);
      reply.header('Cache-Control', 'private, max-age=60');
      return { entitlements: await userEntitlements(pool, me.id), subscription: await subscriptionStatus(pool, me.id), serverTime: new Date().toISOString() };
    });

    /** The price comes from the plan row; the request has no price field. A repeated tap with the same key returns the same order. */
    app.post('/monetization/premium/checkout', async (req, reply) => {
      const me = await ctx.auth(req);
      ctx.limit(`sub-checkout:${me.id}`, 20, 3600);
      const b = z.object({ planId: z.string().uuid(), businessId: z.string().uuid().optional(), createKey: z.string().trim().min(8).max(64).optional() }).strict().parse(req.body ?? {});
      const settings = await manualPaymentSettings(pool);
      const out = await withTx(pool, async (client) => {
        if (b.createKey) {
          const dup = (await client.query('select id, reference, amount_minor, currency, expires_at, status from orders where advertiser_id = $1 and idempotency_key = $2', [me.id, b.createKey])).rows[0];
          if (dup) return { order: dup, repeat: true };
        }
        const plan = (await client.query<PlanRow>('select * from subscription_plans where id = $1 and active', [b.planId])).rows[0];
        if (!plan) throw new HttpError(404, 'not_found', 'That plan is not available.');
        if (!settings.enabled) throw new HttpError(503, 'payments_unavailable', 'Payments are not available yet.');
        if (plan.audience === 'business') {
          if (!b.businessId) throw new HttpError(422, 'validation', 'Choose the business this plan is for.');
          const biz = (await client.query<{ status: string }>(
            `select b.status from businesses b join business_members m on m.business_id = b.id and m.user_id = $2 and m.role = 'owner' where b.id = $1`, [b.businessId, me.id])).rows[0];
          if (!biz) throw new HttpError(404, 'not_found', 'Not found.'); // not yours: same answer as missing
          if (biz.status !== 'approved') throw new HttpError(422, 'validation', 'The business must be approved before it can subscribe.');
        } else if (b.businessId) throw new HttpError(422, 'validation', 'This plan is for a person, not a business.');
        const open = Number((await client.query<{ n: string }>("select count(*) as n from orders where advertiser_id = $1 and plan_id is not null and status = 'pending_payment'", [me.id])).rows[0].n);
        if (open >= MAX_OPEN_PLAN_ORDERS) throw new HttpError(429, 'too_many_open_orders', 'You already have unpaid subscription orders. Finish or wait for them to expire.');
        const price = Number(plan.price_minor);
        const reference = await newUniqueReference(client);
        const snapshot = { code: plan.code, audience: plan.audience, name: plan.name, priceMinor: price, currency: plan.currency, periodDays: plan.period_days, entitlements: plan.entitlements };
        const order = (await client.query(
          `insert into orders (reference, advertiser_id, plan_id, business_id, package_snapshot, amount_minor, currency, status, idempotency_key, expires_at)
           values ($1, $2, $3, $4, $5, $6, $7, 'pending_payment', $8, now() + make_interval(hours => $9::int)) returning id, reference, amount_minor, currency, expires_at, status`,
          [reference, me.id, plan.id, b.businessId ?? null, JSON.stringify(snapshot), price, plan.currency, b.createKey ?? null, ORDER_HOURS])).rows[0];
        return { order, repeat: false };
      });
      reply.code(out.repeat ? 200 : 201);
      const o = out.order;
      return {
        order: { id: o.id, reference: o.reference, amountMinor: Number(o.amount_minor), amount: formatMoney(Number(o.amount_minor), o.currency), status: o.status, expiresAt: o.expires_at },
        pay: settings,
        // Tapping Subscribe proves nothing. Access starts only when staff confirm the payment (POST /orders/:id/payment records the claim).
        note: 'Access starts after your payment is confirmed. Submit your M-Pesa receipt code once you have paid.',
      };
    });

    /** Subscription purchases and their state, newest first. Amounts are the frozen order amounts; a pending order is never shown as paid. */
    app.get('/me/billing-history', async (req) => {
      const me = await ctx.auth(req);
      const q = pageSchema.strict().parse(req.query ?? {});
      const cur = decodeCursor(q.cursor);
      const params: unknown[] = [me.id];
      let where = 'o.advertiser_id = $1 and o.plan_id is not null';
      if (cur) { params.push(cur.t, cur.id); where += ` and (o.created_at, o.id) < ($2::timestamptz, $3::uuid)`; }
      params.push(q.limit + 1);
      const { rows } = await pool.query(
        `select o.id, o.reference, o.status, o.amount_minor, o.currency, o.created_at, o.paid_at, p.name as plan_name, ${tsText('o.created_at')} as "cursorT"
           from orders o join subscription_plans p on p.id = o.plan_id where ${where} order by o.created_at desc, o.id desc limit $${params.length}`, params);
      const page = toPage(rows, q.limit);
      return { items: page.items.map((r) => ({ id: r.id, reference: r.reference, status: r.status, planName: r.plan_name, amountMinor: Number(r.amount_minor), amount: formatMoney(Number(r.amount_minor), r.currency), createdAt: r.created_at, paidAt: r.paid_at })), nextCursor: page.nextCursor };
    });

    // ------------------------------------------------------------ staff: plans (packages.manage)
    app.get('/admin/monetization/plans', async (req) => {
      await need(req, 'packages.manage');
      const { rows } = await pool.query<PlanRow>('select * from subscription_plans order by sort_order');
      return { plans: rows.map((p) => ({ ...planView(p), active: p.active })) };
    });

    app.patch('/admin/monetization/plans/:id', async (req) => {
      const me = await need(req, 'packages.manage');
      const id = idParam((req.params as { id?: string }).id);
      const b = z.object({ active: z.boolean().optional(), priceMinor: z.number().int().min(100).max(MAX_PRICE_MINOR).optional(), confirmPriceChange: z.literal(true).optional() }).strict().parse(req.body ?? {});
      return withTx(pool, async (client) => {
        const cur = (await client.query<PlanRow>('select * from subscription_plans where id = $1 for update', [id])).rows[0];
        if (!cur) throw new HttpError(404, 'not_found', 'Not found.');
        if (b.priceMinor !== undefined && b.priceMinor !== Number(cur.price_minor) && b.confirmPriceChange !== true) {
          throw new HttpError(422, 'confirm_required', `Confirm the price change from ${formatMoney(Number(cur.price_minor))} to ${formatMoney(b.priceMinor)}. Existing orders keep their price.`);
        }
        const next = { active: b.active ?? cur.active, price: b.priceMinor ?? Number(cur.price_minor) };
        // Never put something on sale that has nothing to sell.
        if (next.active && !cur.active && cur.entitlements.length === 0) throw new HttpError(422, 'validation', 'This plan lists no working features yet, so it cannot be switched on.');
        await client.query('update subscription_plans set active = $2, price_minor = $3, updated_at = now() where id = $1', [id, next.active, next.price]);
        await audit(client, me.id, 'plan.update', 'subscription_plan', id, { code: cur.code, from: { active: cur.active, priceMinor: Number(cur.price_minor) }, to: { active: next.active, priceMinor: next.price } });
        return { status: 'ok' };
      });
    });

    // ------------------------------------------------------------ staff: the Premium control page
    /** Everything the Premium admin page needs in one read: is it ready to sell, what is on sale, who is subscribed. */
    app.get('/admin/monetization/premium/overview', async (req) => {
      await need(req, 'packages.manage');
      const [pay, plans, flags, live, orders] = await Promise.all([
        manualPaymentSettings(pool),
        pool.query<PlanRow & { live: string }>(
          `select p.*, (select count(*) from subscriptions s where s.plan_id = p.id and s.status = 'active' and s.period_start <= now() and s.period_end > now()) as live
             from subscription_plans p order by p.sort_order`),
        pool.query<{ key: string; enabled: boolean }>('select key, enabled from premium_feature_flags'),
        pool.query<{ users: string; businesses: string }>(
          `select count(*) filter (where business_id is null) as users, count(*) filter (where business_id is not null) as businesses
             from subscriptions where status = 'active' and period_start <= now() and period_end > now()`),
        pool.query<{ pending: string; paid: string; gross: string }>(
          `select count(*) filter (where status = 'pending_payment') as pending, count(*) filter (where status = 'paid') as paid,
                  coalesce(sum(amount_minor) filter (where status = 'paid'), 0) as gross
             from orders where plan_id is not null`),
      ]);
      const off = new Set(flags.rows.filter((f) => !f.enabled).map((f) => f.key));
      const o = orders.rows[0];
      return {
        payments: { enabled: pay.enabled, hasNumber: !!pay.number },
        plans: plans.rows.map((p) => ({ ...planView(p), active: p.active, liveSubscribers: Number(p.live) })),
        features: ENTITLEMENT_KEYS.map((key) => ({ key, enabled: !off.has(key), inAnyPlan: plans.rows.some((p) => p.entitlements.includes(key)) })),
        totals: { liveStudents: Number(live.rows[0].users), liveBusinesses: Number(live.rows[0].businesses), pendingOrders: Number(o.pending), paidOrders: Number(o.paid), confirmedGrossMinor: Number(o.gross) },
      };
    });

    /** The kill switch. Off removes the key from everyone at once; on restores it. Subscriptions and payments are untouched. */
    app.patch('/admin/monetization/premium/features/:key', async (req) => {
      const me = await need(req, 'packages.manage');
      const key = String((req.params as { key?: string }).key ?? '');
      if (!isEntitlementKey(key)) throw new HttpError(404, 'not_found', 'Not found.');
      const b = z.object({ enabled: z.boolean() }).strict().parse(req.body ?? {});
      await withTx(pool, async (client) => {
        await client.query(
          `insert into premium_feature_flags (key, enabled, updated_by) values ($1, $2, $3)
           on conflict (key) do update set enabled = $2, updated_by = $3, updated_at = now()`, [key, b.enabled, me.id]);
        await audit(client, me.id, b.enabled ? 'premium_feature.on' : 'premium_feature.off', 'premium_feature', null, { key });
      });
      return { status: 'ok' };
    });

    /**
     * Opens a full refund for a paid subscription. Before this existed a Premium purchase could never be handed back,
     * because refunds only started from campaigns. A different staff member then approves and records the payout
     * (same rules as every refund); the period ends only when the payout is recorded.
     */
    app.post('/admin/monetization/subscription-orders/:id/refund', async (req, reply) => {
      const me = await need(req, 'payments.confirm');
      const id = idParam((req.params as { id?: string }).id);
      const b = z.object({ reason: z.string().trim().min(3).max(500) }).strict().parse(req.body ?? {});
      const out = await withTx(pool, async (client) => {
        const o = (await client.query<{ status: string; plan_id: string | null }>('select status, plan_id from orders where id = $1 for update', [id])).rows[0];
        if (!o || !o.plan_id) throw new HttpError(404, 'not_found', 'Not found.');
        if (o.status !== 'paid') throw new HttpError(409, 'invalid_state', 'Only a paid subscription can be refunded.');
        const p = (await client.query<{ id: string; amount_minor: string }>("select id, amount_minor from payments where order_id = $1 and status = 'confirmed' limit 1", [id])).rows[0];
        if (!p) throw new HttpError(409, 'invalid_state', 'There is no confirmed payment to refund.');
        const r = await client.query<{ id: string }>(
          `insert into refunds (payment_id, order_id, amount_minor, reason, requested_by) values ($1, $2, $3, $4, $5) on conflict do nothing returning id`,
          [p.id, id, p.amount_minor, b.reason, me.id]);
        if (!r.rows[0]) throw new HttpError(409, 'invalid_state', 'A refund is already open for this payment.');
        await audit(client, me.id, 'subscription.refund_request', 'order', id, { refundId: r.rows[0].id });
        return { refundId: r.rows[0].id };
      });
      reply.code(201);
      return out;
    });

    /** Who has Premium. Names and handles only (no email, no phone), newest first, a page at a time. */
    app.get('/admin/monetization/subscriptions', async (req) => {
      await need(req, 'payments.view');
      const q = pageSchema.extend({ state: z.enum(['live', 'ended', 'refunded']).default('live') }).strict().parse(req.query ?? {});
      const cur = decodeCursor(q.cursor);
      const params: unknown[] = [];
      const where = [q.state === 'live' ? "s.status = 'active' and s.period_start <= now() and s.period_end > now()"
        : q.state === 'ended' ? "s.status = 'active' and s.period_end <= now()" : "s.status = 'refunded'"];
      if (cur) { params.push(cur.t, cur.id); where.push('(s.created_at, s.id) < ($1::timestamptz, $2::uuid)'); }
      params.push(q.limit + 1);
      const { rows } = await pool.query(
        `select s.id, s.period_start, s.period_end, s.business_id, u.full_name, u.handle, p.name as plan_name, o.reference, ${tsText('s.created_at')} as "cursorT"
           from subscriptions s join users u on u.id = s.user_id join subscription_plans p on p.id = s.plan_id join orders o on o.id = s.order_id
          where ${where.join(' and ')} order by s.created_at desc, s.id desc limit $${params.length}`, params);
      const page = toPage(rows, q.limit);
      return { items: page.items.map((r) => ({ id: r.id, name: r.full_name, handle: r.handle, plan: r.plan_name, reference: r.reference, business: r.business_id !== null, startsAt: r.period_start, endsAt: r.period_end })), nextCursor: page.nextCursor };
    });
  };
}
