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
import { enqueue } from '../jobs';
import type { Hub } from '../realtime';
import { assertMayPostInCommunity } from './community_state';
import { sendMessage } from './messaging';
import { type EventPreference, type NewNotification, notify } from './notify';

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
  latitude: z.number().min(-90).max(90).nullable(),
  longitude: z.number().min(-180).max(180).nullable(),
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
  venueName: base.venueName.optional(), venueAddress: base.venueAddress.default(null), latitude: base.latitude.default(null), longitude: base.longitude.default(null), onlineUrl: base.onlineUrl.default(null),
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
  `(${e}.organizer_id = ${viewer} or (${e}.status <> 'draft' and ${e}.hidden_at is null ` +
  `and not exists (select 1 from users evs where evs.id = ${e}.organizer_id and ((evs.suspended_until is not null and evs.suspended_until > now()) or evs.deactivated_at is not null)) ` +
  `and not ${blockedBetween(viewer, `${e}.organizer_id`)} and (` +
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

/** A map position is a pair: both numbers or neither. */
function assertCoordinatePair(lat: number | null | undefined, lng: number | null | undefined) {
  if ((lat == null) !== (lng == null)) throw new HttpError(422, 'validation', 'A map position needs both latitude and longitude.');
}

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
  if (!ok.rowCount) throw new HttpError(400, 'validation', 'Pick a category from the list.');
}

async function audit(client: Q, eventId: string, actorId: string, action: 'create' | 'publish' | 'update' | 'cancel' | 'complete' | 'announce_update' | 'hide' | 'restore', detail: Record<string, unknown> = {}) {
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
         (select r.reminders from event_registrations r where r.event_id = e.id and r.user_id = $1 and r.status = 'registered') as my_reminders,
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
    category: r.category, eventType: r.event_type, venueName: r.venue_name, venueAddress: r.venue_address, latitude: r.latitude ?? null, longitude: r.longitude ?? null,
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
    // Reminders for this event, for people who registered. They can silence one event without changing their settings.
    remindersOn: joined ? r.my_reminders !== false : null,
    isOrganizer,
    // Hidden by a moderator: only the organizer can still see the event, and is told so.
    hidden: r.hidden_at !== null && r.hidden_at !== undefined,
    // Bumped on every change that matters for a calendar entry; the app compares it with the version it added.
    revision: Number(r.revision ?? 0),
  };
}


/**
 * Free-text match for an event (alias e), shared by Search and Discover. $1 is the viewer; `p` is the parameter holding the
 * already LIKE-escaped term. Matches title, description, venue, category, organizer, university, and the community name
 * only where the viewer is allowed to know the community, so a private community's name cannot be probed through search.
 */
export const eventTextMatch = (p: string) =>
  `(e.title ilike '%' || ${p} || '%' or e.description ilike '%' || ${p} || '%' or e.venue_name ilike '%' || ${p} || '%' ` +
  `or e.venue_address ilike '%' || ${p} || '%' ` +
  `or exists (select 1 from event_categories sc where sc.code = e.category and sc.label ilike '%' || ${p} || '%') ` +
  `or exists (select 1 from users so where so.id = e.organizer_id and (so.full_name ilike '%' || ${p} || '%' or so.handle ilike '%' || ${p} || '%')) ` +
  `or exists (select 1 from universities su where su.id = e.university_id and su.name ilike '%' || ${p} || '%') ` +
  `or exists (select 1 from communities sco where sco.id = e.community_id and sco.status <> 'deleted' and sco.name ilike '%' || ${p} || '%' ` +
  `and (sco.visibility = 'public' or exists (select 1 from community_members scm where scm.community_id = sco.id and scm.user_id = $1 and scm.status = 'active'))))`;

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

/**
 * Stores an event notice unless the same one was already sent: for the same person and event, ever (no window) or
 * within a window. This is what stops repeated edits, register/withdraw cycles and job retries from becoming a storm.
 * Notices carry no actor on purpose: the per-actor dedupe index would otherwise drop a cancellation that follows a change.
 */
async function notifyOnce(client: Q, n: NewNotification & { kind: string }, opts: { withinMinutes?: number; count?: number } = {}): Promise<boolean> {
  const dupe = await client.query(
    `select 1 from notifications where recipient_id = $1 and type = 'event_update' and entity_id = $2 and metadata->>'kind' = $3
        and ($4::int is null or created_at > now() - make_interval(mins => $4::int))
        and ($5::text is null or metadata->>'count' = $5) limit 1`,
    [n.recipientId, n.entityId, n.kind, opts.withinMinutes ?? null, opts.count === undefined ? null : String(opts.count)]);
  if (dupe.rowCount) return false;
  const { kind, ...rest } = n;
  return notify(client as PoolClient, { ...rest, actorId: null, type: 'event_update', entityType: 'event', metadata: { ...(n.metadata ?? {}), kind } });
}

/**
 * Tells the people an event affects (registered or interested) about an important change or a cancellation.
 * The organizer and the editor are never notified about their own action, blocked pairs are skipped, and
 * repeated changes within 15 minutes become one notice.
 */
async function notifyParticipants(client: Q, eventId: string, kind: 'changed' | 'cancelled' | 'update', preference: EventPreference, editorId: string, extra: Record<string, unknown> = {}) {
  const ev = (await client.query<{ title: string; organizer_id: string }>('select title, organizer_id from events where id = $1', [eventId])).rows[0];
  if (!ev) return;
  const { rows } = await client.query<{ user_id: string }>(
    `select p.user_id from (
        select user_id from event_registrations where event_id = $1 and status = 'registered'
        union select user_id from event_interest where event_id = $1) p
      where p.user_id <> $2 and p.user_id <> $3 and not ${blockedBetween('p.user_id', '$3::uuid')}`, [eventId, editorId, ev.organizer_id]);
  for (const r of rows) {
    await notifyOnce(client, { recipientId: r.user_id, actorId: null, type: 'event_update', entityId: eventId, kind, metadata: { title: ev.title, ...extra }, preference }, { withinMinutes: kind === 'changed' ? 15 : undefined, ...(kind === 'update' ? { count: extra.count as number } : {}) });
  }
}

/** The organizer hears about registrations at milestones and when the event fills up, never once per person. */
const MILESTONES = new Set([1, 10, 25, 50, 100, 250, 500]);

export function eventRoutes(ctx: Ctx, hub: Hub) {
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

    app.get('/event-categories', async (req) => {
      await ctx.auth(req);
      const { rows } = await pool.query('select code, label from event_categories where active order by sort_order, label');
      return { items: rows };
    });

    app.post('/events', async (req, reply) => {
      const me = await ctx.auth(req);
      const b = createSchema.parse(req.body ?? {});
      assertCoordinatePair(b.latitude, b.longitude);
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
          `insert into events (organizer_id, university_id, community_id, title, description, category, event_type, venue_name, venue_address, latitude, longitude, online_url,
                               external_registration_url, registration_deadline, timezone, cover_media_id, starts_at, ends_at, capacity, visibility,
                               attendees_visible, status, create_key)
           values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, $22, $23) returning id`,
          [me.id, me.universityId, b.communityId, b.title, b.description, b.category, b.eventType, venueName, b.venueAddress, b.latitude, b.longitude, b.onlineUrl,
            b.externalRegistrationUrl, deadline, b.timezone, b.coverMediaId, starts, ends, b.capacity, visibility, b.attendeesVisible, b.status, b.createKey])).rows[0].id;
        await audit(client, id, me.id, 'create', { status: b.status, communityId: b.communityId });
        if (b.status === 'published') {
          await audit(client, id, me.id, 'publish');
          await announceEvent(client, { id, organizer_id: me.id, community_id: b.communityId });
          if (b.communityId) await enqueue(client, 'event.announce', { eventId: id }, { dedupeKey: `event.announce:${id}` });
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
        if (row.community_id) await enqueue(client, 'event.announce', { eventId: id }, { dedupeKey: `event.announce:${id}` });
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
      if (b.latitude !== undefined || b.longitude !== undefined) assertCoordinatePair(b.latitude, b.longitude);
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
        const cols: Record<string, string> = { title: 'title', description: 'description', category: 'category', eventType: 'event_type', venueName: 'venue_name', venueAddress: 'venue_address', latitude: 'latitude', longitude: 'longitude',
          onlineUrl: 'online_url', externalRegistrationUrl: 'external_registration_url', registrationDeadline: 'registration_deadline', timezone: 'timezone',
          startsAt: 'starts_at', endsAt: 'ends_at', capacity: 'capacity', visibility: 'visibility', attendeesVisible: 'attendees_visible', coverMediaId: 'cover_media_id' };
        const dates = new Set(['startsAt', 'endsAt', 'registrationDeadline']);
        const sets: string[] = []; const values: unknown[] = [id];
        for (const [k, v] of Object.entries(b)) { values.push(dates.has(k) && v !== null ? new Date(v as string) : v); sets.push(`${cols[k]} = $${values.length}`); }
        if (b.eventType === 'online' && b.venueName === undefined && !row.venue_name) { values.push('Online'); sets.push(`venue_name = $${values.length}`); }
        if (sets.length) await client.query(`update events set ${sets.join(', ')}, updated_at = now() where id = $1`, values);
        // Only changes that affect whether and how someone attends notify them; wording edits never do.
        const startMoved = b.startsAt !== undefined && starts.getTime() !== new Date(row.starts_at).getTime();
        const endMoved = b.endsAt !== undefined && ends.getTime() !== new Date(row.ends_at).getTime();
        const same = (a: unknown, b2: unknown) => (a ?? null) === (b2 ?? null);
        const important = startMoved || endMoved
          || (b.venueName !== undefined && !same(b.venueName, row.venue_name)) || (b.venueAddress !== undefined && !same(b.venueAddress, row.venue_address))
          || (b.latitude !== undefined && !same(b.latitude, row.latitude)) || (b.longitude !== undefined && !same(b.longitude, row.longitude))
          || (b.eventType !== undefined && b.eventType !== row.event_type) || (b.onlineUrl !== undefined && !same(b.onlineUrl, row.online_url));
        // Reminders are relative to the start, so a new start clears the sent ones and they are worked out again.
        if (startMoved) await client.query('delete from event_reminders where event_id = $1', [id]);
        if (important) await client.query('update events set revision = revision + 1 where id = $1', [id]);
        if (important && row.status === 'published') await notifyParticipants(client, id, 'changed', 'event_changes', me.id);
        if (sets.length) await audit(client, id, me.id, 'update', { fields: Object.keys(b) });
      });
      return toView(me, await getEvent(me, id));
    });


    // ---------- stage 6: organizer updates

    const updateSchema = z.object({ body: z.string().trim().min(1, 'Write the update.').max(500) }).strict();

    // The organizer tells everyone going or interested something short. It is kept on the event page as well as sent.
    app.post('/events/:eventId/updates', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const b = updateSchema.parse(req.body ?? {});
      ctx.limit(`event-update:${me.id}`, 20, 3600);
      const made = await withTx(pool, async (client) => {
        const row = await manageable(me, id, client);
        if (row.hidden_at) throw new HttpError(409, 'event_hidden', 'This event is hidden by moderators.');
        if (row.status !== 'published' || new Date(row.ends_at as string) <= new Date()) throw new HttpError(409, 'event_closed', 'Updates can only be posted for upcoming or live events.');
        // At most 5 a day per event, so participants are not flooded.
        const today = Number((await client.query("select count(*) from event_updates where event_id = $1 and created_at > now() - interval '24 hours'", [id])).rows[0].count);
        if (today >= 5) throw new HttpError(429, 'rate_limited', 'You can post up to 5 updates a day for one event.');
        const u = (await client.query<{ id: string; created_at: Date }>('insert into event_updates (event_id, author_id, body) values ($1, $2, $3) returning id, created_at', [id, me.id, b.body])).rows[0];
        await audit(client, id, me.id, 'announce_update', { updateId: u.id });
        // `count` (the update's number today) makes each notice unique, so two updates are never merged into one. Text is cut for the notification preview.
        await notifyParticipants(client, id, 'update', 'event_changes', me.id, { updateId: u.id, count: today + 1, preview: b.body.slice(0, 120) });
        return u;
      });
      return reply.status(201).send({ id: made.id, body: b.body, createdAt: made.created_at });
    });

    // Anyone who can see the event can read its updates.
    app.get('/events/:eventId/updates', async (req) => {
      const me = await ctx.auth(req);
      const id = param(req);
      await getEvent(me, id);
      const q = pageSchema.parse(req.query);
      const cur = decodeCursor(q.cursor);
      const { rows } = await pool.query(
        `select u.id, u.body, u.created_at, ${tsText('u.created_at')} as "cursorT" from event_updates u
          where u.event_id = $1 and ($2::timestamptz is null or (u.created_at, u.id) < ($2::timestamptz, $3::uuid))
          order by u.created_at desc, u.id desc limit $4`, [id, cur?.t ?? null, cur?.id ?? null, q.limit + 1]);
      const page = toPage(rows as (Record<string, unknown> & { cursorT: string; id: string })[], q.limit);
      return { items: page.items.map((r) => ({ id: r.id, body: r.body, createdAt: r.created_at })), nextCursor: page.nextCursor };
    });

    // ---------- stage 6: audit read (organizer, or an admin of the same university)

    app.get('/events/:eventId/audit', async (req) => {
      const me = await ctx.auth(req);
      const id = param(req);
      await manageable(me, id);
      const q = z.object({ limit: z.coerce.number().int().min(1).max(100).default(50), before: z.coerce.number().int().positive().optional() }).parse(req.query);
      const { rows } = await pool.query(
        `select a.id, a.action, a.detail, a.created_at, a.actor_id, u.full_name as actor_name from event_audit a left join users u on u.id = a.actor_id
          where a.event_id = $1 and ($2::bigint is null or a.id < $2::bigint) order by a.id desc limit $3`, [id, q.before ?? null, q.limit + 1]);
      const more = rows.length > q.limit;
      const items = rows.slice(0, q.limit).map((r) => ({
        id: Number(r.id), action: r.action, at: r.created_at,
        // Moderator hide/restore is shown as such, without who or why: that belongs to moderators.
        actor: r.action === 'hide' || r.action === 'restore' ? null : r.actor_id ? { id: r.actor_id, name: r.actor_name } : null,
        fields: Array.isArray(r.detail?.fields) ? r.detail.fields : undefined,
      }));
      return { items, nextBefore: more ? items[items.length - 1].id : null };
    });

    // ---------- stage 6: sharing

    const baseUrl = ctx.config.PUBLIC_BASE_URL ?? null;

    // The links for an event. `webUrl` is null unless the server knows its public address. The app link only works inside the app.
    app.get('/events/:eventId/link', async (req) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const row = await getEvent(me, id);
      const sharable = row.status === 'published' && !row.hidden_at;
      return {
        appLink: `univibe://events/${id}`,
        webUrl: sharable && baseUrl ? `${baseUrl}/e/${id}` : null,
        // The preview a web visitor gets exists only for public events; everyone else sees a generic "open in UniVibe" page.
        publicPreview: sharable && row.visibility === 'public',
      };
    });

    const shareSchema = z.object({
      target: z.enum(['chat', 'community']), targetId: z.string().uuid(),
      text: z.string().trim().max(500).default(''),
    }).strict();

    /**
     * Shares the event itself (not a copy) into a chat or a community. Nothing private rides along: the message or post stores
     * only the event's id, and every reader's card is built for that reader, so someone who may not see the event sees
     * "not available". Events that belong to a community are shared inside that community only.
     */
    {
      app.post('/events/:eventId/share', async (req, reply) => {
        const me = await ctx.auth(req);
        const id = param(req);
        const b = shareSchema.parse(req.body ?? {});
        ctx.limit(`event-share:${me.id}`, 30, 3600);
        const ev = await getEvent(me, id);
        if (ev.status === 'draft') throw new HttpError(409, 'not_published', 'Publish the event before sharing it.');
        if (ev.status === 'cancelled' || ev.status === 'completed' || new Date(ev.ends_at as string) <= new Date()) throw new HttpError(409, 'event_closed', 'This event is over or cancelled.');
        if (ev.hidden_at) throw new HttpError(409, 'event_hidden', 'This event is hidden by moderators.');
        if (b.target === 'chat') {
          if (ev.visibility === 'community') throw new HttpError(403, 'share_restricted', 'This event is only for community members, so it can only be shared inside that community.');
          const sent = await sendMessage(ctx, hub, me, b.targetId, { content: b.text }, { eventId: id });
          return reply.status(201).send({ kind: 'message', messageId: sent.message.id });
        }
        if (ev.visibility === 'community' && ev.community_id !== b.targetId) throw new HttpError(403, 'share_restricted', 'This event is only for community members, so it can only be shared inside that community.');
        const postId = await withTx(pool, async (client) => {
          await assertMayPostInCommunity(client, me.id, me.universityId, b.targetId);
          return (await client.query<{ id: string }>(
            "insert into posts (author_id, text, audience, community_id, shared_event_id) values ($1, $2, 'community', $3, $4) returning id", [me.id, b.text, b.targetId, id])).rows[0].id;
        });
        return reply.status(201).send({ kind: 'post', postId });
      });
    }

    // ---------- stage 6: moderator view of one event (the report queue only has a title)

    app.get('/moderation/events/:eventId', async (req) => {
      const me = await ctx.auth(req);
      if (me.role !== 'moderator' && me.role !== 'admin') throw new HttpError(403, 'forbidden', 'This is only available to moderators.');
      const id = param(req);
      const r = (await pool.query(
        `select e.id, e.title, e.description, e.venue_name, e.venue_address, e.status, e.visibility, e.starts_at, e.ends_at, e.hidden_at, e.community_id,
                o.id as organizer_id, o.full_name as organizer_name, o.handle as organizer_handle, (o.suspended_until is not null and o.suspended_until > now()) as organizer_suspended,
                (select count(*) from reports x where x.target_type = 'event' and x.target_id = e.id)::int as report_count
           from events e join users o on o.id = e.organizer_id where e.id = $1 and e.university_id = $2`, [id, me.universityId])).rows[0];
      if (!r) throw new HttpError(404, 'not_found', 'Not found.');
      return {
        id: r.id, title: r.title, description: r.description, venueName: r.venue_name, venueAddress: r.venue_address, status: r.status, visibility: r.visibility,
        startsAt: r.starts_at, endsAt: r.ends_at, hidden: r.hidden_at !== null, communityId: r.community_id, reportCount: r.report_count,
        organizer: { id: r.organizer_id, name: r.organizer_name, handle: r.organizer_handle, suspended: r.organizer_suspended },
      };
    });

    // Cancelling keeps the event (people can see it was cancelled) and tells everyone who was going.
    app.post('/events/:eventId/cancel', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      await withTx(pool, async (client) => {
        await manageable(me, id, client);
        const res = await client.query("update events set status = 'cancelled', updated_at = now() where id = $1 and status = 'published' and ends_at > now() returning id", [id]);
        if (!res.rowCount) throw new HttpError(409, 'event_closed', 'This event cannot be cancelled.');
        await notifyParticipants(client, id, 'cancelled', 'event_cancellations', me.id);
        await audit(client, id, me.id, 'cancel');
      });
      return reply.status(204).send();
    });

    // Deleting is only for events nobody else joined, or that are already cancelled; otherwise cancel.
    app.delete('/events/:eventId', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      await withTx(pool, async (client) => {
        const row = await manageable(me, id, client);
        // A cancelled event can always be removed: everyone was already told, and keeping it only clutters the host's list.
        const others = row.status === 'cancelled' ? { rowCount: 0 } : await client.query("select 1 from event_registrations where event_id = $1 and status = 'registered' and user_id <> $2 limit 1", [id, me.id]);
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
      const ev = (await client.query<{ title: string; organizer_id: string; capacity: number | null }>('select title, organizer_id, capacity from events where id = $1', [id])).rows[0];
      // Confirmation to the person, at most once per ten minutes however often they toggle.
      await notifyOnce(client, { recipientId: me.id, actorId: null, type: 'event_update', entityId: id, kind: 'confirmed', metadata: { title: ev.title }, preference: 'event_confirmations' }, { withinMinutes: 10 });
      // The organizer is told at milestones and when the event fills, not for every person.
      const count = going + 1;
      const filled = ev.capacity !== null && count >= ev.capacity;
      if (ev.organizer_id !== me.id && (MILESTONES.has(count) || filled)) {
        await notifyOnce(client, { recipientId: ev.organizer_id, actorId: null, type: 'event_update', entityId: id, kind: 'registrations', metadata: { title: ev.title, count, full: filled }, preference: 'event_organizer_updates' }, { count });
      }
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

    // Silence (or restore) reminders for one event the person registered for.
    app.put('/events/:eventId/reminders', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const { enabled } = z.object({ enabled: z.boolean() }).strict().parse(req.body ?? {});
      const res = await pool.query("update event_registrations set reminders = $3 where event_id = $1 and user_id = $2 and status = 'registered'", [id, me.id, enabled]);
      if (!res.rowCount) throw new HttpError(409, 'not_registered', 'Reminders are for events you are going to.');
      return reply.status(204).send();
    });

    // Finer event notification settings. The general `events` and `push_events` switches still apply on top of these.
    const eventPrefs = z.object({
      confirmations: z.boolean(), reminders: z.boolean(), changes: z.boolean(), cancellations: z.boolean(), community: z.boolean(),
      organizerUpdates: z.boolean(), reminderOffsets: z.array(z.union([z.literal(1440), z.literal(60)])).max(2),
    }).partial().strict();
    const EVENT_PREF_SQL = `select event_confirmations, event_reminders, event_changes, event_cancellations, event_community, event_organizer_updates, event_reminder_offsets
                              from notification_preferences where user_id = $1`;
    const eventPrefsOf = (r?: Record<string, unknown>) => ({
      confirmations: r?.event_confirmations ?? true, reminders: r?.event_reminders ?? true, changes: r?.event_changes ?? true,
      cancellations: r?.event_cancellations ?? true, community: r?.event_community ?? true, organizerUpdates: r?.event_organizer_updates ?? true,
      reminderOffsets: ((r?.event_reminder_offsets as number[] | undefined) ?? [1440, 60]).slice().sort((a, b) => b - a),
    });
    app.get('/me/event-notification-preferences', async (req) => {
      const me = await ctx.auth(req);
      return eventPrefsOf((await pool.query(EVENT_PREF_SQL, [me.id])).rows[0]);
    });
    app.put('/me/event-notification-preferences', async (req) => {
      const me = await ctx.auth(req);
      const b = eventPrefs.parse(req.body ?? {});
      ctx.limit(`event-prefs:${me.id}`, 60, 3600);
      await pool.query('insert into notification_preferences (user_id) values ($1) on conflict (user_id) do nothing', [me.id]);
      await pool.query(
        `update notification_preferences set
           event_confirmations = coalesce($2, event_confirmations), event_reminders = coalesce($3, event_reminders),
           event_changes = coalesce($4, event_changes), event_cancellations = coalesce($5, event_cancellations),
           event_community = coalesce($6, event_community), event_organizer_updates = coalesce($7, event_organizer_updates),
           event_reminder_offsets = coalesce($8::int[], event_reminder_offsets)
         where user_id = $1`,
        [me.id, b.confirmations ?? null, b.reminders ?? null, b.changes ?? null, b.cancellations ?? null, b.community ?? null, b.organizerUpdates ?? null,
          b.reminderOffsets ? [...new Set(b.reminderOffsets)] : null]);
      return eventPrefsOf((await pool.query(EVENT_PREF_SQL, [me.id])).rows[0]);
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
/**
 * Sends the reminders that are due. Safe to run as often as you like: a reminder is recorded when sent and never repeats.
 * - Only people who are registered (going) get reminders, and only for events that are still published and have not started.
 *   Interested people are told about changes and cancellations instead.
 * - Each person's own offsets are used (default 24 hours and 1 hour; they may keep one or none), and they can silence one event.
 * - A reminder is only sent in the half hour after its moment, so someone who registers late is not sent a 24-hour reminder
 *   for an event that starts in five hours, and a missed run recovers on the next one.
 * - A cancelled, completed or ended event, or a person who withdrew, is never reminded. Moving the start clears the record.
 * Times are absolute instants, so time zones and daylight saving do not shift them.
 */
export async function sendEventReminders(ctx: Ctx): Promise<number> {
  return withTx(ctx.pool, async (client) => {
    const due = await client.query<{ event_id: string; user_id: string; offset_minutes: number; title: string }>(
      `with picks as (
         select r.event_id, r.user_id, o.offset_minutes
           from event_registrations r
           join events e on e.id = r.event_id
           left join notification_preferences np on np.user_id = r.user_id
           cross join lateral unnest(coalesce(np.event_reminder_offsets, array[1440, 60])) as o(offset_minutes)
          where r.status = 'registered' and r.reminders and e.status = 'published' and e.hidden_at is null and e.starts_at > now()
            and not exists (select 1 from users eo where eo.id = e.organizer_id and ((eo.suspended_until is not null and eo.suspended_until > now()) or eo.deactivated_at is not null))
            and e.starts_at - make_interval(mins => o.offset_minutes) <= now()
            and e.starts_at - make_interval(mins => o.offset_minutes) > now() - interval '30 minutes')
       insert into event_reminders (event_id, user_id, offset_minutes)
       select event_id, user_id, offset_minutes from picks
       on conflict do nothing
       returning event_id, user_id, offset_minutes, (select title from events where id = event_reminders.event_id) as title`);
    for (const r of due.rows) {
      await notify(client, {
        recipientId: r.user_id, actorId: null, type: 'event_update', entityType: 'event', entityId: r.event_id,
        metadata: { kind: 'reminder', title: r.title, offsetMinutes: r.offset_minutes }, preference: 'event_reminders',
      });
    }
    return due.rowCount ?? 0;
  });
}
