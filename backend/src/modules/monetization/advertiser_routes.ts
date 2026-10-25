import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import type { AuthUser, Ctx } from '../../context';
import { withTx } from '../../db';
import { HttpError } from '../../errors';
import { decodeCursor, idParam, pageSchema, toPage, tsText } from '../../http';
import { allowedNext, mayResume, resumeTarget } from './campaign_state';
import { formatMoney, normalizeReceipt } from './money';
import {
  PACKAGE_FOR_CAMPAIGN, PLACEMENTS_FOR_PACKAGE, eventPromotable, isUnique, lockCampaign, moveCampaign, newUniqueReference,
  orderStatusOf, factsOf, stopCampaign, type CampaignRow, type Q,
} from './service';

const PLACEMENTS = ['home_feed', 'discover_feed', 'discover_events', 'business_directory'] as const;
const CATEGORIES = ['food', 'retail', 'services', 'education', 'health', 'tech', 'entertainment', 'housing', 'transport', 'other'] as const;
const https = z.string().trim().max(500).regex(/^https:\/\//, 'Use a link that starts with https://');
const key = z.string().trim().min(8).max(64);
const MAX_BUSINESSES = 5;
const MAX_OPEN_DRAFTS = 20;
const ORDER_HOURS = 48;
const MAX_LEAD_DAYS = 60;

const businessBody = z.object({
  name: z.string().trim().min(2).max(80),
  description: z.string().trim().max(1000).default(''),
  category: z.enum(CATEGORIES),
  locationText: z.string().trim().max(160).optional(),
  contactPhone: z.string().trim().regex(/^\+?[0-9 ]{7,20}$/, 'Enter a valid phone number.').optional(),
  contactEmail: z.string().trim().toLowerCase().email().max(254).optional(),
  websiteUrl: https.optional(),
  openingHours: z.record(z.string().max(20), z.string().max(40)).optional(),
  offerings: z.string().trim().max(1000).default(''),
  createKey: key.optional(),
}).strict();

const campaignBody = z.object({
  kind: z.enum(['event_promotion', 'business_listing', 'sponsored_post', 'discover_feature']),
  packageId: z.string().uuid(),
  eventId: z.string().uuid().optional(),
  businessId: z.string().uuid().optional(),
  postId: z.string().uuid().optional(),
  headline: z.string().trim().min(3).max(80),
  body: z.string().trim().max(300).default(''),
  ctaLabel: z.string().trim().min(2).max(24).optional(),
  ctaUrl: https.optional(),
  placements: z.array(z.enum(PLACEMENTS)).min(1).max(4),
  startsAt: z.coerce.date(),
  endsAt: z.coerce.date(),
  createKey: key.optional(),
}).strict();

const receiptBody = z.object({ receipt: z.string().trim().min(8).max(30) }).strict();

type BusinessRow = {
  id: string; name: string; description: string; category: string; location_text: string | null; contact_phone: string | null;
  contact_email: string | null; website_url: string | null; opening_hours: Record<string, string>; offerings: string; status: string;
  verification: string; review_note: string; university_id: string;
};
const businessView = (b: BusinessRow, mine: boolean) => ({
  id: b.id, name: b.name, description: b.description, category: b.category, locationText: b.location_text, contactPhone: b.contact_phone,
  contactEmail: b.contact_email, websiteUrl: b.website_url, openingHours: b.opening_hours, offerings: b.offerings,
  verified: b.verification === 'verified', sponsored: false,
  // The review state and any staff note are for the people who run the business only.
  ...(mine ? { status: b.status, reviewNote: b.review_note } : {}),
});

async function isMember(q: Q, businessId: string, userId: string): Promise<boolean> {
  return !!(await q.query('select 1 from business_members where business_id = $1 and user_id = $2', [businessId, userId])).rowCount;
}

type PackageRow = {
  id: string; code: string; kind: string; name: string; description: string; currency: string; price_minor: string; duration_days: number;
  placements: string[]; impression_cap: number | null; frequency_cap_per_day: number; active: boolean;
};
export const packageView = (p: PackageRow) => ({
  id: p.id, code: p.code, kind: p.kind, name: p.name, description: p.description, currency: p.currency,
  priceMinor: Number(p.price_minor), price: formatMoney(Number(p.price_minor), p.currency), durationDays: p.duration_days,
  placements: p.placements, impressionCap: p.impression_cap, frequencyCapPerDay: p.frequency_cap_per_day,
});

export async function manualPaymentSettings(q: Q) {
  const v = (await q.query<{ value: { enabled?: boolean; method?: string; number?: string; accountName?: string; instructions?: string } }>(
    "select value from monetization_settings where key = 'manual_payment'")).rows[0]?.value ?? {};
  const enabled = v.enabled === true && !!v.number;
  return { enabled, method: v.method ?? 'till', number: enabled ? v.number : '', accountName: enabled ? v.accountName ?? '' : '', instructions: enabled ? v.instructions ?? '' : '' };
}

export function advertiserRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;

    // ------------------------------------------------------------ packages and where to pay
    app.get('/monetization/packages', async (req) => {
      await ctx.auth(req);
      const kind = z.object({ kind: z.enum(['event_promotion', 'featured_business', 'sponsored_post', 'featured_discover']).optional() }).strict().parse(req.query ?? {}).kind;
      const { rows } = await pool.query<PackageRow>(
        `select * from promotion_packages where active and kind <> 'business_plan' ${kind ? 'and kind = $1' : ''} order by price_minor, name`, kind ? [kind] : []);
      return { items: rows.map(packageView) };
    });

    app.get('/monetization/payment-info', async (req) => {
      await ctx.auth(req);
      return manualPaymentSettings(pool);
    });

    // ------------------------------------------------------------ businesses
    app.post('/businesses', async (req, reply) => {
      const me = await ctx.auth(req);
      ctx.limit(`biz-create:${me.id}`, 10, 3600);
      const b = businessBody.parse(req.body ?? {});
      const result = await withTx(pool, async (client) => {
        if (b.createKey) {
          const again = (await client.query<{ id: string }>('select id from businesses where created_by = $1 and create_key = $2', [me.id, b.createKey])).rows[0];
          if (again) return { id: again.id, created: false };
        }
        const owned = Number((await client.query<{ n: string }>('select count(*) as n from business_members where user_id = $1 and role = $2', [me.id, 'owner'])).rows[0].n);
        if (owned >= MAX_BUSINESSES) throw new HttpError(422, 'limit_reached', `You can run at most ${MAX_BUSINESSES} businesses.`);
        const id = (await client.query<{ id: string }>(
          `insert into businesses (university_id, name, description, category, location_text, contact_phone, contact_email, website_url, opening_hours, offerings, created_by, create_key)
           values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12) returning id`,
          [me.universityId, b.name, b.description, b.category, b.locationText ?? null, b.contactPhone ?? null, b.contactEmail ?? null, b.websiteUrl ?? null,
            JSON.stringify(b.openingHours ?? {}), b.offerings, me.id, b.createKey ?? null])).rows[0].id;
        await client.query("insert into business_members (business_id, user_id, role) values ($1, $2, 'owner')", [id, me.id]);
        return { id, created: true };
      });
      reply.code(result.created ? 201 : 200);
      return { id: result.id };
    });

    app.get('/me/businesses', async (req) => {
      const me = await ctx.auth(req);
      const { rows } = await pool.query<BusinessRow>(
        'select b.* from businesses b join business_members m on m.business_id = b.id where m.user_id = $1 order by b.created_at desc limit 50', [me.id]);
      return { items: rows.map((r) => businessView(r, true)) };
    });

    // The public directory is organic only: every item here is a normal listing, never a paid placement.
    app.get('/businesses', async (req) => {
      const me = await ctx.auth(req);
      const q = pageSchema.extend({ category: z.enum(CATEGORIES).optional() }).strict().parse(req.query ?? {});
      const cur = decodeCursor(q.cursor);
      const params: unknown[] = [me.universityId];
      let where = "b.status = 'approved' and b.university_id = $1";
      if (q.category) { params.push(q.category); where += ` and b.category = $${params.length}`; }
      if (cur) { params.push(cur.t, cur.id); where += ` and (b.created_at, b.id) < ($${params.length - 1}::timestamptz, $${params.length}::uuid)`; }
      params.push(q.limit + 1);
      const { rows } = await pool.query<BusinessRow & { cursorT: string }>(
        `select b.*, ${tsText('b.created_at')} as "cursorT" from businesses b where ${where} order by b.created_at desc, b.id desc limit $${params.length}`, params);
      const page = toPage(rows, q.limit);
      return { items: page.items.map((r) => businessView(r as unknown as BusinessRow, false)), nextCursor: page.nextCursor };
    });

    app.get('/businesses/:id', async (req) => {
      const me = await ctx.auth(req);
      const id = idParam((req.params as { id?: string }).id);
      const b = (await pool.query<BusinessRow>('select * from businesses where id = $1', [id])).rows[0];
      const mine = !!b && (await isMember(pool, id, me.id));
      // Anyone who may not see it is told "not found", so ids cannot be probed.
      if (!b || (!mine && !(b.status === 'approved' && b.university_id === me.universityId))) throw new HttpError(404, 'not_found', 'Not found.');
      return businessView(b, mine);
    });

    app.put('/businesses/:id', async (req) => {
      const me = await ctx.auth(req);
      const id = idParam((req.params as { id?: string }).id);
      const b = businessBody.parse(req.body ?? {});
      ctx.limit(`biz-edit:${me.id}`, 30, 3600);
      await withTx(pool, async (client) => {
        const cur = (await client.query<BusinessRow>('select * from businesses where id = $1 for update', [id])).rows[0];
        if (!cur || !(await isMember(client, id, me.id))) throw new HttpError(404, 'not_found', 'Not found.');
        if (cur.status === 'suspended') throw new HttpError(403, 'suspended', 'This listing is suspended. Contact support.');
        // A live listing goes back to review when edited, so approval cannot be used to publish something else later.
        // A changed name also drops verification: the checked identity may no longer match.
        const status = cur.status === 'approved' ? 'pending_review' : cur.status;
        const verification = cur.name !== b.name ? 'unverified' : cur.verification;
        await client.query(
          `update businesses set name = $2, description = $3, category = $4, location_text = $5, contact_phone = $6, contact_email = $7, website_url = $8,
             opening_hours = $9, offerings = $10, status = $11, verification = $12, updated_at = now() where id = $1`,
          [id, b.name, b.description, b.category, b.locationText ?? null, b.contactPhone ?? null, b.contactEmail ?? null, b.websiteUrl ?? null,
            JSON.stringify(b.openingHours ?? {}), b.offerings, status, verification]);
      });
      return { ok: true };
    });

    app.post('/businesses/:id/submit', async (req) => {
      const me = await ctx.auth(req);
      const id = idParam((req.params as { id?: string }).id);
      await withTx(pool, async (client) => {
        const cur = (await client.query<{ status: string }>('select status from businesses where id = $1 for update', [id])).rows[0];
        if (!cur || !(await isMember(client, id, me.id))) throw new HttpError(404, 'not_found', 'Not found.');
        if (!['draft', 'rejected'].includes(cur.status)) throw new HttpError(409, 'invalid_state', 'This listing cannot be submitted right now.');
        await client.query("update businesses set status = 'pending_review', review_note = '', updated_at = now() where id = $1", [id]);
      });
      return { status: 'pending_review' };
    });

    // ------------------------------------------------------------ campaigns
    async function validateCampaign(q: Q, me: AuthUser, b: z.infer<typeof campaignBody>): Promise<PackageRow> {
      const pkg = (await q.query<PackageRow>('select * from promotion_packages where id = $1 and active', [b.packageId])).rows[0];
      if (!pkg || pkg.kind !== PACKAGE_FOR_CAMPAIGN[b.kind]) throw new HttpError(422, 'validation', 'Choose a package that matches this kind of promotion.');
      const allowed = PLACEMENTS_FOR_PACKAGE[pkg.kind] ?? [];
      for (const p of b.placements) {
        if (!pkg.placements.includes(p) || !allowed.includes(p)) throw new HttpError(422, 'validation', 'That package does not include one of the chosen placements.');
      }
      if (new Set(b.placements).size !== b.placements.length) throw new HttpError(422, 'validation', 'Each placement can be chosen once.');
      const now = Date.now();
      if (b.startsAt.getTime() < now - 5 * 60_000) throw new HttpError(422, 'validation', 'The start cannot be in the past.');
      if (b.startsAt.getTime() > now + MAX_LEAD_DAYS * 86_400_000) throw new HttpError(422, 'validation', `Start within ${MAX_LEAD_DAYS} days.`);
      if (b.endsAt <= b.startsAt) throw new HttpError(422, 'validation', 'The end must be after the start.');
      if (b.endsAt.getTime() - b.startsAt.getTime() > pkg.duration_days * 86_400_000) throw new HttpError(422, 'validation', `This package runs for at most ${pkg.duration_days} days.`);

      if (b.kind === 'event_promotion') {
        if (!b.eventId) throw new HttpError(422, 'validation', 'Choose the event to promote.');
        const ev = await eventPromotable(q, b.eventId, me.id);
        // Not yours, hidden, cancelled and unknown events all look the same, so events cannot be probed.
        if (!ev.ok) throw new HttpError(422, 'validation', 'That event cannot be promoted. Only your own published, upcoming events can.');
        if (b.endsAt > ev.endsAt!) throw new HttpError(422, 'validation', 'The promotion cannot run past the end of the event.');
      } else if (b.eventId) throw new HttpError(422, 'validation', 'An event can only be attached to an event promotion.');

      const needsBusiness = b.kind !== 'event_promotion';
      if (needsBusiness && !b.businessId) throw new HttpError(422, 'validation', 'Choose the business this promotion is for.');
      if (b.businessId) {
        const ok = (await q.query("select 1 from businesses b join business_members m on m.business_id = b.id and m.user_id = $2 where b.id = $1 and b.status = 'approved'", [b.businessId, me.id])).rowCount;
        if (!ok) throw new HttpError(422, 'validation', 'That business is not approved yet, or is not yours.');
      }
      if (b.postId) {
        if (b.kind !== 'sponsored_post') throw new HttpError(422, 'validation', 'A post can only be attached to a sponsored post.');
        const ok = (await q.query("select 1 from posts where id = $1 and author_id = $2 and status = 'active'", [b.postId, me.id])).rowCount;
        if (!ok) throw new HttpError(422, 'validation', 'You can only promote your own posts.');
      }
      if ((b.ctaLabel && !b.ctaUrl) || (!b.ctaLabel && b.ctaUrl)) throw new HttpError(422, 'validation', 'A button needs both a label and a link.');
      return pkg;
    }

    app.post('/campaigns', async (req, reply) => {
      const me = await ctx.auth(req);
      ctx.limit(`camp-create:${me.id}`, 30, 3600);
      const b = campaignBody.parse(req.body ?? {});
      try {
        const out = await withTx(pool, async (client) => {
          if (b.createKey) {
            const again = (await client.query<{ id: string }>('select id from campaigns where advertiser_id = $1 and create_key = $2', [me.id, b.createKey])).rows[0];
            if (again) return { id: again.id, created: false };
          }
          const open = Number((await client.query<{ n: string }>("select count(*) as n from campaigns where advertiser_id = $1 and status = 'draft'", [me.id])).rows[0].n);
          if (open >= MAX_OPEN_DRAFTS) throw new HttpError(422, 'limit_reached', 'Finish or cancel some drafts first.');
          const pkg = await validateCampaign(client, me, b);
          const id = (await client.query<{ id: string }>(
            `insert into campaigns (advertiser_id, kind, business_id, event_id, post_id, package_id, headline, body, cta_label, cta_url, placements,
               starts_at, ends_at, impression_cap, frequency_cap_per_day, create_key)
             values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16) returning id`,
            [me.id, b.kind, b.businessId ?? null, b.eventId ?? null, b.postId ?? null, pkg.id, b.headline, b.body, b.ctaLabel ?? null, b.ctaUrl ?? null,
              b.placements, b.startsAt, b.endsAt, pkg.impression_cap, pkg.frequency_cap_per_day, b.createKey ?? null])).rows[0].id;
          await client.query("insert into campaign_status_history (campaign_id, from_status, to_status, actor_id, actor_kind) values ($1, null, 'draft', $2, 'advertiser')", [id, me.id]);
          return { id, created: true };
        });
        reply.code(out.created ? 201 : 200);
        return { id: out.id };
      } catch (e) {
        if (isUnique(e)) throw new HttpError(409, 'already_promoted', 'This event already has an open promotion.');
        throw e;
      }
    });

    type CampaignListRow = { id: string; kind: string; headline: string; status: string; review_status: string; starts_at: Date; ends_at: Date;
      delivered_impressions: string; delivered_clicks: string; order_status: string | null; amount_minor: string | null; currency: string | null; cursorT: string };
    app.get('/me/campaigns', async (req) => {
      const me = await ctx.auth(req);
      const q = pageSchema.strict().parse(req.query ?? {});
      const cur = decodeCursor(q.cursor);
      const params: unknown[] = [me.id];
      let where = 'c.advertiser_id = $1';
      if (cur) { params.push(cur.t, cur.id); where += ` and (c.created_at, c.id) < ($2::timestamptz, $3::uuid)`; }
      params.push(q.limit + 1);
      const { rows } = await pool.query<CampaignListRow>(
        `select c.id, c.kind, c.headline, c.status, c.review_status, c.starts_at, c.ends_at, c.delivered_impressions, c.delivered_clicks,
                o.status as order_status, o.amount_minor, o.currency, ${tsText('c.created_at')} as "cursorT"
           from campaigns c left join orders o on o.id = c.order_id where ${where} order by c.created_at desc, c.id desc limit $${params.length}`, params);
      const page = toPage(rows, q.limit);
      return {
        items: page.items.map((r) => ({
          id: r.id, kind: r.kind, headline: r.headline, status: r.status, reviewStatus: r.review_status, startsAt: r.starts_at, endsAt: r.ends_at,
          impressions: Number(r.delivered_impressions), clicks: Number(r.delivered_clicks), paymentStatus: r.order_status,
          amount: r.amount_minor ? formatMoney(Number(r.amount_minor), r.currency ?? 'KES') : null,
        })),
        nextCursor: page.nextCursor,
      };
    });

    app.get('/campaigns/:id', async (req) => {
      const me = await ctx.auth(req);
      const id = idParam((req.params as { id?: string }).id);
      const c = (await pool.query<CampaignRow & Record<string, unknown>>('select * from campaigns where id = $1 and advertiser_id = $2', [id, me.id])).rows[0];
      if (!c) throw new HttpError(404, 'not_found', 'Not found.');
      const order = c.order_id ? (await pool.query('select id, reference, amount_minor, currency, status, expires_at, paid_at from orders where id = $1', [c.order_id])).rows[0] : null;
      const payment = c.order_id ? (await pool.query(
        'select status, failure_reason, created_at, confirmed_at from payments where order_id = $1 order by created_at desc limit 1', [c.order_id])).rows[0] : null;
      const history = (await pool.query('select to_status, actor_kind, note, created_at from campaign_status_history where campaign_id = $1 order by id', [id])).rows;
      return {
        id: c.id, kind: c.kind, headline: c.headline, body: c.body, status: c.status, reviewStatus: c.review_status,
        // A rejection reason is for the advertiser to read and fix; internal notes are never written to this field.
        reviewNote: c.review_status === 'rejected' ? c.review_note : '',
        placements: c.placements, startsAt: c.starts_at, endsAt: c.ends_at, impressions: Number(c.delivered_impressions), clicks: Number(c.delivered_clicks),
        order: order && { id: order.id, reference: order.reference, amountMinor: Number(order.amount_minor), amount: formatMoney(Number(order.amount_minor), order.currency),
          status: order.status, expiresAt: order.expires_at, paidAt: order.paid_at },
        payment: payment && { status: payment.status, failureReason: payment.failure_reason, submittedAt: payment.created_at, confirmedAt: payment.confirmed_at },
        history: history.map((h) => ({ status: h.to_status, by: h.actor_kind, note: h.note, at: h.created_at })),
        actions: allowedNext(c.status, 'advertiser'),
      };
    });

    /**
     * Creates the order from the CURRENT package price and freezes it. The client sends no price; there is nowhere to put one.
     * A free package skips payment and goes straight to review.
     */
    app.post('/campaigns/:id/checkout', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = idParam((req.params as { id?: string }).id);
      ctx.limit(`checkout:${me.id}`, 20, 3600);
      const settings = await manualPaymentSettings(pool);
      const out = await withTx(pool, async (client) => {
        const c = await lockCampaign(client, id);
        if (c.advertiser_id !== me.id) throw new HttpError(404, 'not_found', 'Not found.');
        if (c.status !== 'draft') {
          // A repeated tap returns the order that already exists.
          const existing = c.order_id ? (await client.query('select id, reference, amount_minor, currency, expires_at, status from orders where id = $1', [c.order_id])).rows[0] : null;
          if (existing) return { order: existing, repeat: true };
          throw new HttpError(409, 'invalid_state', 'This campaign cannot be paid for right now.');
        }
        const pkg = (await client.query<PackageRow>('select * from promotion_packages where id = $1 and active', [c.package_id])).rows[0];
        if (!pkg) throw new HttpError(409, 'package_unavailable', 'This package is no longer available. Create the promotion again with another package.');
        const price = Number(pkg.price_minor);
        if (price > 0 && !settings.enabled) throw new HttpError(503, 'payments_unavailable', 'Payments are not available yet.');
        if (c.kind === 'event_promotion' && c.event_id && !(await eventPromotable(client, c.event_id, me.id)).ok) {
          throw new HttpError(422, 'validation', 'That event can no longer be promoted.');
        }
        if (c.starts_at.getTime() < Date.now() - 5 * 60_000) throw new HttpError(422, 'validation', 'The start time has passed. Create the promotion again with a new start.');
        const reference = await newUniqueReference(client);
        const snapshot = { code: pkg.code, kind: pkg.kind, name: pkg.name, priceMinor: price, currency: pkg.currency, durationDays: pkg.duration_days,
          placements: pkg.placements, impressionCap: pkg.impression_cap, frequencyCapPerDay: pkg.frequency_cap_per_day };
        const order = (await client.query(
          `insert into orders (reference, advertiser_id, package_id, package_snapshot, amount_minor, currency, status, expires_at, paid_at)
           values ($1, $2, $3, $4, $5, $6, $7, now() + make_interval(hours => $8::int), $9) returning id, reference, amount_minor, currency, expires_at, status`,
          [reference, me.id, pkg.id, JSON.stringify(snapshot), price, pkg.currency, price === 0 ? 'paid' : 'pending_payment', ORDER_HOURS, price === 0 ? new Date() : null])).rows[0];
        await client.query('update campaigns set order_id = $2 where id = $1', [id, order.id]);
        if (price === 0) await moveCampaign(client, c, 'pending_review', 'system', null, 'free package');
        else await moveCampaign(client, c, 'pending_payment', 'advertiser', me.id, 'order created');
        return { order, repeat: false };
      });
      reply.code(out.repeat ? 200 : 201);
      const o = out.order;
      return {
        order: { id: o.id, reference: o.reference, amountMinor: Number(o.amount_minor), amount: formatMoney(Number(o.amount_minor), o.currency), status: o.status, expiresAt: o.expires_at },
        pay: settings,
      };
    });

    /**
     * The advertiser says "I paid, here is the receipt code". That is a CLAIM: it records the code and waits.
     * Only a staff confirmation (or, later, a verified provider response) marks the order paid.
     */
    app.post('/orders/:id/payment', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = idParam((req.params as { id?: string }).id);
      ctx.limit(`receipt:${me.id}`, 10, 3600);
      const { receipt } = receiptBody.parse(req.body ?? {});
      const code = normalizeReceipt(receipt);
      if (!code) throw new HttpError(422, 'validation', 'That does not look like an M-Pesa receipt code. It has 10 letters and numbers.');
      try {
        await withTx(pool, async (client) => {
          const o = (await client.query<{ advertiser_id: string | null; status: string; amount_minor: string; currency: string; expires_at: Date }>(
            'select advertiser_id, status, amount_minor, currency, expires_at from orders where id = $1 for update', [id])).rows[0];
          if (!o || o.advertiser_id !== me.id) throw new HttpError(404, 'not_found', 'Not found.');
          if (o.status !== 'pending_payment') throw new HttpError(409, 'invalid_state', 'This order is not waiting for payment.');
          if (o.expires_at.getTime() < Date.now()) throw new HttpError(409, 'expired', 'This order has expired. Create the promotion again.');
          if ((await client.query("select 1 from payments where order_id = $1 and status = 'awaiting_verification'", [id])).rowCount) {
            throw new HttpError(409, 'already_submitted', 'Your payment is already being checked.');
          }
          const p = (await client.query<{ id: string }>(
            `insert into payments (order_id, provider, status, amount_minor, currency, provider_reference) values ($1, 'manual_till', 'awaiting_verification', $2, $3, $4) returning id`,
            [id, o.amount_minor, o.currency, code])).rows[0];
          await client.query("insert into payment_events (payment_id, kind, actor_id) values ($1, 'receipt_submitted', $2)", [p.id, me.id]);
        });
      } catch (e) {
        if (isUnique(e)) throw new HttpError(409, 'receipt_used', 'That receipt code has already been used.');
        throw e;
      }
      reply.code(202);
      return { status: 'awaiting_verification' };
    });

    // ------------------------------------------------------------ advertiser controls
    async function control(req: Parameters<Ctx['auth']>[0], action: 'pause' | 'resume' | 'cancel') {
      const me = await ctx.auth(req);
      const id = idParam((req.params as { id?: string }).id);
      ctx.limit(`camp-ctl:${me.id}`, 30, 3600);
      return withTx(pool, async (client) => {
        const c = await lockCampaign(client, id);
        if (c.advertiser_id !== me.id) throw new HttpError(404, 'not_found', 'Not found.');
        if (action === 'pause') await moveCampaign(client, c, 'paused', 'advertiser', me.id, 'paused by advertiser', { paused_by: 'advertiser' });
        else if (action === 'resume') {
          if (c.status !== 'paused') throw new HttpError(409, 'invalid_state', 'This campaign is not paused.');
          if (!mayResume(c.paused_by, 'advertiser')) throw new HttpError(403, 'staff_pause', 'Support paused this campaign. Contact support to resume it.');
          const to = resumeTarget({ startsAt: c.starts_at, endsAt: c.ends_at, impressionCap: c.impression_cap, deliveredImpressions: Number(c.delivered_impressions) }, new Date());
          // Resuming only restores what payment and review already allowed; both are checked again.
          const facts = factsOf(c, await orderStatusOf(client, c.order_id));
          if (facts.orderStatus !== 'paid' || facts.reviewStatus !== 'approved') throw new HttpError(409, 'invalid_state', 'This campaign cannot run.');
          await moveCampaign(client, c, to, to === 'completed' ? 'system' : 'advertiser', me.id, 'resumed by advertiser', { paused_by: null });
        } else await stopCampaign(client, c, 'advertiser', me.id, 'cancelled by advertiser');
        return { status: c.status };
      });
    }
    app.post('/campaigns/:id/pause', (req) => control(req, 'pause'));
    app.post('/campaigns/:id/resume', (req) => control(req, 'resume'));
    app.post('/campaigns/:id/cancel', (req) => control(req, 'cancel'));
  };
}
