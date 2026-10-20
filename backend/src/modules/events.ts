import type { FastifyInstance, FastifyRequest } from 'fastify';
import type { PoolClient } from 'pg';
import { z } from 'zod';
import { blockedBetween } from '../access';
import type { AuthUser, Ctx } from '../context';
import { withTx } from '../db';
import { HttpError } from '../errors';
import { decodeCursor, encodeCursor, idParam, pageSchema, toPage, tsText } from '../http';
import { cardColumns, cardJoin, type CardRow, signedUrl, toCard } from './cards';
import { isStaff } from './community_state';
import { notify } from './notify';

const EVENT_TYPES = ['in_person', 'online', 'hybrid'] as const;
const VISIBILITIES = ['public', 'university', 'community'] as const;
const MAX_DURATION_HOURS = 48;
const MIN_LEAD_MINUTES = 5;
const MAX_AHEAD_DAYS = 365;
const MAX_UPCOMING_PER_ORGANIZER = 5;
export const RETENTION_DAYS = 7;
const CHECK_IN_OPENS_MINUTES = 30;

const when = z.string().datetime({ offset: true });
const https = z.string().trim().url().max(500).refine((u) => u.startsWith('https://'), 'Links must start with https://');
const base = {
  title: z.string().trim().min(3, 'Title needs at least 3 characters.').max(80),
  description: z.string().trim().max(1000),
  category: z.string().trim().regex(/^[a-z][a-z_]{1,31}$/),
  eventType: z.enum(EVENT_TYPES),
  venueName: z.string().trim().min(2).max(80),
  venueAddress: z.string().trim().max(200).nullable(),
  onlineUrl: https.nullable(),
  externalRegistrationUrl: https.nullable(),
  registrationDeadline: when.nullable(),
  timezone: z.string().trim().min(1).max(64).nullable(),
  startsAt: when, endsAt: when,
  capacity: z.number().int().min(1).max(5000).nullable(),
  visibility: z.enum(VISIBILITIES),
  attendeesVisible: z.boolean(),
  coverMediaId: z.string().uuid().nullable(),
};
const createSchema = z.object({
  ...base,
  description: base.description.default(''), eventType: base.eventType.default('in_person'),
  venueName: base.venueName.optional(), venueAddress: base.venueAddress.default(null), onlineUrl: base.onlineUrl.default(null),
  externalRegistrationUrl: base.externalRegistrationUrl.default(null), registrationDeadline: base.registrationDeadline.default(null),
  timezone: base.timezone.default(null), capacity: base.capacity.default(null),
  visibility: base.visibility.optional(), attendeesVisible: base.attendeesVisible.default(true), coverMediaId: base.coverMediaId.default(null),
  communityId: z.string().uuid().nullable().default(null),
  status: z.enum(['draft', 'published']).default('published'),
  createKey: z.string().trim().min(8).max(64).nullable().default(null),
}).strict();
const patchSchema = z.object(base).partial().strict();
const listSchema = pageSchema.extend({
  scope: z.enum(['upcoming', 'past']).default('upcoming'),
  category: z.string().trim().regex(/^[a-z][a-z_]{1,31}$/).optional(),
  from: when.optional(), to: when.optional(),
  q: z.string().trim().min(1).max(80).optional(),
  communityId: z.string().uuid().optional(),
});

type Req = FastifyRequest;
type Q = Pick<PoolClient, 'query'>;

/**
 * Event visible to viewer ($1, university $2). Alias e.
 * - The organizer always sees their own events, including drafts. Nobody else ever sees a draft.
 * - 'community' events (required for private communities) are for active members of that active community only.
 * - 'university' events are for the same university; 'public' events for everyone. Blocked organizers are hidden either way.
 */
export const eventVisible = (viewer = '$1', uni = '$2', e = 'e') =>
  `(${e}.organizer_id = ${viewer} or (${e}.status <> 'draft' and not ${blockedBetween(viewer, `${e}.organizer_id`)} and (` +
  `(${e}.visibility = 'community' and exists (select 1 from community_members ecm join communities ec on ec.id = ecm.community_id ` +
  `where ecm.community_id = ${e}.community_id and ecm.user_id = ${viewer} and ecm.status = 'active' and ec.status = 'active')) ` +
  `or (${e}.visibility <> 'community' and (${e}.university_id = ${uni} or ${e}.visibility = 'public')))))`;

function validateTimes(startsAt: Date, endsAt: Date, opts: { creating: boolean; startChanged: boolean }) {
  const now = Date.now();
  if (endsAt <= startsAt) throw new HttpError(422, 'validation', 'The event must end after it starts.');
  if (endsAt.getTime() - startsAt.getTime() > MAX_DURATION_HOURS * 3600_000) throw new HttpError(422, 'validation', `Events can last at most ${MAX_DURATION_HOURS} hours.`);
  if (opts.startChanged && startsAt.getTime() < now + MIN_LEAD_MINUTES * 60_000) throw new HttpError(422, 'validation', `Events must start at least ${MIN_LEAD_MINUTES} minutes from now.`);
  if (startsAt.getTime() > now + MAX_AHEAD_DAYS * 86400_000) throw new HttpError(422, 'validation', 'Events can be at most a year ahead.');
}

interface Shape { eventType: string; venueName?: string | null; onlineUrl?: string | null; registrationDeadline?: Date | null; endsAt: Date }

/** Rules that depend on several fields together. Run on the merged result for edits. */
function validateShape(x: Shape) {
  if (x.eventType !== 'online' && !x.venueName) throw new HttpError(422, 'validation', 'Add a venue for this event.');
  if (x.eventType !== 'in_person' && !x.onlineUrl) throw new HttpError(422, 'validation', 'Add the link people use to join online.');
  if (x.registrationDeadline && x.registrationDeadline > x.endsAt) throw new HttpError(422, 'validation', 'Registration must close before the event ends.');
}

/** The server decides who may organize for a community; the client's idea of its own role is never used. */
async function assertMayHostForCommunity(client: Q, me: AuthUser, communityId: string): Promise<{ visibility: string }> {
  const { rows } = await client.query<{ status: string; visibility: string; role: string | null; member_status: string | null }>(
    `select c.status, c.visibility, m.role, m.status as member_status
       from communities c left join community_members m on m.community_id = c.id and m.user_id = $1
      where c.id = $2 and c.university_id = $3 and c.status <> 'deleted' for share of c`, [me.id, communityId, me.universityId]);
  const c = rows[0];
  if (!c || (c.visibility === 'private' && !c.member_status)) throw new HttpError(404, 'not_found', 'That community is not available.');
  if (c.status !== 'active') throw new HttpError(403, 'community_unavailable', 'This community is not accepting new events.');
  if (c.member_status !== 'active' || !isStaff(c.role)) throw new HttpError(403, 'staff_only', 'Only the community owner or moderators can host its events.');
  return { visibility: c.visibility };
}

async function assertCategory(client: Q, code: string) {
  const ok = await client.query('select 1 from event_categories where code = $1 and active', [code]);
  if (!ok.rowCount) throw new HttpError(422, 'validation', 'Pick a category from the list.');
}

async function audit(client: Q, eventId: string, actorId: string, action: 'create' | 'publish' | 'update' | 'cancel' | 'complete', detail: Record<string, unknown> = {}) {
  await client.query('insert into event_audit (event_id, actor_id, action, detail) values ($1, $2, $3, $4::jsonb)', [eventId, actorId, action, JSON.stringify(detail)]);
}

export const EVENT_SELECT = `
  select e.*, ${tsText('e.starts_at')} as "cursorT", ${cardColumns('o')}, cv.object_key as cover_key,
         case when ec.visibility = 'public' or exists (select 1 from community_members vm where vm.community_id = ec.id and vm.user_id = $1 and vm.status = 'active') then ec.name end as community_name,
         (select count(*) from event_registrations r where r.event_id = e.id and r.status = 'registered') as registered_count,
         (select count(*) from event_interest i where i.event_id = e.id) as interested_count,
         exists (select 1 from event_interest i where i.event_id = e.id and i.user_id = $1) as my_interested,
         exists (select 1 from saved_events sv where sv.event_id = e.id and sv.user_id = $1) as my_saved,
         (select r.status from event_registrations r where r.event_id = e.id and r.user_id = $1) as my_status,
         (select r.checked_in_at is not null from event_registrations r where r.event_id = e.id and r.user_id = $1 and r.status = 'registered') as my_checked_in
    from events e join users o on o.id = e.organizer_id ${cardJoin('o')}
    left join media cv on cv.id = e.cover_media_id and cv.status = 'ready'
    left join communities ec on ec.id = e.community_id and ec.status <> 'deleted'`;


/**
 * Time-dependent state is derived here, from the server clock and the stored event; the app never decides it.
 * phase: where the event is in time. registration: whether a new person could register right now.
 */
function deriveEventState(r: Record<string, unknown>, registered: number) {
  const now = Date.now();
  const starts = new Date(r.starts_at as string).getTime(), ends = new Date(r.ends_at as string).getTime();
  const status = r.status as string;
  const phase = status === 'completed' || now >= ends ? 'ended' : now >= starts ? 'live' : 'upcoming';
  const deadline = r.registration_deadline ? new Date(r.registration_deadline as string).getTime() : null;
  const capacity = r.capacity as number | null;
  const registration = status === 'draft' ? 'draft' : status === 'cancelled' ? 'cancelled' : phase === 'ended' ? 'ended'
    : deadline !== null && now >= deadline ? 'closed' : capacity !== null && registered >= capacity ? 'full' : 'open';
  return { phase, registration };
}

export async function toEventView(ctx: Pick<Ctx, 'storage'>, meId: string, r: Record<string, unknown>) {
  const registered = Number(r.registered_count);
  const capacity = r.capacity as number | null;
  const isOrganizer = r.organizer_id === meId;
  const joined = r.my_status === 'registered';
  return {
    id: r.id as string, organizer: await toCard(ctx, r as unknown as CardRow), title: r.title, description: r.description,
    category: r.category, eventType: r.event_type, venueName: r.venue_name, venueAddress: r.venue_address,
    // The join link is for the organizer and people who registered; everyone else who can see the event cannot.
    onlineUrl: isOrganizer || joined ? r.online_url : null, hasOnlineLink: r.online_url !== null,
    externalRegistrationUrl: r.external_registration_url, registrationDeadline: r.registration_deadline, timezone: r.timezone,
    startsAt: r.starts_at, endsAt: r.ends_at, capacity,
    registeredCount: registered, spotsLeft: capacity === null ? null : Math.max(0, capacity - registered),
    visibility: r.visibility, attendeesVisible: r.attendees_visible, status: r.status, ...deriveEventState(r, registered),
    communityId: r.community_name ? r.community_id : null, communityName: r.community_name ?? null,
    coverUrl: await signedUrl(ctx, r.cover_key as string | null),
    myRegistration: joined ? (r.my_checked_in ? 'checked_in' : 'registered') : null,
    interestedCount: Number(r.interested_count),
    // Interested never holds a seat; going is the registration. The two are mutually exclusive.
    myRsvp: joined ? 'going' : r.my_interested ? 'interested' : null,
    saved: r.my_saved === true,
    isOrganizer,
  };
}


/**
 * Creates the feed announcement for a published event, inside the caller's transaction. Safe to repeat: there is at most one
 * announcement per event, so publishing twice or editing later never adds another. Private-community events are announced
 * inside that community (audience 'community'); everything else on campus.
 */
export async function announceEvent(client: Q, e: { id: string; organizer_id: string; community_id: string | null }) {
  await client.query(
    `insert into posts (author_id, text, audience, community_id, event_id) values ($1, '', $2, $3, $4)
     on conflict (event_id) where event_id is not null do nothing`,
    [e.organizer_id, e.community_id ? 'community' : 'campus', e.community_id, e.id]);
}

/** The canonical event views for these ids, as this viewer may see them. An id the viewer cannot see is simply missing. */
export async function loadEventViews(ctx: Pick<Ctx, 'pool' | 'storage'>, viewer: { id: string; universityId: string }, ids: string[]) {
  const out = new Map<string, Awaited<ReturnType<typeof toEventView>>>();
  if (ids.length === 0) return out;
  const { rows } = await ctx.pool.query(`${EVENT_SELECT} where e.id = any($3::uuid[]) and ${eventVisible('$1', '$2')}`, [viewer.id, viewer.universityId, ids]);
  for (const r of rows) out.set(r.id as string, await toEventView(ctx, viewer.id, r as Record<string, unknown>));
  return out;
}

export function eventRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;
    const param = (req: Req, name = 'eventId') => idParam((req.params as Record<string, string>)[name]);

    const SELECT = EVENT_SELECT;

    const toView = (me: AuthUser, r: Record<string, unknown>) => toEventView(ctx, me.id, r);

    // Parameters: $1 viewer id, $2 viewer university id, then the caller's own.
    const visible = () => eventVisible('$1', '$2');
    async function getEvent(me: AuthUser, id: string, client: Q = pool) {
      const { rows } = await client.query(`${SELECT} where e.id = $3 and ${visible()}`, [me.id, me.universityId, id]);
      if (!rows[0]) throw new HttpError(404, 'not_found', 'Not found.');
      return rows[0] as Record<string, unknown>;
    }

    /** Organizers, and admins acting for the university, may change an event. Everyone else gets "not found". */
    async function manageable(me: AuthUser, id: string, client: Q = pool) {
      const row = await getEvent(me, id, client);
      const allowed = row.organizer_id === me.id || (me.role === 'admin' && row.university_id === me.universityId);
      if (!allowed) throw new HttpError(404, 'not_found', 'Not found.');
      return row;
    }

    async function coverOk(me: AuthUser, id: string | null) {
      if (!id) return;
      const ok = await pool.query("select 1 from media where id = $1 and owner_id = $2 and purpose = 'event' and status = 'ready' and kind = 'image'", [id, me.id]);
      if (!ok.rowCount) throw new HttpError(422, 'invalid_media', 'That cover image is not available.');
    }

    async function notifyRegistrants(client: Q, me: AuthUser, eventId: string, kind: string) {
      const title = (await client.query<{ title: string }>('select title from events where id = $1', [eventId])).rows[0]?.title ?? '';
      const { rows } = await client.query<{ user_id: string }>(
        "select user_id from event_registrations where event_id = $1 and status = 'registered' and user_id <> $2", [eventId, me.id]);
      for (const r of rows) await notify(client as PoolClient, { recipientId: r.user_id, actorId: me.id, type: 'event_update', entityType: 'event', entityId: eventId, metadata: { kind, title } });
    }

    app.get('/event-categories', async (req) => {
      await ctx.auth(req);
      const { rows } = await pool.query('select code, label from event_categories where active order by sort_order, label');
      return { items: rows };
    });

    app.post('/events', async (req, reply) => {
      const me = await ctx.auth(req);
      const b = createSchema.parse(req.body ?? {});
      ctx.limit(`event:${me.id}`, 10, 3600);
      const starts = new Date(b.startsAt), ends = new Date(b.endsAt);
      const deadline = b.registrationDeadline ? new Date(b.registrationDeadline) : null;
      validateTimes(starts, ends, { creating: true, startChanged: true });
      const venueName = b.venueName ?? (b.eventType === 'online' ? 'Online' : undefined);
      validateShape({ eventType: b.eventType, venueName, onlineUrl: b.onlineUrl, registrationDeadline: deadline, endsAt: ends });
      await coverOk(me, b.coverMediaId);
      const result = await withTx(pool, async (client) => {
        await client.query('select 1 from users where id = $1 for update', [me.id]); // serialises the per-organizer limit and the retry key
        if (b.createKey) {
          const again = await client.query<{ id: string }>('select id from events where organizer_id = $1 and create_key = $2', [me.id, b.createKey]);
          if (again.rows[0]) return { id: again.rows[0].id, created: false };
        }
        await assertCategory(client, b.category);
        let visibility = b.visibility ?? 'university';
        if (b.communityId) {
          const c = await assertMayHostForCommunity(client, me, b.communityId);
          // A private community's events never leave it. A public community's events may be wider, as the organizer chooses.
          if (c.visibility === 'private') visibility = 'community';
        } else if (visibility === 'community') {
          throw new HttpError(422, 'validation', 'Community-only events need a community.');
        }
        if (b.status === 'published') {
          const open = Number((await client.query(
            "select count(*) from events where organizer_id = $1 and status = 'published' and ends_at > now()", [me.id])).rows[0].count);
          if (open >= MAX_UPCOMING_PER_ORGANIZER) throw new HttpError(409, 'too_many_events', `You can have up to ${MAX_UPCOMING_PER_ORGANIZER} upcoming events.`);
        }
        const id = (await client.query<{ id: string }>(
          `insert into events (organizer_id, university_id, community_id, title, description, category, event_type, venue_name, venue_address, online_url,
                               external_registration_url, registration_deadline, timezone, cover_media_id, starts_at, ends_at, capacity, visibility,
                               attendees_visible, status, create_key)
           values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21) returning id`,
          [me.id, me.universityId, b.communityId, b.title, b.description, b.category, b.eventType, venueName, b.venueAddress, b.onlineUrl,
            b.externalRegistrationUrl, deadline, b.timezone, b.coverMediaId, starts, ends, b.capacity, visibility, b.attendeesVisible, b.status, b.createKey])).rows[0].id;
        await audit(client, id, me.id, 'create', { status: b.status, communityId: b.communityId });
        if (b.status === 'published') {
          await audit(client, id, me.id, 'publish');
          await announceEvent(client, { id, organizer_id: me.id, community_id: b.communityId });
        }
        return { id, created: true };
      });
      return reply.status(result.created ? 201 : 200).send(await toView(me, await getEvent(me, result.id)));
    });

    // Publishing a draft re-checks everything that depends on "now", and is safe to repeat.
    app.post('/events/:eventId/publish', async (req) => {
      const me = await ctx.auth(req);
      const id = param(req);
      await withTx(pool, async (client) => {
        await manageable(me, id, client);
        const row = (await client.query('select * from events where id = $1 for update', [id])).rows[0];
        if (row.status === 'published') return;
        if (row.status !== 'draft') throw new HttpError(409, 'event_closed', 'This event cannot be published.');
        await client.query('select 1 from users where id = $1 for update', [row.organizer_id]);
        validateTimes(new Date(row.starts_at), new Date(row.ends_at), { creating: true, startChanged: true });
        validateShape({ eventType: row.event_type, venueName: row.venue_name, onlineUrl: row.online_url,
          registrationDeadline: row.registration_deadline ? new Date(row.registration_deadline) : null, endsAt: new Date(row.ends_at) });
        if (row.community_id) await assertMayHostForCommunity(client, { ...me, id: row.organizer_id } as AuthUser, row.community_id);
        const open = Number((await client.query(
          "select count(*) from events where organizer_id = $1 and status = 'published' and ends_at > now()", [row.organizer_id])).rows[0].count);
        if (open >= MAX_UPCOMING_PER_ORGANIZER) throw new HttpError(409, 'too_many_events', `You can have up to ${MAX_UPCOMING_PER_ORGANIZER} upcoming events.`);
        await client.query("update events set status = 'published', updated_at = now() where id = $1", [id]);
        await audit(client, id, me.id, 'publish');
        await announceEvent(client, { id, organizer_id: row.organizer_id, community_id: row.community_id });
      });
      return toView(me, await getEvent(me, id));
    });

    // The organizer closes an event that has started. Ending is otherwise derived from the end time.
    app.post('/events/:eventId/complete', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      await withTx(pool, async (client) => {
        await manageable(me, id, client);
        const res = await client.query("update events set status = 'completed', updated_at = now() where id = $1 and status = 'published' and starts_at <= now() returning id", [id]);
        if (!res.rowCount) throw new HttpError(409, 'event_closed', 'Only an event that has started can be marked completed.');
        await audit(client, id, me.id, 'complete');
      });
      return reply.status(204).send();
    });

    app.get('/events', async (req) => {
      const me = await ctx.auth(req);
      const q = listSchema.parse(req.query);
      ctx.limit(`event-list:${me.id}`, 120, 600);
      const cur = decodeCursor(q.cursor);
      const upcoming = q.scope === 'upcoming';
      const { rows } = await pool.query(
        `${SELECT} where ${visible()} and e.status <> 'draft'
            and (${upcoming ? "e.ends_at > now() and e.status <> 'completed'" : "(e.ends_at <= now() or e.status = 'completed')"})
            and ($3::text is null or e.category = $3)
            and ($4::timestamptz is null or e.starts_at >= $4) and ($5::timestamptz is null or e.starts_at <= $5)
            and ($6::text is null or e.title ilike '%' || $6 || '%' or e.venue_name ilike '%' || $6 || '%')
            and ($7::timestamptz is null or (e.starts_at, e.id) ${upcoming ? '>' : '<'} ($7::timestamptz, $8::uuid))
            and ($9::uuid is null or e.community_id = $9)
          order by e.starts_at ${upcoming ? 'asc' : 'desc'}, e.id ${upcoming ? 'asc' : 'desc'} limit $10`,
        [me.id, me.universityId, q.category ?? null, q.from ?? null, q.to ?? null, q.q ? q.q.replace(/[%_\\]/g, '\\$&') : null, cur?.t ?? null, cur?.id ?? null, q.communityId ?? null, q.limit + 1],
      );
      const page = toPage(rows as (Record<string, unknown> & { cursorT: string; id: string })[], q.limit);
      return { items: await Promise.all(page.items.map((r) => toView(me, r as Record<string, unknown>))), nextCursor: page.nextCursor };
    });

    app.get('/me/events', async (req) => {
      const me = await ctx.auth(req);
      const q = listSchema.parse(req.query);
      const cur = decodeCursor(q.cursor);
      const upcoming = q.scope === 'upcoming';
      const { rows } = await pool.query(
        `${SELECT} where $2::text is not null and ${visible()} and (e.organizer_id = $1 or exists (select 1 from event_registrations r where r.event_id = e.id and r.user_id = $1 and r.status = 'registered'))
            and (${upcoming ? "e.ends_at > now() and e.status <> 'completed'" : "(e.ends_at <= now() or e.status = 'completed')"})
            and ($3::timestamptz is null or (e.starts_at, e.id) ${upcoming ? '>' : '<'} ($3::timestamptz, $4::uuid))
          order by e.starts_at ${upcoming ? 'asc' : 'desc'}, e.id ${upcoming ? 'asc' : 'desc'} limit $5`,
        [me.id, me.universityId, cur?.t ?? null, cur?.id ?? null, q.limit + 1],
      );
      const page = toPage(rows as (Record<string, unknown> & { cursorT: string; id: string })[], q.limit);
      return { items: await Promise.all(page.items.map((r) => toView(me, r as Record<string, unknown>))), nextCursor: page.nextCursor };
    });

    app.get('/events/:eventId', async (req) => {
      const me = await ctx.auth(req);
      return toView(me, await getEvent(me, param(req)));
    });

    app.patch('/events/:eventId', async (req) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const b = patchSchema.parse(req.body ?? {});
      await withTx(pool, async (client) => {
        await manageable(me, id, client);
        const row = (await client.query('select * from events where id = $1 for update', [id])).rows[0];
        if (row.status === 'cancelled' || row.status === 'completed') throw new HttpError(409, 'event_closed', 'This event can no longer be edited.');
        if (new Date(row.ends_at) <= new Date()) throw new HttpError(409, 'event_closed', 'This event has ended.');
        const starts = b.startsAt ? new Date(b.startsAt) : new Date(row.starts_at);
        const ends = b.endsAt ? new Date(b.endsAt) : new Date(row.ends_at);
        // An unchanged start may stay, even if it is now less than five minutes away.
        validateTimes(starts, ends, { creating: false, startChanged: b.startsAt !== undefined && starts.getTime() !== new Date(row.starts_at).getTime() });
        const deadline = b.registrationDeadline === undefined ? (row.registration_deadline ? new Date(row.registration_deadline) : null) : (b.registrationDeadline ? new Date(b.registrationDeadline) : null);
        const eventType = b.eventType ?? row.event_type;
        const onlineUrl = b.onlineUrl === undefined ? row.online_url : b.onlineUrl;
        const venueName = b.venueName ?? row.venue_name ?? (eventType === 'online' ? 'Online' : null);
        validateShape({ eventType, venueName, onlineUrl, registrationDeadline: deadline, endsAt: ends });
        if (b.category !== undefined) await assertCategory(client, b.category);
        if (b.visibility !== undefined) {
          // The community and the host never change. A community event may not be widened past its community when that community is private.
          if (b.visibility === 'community' && !row.community_id) throw new HttpError(422, 'validation', 'Community-only events need a community.');
          if (row.community_id && b.visibility !== 'community') {
            const c = (await client.query('select visibility from communities where id = $1', [row.community_id])).rows[0];
            if (c?.visibility === 'private') throw new HttpError(422, 'validation', 'Events of a private community stay inside it.');
          }
        }
        if (b.capacity !== undefined && b.capacity !== null) {
          const going = Number((await client.query("select count(*) from event_registrations where event_id = $1 and status = 'registered'", [id])).rows[0].count);
          if (b.capacity < going) throw new HttpError(422, 'validation', `${going} people are already going, so capacity cannot be lower.`);
        }
        if (b.coverMediaId) await coverOk(me, b.coverMediaId);
        const cols: Record<string, string> = { title: 'title', description: 'description', category: 'category', eventType: 'event_type', venueName: 'venue_name', venueAddress: 'venue_address',
          onlineUrl: 'online_url', externalRegistrationUrl: 'external_registration_url', registrationDeadline: 'registration_deadline', timezone: 'timezone',
          startsAt: 'starts_at', endsAt: 'ends_at', capacity: 'capacity', visibility: 'visibility', attendeesVisible: 'attendees_visible', coverMediaId: 'cover_media_id' };
        const dates = new Set(['startsAt', 'endsAt', 'registrationDeadline']);
        const sets: string[] = []; const values: unknown[] = [id];
        for (const [k, v] of Object.entries(b)) { values.push(dates.has(k) && v !== null ? new Date(v as string) : v); sets.push(`${cols[k]} = $${values.length}`); }
        if (b.eventType === 'online' && b.venueName === undefined && !row.venue_name) { values.push('Online'); sets.push(`venue_name = $${values.length}`); }
        if (sets.length) await client.query(`update events set ${sets.join(', ')}, updated_at = now() where id = $1`, values);
        // Only changes that affect whether and how someone attends notify them; wording edits never do.
        const important = ['startsAt', 'endsAt', 'venueName', 'venueAddress', 'eventType', 'onlineUrl'].filter((k) => k in b);
        if (important.length && row.status === 'published') await notifyRegistrants(client, me, id, 'changed');
        if (sets.length) await audit(client, id, me.id, 'update', { fields: Object.keys(b) });
      });
      return toView(me, await getEvent(me, id));
    });

    // Cancelling keeps the event (people can see it was cancelled) and tells everyone who was going.
    app.post('/events/:eventId/cancel', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      await withTx(pool, async (client) => {
        await manageable(me, id, client);
        const res = await client.query("update events set status = 'cancelled', updated_at = now() where id = $1 and status = 'published' and ends_at > now() returning id", [id]);
        if (!res.rowCount) throw new HttpError(409, 'event_closed', 'This event cannot be cancelled.');
        await notifyRegistrants(client, me, id, 'cancelled');
        await audit(client, id, me.id, 'cancel');
      });
      return reply.status(204).send();
    });

    // Deleting is only for events nobody else joined; otherwise cancel.
    app.delete('/events/:eventId', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      await withTx(pool, async (client) => {
        await manageable(me, id, client);
        const others = await client.query("select 1 from event_registrations where event_id = $1 and status = 'registered' and user_id <> $2 limit 1", [id, me.id]);
        if (others.rowCount) throw new HttpError(409, 'has_registrations', 'People have registered. Cancel the event instead.');
        await client.query("delete from notifications where entity_type = 'event' and entity_id = $1", [id]);
        await client.query('delete from events where id = $1', [id]);
      });
      return reply.status(204).send();
    });

    /** Takes a seat (or confirms the one already held). Locks the event row so the last place can only be taken once. */
    async function registerTx(client: PoolClient, me: AuthUser, id: string): Promise<{ created: boolean }> {
      await getEvent(me, id, client); // 404 if the viewer may not see it
      const e = (await client.query('select capacity, status, ends_at, registration_deadline from events where id = $1 for update', [id])).rows[0];
      if (e.status !== 'published' || new Date(e.ends_at) <= new Date()) throw new HttpError(409, 'event_closed', 'Registration is closed for this event.');
      // The server clock decides the deadline, never the device's.
      if (e.registration_deadline && new Date(e.registration_deadline) <= new Date()) throw new HttpError(409, 'registration_closed', 'The registration deadline has passed.');
      const mine = (await client.query('select status from event_registrations where event_id = $1 and user_id = $2', [id, me.id])).rows[0];
      if (mine?.status === 'registered') return { created: false };
      const going = Number((await client.query("select count(*) from event_registrations where event_id = $1 and status = 'registered'", [id])).rows[0].count);
      if (e.capacity !== null && going >= e.capacity) throw new HttpError(409, 'event_full', 'This event is full.');
      await client.query(
        `insert into event_registrations (event_id, user_id) values ($1, $2)
         on conflict (event_id, user_id) do update set status = 'registered', registered_at = now(), cancelled_at = null`, [id, me.id]);
      await client.query('delete from event_interest where event_id = $1 and user_id = $2', [id, me.id]); // going replaces interested
      return { created: true };
    }

    app.post('/events/:eventId/register', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      ctx.limit(`event-reg:${me.id}`, 30, 3600);
      const result = await withTx(pool, (client) => registerTx(client, me, id));
      return reply.status(result.created ? 201 : 200).send({ registered: true });
    });

    // One endpoint for the whole response: interested, going or none. Changing it is a single transaction,
    // so a person is never counted as both, and interest never takes a seat.
    app.put('/events/:eventId/rsvp', async (req) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const { state } = z.object({ state: z.enum(['interested', 'going', 'none']) }).strict().parse(req.body ?? {});
      ctx.limit(`event-rsvp:${me.id}`, 60, 3600);
      await withTx(pool, async (client) => {
        if (state === 'going') { await registerTx(client, me, id); return; }
        await getEvent(me, id, client);
        const e = (await client.query('select status, ends_at from events where id = $1 for update', [id])).rows[0];
        if (state === 'interested') {
          if (e.status !== 'published' || new Date(e.ends_at) <= new Date()) throw new HttpError(409, 'event_closed', 'This event is closed.');
          await client.query("update event_registrations set status = 'cancelled', cancelled_at = now() where event_id = $1 and user_id = $2 and status = 'registered'", [id, me.id]);
          await client.query('insert into event_interest (event_id, user_id) values ($1, $2) on conflict do nothing', [id, me.id]);
        } else {
          // Withdrawing is always allowed, even after the event, so nobody is stuck with an old response.
          await client.query("update event_registrations set status = 'cancelled', cancelled_at = now() where event_id = $1 and user_id = $2 and status = 'registered'", [id, me.id]);
          await client.query('delete from event_interest where event_id = $1 and user_id = $2', [id, me.id]);
        }
      });
      return toView(me, await getEvent(me, id));
    });

    // Saved events: private to the person, synced through the server, one row per (person, event).
    app.put('/events/:eventId/save', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      ctx.limit(`event-save:${me.id}`, 120, 3600);
      await getEvent(me, id);
      await pool.query('insert into saved_events (user_id, event_id) values ($1, $2) on conflict do nothing', [me.id, id]);
      return reply.status(204).send();
    });

    app.delete('/events/:eventId/save', async (req, reply) => {
      const me = await ctx.auth(req);
      await pool.query('delete from saved_events where user_id = $1 and event_id = $2', [me.id, param(req)]);
      return reply.status(204).send();
    });

    // Newest saved first. An event the person can no longer see (private community left, blocked organizer) is not listed.
    app.get('/me/saved-events', async (req) => {
      const me = await ctx.auth(req);
      const q = pageSchema.parse(req.query);
      const cur = decodeCursor(q.cursor);
      // Same columns as every other event list, driven from the saved rows.
      const savedSelect = SELECT.replace('select e.*,', `select e.*, ${tsText('sv0.created_at')} as "savedT",`)
        .replace('from events e join users o', 'from saved_events sv0 join events e on e.id = sv0.event_id join users o');
      const res = await pool.query(
        `${savedSelect}
          where sv0.user_id = $1 and ${visible()}
            and ($3::timestamptz is null or (sv0.created_at, e.id) < ($3::timestamptz, $4::uuid))
          order by sv0.created_at desc, e.id desc limit $5`,
        [me.id, me.universityId, cur?.t ?? null, cur?.id ?? null, q.limit + 1]);
      const more = res.rows.length > q.limit;
      const page = more ? res.rows.slice(0, q.limit) : res.rows;
      const last = page[page.length - 1] as { savedT: string; id: string } | undefined;
      const nextCursor = more && last ? encodeCursor({ t: last.savedT, id: last.id }) : null;
      return { items: await Promise.all(page.map((r) => toView(me, r as Record<string, unknown>))), nextCursor };
    });

    app.delete('/events/:eventId/register', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      await pool.query(
        "update event_registrations set status = 'cancelled', cancelled_at = now() where event_id = $1 and user_id = $2 and status = 'registered'", [id, me.id]);
      return reply.status(204).send();
    });

    app.get('/events/:eventId/attendees', async (req) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const q = pageSchema.parse(req.query);
      const e = await getEvent(me, id);
      const isOrganizer = e.organizer_id === me.id;
      // Organizers always see attendees; others only when the organizer allows it and they are going themselves.
      if (!isOrganizer && !(e.attendees_visible && e.my_status === 'registered')) throw new HttpError(403, 'attendees_hidden', 'The attendee list is not available.');
      const cur = decodeCursor(q.cursor);
      const { rows } = await pool.query(
        `select u.id, ${cardColumns('u')}, ${tsText('r.registered_at')} as "cursorT", r.checked_in_at is not null as checked_in
           from event_registrations r join users u on u.id = r.user_id ${cardJoin('u')}
          where r.event_id = $2 and r.status = 'registered' and (u.id = $1 or not ${blockedBetween('$1', 'u.id')})
            and ($3::timestamptz is null or (r.registered_at, u.id) > ($3::timestamptz, $4::uuid))
          order by r.registered_at, u.id limit $5`,
        [me.id, id, cur?.t ?? null, cur?.id ?? null, q.limit + 1],
      );
      const page = toPage(rows as (CardRow & { cursorT: string; id: string; checked_in: boolean })[], q.limit);
      return {
        items: await Promise.all(page.items.map(async (r) => {
          const row = r as unknown as CardRow & { checked_in: boolean };
          return { ...(await toCard(ctx, row)), ...(isOrganizer ? { checkedIn: row.checked_in } : {}) };
        })),
        nextCursor: page.nextCursor,
      };
    });

    app.post('/events/:eventId/check-in', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const { userId } = z.object({ userId: z.string().uuid() }).strict().parse(req.body ?? {});
      const e = await manageable(me, id); // only the organizer (or a university admin)
      const opens = new Date(e.starts_at as Date).getTime() - CHECK_IN_OPENS_MINUTES * 60_000;
      if (e.status !== 'published' || Date.now() < opens || new Date(e.ends_at as Date) <= new Date()) {
        throw new HttpError(409, 'check_in_closed', 'Check-in is not open for this event.');
      }
      const res = await pool.query(
        "update event_registrations set checked_in_at = coalesce(checked_in_at, now()) where event_id = $1 and user_id = $2 and status = 'registered'", [id, userId]);
      if (!res.rowCount) throw new HttpError(404, 'not_found', 'That person is not registered.');
      return reply.status(204).send();
    });
  };
}

/** Background job: removes events 7 days after they end (with registrations, reminders and notifications). Safe to repeat. */
export async function purgeExpiredEvents(ctx: Ctx): Promise<number> {
  return withTx(ctx.pool, async (client) => {
    const old = await client.query<{ id: string }>(`select id from events where ends_at < now() - make_interval(days => $1::int)`, [RETENTION_DAYS]);
    const ids = old.rows.map((r) => r.id);
    if (!ids.length) return 0;
    await client.query("delete from notifications where entity_type = 'event' and entity_id = any($1::uuid[])", [ids]);
    await client.query('delete from events where id = any($1::uuid[])', [ids]);
    return ids.length;
  });
}

/** Background job: one reminder per registered person, for events starting within the next hour. Idempotent. */
export async function sendEventReminders(ctx: Ctx): Promise<number> {
  return withTx(ctx.pool, async (client) => {
    const due = await client.query<{ event_id: string; user_id: string; title: string }>(
      `insert into event_reminders (event_id, user_id)
       select r.event_id, r.user_id from event_registrations r join events e on e.id = r.event_id
        where r.status = 'registered' and e.status = 'published' and e.starts_at > now() and e.starts_at <= now() + interval '1 hour'
       on conflict do nothing returning event_id, user_id,
         (select title from events where id = event_reminders.event_id) as title`);
    for (const r of due.rows) {
      await notify(client, { recipientId: r.user_id, actorId: null, type: 'event_update', entityType: 'event', entityId: r.event_id, metadata: { kind: 'reminder', title: r.title } });
    }
    return due.rowCount ?? 0;
  });
}
