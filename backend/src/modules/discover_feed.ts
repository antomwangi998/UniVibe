import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { blockedBetween, crossCampusOk, inScope, notSuspended, resolveScope } from '../access';
import type { AuthUser, Ctx } from '../context';
import { HttpError } from '../errors';
import { decodeCursor, tsText } from '../http';
import { communityNotMuted, discoverAudience } from './community_state';
import { POST_COLS, POST_FROM, type PostRow, presentPosts, visible } from '../posts';

/**
 * Discover content: a ranked For You feed, Trending, photo and video grids, and topics.
 *
 * Everything returned is a post the student could already see in the Home feed (same `visible` rule), limited to
 * campus-wide posts so followers-only posts never surface to strangers. Ranking is a plain, documented formula:
 * no machine learning, and only signals that exist in the database (see SCORE below).
 */

const WINDOWS = { '24h': 24, '7d': 168 } as const;
const FEED_WINDOW_HOURS = 14 * 24;
const PER_AUTHOR_FEED = 3; // one author may fill at most this many slots of the whole feed
const PER_AUTHOR_TRENDING = 2;
const TRENDING_MIN_PEOPLE = 2; // a post needs two different people interacting before it can trend
const TRENDING_LIST_SIZE = 100;
const CACHE_SECONDS = 60;
const TOPIC_CACHE_SECONDS = 120;
const TOPIC_MIN_AUTHORS = 2; // a topic needs two different authors, so one account cannot make a hashtag trend

const tagRe = /^[a-z0-9_]{2,30}$/;
const normalizeTag = (raw: string) => raw.trim().replace(/^#/, '').toLowerCase();
const tagSchema = z.string().transform(normalizeTag).refine((t) => tagRe.test(t), 'Not a valid topic.');

const limitSchema = z.coerce.number().int().min(1).max(30).default(15);
/** `home` (default), `all`, or one university id; see `resolveScope`. */
const campusScopeSchema = z.string().trim().min(1).max(64).optional();
const scoredCursor = z.object({ s: z.string().regex(/^-?\d{1,6}(\.\d{1,9})?$/), id: z.string().uuid(), a: z.string().refine((v) => !Number.isNaN(Date.parse(v))) });
type ScoredCursor = z.infer<typeof scoredCursor>;

function readScoredCursor(raw: string | undefined): ScoredCursor | null {
  if (!raw) return null;
  try {
    const parsed = scoredCursor.safeParse(JSON.parse(Buffer.from(raw, 'base64url').toString('utf8')));
    if (parsed.success) return parsed.data;
  } catch { /* fall through */ }
  throw new HttpError(400, 'invalid_cursor', 'That page cursor is not valid.');
}
const writeScoredCursor = (c: ScoredCursor) => Buffer.from(JSON.stringify(c)).toString('base64url');

/** Things this student asked not to see. $1 = viewer. Uses post alias p. */
const NOT_HIDDEN = `
  (p.audience <> 'community' or ${communityNotMuted('p')}) and
  not exists (select 1 from discover_feedback f where f.user_id = $1 and (
       (f.kind = 'not_interested' and f.post_id = p.id) or (f.kind = 'hide_author' and f.author_id = p.author_id)))
  and not exists (select 1 from discover_feedback f where f.user_id = $1 and f.kind = 'mute_topic'
       and lower(p.text) ~ ('(^|[^a-z0-9_])#' || f.topic || '($|[^a-z0-9_])'))`;

/**
 * SCORE for post p as of time $3 (a fixed instant, so pages of one feed never reshuffle):
 *   engagement = ln(1 + people who liked) + 1.5 * ln(1 + people who commented) + 0.3 * ln(1 + min(shares, 20))
 *   velocity   = ln(1 + likes in the last 6h) + 1.5 * ln(1 + people who commented in the last 6h)
 *   score      = (0.6 * engagement + velocity + 0.4 + personal) / (1 + hours_old / 12) ^ 1.2 / (1 + reports)
 * Likes and commenters are counted per person, so one account repeating itself adds nothing. The 0.4 base keeps a
 * brand-new post with no engagement visible, and the age decay lets fresh posts overtake old popular ones.
 * `personal` (For You only) adds 0.5 for an author the student follows and 0.4 for a match with their own interests.
 */
const candidateSql = (personal: boolean, audienceOnlyCampus = true) => `
  select p.id, p.author_id, p.created_at,
    likers, commenters, recent_likes, recent_commenters,
    followed, interest_match,
    round((((0.6 * (ln(1 + likers) + 1.5 * ln(1 + commenters) + 0.3 * ln(1 + least(p.share_count, 20)))
        + (ln(1 + recent_likes) + 1.5 * ln(1 + recent_commenters)) + 0.4
        ${personal ? '+ (case when followed then 0.5 else 0 end) + (case when interest_match then 0.4 else 0 end) + (case when a.university_id = $2 then 0.3 else 0 end)' : ''})
      / power(1 + extract(epoch from ($3::timestamptz - p.created_at)) / 3600.0 / 12.0, 1.2)
      / (1 + reports)))::numeric, 9) as score
  from posts p join users a on a.id = p.author_id
  cross join lateral (select
      (select count(distinct l.user_id) from post_likes l where l.post_id = p.id and l.created_at <= $3 and l.user_id <> p.author_id) as likers,
      (select count(distinct c.author_id) from comments c where c.post_id = p.id and c.status = 'active' and c.created_at <= $3 and c.author_id <> p.author_id) as commenters,
      (select count(*) from post_likes l where l.post_id = p.id and l.created_at <= $3 and l.created_at > $3::timestamptz - interval '6 hours' and l.user_id <> p.author_id) as recent_likes,
      (select count(distinct c.author_id) from comments c where c.post_id = p.id and c.status = 'active' and c.created_at <= $3 and c.created_at > $3::timestamptz - interval '6 hours' and c.author_id <> p.author_id) as recent_commenters,
      (select count(*) from post_reports r where r.post_id = p.id) as reports,
      exists (select 1 from follows f where f.follower_id = $1 and f.followee_id = p.author_id) as followed,
      exists (select 1 from users me, unnest(me.interests) i where me.id = $1 and length(i) >= 2 and position(lower(i) in lower(p.text)) > 0) as interest_match
  ) sig
  where ${visible('p', 'a')} and ${inScope('a', '$8')} ${audienceOnlyCampus ? `and ${discoverAudience('p')}` : ''}
    and p.created_at <= $3 and p.created_at > $3::timestamptz - make_interval(hours => $4::int)
    and ${NOT_HIDDEN}`;

type Reason = 'following' | 'interests' | 'trending_campus' | 'popular_campus' | null;
const reasonOf = (r: { followed: boolean; interest_match: boolean; recent_likes: number; recent_commenters: number; likers: number; commenters: number }): Reason => {
  if (r.followed) return 'following';
  if (r.interest_match) return 'interests';
  if (Number(r.recent_likes) + Number(r.recent_commenters) >= 3) return 'trending_campus';
  if (Number(r.likers) + Number(r.commenters) >= 3) return 'popular_campus';
  return null; // never claim a reason the data does not support
};

interface TtlEntry<T> { exp: number; value: T }
class TtlCache<T> {
  private m = new Map<string, TtlEntry<T>>();
  constructor(private max = 200) {}
  get(k: string): T | undefined {
    const e = this.m.get(k);
    if (!e) return undefined;
    if (e.exp < Date.now()) { this.m.delete(k); return undefined; }
    return e.value;
  }
  set(k: string, value: T, seconds: number) {
    if (this.m.size >= this.max) this.m.delete(this.m.keys().next().value as string);
    this.m.set(k, { exp: Date.now() + seconds * 1000, value });
  }
}

interface RankedRef { id: string; author_id: string; score: string }
interface TrendingList { asOf: string; refs: RankedRef[] }
const cmpDesc = (a: RankedRef, b: RankedRef) => (Number(b.score) - Number(a.score)) || (a.id < b.id ? 1 : -1);

const feedbackSchema = z.discriminatedUnion('kind', [
  z.object({ kind: z.literal('not_interested'), postId: z.string().uuid() }),
  z.object({ kind: z.literal('hide_author'), authorId: z.string().uuid() }),
  z.object({ kind: z.literal('mute_topic'), topic: tagSchema }),
]);

export function discoverFeedRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;
    const trendingCache = new TtlCache<TrendingList>();
    const topicCache = new TtlCache<{ tag: string; posts: number; authors: number }[]>();

    /** Full post JSON (the same shape as /posts) for ids already chosen, in the order given. Re-checks visibility. */
    async function present(me: AuthUser, ids: string[], extra: Map<string, Reason> = new Map()) {
      if (ids.length === 0) return [];
      const { rows } = await pool.query<PostRow>(
        `select ${POST_COLS} ${POST_FROM} where p.id = any($3::uuid[]) and ${visible('p', 'a')}`,
        [me.id, me.universityId, ids],
      );
      const byId = new Map(rows.map((r) => [r.id, r]));
      const ordered = ids.map((i) => byId.get(i)).filter((r): r is PostRow => !!r);
      const out = await presentPosts(pool, ctx.storage, ordered, { id: me.id, universityId: me.universityId });
      return out.map((p) => ({ ...p, reason: extra.get(p.id) ?? null }));
    }

    // ---- For You: personalised, ranked, stable pages
    app.get('/discover/feed', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`discover-feed:${me.id}`, 240, 600);
      const q = z.object({ limit: limitSchema, cursor: z.string().max(400).optional(), campusScope: campusScopeSchema }).parse(req.query);
      const cur = readScoredCursor(q.cursor);
      const asOf = cur?.a ?? new Date().toISOString();
      const { rows } = await pool.query(
        `with cand as (${candidateSql(true)} and p.author_id <> $1),
              ranked as (select *, row_number() over (partition by author_id order by score desc, id desc) as rn from cand)
         select id, score::text as score, followed, interest_match, recent_likes, recent_commenters, likers, commenters
           from ranked
          where rn <= ${PER_AUTHOR_FEED} and ($5::numeric is null or (score, id) < ($5::numeric, $6::uuid))
          order by score desc, id desc limit $7`,
        [me.id, me.universityId, asOf, FEED_WINDOW_HOURS, cur?.s ?? null, cur?.id ?? null, q.limit + 1, resolveScope(q.campusScope, me.universityId)],
      );
      const more = rows.length > q.limit;
      const page = more ? rows.slice(0, q.limit) : rows;
      const last = page[page.length - 1];
      const reasons = new Map<string, Reason>(page.map((r) => [r.id, reasonOf(r)]));
      return {
        items: await present(me, page.map((r) => r.id), reasons),
        nextCursor: more && last ? writeScoredCursor({ s: last.score, id: last.id, a: asOf }) : null,
      };
    });

    // ---- Trending: one shared, short-lived ranking per campus scope; each student's own filters are applied on top
    app.get('/discover/trending', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`discover-trending:${me.id}`, 240, 600);
      const q = z.object({ window: z.enum(['24h', '7d']).default('24h'), limit: limitSchema, cursor: z.string().max(400).optional(), campusScope: campusScopeSchema }).parse(req.query);
      const cur = readScoredCursor(q.cursor);
      const scope = resolveScope(q.campusScope, me.universityId);
      const key = `${scope}|${q.window}`;
      let list = trendingCache.get(key);
      if (!list) {
        // Viewer-independent: this university's active campus posts. No blocks, feedback or private audiences here;
        // those are applied per student below, and the posts are re-checked when presented.
        const asOf = new Date().toISOString();
        const { rows } = await pool.query<RankedRef>(
          `with cand as (
             select p.id, p.author_id, p.created_at, likers, commenters,
               round((((0.6 * (ln(1 + likers) + 1.5 * ln(1 + commenters) + 0.3 * ln(1 + least(p.share_count, 20)))
                  + (ln(1 + recent_likes) + 1.5 * ln(1 + recent_commenters)) + 0.4)
                 / power(1 + extract(epoch from ($3::timestamptz - p.created_at)) / 3600.0 / 12.0, 1.2)
                 / (1 + reports)))::numeric, 9) as score
             from posts p join users a on a.id = p.author_id
             cross join lateral (select
                (select count(distinct l.user_id) from post_likes l where l.post_id = p.id and l.created_at <= $3 and l.user_id <> p.author_id) as likers,
                (select count(distinct c.author_id) from comments c where c.post_id = p.id and c.status = 'active' and c.created_at <= $3 and c.author_id <> p.author_id) as commenters,
                (select count(*) from post_likes l where l.post_id = p.id and l.created_at <= $3 and l.created_at > $3::timestamptz - interval '6 hours' and l.user_id <> p.author_id) as recent_likes,
                (select count(distinct c.author_id) from comments c where c.post_id = p.id and c.status = 'active' and c.created_at <= $3 and c.created_at > $3::timestamptz - interval '6 hours' and c.author_id <> p.author_id) as recent_commenters,
                (select count(*) from post_reports r where r.post_id = p.id) as reports) sig
            where $1::uuid is not null and p.status = 'active' and ${discoverAudience('p')} and ${inScope('a', '$2')} and ${notSuspended('a')}
              and p.created_at <= $3 and p.created_at > $3::timestamptz - make_interval(hours => $4::int))
           select id, author_id, score::text as score from cand
            where likers + commenters >= ${TRENDING_MIN_PEOPLE}
            order by score desc, id desc limit ${TRENDING_LIST_SIZE}`,
          [me.id, scope, asOf, WINDOWS[q.window]], // $2 is the resolved campus scope; $1 is unused by the ranking: the list is the same for everyone
        );
        list = { asOf, refs: rows };
        trendingCache.set(key, list, CACHE_SECONDS);
      }
      // This student's own restrictions, applied fresh on every request (a block or removal is never cached).
      const { rows: ok } = await pool.query<{ id: string }>(
        `select p.id from posts p join users a on a.id = p.author_id
          where p.id = any($3::uuid[]) and ${visible('p', 'a')} and ${discoverAudience('p')} and ${NOT_HIDDEN}`,
        [me.id, me.universityId, list.refs.map((r) => r.id)],
      );
      const allowed = new Set(ok.map((r) => r.id));
      const perAuthor = new Map<string, number>();
      const eligible = list.refs.filter((r) => allowed.has(r.id)).sort(cmpDesc).filter((r) => {
        const n = (perAuthor.get(r.author_id) ?? 0) + 1;
        perAuthor.set(r.author_id, n);
        return n <= PER_AUTHOR_TRENDING;
      });
      const start = cur ? eligible.findIndex((r) => Number(r.score) < Number(cur.s) || (Number(r.score) === Number(cur.s) && r.id < cur.id)) : 0;
      const slice = start < 0 ? [] : eligible.slice(start, start + q.limit);
      const next = start >= 0 && start + q.limit < eligible.length ? slice[slice.length - 1] : undefined;
      return {
        items: (await present(me, slice.map((r) => r.id))).map((p) => ({ ...p, reason: 'trending_campus' as Reason })),
        nextCursor: next ? writeScoredCursor({ s: next.score, id: next.id, a: list.asOf }) : null,
      };
    });

    // ---- Photos and videos: newest campus posts that carry that kind of media
    for (const [path, kind] of [['photos', 'image'], ['videos', 'video']] as const) {
      app.get(`/discover/${path}`, async (req) => {
        const me = await ctx.auth(req);
        ctx.limit(`discover-${path}:${me.id}`, 240, 600);
        const q = z.object({ limit: limitSchema, cursor: z.string().max(300).optional(), campusScope: campusScopeSchema }).parse(req.query);
        const cur = decodeCursor(q.cursor);
        const { rows } = await pool.query<{ id: string; cursorT: string }>(
          `select p.id, ${tsText('p.created_at')} as "cursorT"
             from posts p join users a on a.id = p.author_id
            where ${visible('p', 'a')} and ${discoverAudience('p')} and ${inScope('a', '$7')} and p.author_id <> $1 and ${NOT_HIDDEN}
              and exists (select 1 from media m where m.post_id = p.id and m.status = 'ready' and m.kind = $3)
              and ($4::timestamptz is null or (p.created_at, p.id) < ($4::timestamptz, $5::uuid))
            order by p.created_at desc, p.id desc limit $6`,
          [me.id, me.universityId, kind, cur?.t ?? null, cur?.id ?? null, q.limit + 1, resolveScope(q.campusScope, me.universityId)],
        );
        const more = rows.length > q.limit;
        const page = more ? rows.slice(0, q.limit) : rows;
        const last = page[page.length - 1];
        return {
          items: await present(me, page.map((r) => r.id)),
          nextCursor: more && last ? Buffer.from(JSON.stringify({ t: last.cursorT, id: last.id })).toString('base64url') : null,
        };
      });
    }

    // ---- Topics: hashtags used by at least two different authors recently
    app.get('/discover/topics', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`discover-topics:${me.id}`, 120, 600);
      const q = z.object({ window: z.enum(['24h', '7d']).default('7d'), limit: z.coerce.number().int().min(1).max(50).default(30), campusScope: campusScopeSchema }).parse(req.query);
      const scope = resolveScope(q.campusScope, me.universityId);
      const key = `${scope}|${q.window}`;
      let topics = topicCache.get(key);
      if (!topics) {
        const { rows } = await pool.query<{ tag: string; posts: string; authors: string }>(
          `select m[1] as tag, count(distinct p.id) as posts, count(distinct p.author_id) as authors
             from posts p join users a on a.id = p.author_id,
                  lateral regexp_matches(lower(p.text), '(?:^|[^a-z0-9_])#([a-z0-9_]{2,30})', 'g') as m
            where p.status = 'active' and ${discoverAudience('p')} and ${inScope('a', '$1')} and ${notSuspended('a')}
              and p.created_at > now() - make_interval(hours => $2::int)
            group by m[1] having count(distinct p.author_id) >= ${TOPIC_MIN_AUTHORS}
            order by count(distinct p.author_id) * 2 + count(distinct p.id) desc, m[1] limit 100`,
          [scope, WINDOWS[q.window]],
        );
        topics = rows.map((r) => ({ tag: r.tag, posts: Number(r.posts), authors: Number(r.authors) }));
        topicCache.set(key, topics, TOPIC_CACHE_SECONDS);
      }
      const { rows: muted } = await pool.query<{ topic: string }>("select topic from discover_feedback where user_id = $1 and kind = 'mute_topic'", [me.id]);
      const hide = new Set(muted.map((m) => m.topic));
      return { items: topics.filter((t) => !hide.has(t.tag)).slice(0, q.limit), nextCursor: null };
    });

    app.get('/discover/topics/:tag', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`discover-topic:${me.id}`, 240, 600);
      const tag = tagSchema.safeParse((req.params as { tag: string }).tag);
      if (!tag.success) throw new HttpError(404, 'not_found', 'Not found.');
      const q = z.object({ limit: limitSchema, cursor: z.string().max(300).optional(), campusScope: campusScopeSchema }).parse(req.query);
      const cur = decodeCursor(q.cursor);
      const { rows } = await pool.query<{ id: string; cursorT: string }>(
        `select p.id, ${tsText('p.created_at')} as "cursorT"
           from posts p join users a on a.id = p.author_id
          where ${visible('p', 'a')} and ${discoverAudience('p')} and ${inScope('a', '$7')} and (p.audience <> 'community' or ${communityNotMuted('p')})
            and not exists (select 1 from discover_feedback f where f.user_id = $1 and ((f.kind = 'not_interested' and f.post_id = p.id) or (f.kind = 'hide_author' and f.author_id = p.author_id)))
            and lower(p.text) ~ ('(^|[^a-z0-9_])#' || $3::text || '($|[^a-z0-9_])')
            and ($4::timestamptz is null or (p.created_at, p.id) < ($4::timestamptz, $5::uuid))
          order by p.created_at desc, p.id desc limit $6`,
        [me.id, me.universityId, tag.data, cur?.t ?? null, cur?.id ?? null, q.limit + 1, resolveScope(q.campusScope, me.universityId)],
      );
      const more = rows.length > q.limit;
      const page = more ? rows.slice(0, q.limit) : rows;
      const last = page[page.length - 1];
      return {
        topic: tag.data,
        items: await present(me, page.map((r) => r.id)),
        nextCursor: more && last ? Buffer.from(JSON.stringify({ t: last.cursorT, id: last.id })).toString('base64url') : null,
      };
    });

    // ---- Feedback: private "not interested" signals that shape this student's own Discover only
    app.post('/discover/feedback', async (req, reply) => {
      const me = await ctx.auth(req);
      ctx.limit(`discover-feedback:${me.id}`, 200, 3600);
      const b = feedbackSchema.parse(req.body ?? {});
      if (b.kind === 'not_interested') {
        const ok = await pool.query(`select 1 from posts p join users a on a.id = p.author_id where p.id = $3 and ${visible('p', 'a')}`, [me.id, me.universityId, b.postId]);
        if (!ok.rowCount) throw new HttpError(404, 'not_found', 'That post is not available.');
        await pool.query("insert into discover_feedback (user_id, kind, post_id) values ($1, 'not_interested', $2) on conflict do nothing", [me.id, b.postId]);
      } else if (b.kind === 'hide_author') {
        if (b.authorId === me.id) throw new HttpError(400, 'invalid_request', 'You cannot hide yourself.');
        const ok = await pool.query(`select 1 from users u where u.id = $3 and ${crossCampusOk('u', '$2')} and not ${blockedBetween('$1', 'u.id')}`, [me.id, me.universityId, b.authorId]);
        if (!ok.rowCount) throw new HttpError(404, 'not_found', 'Not found.');
        await pool.query("insert into discover_feedback (user_id, kind, author_id) values ($1, 'hide_author', $2) on conflict do nothing", [me.id, b.authorId]);
      } else {
        await pool.query("insert into discover_feedback (user_id, kind, topic) values ($1, 'mute_topic', $2) on conflict do nothing", [me.id, b.topic]);
      }
      return reply.status(204).send();
    });

    // Undo. POST rather than DELETE-with-a-body, which some HTTP clients cannot send.
    app.post('/discover/feedback/undo', async (req, reply) => {
      const me = await ctx.auth(req);
      ctx.limit(`discover-feedback:${me.id}`, 200, 3600);
      const b = feedbackSchema.parse(req.body ?? {});
      if (b.kind === 'not_interested') await pool.query("delete from discover_feedback where user_id = $1 and kind = 'not_interested' and post_id = $2", [me.id, b.postId]);
      else if (b.kind === 'hide_author') await pool.query("delete from discover_feedback where user_id = $1 and kind = 'hide_author' and author_id = $2", [me.id, b.authorId]);
      else await pool.query("delete from discover_feedback where user_id = $1 and kind = 'mute_topic' and topic = $2", [me.id, b.topic]);
      return reply.status(204).send();
    });
  };
}
