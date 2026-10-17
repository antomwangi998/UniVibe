import type { FastifyInstance, FastifyRequest } from 'fastify';
import type { PoolClient } from 'pg';
import { z } from 'zod';
import { blockedBetween } from '../access';
import type { AuthUser, Ctx } from '../context';
import { withTx } from '../db';
import { HttpError } from '../errors';
import { decodeCursor, idParam, pageSchema, toPage, tsText } from '../http';
import { cardColumns, cardJoin, type CardRow, signedUrl, toCard } from './cards';
import { notify } from './notify';

export const CATEGORIES = ['academic', 'workshops', 'sports', 'social', 'clubs', 'technology', 'entrepreneurship', 'arts', 'other'] as const;
const MAX_DURATION_HOURS = 48;
const MIN_LEAD_MINUTES = 5;
const MAX_AHEAD_DAYS = 365;
const MAX_UPCOMING_PER_ORGANIZER = 5;
export const RETENTION_DAYS = 7;
const CHECK_IN_OPENS_MINUTES = 30;

const when = z.string().datetime({ offset: true });
const base = {
  title: z.string().trim().min(3, 'Title needs at least 3 characters.').max(80),
  description: z.string().trim().max(1000),
  category: z.enum(CATEGORIES),
  venueName: z.string().trim().min(2).max(80),
  startsAt: when, endsAt: when,
  capacity: z.number().int().min(1).max(5000).nullable(),
  visibility: z.enum(['public', 'university']),
  attendeesVisible: z.boolean(),
  coverMediaId: z.string().uuid().nullable(),
};
const createSchema = z.object({ ...base, description: base.description.default(''), capacity: base.capacity.default(null),
  visibility: base.visibility.default('university'), attendeesVisible: base.attendeesVisible.default(true), coverMediaId: base.coverMediaId.default(null) }).strict();
const patchSchema = z.object(base).partial().strict();
const listSchema = pageSchema.extend({
  scope: z.enum(['upcoming', 'past']).default('upcoming'),
  category: z.enum(CATEGORIES).optional(),
  from: when.optional(), to: when.optional(),
  q: z.string().trim().min(1).max(80).optional(),
});

type Req = FastifyRequest;
type Q = Pick<PoolClient, 'query'>;

/** Event visible to viewer ($1, university $2): own university, or public; organizer not blocked either way. Alias e. */
export const eventVisible = (viewer = '$1', uni = '$2') =>
  `(e.organizer_id = ${viewer} or ((e.university_id = ${uni} or e.visibility = 'public') and not ${blockedBetween(viewer, 'e.organizer_id')}))`;

function validateTimes(startsAt: Date, endsAt: Date, opts: { creating: boolean; startChanged: boolean }) {
  const now = Date.now();
  if (endsAt <= startsAt) throw new HttpError(422, 'validation', 'The event must end after it starts.');
  if (endsAt.getTime() - startsAt.getTime() > MAX_DURATION_HOURS * 3600_000) throw new HttpError(422, 'validation', `Events can last at most ${MAX_DURATION_HOURS} hours.`);
  if (opts.startChanged && startsAt.getTime() < now + MIN_LEAD_MINUTES * 60_000) throw new HttpError(422, 'validation', `Events must start at least ${MIN_LEAD_MINUTES} minutes from now.`);
  if (startsAt.getTime() > now + MAX_AHEAD_DAYS * 86400_000) throw new HttpError(422, 'validation', 'Events can be at most a year ahead.');
}

export function eventRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;
    const param = (req: Req, name = 'eventId') => idParam((req.params as Record<string, string>)[name]);

    const SELECT = `
      select e.*, ${tsText('e.starts_at')} as "cursorT", ${cardColumns('o')}, cv.object_key as cover_key,
             (select count(*) from event_registrations r where r.event_id = e.id and r.status = 'registered') as registered_count,
             (select r.status from event_registrations r where r.event_id = e.id and r.user_id = $1) as my_status,
             (select r.checked_in_at is not null from event_registrations r where r.event_id = e.id and r.user_id = $1 and r.status = 'registered') as my_checked_in
        from events e join users o on o.id = e.organizer_id ${cardJoin('o')}
        left join media cv on cv.id = e.cover_media_id and cv.status = 'ready'`;

    async function toView(me: AuthUser, r: Record<string, unknown>) {
      const registered = Number(r.registered_count);
      const capacity = r.capacity as number | null;
      return {
        id: r.id as string, organizer: await toCard(ctx, r as unknown as CardRow), title: r.title, description: r.description,
        category: r.category, venueName: r.venue_name, startsAt: r.starts_at, endsAt: r.ends_at, capacity,
        registeredCount: registered, spotsLeft: capacity === null ? null : Math.max(0, capacity - registered),
        visibility: r.visibility, attendeesVisible: r.attendees_visible, status: r.status,
        coverUrl: await signedUrl(ctx, r.cover_key as string | null),
        myRegistration: r.my_status === 'registered' ? (r.my_checked_in ? 'checked_in' : 'registered') : null,
        isOrganizer: r.organizer_id === me.id,
      };
    }

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
      const { rows } = await client.query<{ user_id: string }>(
        "select user_id from event_registrations where event_id = $1 and status = 'registered' and user_id <> $2", [eventId, me.id]);
      for (const r of rows) await notify(client as PoolClient, { recipientId: r.user_id, actorId: me.id, type: 'event_update', entityType: 'event', entityId: eventId, metadata: { kind } });
    }

    app.post('/events', async (req, reply) => {
      const me = await ctx.auth(req);
      const b = createSchema.parse(req.body ?? {});
      ctx.limit(`event:${me.id}`, 10, 3600);
      const starts = new Date(b.startsAt), ends = new Date(b.endsAt);
      validateTimes(starts, ends, { creating: true, startChanged: true });
      await coverOk(me, b.coverMediaId);
      const id = await withTx(pool, async (client) => {
        await client.query('select 1 from users where id = $1 for update', [me.id]); // serialises the per-organizer limit
        const open = Number((await client.query(
          "select count(*) from events where organizer_id = $1 and status = 'published' and ends_at > now()", [me.id])).rows[0].count);
        if (open >= MAX_UPCOMING_PER_ORGANIZER) throw new HttpError(409, 'too_many_events', `You can have up to ${MAX_UPCOMING_PER_ORGANIZER} upcoming events.`);
        return (await client.query<{ id: string }>(
          `insert into events (organizer_id, university_id, title, description, category, venue_name, cover_media_id, starts_at, ends_at, capacity, visibility, attendees_visible)
           values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12) returning id`,
          [me.id, me.universityId, b.title, b.description, b.category, b.venueName, b.coverMediaId, starts, ends, b.capacity, b.visibility, b.attendeesVisible])).rows[0].id;
      });
      return reply.status(201).send(await toView(me, await getEvent(me, id)));
    });

    app.get('/events', async (req) => {
      const me = await ctx.auth(req);
      const q = listSchema.parse(req.query);
      ctx.limit(`event-list:${me.id}`, 120, 600);
      const cur = decodeCursor(q.cursor);
      const upcoming = q.scope === 'upcoming';
      const { rows } = await pool.query(
        `${SELECT} where ${visible()}
            and (${upcoming ? 'e.ends_at > now()' : 'e.ends_at <= now()'})
            and ($3::text is null or e.category = $3)
            and ($4::timestamptz is null or e.starts_at >= $4) and ($5::timestamptz is null or e.starts_at <= $5)
            and ($6::text is null or e.title ilike '%' || $6 || '%' or e.venue_name ilike '%' || $6 || '%')
            and ($7::timestamptz is null or (e.starts_at, e.id) ${upcoming ? '>' : '<'} ($7::timestamptz, $8::uuid))
          order by e.starts_at ${upcoming ? 'asc' : 'desc'}, e.id ${upcoming ? 'asc' : 'desc'} limit $9`,
        [me.id, me.universityId, q.category ?? null, q.from ?? null, q.to ?? null, q.q ? q.q.replace(/[%_\\]/g, '\\$&') : null, cur?.t ?? null, cur?.id ?? null, q.limit + 1],
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
        `${SELECT} where $2::text is not null and (e.organizer_id = $1 or exists (select 1 from event_registrations r where r.event_id = e.id and r.user_id = $1 and r.status = 'registered'))
            and (${upcoming ? 'e.ends_at > now()' : 'e.ends_at <= now()'})
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
        if (row.status === 'cancelled') throw new HttpError(409, 'event_closed', 'A cancelled event cannot be edited.');
        if (new Date(row.ends_at) <= new Date()) throw new HttpError(409, 'event_closed', 'This event has ended.');
        const starts = b.startsAt ? new Date(b.startsAt) : new Date(row.starts_at);
        const ends = b.endsAt ? new Date(b.endsAt) : new Date(row.ends_at);
        // An unchanged start may stay, even if it is now less than five minutes away.
        validateTimes(starts, ends, { creating: false, startChanged: b.startsAt !== undefined && starts.getTime() !== new Date(row.starts_at).getTime() });
        if (b.capacity !== undefined && b.capacity !== null) {
          const going = Number((await client.query("select count(*) from event_registrations where event_id = $1 and status = 'registered'", [id])).rows[0].count);
          if (b.capacity < going) throw new HttpError(422, 'validation', `${going} people are already going, so capacity cannot be lower.`);
        }
        if (b.coverMediaId) await coverOk(me, b.coverMediaId);
        const cols: Record<string, string> = { title: 'title', description: 'description', category: 'category', venueName: 'venue_name', startsAt: 'starts_at', endsAt: 'ends_at', capacity: 'capacity', visibility: 'visibility', attendeesVisible: 'attendees_visible', coverMediaId: 'cover_media_id' };
        const sets: string[] = []; const values: unknown[] = [id];
        for (const [k, v] of Object.entries(b)) { values.push(k === 'startsAt' || k === 'endsAt' ? new Date(v as string) : v); sets.push(`${cols[k]} = $${values.length}`); }
        if (sets.length) await client.query(`update events set ${sets.join(', ')}, updated_at = now() where id = $1`, values);
        if (b.startsAt !== undefined || b.endsAt !== undefined || b.venueName !== undefined) await notifyRegistrants(client, me, id, 'changed');
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

    app.post('/events/:eventId/register', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      ctx.limit(`event-reg:${me.id}`, 30, 3600);
      const result = await withTx(pool, async (client) => {
        await getEvent(me, id, client); // 404 if the viewer may not see it
        // Locking the event row makes concurrent registrations line up, so the last place can only be taken once.
        const e = (await client.query('select capacity, status, ends_at from events where id = $1 for update', [id])).rows[0];
        if (e.status !== 'published' || new Date(e.ends_at) <= new Date()) throw new HttpError(409, 'event_closed', 'Registration is closed for this event.');
        const mine = (await client.query('select status from event_registrations where event_id = $1 and user_id = $2', [id, me.id])).rows[0];
        if (mine?.status === 'registered') return { created: false };
        const going = Number((await client.query("select count(*) from event_registrations where event_id = $1 and status = 'registered'", [id])).rows[0].count);
        if (e.capacity !== null && going >= e.capacity) throw new HttpError(409, 'event_full', 'This event is full.');
        await client.query(
          `insert into event_registrations (event_id, user_id) values ($1, $2)
           on conflict (event_id, user_id) do update set status = 'registered', registered_at = now(), cancelled_at = null`, [id, me.id]);
        return { created: true };
      });
      return reply.status(result.created ? 201 : 200).send({ registered: true });
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
    const due = await client.query<{ event_id: string; user_id: string; organizer_id: string }>(
      `insert into event_reminders (event_id, user_id)
       select r.event_id, r.user_id from event_registrations r join events e on e.id = r.event_id
        where r.status = 'registered' and e.status = 'published' and e.starts_at > now() and e.starts_at <= now() + interval '1 hour'
       on conflict do nothing returning event_id, user_id,
         (select organizer_id from events where id = event_reminders.event_id) as organizer_id`);
    for (const r of due.rows) {
      await notify(client, { recipientId: r.user_id, actorId: null, type: 'event_update', entityType: 'event', entityId: r.event_id, metadata: { kind: 'reminder' } });
    }
    return due.rowCount ?? 0;
  });
}
