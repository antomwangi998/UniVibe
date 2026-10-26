import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import type { Ctx } from '../context';

const idSchema = z.string().uuid();

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c] as string));

/**
 * What a web visitor (or a link-preview robot) may learn about an event: only for PUBLIC, published, visible, not-yet-over events,
 * and only the title, a short description, when, the kind of event and the venue name for events held in person. No organizer,
 * no attendees, no join link, no address, no community. Everything else (private, community-only, hidden, drafts, finished,
 * unknown ids) gets the same generic answer, so these links cannot be used to find out whether an event exists.
 */
export async function publicPreview(ctx: Pick<Ctx, 'pool'>, rawId: string) {
  return previewRow(ctx, rawId, false);
}

/** [withCoverKey] adds the storage key of the cover, for the cover route only; it never leaves the server. */
async function previewRow(ctx: Pick<Ctx, 'pool'>, rawId: string, withCoverKey: boolean) {
  const id = idSchema.safeParse(rawId);
  if (!id.success) return null;
  const { rows } = await ctx.pool.query(
    `select e.title, left(e.description, 160) as description, e.starts_at, e.ends_at, e.event_type, e.venue_name, cv.object_key as cover_key
       from events e join users o on o.id = e.organizer_id left join media cv on cv.id = e.cover_media_id and cv.status = 'ready'
      where e.id = $1 and e.visibility = 'public' and e.status = 'published' and e.hidden_at is null and e.ends_at > now()
        and (o.suspended_until is null or o.suspended_until <= now()) and o.deactivated_at is null`, [id.data]);
  const r = rows[0];
  if (!r) return null;
  return {
    title: r.title as string, description: r.description as string, startsAt: r.starts_at as Date, endsAt: r.ends_at as Date,
    eventType: r.event_type as string, venueName: r.event_type === 'online' ? null : (r.venue_name as string),
    // Only whether there is a cover. The picture itself is served from /public/events/:id/cover (see below).
    hasCover: Boolean(r.cover_key),
    ...(withCoverKey ? { coverKey: r.cover_key as string | null } : {}),
  };
}

const whenText = (d: Date) => `${d.toUTCString().replace(' GMT', '')} UTC`;

export function publicEventPage(rawId: string, p: Awaited<ReturnType<typeof publicPreview>>, baseUrl: string | null = null): string {
  const id = idSchema.safeParse(rawId);
  const app = id.success ? `univibe://events/${id.data}` : 'univibe://events';
  const title = p ? p.title : 'An event on UniVibe';
  const desc = p ? `${whenText(new Date(p.startsAt))}${p.venueName ? ` · ${p.venueName}` : ''}${p.description ? ` — ${p.description}` : ''}` : 'Open UniVibe to see this event.';
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title><meta name="robots" content="noindex">
<meta property="og:title" content="${esc(title)}"><meta property="og:description" content="${esc(desc)}"><meta property="og:type" content="website">${p?.hasCover && baseUrl && id.success ? `\n<meta property="og:image" content="${esc(`${baseUrl}/public/events/${id.data}/cover`)}"><meta name="twitter:card" content="summary_large_image">` : ''}
<style>body{font-family:system-ui,sans-serif;background:#0b1214;color:#e8f1f2;margin:0;display:grid;place-items:center;min-height:100vh}
main{max-width:420px;padding:24px}h1{font-size:1.4rem}p{color:#a9bcc0;line-height:1.5}
a.btn{display:inline-block;margin-top:16px;padding:12px 20px;border-radius:12px;background:#1ec8c8;color:#04201f;text-decoration:none;font-weight:600}</style></head>
<body><main><h1>${esc(title)}</h1><p>${esc(desc)}</p><a class="btn" href="${esc(app)}">Open in UniVibe</a>
<p>You need the UniVibe app and an account at your university to see more.</p></main></body></html>`;
}

export function publicEventRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    app.get('/public/events/:id', async (req, reply) => {
      ctx.limit(`public-event:${req.ip}`, 60, 60);
      const p = await publicPreview(ctx, (req.params as { id: string }).id);
      if (!p) return reply.status(404).send({ error: { code: 'not_found', message: 'Not found.' } });
      return p;
    });
    // The page behind <PUBLIC_BASE_URL>/e/<id>. Always 200 with a page, so nothing is revealed by the status code.
    app.get('/e/:id', async (req, reply) => {
      ctx.limit(`public-event:${req.ip}`, 60, 60);
      const id = (req.params as { id: string }).id;
      return reply.header('content-type', 'text/html; charset=utf-8').header('cache-control', 'public, max-age=60').send(publicEventPage(id, await publicPreview(ctx, id), ctx.config.PUBLIC_BASE_URL ?? null));
    });
    /**
     * The cover of a PUBLIC event, at an address that never expires. Link-preview robots keep this address, and each visit gets a
     * fresh short-lived storage link, so the picture does not break the way a stored signed link would. Same rules as the preview:
     * anything that is not a visible public event with a cover is a plain 404.
     */
    app.get('/public/events/:id/cover', async (req, reply) => {
      ctx.limit(`public-event-cover:${req.ip}`, 120, 60);
      const p = await previewRow(ctx, (req.params as { id: string }).id, true);
      if (!p || !p.coverKey || !ctx.storage) return reply.status(404).send({ error: { code: 'not_found', message: 'Not found.' } });
      // The redirect may be cached for less time than the link lives, so a cached redirect never points at an expired link.
      return reply.header('cache-control', 'public, max-age=120').redirect(await ctx.storage.signedUrl(p.coverKey, 600));
    });
  };
}
