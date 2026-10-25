import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { makeNeed } from '../../admin_guard';
import type { Ctx } from '../../context';
import { withTx } from '../../db';
import { HttpError } from '../../errors';
import { decodeCursor, idParam, pageSchema, toPage, tsText } from '../../http';
import { audit } from '../moderation';
import { resumeTarget } from './campaign_state';
import { formatMoney } from './money';
import { packageView } from './advertiser_routes';
import {
  PLACEMENTS_FOR_PACKAGE, advanceByClock, eventPromotable, factsOf, isUnique, lockCampaign, moveCampaign, orderStatusOf,
  requestRefundIfPaid, stopCampaign, sweepCampaigns, type CampaignRow,
} from './service';

const KINDS = ['event_promotion', 'featured_business', 'sponsored_post', 'featured_discover'] as const;
const MAX_PRICE_MINOR = 100_000_000; // KES 1,000,000: a typo guard, not a business rule
const note = z.string().trim().min(3, 'Say why (at least 3 characters).').max(500);

const packageBody = z.object({
  code: z.string().trim().regex(/^[a-z0-9_-]{3,40}$/, 'Use 3–40 lower-case letters, numbers, - or _.'),
  kind: z.enum(KINDS),
  name: z.string().trim().min(3).max(60),
  description: z.string().trim().max(500).default(''),
  priceMinor: z.number().int().min(0).max(MAX_PRICE_MINOR),
  currency: z.literal('KES').default('KES'),
  durationDays: z.number().int().min(1).max(90),
  placements: z.array(z.enum(['home_feed', 'discover_feed', 'discover_events', 'business_directory'])).min(1).max(4),
  impressionCap: z.number().int().min(1).max(10_000_000).nullable().default(null),
  frequencyCapPerDay: z.number().int().min(1).max(20).default(3),
  active: z.boolean().default(true),
}).strict();
// A change is a partial edit; changing the price must be confirmed so a slip of the finger cannot reprice the shop.
const packagePatch = packageBody.omit({ code: true, kind: true, currency: true }).partial().extend({ confirmPriceChange: z.literal(true).optional() }).strict();

const paymentSettings = z.object({
  enabled: z.boolean(),
  method: z.enum(['till', 'paybill']),
  number: z.string().trim().regex(/^\d{5,10}$/, 'Enter the till or paybill number (5–10 digits).').or(z.literal('')),
  accountName: z.string().trim().max(60).default(''),
  instructions: z.string().trim().max(300).default(''),
}).strict().refine((v) => !v.enabled || v.number !== '', { message: 'Enter the number before switching payments on.', path: ['number'] });

export function monetizationAdminRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;
    const need = makeNeed(ctx);
    const status = z.object({ status: z.string().max(30).optional() });

    // ------------------------------------------------------------ packages and settings
    app.get('/admin/monetization/packages', async (req) => {
      await need(req, 'packages.manage');
      const { rows } = await pool.query('select * from promotion_packages order by kind, price_minor, name');
      return { items: rows.map((r) => ({ ...packageView(r), active: r.active })) };
    });

    app.post('/admin/monetization/packages', async (req, reply) => {
      const me = await need(req, 'packages.manage');
      const b = packageBody.parse(req.body ?? {});
      const allowed = PLACEMENTS_FOR_PACKAGE[b.kind];
      if (!b.placements.every((p) => allowed.includes(p))) throw new HttpError(422, 'validation', `That kind of package can use: ${allowed.join(', ')}.`);
      try {
        const id = await withTx(pool, async (client) => {
          const row = (await client.query<{ id: string }>(
            `insert into promotion_packages (code, kind, name, description, currency, price_minor, duration_days, placements, impression_cap, frequency_cap_per_day, active, created_by)
             values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12) returning id`,
            [b.code, b.kind, b.name, b.description, b.currency, b.priceMinor, b.durationDays, b.placements, b.impressionCap, b.frequencyCapPerDay, b.active, me.id])).rows[0];
          await audit(client, me.id, 'package.create', 'package', row.id, { code: b.code, priceMinor: b.priceMinor });
          return row.id;
        });
        reply.code(201);
        return { id };
      } catch (e) {
        if (isUnique(e)) throw new HttpError(409, 'duplicate', 'A package with that code already exists.');
        throw e;
      }
    });

    app.patch('/admin/monetization/packages/:id', async (req) => {
      const me = await need(req, 'packages.manage');
      const id = idParam((req.params as { id?: string }).id);
      const b = packagePatch.parse(req.body ?? {});
      await withTx(pool, async (client) => {
        const cur = (await client.query<{ kind: string; price_minor: string; name: string; active: boolean; placements: string[] }>(
          'select kind, price_minor, name, active, placements from promotion_packages where id = $1 for update', [id])).rows[0];
        if (!cur) throw new HttpError(404, 'not_found', 'Not found.');
        if (b.priceMinor !== undefined && b.priceMinor !== Number(cur.price_minor) && b.confirmPriceChange !== true) {
          throw new HttpError(422, 'confirm_required', `Confirm the price change from ${formatMoney(Number(cur.price_minor))} to ${formatMoney(b.priceMinor)}. Existing orders keep their price.`);
        }
        if (b.placements && !b.placements.every((p) => PLACEMENTS_FOR_PACKAGE[cur.kind].includes(p))) throw new HttpError(422, 'validation', 'A placement is not allowed for this kind of package.');
        const map: Record<string, unknown> = {
          name: b.name, description: b.description, price_minor: b.priceMinor, duration_days: b.durationDays, placements: b.placements,
          impression_cap: b.impressionCap, frequency_cap_per_day: b.frequencyCapPerDay, active: b.active,
        };
        const cols = Object.keys(map).filter((k) => map[k] !== undefined);
        if (!cols.length) throw new HttpError(422, 'validation', 'Nothing to change.');
        // Column names are the fixed keys of `map` above; values are parameters.
        await client.query(`update promotion_packages set ${cols.map((k, i) => `${k} = $${i + 2}`).join(', ')}, updated_at = now() where id = $1`, [id, ...cols.map((k) => map[k])]);
        await audit(client, me.id, 'package.update', 'package', id, {
          changed: cols, ...(b.priceMinor !== undefined ? { priceFrom: Number(cur.price_minor), priceTo: b.priceMinor } : {}),
        });
      });
      return { ok: true };
    });

    app.get('/admin/monetization/payment-settings', async (req) => {
      await need(req, 'packages.manage');
      return (await pool.query("select value from monetization_settings where key = 'manual_payment'")).rows[0]?.value ?? {};
    });
    app.put('/admin/monetization/payment-settings', async (req) => {
      const me = await need(req, 'packages.manage');
      const b = paymentSettings.parse(req.body ?? {});
      await withTx(pool, async (client) => {
        await client.query(
          `insert into monetization_settings (key, value, updated_by, updated_at) values ('manual_payment', $1, $2, now())
           on conflict (key) do update set value = excluded.value, updated_by = excluded.updated_by, updated_at = now()`, [JSON.stringify(b), me.id]);
        // The number itself is not written to the audit log; who changed it, when, and whether payments are on is.
        await audit(client, me.id, 'payment_settings.update', 'settings', null, { enabled: b.enabled, method: b.method });
      });
      return { ok: true };
    });

    // ------------------------------------------------------------ payments
    app.get('/admin/monetization/payments', async (req) => {
      await need(req, 'payments.view');
      const q = pageSchema.merge(status).strict().parse(req.query ?? {});
      const cur = decodeCursor(q.cursor);
      const params: unknown[] = [];
      let where = 'true';
      if (q.status) { params.push(q.status); where += ` and p.status = $${params.length}`; }
      if (cur) { params.push(cur.t, cur.id); where += ` and (p.created_at, p.id) < ($${params.length - 1}::timestamptz, $${params.length}::uuid)`; }
      params.push(q.limit + 1);
      const { rows } = await pool.query(
        `select p.id, p.status, p.provider, p.provider_reference, p.amount_minor, p.received_minor, p.currency, p.reconciliation, p.failure_reason, p.created_at, p.confirmed_at,
                o.reference as order_reference, u.handle as advertiser_handle, c.headline, c.id as campaign_id,
                (select r.status from refunds r where r.payment_id = p.id order by r.created_at desc limit 1) as refund_status,
                ${tsText('p.created_at')} as "cursorT"
           from payments p join orders o on o.id = p.order_id left join users u on u.id = o.advertiser_id left join campaigns c on c.order_id = o.id
          where ${where} order by p.created_at desc, p.id desc limit $${params.length}`, params);
      const page = toPage(rows as (Record<string, unknown> & { cursorT: string; id: string })[], q.limit);
      return {
        nextCursor: page.nextCursor,
        items: page.items.map((r) => ({
          id: r.id, status: r.status, provider: r.provider, receipt: r.provider_reference, amountMinor: Number(r.amount_minor), amount: formatMoney(Number(r.amount_minor), r.currency as string),
          currency: r.currency, receivedMinor: r.received_minor === null ? null : Number(r.received_minor), reconciliation: r.reconciliation, failureReason: r.failure_reason,
          submittedAt: r.created_at, confirmedAt: r.confirmed_at, orderReference: r.order_reference, advertiser: r.advertiser_handle, campaignId: r.campaign_id,
          campaign: r.headline, refundStatus: r.refund_status,
        })),
      };
    });

    app.post('/admin/monetization/payments/:id/confirm', async (req) => {
      const me = await need(req, 'payments.confirm');
      const id = idParam((req.params as { id?: string }).id);
      const b = z.object({ receivedMinor: z.number().int().min(0).max(MAX_PRICE_MINOR * 10), note: z.string().trim().max(300).default('') }).strict().parse(req.body ?? {});
      const res = await withTx(pool, async (client) => {
        const p = (await client.query<{ id: string; order_id: string; status: string; amount_minor: string; provider_reference: string | null }>(
          'select id, order_id, status, amount_minor, provider_reference from payments where id = $1 for update', [id])).rows[0];
        if (!p) throw new HttpError(404, 'not_found', 'Not found.');
        if (p.status === 'confirmed') return { result: 'confirmed' as const, repeat: true }; // a second click changes nothing
        if (p.status !== 'awaiting_verification') throw new HttpError(409, 'invalid_state', 'This payment is not waiting for confirmation.');
        const o = (await client.query<{ id: string; advertiser_id: string | null; status: string; reference: string }>(
          'select id, advertiser_id, status, reference from orders where id = $1 for update', [p.order_id])).rows[0];
        // Nobody confirms their own payment.
        if (o.advertiser_id === me.id) throw new HttpError(403, 'conflict_of_interest', 'You cannot confirm your own payment.');
        if (b.receivedMinor !== Number(p.amount_minor)) {
          await client.query("update payments set received_minor = $2, reconciliation = 'mismatch', updated_at = now() where id = $1", [id, b.receivedMinor]);
          await client.query("insert into payment_events (payment_id, kind, actor_id, detail) values ($1, 'amount_mismatch', $2, $3)", [id, me.id, JSON.stringify({ expected: Number(p.amount_minor), received: b.receivedMinor })]);
          await audit(client, me.id, 'payment.mismatch', 'payment', id, { order: o.reference, expected: Number(p.amount_minor), received: b.receivedMinor });
          return { result: 'mismatch' as const };
        }
        await client.query(
          "update payments set status = 'confirmed', received_minor = $2, reconciliation = 'reconciled', confirmed_by = $3, confirmed_at = now(), updated_at = now() where id = $1",
          [id, b.receivedMinor, me.id]);
        await client.query("insert into payment_events (payment_id, kind, actor_id, detail) values ($1, 'confirmed', $2, $3)", [id, me.id, JSON.stringify({ note: b.note })]);
        const late = o.status !== 'pending_payment';
        await client.query("update orders set status = 'paid', paid_at = now(), updated_at = now() where id = $1", [o.id]);
        const cid = (await client.query<{ id: string }>('select id from campaigns where order_id = $1', [o.id])).rows[0]?.id;
        if (cid) {
          const c = await lockCampaign(client, cid);
          if (c.status === 'pending_payment') await moveCampaign(client, c, 'pending_review', 'system', me.id, 'payment confirmed');
          else if (late || c.status === 'cancelled' || c.status === 'rejected') {
            // The money arrived for a promotion that no longer exists: do not keep it, queue it for refund.
            if (await requestRefundIfPaid(client, c, 'Payment arrived after the promotion was cancelled.', me.id)) {
              if (c.status === 'cancelled' || c.status === 'rejected') await moveCampaign(client, c, 'refund_pending', 'system', me.id, 'refund requested');
            }
          }
        }
        await audit(client, me.id, 'payment.confirm', 'payment', id, { order: o.reference, amountMinor: Number(p.amount_minor), late });
        return { result: 'confirmed' as const, repeat: false };
      });
      if (res.result === 'mismatch') throw new HttpError(422, 'amount_mismatch', 'The amount received does not match the order. Nothing was confirmed; reject this payment or ask the advertiser to contact support.');
      return { status: 'confirmed' };
    });

    app.post('/admin/monetization/payments/:id/reject', async (req) => {
      const me = await need(req, 'payments.confirm');
      const id = idParam((req.params as { id?: string }).id);
      const { reason } = z.object({ reason: note }).strict().parse(req.body ?? {});
      await withTx(pool, async (client) => {
        const p = (await client.query<{ status: string }>('select status from payments where id = $1 for update', [id])).rows[0];
        if (!p) throw new HttpError(404, 'not_found', 'Not found.');
        if (p.status !== 'awaiting_verification') throw new HttpError(409, 'invalid_state', 'This payment is not waiting for confirmation.');
        await client.query("update payments set status = 'failed', failure_reason = $2, updated_at = now() where id = $1", [id, reason]);
        await client.query("insert into payment_events (payment_id, kind, actor_id, detail) values ($1, 'rejected', $2, $3)", [id, me.id, JSON.stringify({ reason })]);
        await audit(client, me.id, 'payment.reject', 'payment', id, {});
      });
      return { status: 'failed' };
    });

    // ------------------------------------------------------------ refunds
    app.get('/admin/monetization/refunds', async (req) => {
      await need(req, 'payments.view');
      const q = pageSchema.merge(status).strict().parse(req.query ?? {});
      const cur = decodeCursor(q.cursor);
      const params: unknown[] = [];
      let where = 'true';
      if (q.status) { params.push(q.status); where += ` and r.status = $${params.length}`; }
      if (cur) { params.push(cur.t, cur.id); where += ` and (r.created_at, r.id) < ($${params.length - 1}::timestamptz, $${params.length}::uuid)`; }
      params.push(q.limit + 1);
      const { rows } = await pool.query(
        `select r.id, r.status, r.amount_minor, r.reason, r.decision_note, r.payout_reference, r.created_at, o.reference as order_reference, p.provider_reference, ${tsText('r.created_at')} as "cursorT"
           from refunds r join orders o on o.id = r.order_id join payments p on p.id = r.payment_id where ${where} order by r.created_at desc, r.id desc limit $${params.length}`, params);
      const page = toPage(rows as (Record<string, unknown> & { cursorT: string; id: string })[], q.limit);
      return { nextCursor: page.nextCursor, items: page.items.map((r) => ({ ...r, amount: formatMoney(Number(r.amount_minor)), amount_minor: undefined, amountMinor: Number(r.amount_minor) })) };
    });

    async function decideRefund(req: Parameters<Ctx['auth']>[0], step: 'approve' | 'reject' | 'paid') {
      const me = await need(req, 'refunds.approve');
      const id = idParam((req.params as { id?: string }).id);
      const b = z.object({ note: z.string().trim().max(500).default(''), payoutReference: z.string().trim().min(6).max(60).optional() }).strict().parse(req.body ?? {});
      if (step === 'reject' && b.note.length < 3) throw new HttpError(422, 'validation', 'Say why the refund is declined.');
      if (step === 'paid' && !b.payoutReference) throw new HttpError(422, 'validation', 'Enter the M-Pesa reference of the refund you sent.');
      return withTx(pool, async (client) => {
        const r = (await client.query<{ id: string; status: string; payment_id: string; order_id: string; requested_by: string | null }>(
          'select id, status, payment_id, order_id, requested_by from refunds where id = $1 for update', [id])).rows[0];
        if (!r) throw new HttpError(404, 'not_found', 'Not found.');
        // The person who asked for a refund cannot also approve it.
        if (step !== 'paid' && r.requested_by === me.id) throw new HttpError(403, 'conflict_of_interest', 'Someone else must decide a refund you requested.');
        const cid = (await client.query<{ id: string }>('select id from campaigns where order_id = $1', [r.order_id])).rows[0]?.id;
        if (step === 'approve') {
          if (r.status !== 'requested') throw new HttpError(409, 'invalid_state', 'This refund is not waiting for a decision.');
          await client.query("update refunds set status = 'approved', decided_by = $2, decided_at = now(), decision_note = $3 where id = $1", [id, me.id, b.note]);
        } else if (step === 'reject') {
          if (r.status !== 'requested') throw new HttpError(409, 'invalid_state', 'This refund is not waiting for a decision.');
          await client.query("update refunds set status = 'rejected', decided_by = $2, decided_at = now(), decision_note = $3 where id = $1", [id, me.id, b.note]);
          if (cid) { const c = await lockCampaign(client, cid); if (c.status === 'refund_pending') await moveCampaign(client, c, 'cancelled', 'admin', me.id, 'refund declined'); }
        } else {
          if (r.status !== 'approved') throw new HttpError(409, 'invalid_state', 'Approve the refund before recording the payout.');
          await client.query("update refunds set status = 'paid_out', payout_reference = $2 where id = $1", [id, b.payoutReference]);
          await client.query("update payments set status = 'refunded', updated_at = now() where id = $1", [r.payment_id]);
          await client.query("update orders set status = 'refunded', updated_at = now() where id = $1", [r.order_id]);
          await client.query("insert into payment_events (payment_id, kind, actor_id) values ($1, 'refunded', $2)", [r.payment_id, me.id]);
          if (cid) { const c = await lockCampaign(client, cid); if (c.status === 'refund_pending') await moveCampaign(client, c, 'refunded', 'admin', me.id, 'refund paid'); }
        }
        await audit(client, me.id, `refund.${step}`, 'refund', id, {});
        return { status: step === 'approve' ? 'approved' : step === 'reject' ? 'rejected' : 'paid_out' };
      });
    }
    app.post('/admin/monetization/refunds/:id/approve', (req) => decideRefund(req, 'approve'));
    app.post('/admin/monetization/refunds/:id/reject', (req) => decideRefund(req, 'reject'));
    app.post('/admin/monetization/refunds/:id/paid', (req) => decideRefund(req, 'paid'));

    // ------------------------------------------------------------ campaigns
    app.get('/admin/monetization/campaigns', async (req) => {
      await need(req, 'campaigns.review');
      const q = pageSchema.merge(status).strict().parse(req.query ?? {});
      const cur = decodeCursor(q.cursor);
      const params: unknown[] = [];
      let where = 'true';
      if (q.status) { params.push(q.status); where += ` and c.status = $${params.length}`; }
      if (cur) { params.push(cur.t, cur.id); where += ` and (c.created_at, c.id) < ($${params.length - 1}::timestamptz, $${params.length}::uuid)`; }
      params.push(q.limit + 1);
      const { rows } = await pool.query(
        `select c.id, c.kind, c.headline, c.status, c.review_status, c.starts_at, c.ends_at, c.delivered_impressions, c.delivered_clicks, c.event_id, c.business_id,
                o.status as order_status, o.amount_minor, o.currency, u.handle as advertiser_handle, ${tsText('c.created_at')} as "cursorT"
           from campaigns c left join orders o on o.id = c.order_id left join users u on u.id = c.advertiser_id
          where ${where} order by c.created_at desc, c.id desc limit $${params.length}`, params);
      const page = toPage(rows as (Record<string, unknown> & { cursorT: string; id: string })[], q.limit);
      return { nextCursor: page.nextCursor, items: page.items.map((r) => ({ ...r, amount: r.amount_minor ? formatMoney(Number(r.amount_minor), r.currency as string) : null, amount_minor: undefined })) };
    });

    app.get('/admin/monetization/campaigns/:id', async (req) => {
      await need(req, 'campaigns.review');
      const id = idParam((req.params as { id?: string }).id);
      const c = (await pool.query('select * from campaigns where id = $1', [id])).rows[0];
      if (!c) throw new HttpError(404, 'not_found', 'Not found.');
      const history = (await pool.query('select h.to_status, h.actor_kind, h.note, h.created_at, u.handle from campaign_status_history h left join users u on u.id = h.actor_id where h.campaign_id = $1 order by h.id', [id])).rows;
      return { campaign: c, history };
    });

    async function review(req: Parameters<Ctx['auth']>[0], action: 'approve' | 'reject' | 'pause' | 'resume' | 'cancel') {
      const me = await need(req, 'campaigns.review');
      const id = idParam((req.params as { id?: string }).id);
      const b = z.object({ note: z.string().trim().max(500).default(''), refund: z.boolean().optional() }).strict().parse(req.body ?? {});
      if (['reject', 'pause', 'cancel'].includes(action) && b.note.length < 3) throw new HttpError(422, 'validation', 'Say why (at least 3 characters).');
      return withTx(pool, async (client) => {
        const c: CampaignRow = await lockCampaign(client, id);
        const orderStatus = await orderStatusOf(client, c.order_id);
        const decided = { review_note: b.note, reviewed_by: me.id, reviewed_at: new Date().toISOString() };
        if (action === 'approve') {
          if (c.status !== 'pending_review') throw new HttpError(409, 'invalid_state', 'Only a campaign waiting for review can be approved.');
          if (orderStatus !== 'paid') throw new HttpError(409, 'not_paid', 'This campaign has not been paid for.');
          if (c.kind === 'event_promotion' && !(c.event_id && (await eventPromotable(client, c.event_id, null)).ok)) throw new HttpError(409, 'event_ineligible', 'The event can no longer be promoted.');
          if (c.business_id && !(await client.query("select 1 from businesses where id = $1 and status = 'approved'", [c.business_id])).rowCount) throw new HttpError(409, 'business_ineligible', 'The business is not approved.');
          await moveCampaign(client, c, 'approved', 'admin', me.id, b.note, { review_status: 'approved', ...decided });
          // Approval does not start delivery by itself: the clock rules re-check payment, review and dates.
          await advanceByClock(client, c);
        } else if (action === 'reject') {
          await moveCampaign(client, c, 'rejected', 'admin', me.id, b.note, { review_status: 'rejected', ...decided });
          if (orderStatus === 'paid' && (await requestRefundIfPaid(client, c, `Campaign rejected: ${b.note}`.slice(0, 500), me.id))) await moveCampaign(client, c, 'refund_pending', 'system', me.id, 'refund requested');
        } else if (action === 'pause') {
          await moveCampaign(client, c, 'paused', 'admin', me.id, b.note, { paused_by: 'admin' });
        } else if (action === 'resume') {
          if (c.status !== 'paused') throw new HttpError(409, 'invalid_state', 'This campaign is not paused.');
          if (orderStatus !== 'paid' || c.review_status !== 'approved') throw new HttpError(409, 'invalid_state', 'This campaign cannot run.');
          const to = resumeTarget({ startsAt: c.starts_at, endsAt: c.ends_at, impressionCap: c.impression_cap, deliveredImpressions: Number(c.delivered_impressions) }, new Date());
          await moveCampaign(client, c, to, to === 'completed' ? 'system' : 'admin', me.id, b.note, { paused_by: null });
        } else {
          await stopCampaign(client, c, 'admin', me.id, b.note, b.refund);
        }
        await audit(client, me.id, `campaign.${action}`, 'campaign', id, { status: c.status });
        return { status: c.status };
      });
    }
    for (const a of ['approve', 'reject', 'pause', 'resume', 'cancel'] as const) app.post(`/admin/monetization/campaigns/:id/${a}`, (req) => review(req, a));

    // ------------------------------------------------------------ businesses
    app.get('/admin/monetization/businesses', async (req) => {
      await need(req, 'advertisers.view');
      const q = pageSchema.merge(status).strict().parse(req.query ?? {});
      const cur = decodeCursor(q.cursor);
      const params: unknown[] = [];
      let where = 'true';
      if (q.status) { params.push(q.status); where += ` and b.status = $${params.length}`; }
      if (cur) { params.push(cur.t, cur.id); where += ` and (b.created_at, b.id) < ($${params.length - 1}::timestamptz, $${params.length}::uuid)`; }
      params.push(q.limit + 1);
      const { rows } = await pool.query(
        `select b.id, b.name, b.category, b.status, b.verification, b.review_note, b.created_at, u.handle as owner_handle,
                (select count(*) from campaigns c where c.business_id = b.id) as campaigns, ${tsText('b.created_at')} as "cursorT"
           from businesses b left join users u on u.id = b.created_by where ${where} order by b.created_at desc, b.id desc limit $${params.length}`, params);
      const page = toPage(rows as (Record<string, unknown> & { cursorT: string; id: string })[], q.limit);
      return { items: page.items, nextCursor: page.nextCursor };
    });

    app.post('/admin/monetization/businesses/:id/review', async (req) => {
      const me = await need(req, 'campaigns.review');
      const id = idParam((req.params as { id?: string }).id);
      const b = z.object({ decision: z.enum(['approve', 'reject', 'suspend', 'restore', 'verify', 'unverify']), note: z.string().trim().max(500).default('') }).strict().parse(req.body ?? {});
      if (['reject', 'suspend'].includes(b.decision) && b.note.length < 3) throw new HttpError(422, 'validation', 'Say why (at least 3 characters).');
      await withTx(pool, async (client) => {
        const cur = (await client.query<{ status: string; verification: string }>('select status, verification from businesses where id = $1 for update', [id])).rows[0];
        if (!cur) throw new HttpError(404, 'not_found', 'Not found.');
        const need_ = (ok: boolean) => { if (!ok) throw new HttpError(409, 'invalid_state', 'That decision does not apply to this listing right now.'); };
        let sql: string;
        switch (b.decision) {
          case 'approve': need_(cur.status === 'pending_review'); sql = "status = 'approved'"; break;
          case 'reject': need_(cur.status === 'pending_review'); sql = "status = 'rejected'"; break;
          case 'suspend': need_(cur.status === 'approved'); sql = "status = 'suspended'"; break;
          case 'restore': need_(cur.status === 'suspended'); sql = "status = 'approved'"; break;
          // Verification is a separate statement about identity; it never changes whether the listing is approved.
          case 'verify': need_(cur.status === 'approved'); sql = "verification = 'verified'"; break;
          default: need_(cur.verification === 'verified'); sql = "verification = 'unverified'";
        }
        // `sql` is chosen from the fixed list above, never from the request.
        await client.query(`update businesses set ${sql}, review_note = $2, reviewed_by = $3, reviewed_at = now(), updated_at = now() where id = $1`, [id, b.note, me.id]);
        await audit(client, me.id, `business.${b.decision}`, 'business', id, {});
        // A business that is no longer approved stops its running campaigns; paid ones go to refund review.
        if (b.decision === 'suspend') {
          const live = await client.query<{ id: string }>("select id from campaigns where business_id = $1 and status in ('pending_review', 'approved', 'scheduled', 'active', 'paused')", [id]);
          for (const row of live.rows) await stopCampaign(client, await lockCampaign(client, row.id), 'admin', me.id, 'The business listing was suspended.');
        }
      });
      return { ok: true };
    });

    // Manual trigger for the same sweep the server runs every minute (clock, ineligible events, unpaid orders).
    app.post('/admin/monetization/sweep', async (req) => {
      const me = await need(req, 'campaigns.review');
      const r = await sweepCampaigns(pool);
      await audit(pool, me.id, 'campaign.sweep', null, null, { ...r });
      return r;
    });
  };
}
