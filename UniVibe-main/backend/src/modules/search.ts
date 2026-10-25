import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { userVisible } from '../access';
import type { Ctx } from '../context';
import { HttpError } from '../errors';
import { decodeCursor, pageSchema, toPage, tsText } from '../http';
import { visible } from '../posts';
import { cardColumns, cardJoin, type CardRow, toCard } from './cards';
import { listCommunities } from './communities';
import { eventTextMatch, eventVisible } from './events';

const termSchema = z.object({ q: z.string().trim().min(2, 'Type at least 2 characters.').max(80) });
/** The term is matched literally: % and _ typed by the user are not wildcards. Everything goes through parameters. */
const escapeLike = (q: string) => q.replace(/[\\%_]/g, '\\$&');

/** Search results, limited to what the searcher could already see elsewhere. Private messages are never searched. */
export function searchRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;

    const users = async (meId: string, uni: string, term: string, limit: number, after: { h: string; id: string } | null) => {
      const { rows } = await pool.query(
        `select u.id, lower(u.handle) as sort_handle, u.course, ${cardColumns('u')}
           from users u ${cardJoin('u')}
          where ${userVisible('u', '$1', '$2')} and u.id <> $1
            and (u.handle ilike $3 || '%' or u.full_name ilike '%' || $3 || '%')
            and ($4::text is null or (lower(u.handle), u.id) > ($4::text, $5::uuid))
          order by lower(u.handle), u.id limit $6`,
        [meId, uni, escapeLike(term), after?.h ?? null, after?.id ?? null, limit + 1],
      );
      const more = rows.length > limit;
      const page = more ? rows.slice(0, limit) : rows;
      const last = page[page.length - 1];
      return {
        items: await Promise.all(page.map(async (r) => ({ ...(await toCard(ctx, r as CardRow)), course: r.course }))),
        nextCursor: more && last ? Buffer.from(JSON.stringify({ h: last.sort_handle, id: last.id })).toString('base64url') : null,
      };
    };

    const posts = async (meId: string, uni: string, term: string, limit: number, cur: { t: string; id: string } | null) => {
      const { rows } = await pool.query(
        `select p.id, left(p.text, 200) as snippet, p.audience, p.created_at, ${tsText('p.created_at')} as "cursorT", ${cardColumns('a')}
           from posts p join users a on a.id = p.author_id ${cardJoin('a')}
          where ${visible('p', 'a')} and p.text ilike '%' || $3 || '%'
            and ($4::timestamptz is null or (p.created_at, p.id) < ($4::timestamptz, $5::uuid))
          order by p.created_at desc, p.id desc limit $6`,
        [meId, uni, escapeLike(term), cur?.t ?? null, cur?.id ?? null, limit + 1],
      );
      const page = toPage(rows as (CardRow & { cursorT: string; id: string })[], limit);
      return {
        items: await Promise.all(page.items.map(async (r) => {
          const row = r as unknown as CardRow & { id: string; snippet: string; audience: string; created_at: Date };
          return { id: row.id, snippet: row.snippet, audience: row.audience, createdAt: row.created_at, author: await toCard(ctx, row) };
        })),
        nextCursor: page.nextCursor,
      };
    };

    const events = async (meId: string, uni: string, term: string, limit: number, cur: { t: string; id: string } | null) => {
      const { rows } = await pool.query(
        `select e.id, e.title, e.venue_name, e.category, e.starts_at, ${tsText('e.starts_at')} as "cursorT"
           from events e
          where ${eventVisible('$1', '$2')} and e.status = 'published' and e.ends_at > now()
            and ${eventTextMatch('$3')}
            and ($4::timestamptz is null or (e.starts_at, e.id) > ($4::timestamptz, $5::uuid))
          order by e.starts_at, e.id limit $6`,
        [meId, uni, escapeLike(term), cur?.t ?? null, cur?.id ?? null, limit + 1],
      );
      const page = toPage(rows as { cursorT: string; id: string }[], limit);
      return {
        items: page.items.map((r) => {
          const row = r as unknown as { id: string; title: string; venue_name: string; category: string; starts_at: Date };
          return { id: row.id, title: row.title, venueName: row.venue_name, category: row.category, startsAt: row.starts_at };
        }),
        nextCursor: page.nextCursor,
      };
    };

    const userCursor = (raw: string | undefined) => {
      if (!raw) return null;
      try {
        const c = JSON.parse(Buffer.from(raw, 'base64url').toString('utf8'));
        if (typeof c.h === 'string' && z.string().uuid().safeParse(c.id).success) return c as { h: string; id: string };
      } catch { /* fall through */ }
      throw new HttpError(400, 'invalid_cursor', 'That page cursor is not valid.');
    };

    app.get('/search', async (req) => {
      const me = await ctx.auth(req);
      const { q } = termSchema.parse(req.query);
      ctx.limit(`search:${me.id}`, 60, 60);
      // The overview shows a few of each kind; use the typed endpoints to page through more.
      const [u, p, e, c] = await Promise.all([
        users(me.id, me.universityId, q, 5, null), posts(me.id, me.universityId, q, 5, null), events(me.id, me.universityId, q, 5, null),
        // Community profiles only (name, description, counts, my membership): never posts or members of private ones.
        listCommunities(ctx, me, { term: q, limit: 5 }),
      ]);
      return { users: u.items, posts: p.items, events: e.items, communities: c.items };
    });

    app.get('/search/users', async (req) => {
      const me = await ctx.auth(req);
      const { q } = termSchema.parse(req.query);
      const page = pageSchema.parse(req.query);
      ctx.limit(`search:${me.id}`, 60, 60);
      return users(me.id, me.universityId, q, page.limit, userCursor(page.cursor));
    });

    app.get('/search/posts', async (req) => {
      const me = await ctx.auth(req);
      const { q } = termSchema.parse(req.query);
      const page = pageSchema.parse(req.query);
      ctx.limit(`search:${me.id}`, 60, 60);
      return posts(me.id, me.universityId, q, page.limit, decodeCursor(page.cursor));
    });

    app.get('/search/communities', async (req) => {
      const me = await ctx.auth(req);
      const { q } = termSchema.parse(req.query);
      const page = pageSchema.parse(req.query);
      ctx.limit(`search:${me.id}`, 60, 60);
      return listCommunities(ctx, me, { term: q, limit: page.limit, cursor: page.cursor });
    });

    app.get('/search/events', async (req) => {
      const me = await ctx.auth(req);
      const { q } = termSchema.parse(req.query);
      const page = pageSchema.parse(req.query);
      ctx.limit(`search:${me.id}`, 60, 60);
      return events(me.id, me.universityId, q, page.limit, decodeCursor(page.cursor));
    });
  };
}
