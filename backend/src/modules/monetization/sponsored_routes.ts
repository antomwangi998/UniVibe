import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { blockedBetween, notSuspended } from '../../access';
import type { Ctx } from '../../context';
import { withTx } from '../../db';
import { HttpError } from '../../errors';
import { idParam } from '../../http';
import { visible } from '../../posts';
import { eventVisible } from '../events';
import { signedUrl } from '../cards';
import { issueToken, verifyToken, viewerKey } from './sponsored_token';

const PLACEMENTS = ['home_feed', 'discover_feed', 'discover_events', 'business_directory'] as const;
/** At most one sponsored card per request, however many campaigns qualify. The app places it; it never gets a list to flood the feed. */
const MAX_PER_REQUEST = 1;
/** Across every campaign, one student sees at most this many sponsored cards a day (UTC day). */
const DAILY_TOTAL_CAP = 8;
/** Three open reports from different people hold a campaign back until staff decide. */
const REPORT_HOLD = 3;
/** A second tap on the same ad within this window is not counted again. */
const CLICK_GAP_MINUTES = 30;
const REFRESH_SECONDS = 60;

const reportBody = z.object({ reason: z.enum(['misleading', 'inappropriate', 'spam', 'scam', 'offensive', 'other']), details: z.string().trim().max(500).default('') }).strict();
const eventBody = z.object({ token: z.string().min(20).max(600) }).strict();

interface CardRow {
  id: string; kind: string; headline: string; body: string; cta_label: string | null; cta_url: string | null;
  event_id: string | null; business_id: string | null; post_id: string | null;
  business_name: string | null; business_verification: string | null; business_category: string | null; business_location: string | null;
  event_title: string | null; event_starts_at: Date | null; event_venue: string | null; organizer_name: string | null;
  logo_key: string | null; image_key: string | null; image_width: number | null; image_height: number | null;
}

const UTC_DAY = "(date_trunc('day', now() at time zone 'UTC') at time zone 'UTC')";

export function sponsoredRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;
    const secret = ctx.config.CODE_PEPPER;

    /**
     * The sponsored card (if any) for one placement. Everything is decided here: payment, approval, schedule, caps,
     * the viewer's own visibility rules, blocks and "not interested". An empty list is the normal answer, and the app
     * simply shows no ad. Cards are always labelled and name their advertiser.
     */
    app.get('/sponsored', async (req, reply) => {
      const me = await ctx.auth(req);
      ctx.limit(`sponsored:${me.id}`, 60, 60);
      const { placement } = z.object({ placement: z.enum(PLACEMENTS) }).strict().parse(req.query ?? {});
      const vk = viewerKey(secret, me.id);
      // Cheap to ask the client again after a minute; not worth asking sooner.
      reply.header('cache-control', `private, max-age=${REFRESH_SECONDS}`);

      const seen = Number((await pool.query<{ n: string }>(
        `select count(*) as n from campaign_events where viewer_key = $1 and kind = 'impression' and created_at >= ${UTC_DAY}`, [vk])).rows[0].n);
      if (seen >= DAILY_TOTAL_CAP) return { items: [], refreshAfterSeconds: REFRESH_SECONDS };

      // $1 viewer, $2 viewer's university (the numbering eventVisible and visible expect), $3 placement, $4 viewer key, $5 report hold, $6 limit.
      const { rows } = await pool.query<CardRow>(
        `select c.id, c.kind, c.headline, c.body, c.cta_label, c.cta_url, c.event_id, c.business_id, c.post_id,
                b.name as business_name, b.verification as business_verification, b.category as business_category, b.location_text as business_location,
                e.title as event_title, e.starts_at as event_starts_at, e.venue_name as event_venue, org.full_name as organizer_name,
                lm.object_key as logo_key, im.object_key as image_key, im.width as image_width, im.height as image_height
           from campaigns c
           join orders o on o.id = c.order_id and o.status = 'paid'
           join users adv on adv.id = c.advertiser_id
           left join businesses b on b.id = c.business_id
           left join media lm on lm.id = b.logo_media_id and lm.status = 'ready'
           left join media im on im.id = c.image_media_id and im.status = 'ready'
           left join events e on e.id = c.event_id
           left join users org on org.id = e.organizer_id
          where c.status = 'active' and c.review_status = 'approved' and c.starts_at <= now() and c.ends_at > now()
            and $3 = any(c.placements)
            and (c.impression_cap is null or c.delivered_impressions < c.impression_cap)
            and adv.university_id = $2 and ${notSuspended('adv')} and not ${blockedBetween('$1', 'adv.id')}
            and (c.business_id is null or (b.status = 'approved' and b.university_id = $2))
            and (c.kind <> 'event_promotion' or (e.id is not null and e.status = 'published' and e.ends_at > now() and ${eventVisible('$1', '$2', 'e')}
                 and not exists (select 1 from event_feedback ef where ef.user_id = $1 and ef.event_id = e.id)
                 and not exists (select 1 from event_registrations er where er.user_id = $1 and er.event_id = e.id and er.status = 'registered')))
            and (c.post_id is null or exists (select 1 from posts p join users a on a.id = p.author_id where p.id = c.post_id and ${visible('p', 'a')}))
            and (select count(*) from campaign_reports r where r.campaign_id = c.id and r.status = 'open') < $5
            and (select count(*) from campaign_events ev where ev.campaign_id = c.id and ev.viewer_key = $4 and ev.kind = 'impression' and ev.created_at >= ${UTC_DAY}) < c.frequency_cap_per_day
          order by c.delivered_impressions asc, random() limit $6`,
        [me.id, me.universityId, placement, vk, REPORT_HOLD, MAX_PER_REQUEST]);

      return {
        refreshAfterSeconds: REFRESH_SECONDS,
        items: await Promise.all(rows.map(async (r) => {
          const { token } = issueToken(secret, me.id, r.id, placement);
          const imageUrl = await signedUrl(ctx, r.image_key);
          const target = r.event_id ? { type: 'event', id: r.event_id } : r.post_id ? { type: 'post', id: r.post_id } : r.business_id ? { type: 'business', id: r.business_id } : null;
          return {
            campaignId: r.id, label: 'Sponsored', placement, kind: r.kind,
            // Attribution: the business when there is one, otherwise the organizer, whose name the event page already shows.
            advertiser: { name: r.business_name ?? r.organizer_name ?? 'Advertiser', verified: r.business_verification === 'verified', logoUrl: await signedUrl(ctx, r.logo_key) },
            headline: r.headline, body: r.body, cta: r.cta_label && r.cta_url ? { label: r.cta_label, url: r.cta_url } : null, target,
            event: r.event_id ? { title: r.event_title, startsAt: r.event_starts_at, venue: r.event_venue } : null,
            business: r.business_id ? { category: r.business_category, location: r.business_location } : null,
            // Only when the campaign has a ready image. The app draws nothing for a missing one.
            image: imageUrl ? { url: imageUrl, width: r.image_width, height: r.image_height } : null,
            impressionToken: token,
          };
        })),
      };
    });

    /** Records that the card was actually on screen. Counted once per token, only while the campaign may still deliver. */
    app.post('/sponsored/impression', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`spons-imp:${me.id}`, 200, 60);
      const { token } = eventBody.parse(req.body ?? {});
      const claims = verifyToken(secret, me.id, token);
      if (!claims) throw new HttpError(400, 'invalid_token', 'That ad is no longer valid.');
      const vk = viewerKey(secret, me.id);
      const counted = await withTx(pool, async (client) => {
        const ins = await client.query(
          "insert into campaign_events (campaign_id, kind, viewer_key, placement, nonce) values ($1, 'impression', $2, $3, $4) on conflict do nothing returning id",
          [claims.c, vk, claims.p, claims.n]);
        if (!ins.rowCount) return false; // this token was already counted
        // The campaign must still be live and under both caps right now, otherwise the event is thrown away.
        const upd = await client.query(
          `update campaigns c set delivered_impressions = delivered_impressions + 1
            where c.id = $1 and c.status = 'active' and c.ends_at > now()
              and (c.impression_cap is null or c.delivered_impressions < c.impression_cap)
              and (select count(*) from campaign_events ev where ev.campaign_id = c.id and ev.viewer_key = $2 and ev.kind = 'impression' and ev.created_at >= ${UTC_DAY}) <= c.frequency_cap_per_day`,
          [claims.c, vk]);
        if (!upd.rowCount) { await client.query('delete from campaign_events where id = $1', [ins.rows[0].id]); return false; }
        return true;
      });
      return { counted };
    });

    /**
     * A tap. It must follow an impression of the same token (so a click cannot exist for a card that was never shown),
     * and a second tap by the same person within 30 minutes is not counted.
     */
    app.post('/sponsored/click', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`spons-clk:${me.id}`, 60, 60);
      const { token } = eventBody.parse(req.body ?? {});
      const claims = verifyToken(secret, me.id, token);
      if (!claims) throw new HttpError(400, 'invalid_token', 'That ad is no longer valid.');
      const vk = viewerKey(secret, me.id);
      const counted = await withTx(pool, async (client) => {
        const shown = await client.query("select 1 from campaign_events where campaign_id = $1 and nonce = $2 and kind = 'impression' and viewer_key = $3", [claims.c, claims.n, vk]);
        if (!shown.rowCount) throw new HttpError(400, 'not_shown', 'That ad was not shown.');
        const recent = await client.query(
          "select 1 from campaign_events where campaign_id = $1 and viewer_key = $2 and kind = 'click' and created_at > now() - make_interval(mins => $3::int)", [claims.c, vk, CLICK_GAP_MINUTES]);
        if (recent.rowCount) return false;
        const ins = await client.query("insert into campaign_events (campaign_id, kind, viewer_key, placement, nonce) values ($1, 'click', $2, $3, $4) on conflict do nothing returning id", [claims.c, vk, claims.p, claims.n]);
        if (!ins.rowCount) return false;
        await client.query('update campaigns set delivered_clicks = delivered_clicks + 1 where id = $1', [claims.c]);
        return true;
      });
      return { counted };
    });

    /** "Report this ad". The campaign must be one the viewer could have been shown; anything else looks like not found. */
    app.post('/sponsored/:id/report', async (req, reply) => {
      const me = await ctx.auth(req);
      ctx.limit(`spons-rep:${me.id}`, 20, 3600);
      const id = idParam((req.params as { id?: string }).id);
      const b = reportBody.parse(req.body ?? {});
      const shown = await pool.query(
        `select 1 from campaigns c join users adv on adv.id = c.advertiser_id where c.id = $1 and c.status in ('active', 'paused', 'completed') and adv.university_id = $2
            and exists (select 1 from campaign_events ev where ev.campaign_id = c.id and ev.viewer_key = $3)`, [id, me.universityId, viewerKey(secret, me.id)]);
      if (!shown.rowCount) throw new HttpError(404, 'not_found', 'Not found.');
      await pool.query('insert into campaign_reports (campaign_id, reporter_id, reason, details) values ($1, $2, $3, $4) on conflict do nothing', [id, me.id, b.reason, b.details]);
      reply.code(201);
      return { ok: true };
    });
  };
}
