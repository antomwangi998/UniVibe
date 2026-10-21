import type { FastifyInstance, FastifyRequest } from 'fastify';
import { blockedBetween, notSuspended } from './access';
import { enqueue } from './jobs';
import { notify, retract } from './modules/notify';
import { assertMayPostInCommunity, joinState } from './modules/community_state';
import { eventVisible, loadEventViews } from './modules/events';
import type { Pool } from 'pg';
import { z } from 'zod';
import { withTx } from './db';
import { HttpError } from './errors';
import { READ_LINK_SECONDS } from './media';
import type { Storage } from './storage';
import { AUTHOR_COLS, AuthorCols, RouteContext, SignedInUser, authorJson, isUniqueViolation, paramId, signAuthors } from './shared';

const TEXT_MAX = 280;
const POLL_MAX_HOURS = 72;
const text = z.string().trim().min(1, 'Write something first.').max(TEXT_MAX, `Keep it under ${TEXT_MAX} characters.`);

const pollSchema = z.object({
  options: z
    .array(z.string().trim().min(1, 'Poll options cannot be empty.').max(40, 'Keep poll options under 40 characters.'))
    .min(2, 'A poll needs at least 2 options.')
    .max(4, 'A poll can have at most 4 options.')
    .refine((o) => new Set(o.map((s) => s.toLowerCase())).size === o.length, 'Poll options must be different.'),
  /** How long voting stays open. Never more than 3 days. */
  durationHours: z.number().int().min(1, 'A poll must run for at least an hour.').max(POLL_MAX_HOURS, 'A poll can run for 3 days at most.').default(24),
});
// A post may have no text when it carries photos or a video.
const maybeText = z.string().trim().max(TEXT_MAX, `Keep it under ${TEXT_MAX} characters.`);
const createSchema = z.object({
  text: maybeText.default(''),
  audience: z.enum(['campus', 'followers']).default('campus'),
  /** Publish into a community instead of the personal feed. Membership and posting rights are checked on the server. */
  communityId: z.string().uuid().optional(),
  /** Moderators only: an announcement notifies members (subject to their settings) and is flagged in the feed. */
  announcement: z.boolean().optional(),
  poll: pollSchema.optional(),
  mediaIds: z.array(z.string().uuid()).max(4, 'You can add up to 4 photos.').optional(),
});
const editSchema = z.object({ text: maybeText });
const voteSchema = z.object({ optionIndex: z.number().int().min(0).max(3) });
const viewsSchema = z.object({ ids: z.array(z.string().uuid()).min(1).max(50) });
const reportSchema = z.object({ reason: z.string().trim().min(1).max(200) });
const feedQuery = z.object({
  filter: z.enum(['campus', 'following']).default('campus'),
  /** Page size. Without it the feed behaves as before (up to 100 posts at once). */
  limit: z.coerce.number().int().min(1).max(50).optional(),
  /** Id of the last post the app already has; the page continues with the posts just older than it. */
  before: z.string().uuid().optional(),
  /** Only this student's posts (a profile). The usual visibility rules still apply. */
  author: z.string().uuid().optional(),
});
const savedQuery = z.object({
  limit: z.coerce.number().int().min(1).max(50).optional(),
  /** Id of the last saved post the app already has. */
  before: z.string().uuid().optional(),
});
const commentSchema = z.object({ text, replyToId: z.string().uuid().optional() });

/**
 * Who may see a post. $1 = the viewer's id, $2 = the viewer's university id.
 * Authors always see their own posts. Campus posts stay inside the author's university.
 * Followers-only posts need a follow. The server is the only place this is enforced.
 */
export const visible = (p: string, author: string) =>
  `(${p}.status = 'active' and (${p}.event_id is null or exists (select 1 from events pe where pe.id = ${p}.event_id and ${eventVisible('$1', '$2', 'pe')})) ` +
  // A post that shares an event needs the event to be visible to the reader too (hidden, private or deleted events drop the post).
  `and (${p}.shared_event_id is null or exists (select 1 from events ps where ps.id = ${p}.shared_event_id and ${eventVisible('$1', '$2', 'ps')})) and (` +
  // Personal-feed posts: unchanged rules.
  `(${p}.audience <> 'community' and (${p}.author_id = $1 or (` +
  `((${p}.audience = 'campus' and ${author}.university_id = $2) or ` +
  `(${p}.audience = 'followers' and exists (select 1 from follows f where f.follower_id = $1 and f.followee_id = ${p}.author_id))) ` +
  `and ${notSuspended(author)} and not ${blockedBetween('$1', `${p}.author_id`)}))) or ` +
  // Community posts: the community must be live and readable by the viewer. Authorship alone is not enough, so a
  // person who left a private community (or was removed) stops seeing their own posts there.
  `(${p}.audience = 'community' and ${communityReadable(p)} and ` +
  `(${p}.author_id = $1 or (${notSuspended(author)} and not ${blockedBetween('$1', `${p}.author_id`)})))))`;

/**
 * May the viewer read posts of the community this post belongs to? $1 = viewer, $2 = viewer's university.
 * Same university only. Public: anyone. Private: active members only. Suspended and deleted communities are hidden.
 */
export const communityReadable = (p: string) =>
  `exists (select 1 from communities rc where rc.id = ${p}.community_id and rc.status in ('active', 'archived') ` +
  `and rc.university_id = $2 and (rc.visibility = 'public' or exists ` +
  `(select 1 from community_members rm where rm.community_id = rc.id and rm.user_id = $1 and rm.status = 'active')))`;

/** Comments the viewer may see: not removed, not written by someone blocked in either direction, and not by a deactivated account. */
export const commentShown = (c: string) =>
  `(${c}.status = 'active' and (${c}.author_id = $1 or (not ${blockedBetween('$1', `${c}.author_id`)} and not exists (select 1 from users cdu where cdu.id = ${c}.author_id and cdu.deactivated_at is not null))))`;

export const POST_COLS =
  'p.id, p.text, p.audience, p.community_id, p.event_id, p.shared_event_id, p.is_announcement, p.share_count, p.poll_options, p.created_at, p.edited_at, ' +
  "case when p.poll_options is null then null else greatest(0, floor(extract(epoch from (p.poll_ends_at - now()))))::int end as poll_seconds_left, " +
  'floor(extract(epoch from (now() - p.created_at)))::int as age_seconds, ' +
  AUTHOR_COLS +
  ', pc.name as c_name, pc.slug as c_slug, pc.visibility as c_visibility, pc.membership_policy as c_policy' +
  ', pc.member_count as c_members, pc.status as c_status' +
  ", (select m.object_key from media m where m.id = pc.avatar_media_id and m.status = 'ready') as c_avatar_key" +
  ", (select cm.role from community_members cm where cm.community_id = pc.id and cm.user_id = $1 and cm.status = 'active') as c_my_role" +
  ", exists (select 1 from community_members cm where cm.community_id = pc.id and cm.user_id = $1 and cm.status = 'suspended') as c_suspended" +
  ", exists (select 1 from community_requests cr where cr.community_id = pc.id and cr.user_id = $1 and cr.status = 'pending') as c_pending" +
  ", exists (select 1 from community_invitations ci where ci.community_id = pc.id and ci.invitee_id = $1 and ci.status = 'pending' and ci.expires_at > now()) as c_invited" +
  ', (select count(*) from post_likes l where l.post_id = p.id)::int as like_count' +
  `, (select count(*) from comments c where c.post_id = p.id and ${commentShown('c')})::int as comment_count` +
  ', exists (select 1 from post_likes l where l.post_id = p.id and l.user_id = $1) as liked' +
  ', exists (select 1 from post_saves s where s.post_id = p.id and s.user_id = $1) as saved' +
  ', (select count(*) from post_reposts rp where rp.post_id = p.id)::int as repost_count' +
  ', exists (select 1 from post_reposts rp where rp.post_id = p.id and rp.user_id = $1) as reposted' +
  ', (select count(*) from post_views pv where pv.post_id = p.id)::int as view_count' +
  ', (select v.option_index from poll_votes v where v.post_id = p.id and v.user_id = $1) as my_vote' +
  ", (select coalesce(json_object_agg(t.option_index, t.n), '{}'::json) from " +
  '(select option_index, count(*)::int as n from poll_votes where post_id = p.id group by option_index) t) as vote_counts';

export const POST_FROM =
  'from posts p join users a on a.id = p.author_id join universities uni on uni.id = a.university_id ' +
  'left join communities pc on pc.id = p.community_id';

export type PostRow = AuthorCols & {
  id: string; text: string; audience: string; community_id: string | null; event_id: string | null; shared_event_id: string | null; is_announcement: boolean; share_count: number; poll_options: string[] | null;
  created_at: Date; edited_at: Date | null; age_seconds: number;
  c_name: string | null; c_slug: string | null; c_visibility: string | null; c_policy: string | null; c_members: number | null;
  c_status: string | null; c_avatar_key: string | null; c_avatar_url?: string | null;
  c_my_role: string | null; c_suspended: boolean; c_pending: boolean; c_invited: boolean;
  like_count: number; comment_count: number; liked: boolean; saved: boolean;
  repost_count: number; reposted: boolean; view_count: number; poll_seconds_left: number | null;
  my_vote: number | null; vote_counts: Record<string, number> | null;
};

type MediaOut = { id: string; kind: string; url: string; thumbUrl: string | null; width: number | null; height: number | null };

function postJson(r: PostRow, media: MediaOut[], event: unknown = null) {
  const counts = r.vote_counts ?? {};
  return {
    id: r.id,
    media,
    author: authorJson(r),
    createdAt: r.created_at,
    ageSeconds: r.age_seconds,
    text: r.text,
    audience: r.audience,
    announcement: r.is_announcement,
    // An event announcement carries the event's CURRENT state, built by the same code as the Events tab. Null for other posts.
    eventId: r.event_id ?? r.shared_event_id,
    // True when someone shared the event into a community, false for the organizer's own announcement.
    sharedEvent: r.shared_event_id !== null && r.shared_event_id !== undefined,
    event,
    community: r.community_id
      ? {
          id: r.community_id, name: r.c_name, slug: r.c_slug, visibility: r.c_visibility, memberCount: r.c_members,
          avatarUrl: r.c_avatar_url ?? null,
          // One computed membership state, so Home, Discover and the community page never disagree.
          membership: joinState({
            status: r.c_status ?? 'deleted', visibility: r.c_visibility ?? 'public', policy: r.c_policy ?? 'open',
            role: r.c_my_role, suspended: r.c_suspended, pending: r.c_pending, invited: r.c_invited,
          }),
        }
      : null,
    likeCount: r.like_count,
    commentCount: r.comment_count,
    shareCount: r.share_count,
    likedByMe: r.liked,
    savedByMe: r.saved,
    repostCount: r.repost_count,
    repostedByMe: r.reposted,
    viewCount: r.view_count,
    edited: r.edited_at !== null,
    poll: r.poll_options
      ? {
          options: r.poll_options.map((t, i) => ({ text: t, votes: counts[String(i)] ?? 0 })),
          myVote: r.my_vote,
          // Seconds left, not a date, so a wrong clock on the phone cannot keep a poll open or end it early.
          secondsLeft: r.poll_seconds_left ?? 0,
          ended: (r.poll_seconds_left ?? 0) <= 0,
        }
      : null,
  };
}

/** Posts as the API returns them, with short-lived links to their photos and videos. Shared with Discover. */
export async function presentPosts(pool: Pool, storage: Storage | null | undefined, rows: PostRow[], viewer?: { id: string; universityId: string }) {
  if (rows.length === 0) return [];
  const events = viewer ? await loadEventViews({ pool, storage: storage ?? null }, viewer, rows.flatMap((r) => (r.event_id ?? r.shared_event_id ? [(r.event_id ?? r.shared_event_id) as string] : []))) : new Map();
  await signAuthors(storage ?? undefined, rows);
  await Promise.all(rows.map(async (r) => {
    r.c_avatar_url = r.c_avatar_key && storage ? await storage.signedUrl(r.c_avatar_key, 900) : null;
  }));
  const byPost = new Map<string, MediaOut[]>();
  if (storage) {
    const { rows: files } = await pool.query<{ post_id: string; id: string; kind: string; object_key: string; thumb_key: string | null; width: number | null; height: number | null }>(
      "select post_id, id, kind, object_key, thumb_key, width, height from media where post_id = any($1::uuid[]) and status = 'ready' order by position",
      [rows.map((r) => r.id)],
    );
    for (const f of files) {
      const url = await storage.signedUrl(f.object_key, READ_LINK_SECONDS);
      const thumbUrl = f.thumb_key ? await storage.signedUrl(f.thumb_key, READ_LINK_SECONDS) : null;
      byPost.set(f.post_id, [...(byPost.get(f.post_id) ?? []), { id: f.id, kind: f.kind, url, thumbUrl, width: f.width, height: f.height }]);
    }
  }
  return rows.map((r) => postJson(r, byPost.get(r.id) ?? [], (r.event_id ?? r.shared_event_id) ? events.get((r.event_id ?? r.shared_event_id) as string) ?? null : null));
}

const COMMENT_COLS =
  'c.id, c.post_id, c.author_id, c.text, c.parent_id, c.reply_to_handle, c.created_at, p.author_id as post_author_id, ' +
  'floor(extract(epoch from (now() - c.created_at)))::int as age_seconds, ' +
  AUTHOR_COLS +
  ', (select count(*) from comment_likes l where l.comment_id = c.id)::int as like_count' +
  ', exists (select 1 from comment_likes l where l.comment_id = c.id and l.user_id = $1) as liked';

const COMMENT_FROM =
  'from comments c join posts p on p.id = c.post_id join users a on a.id = c.author_id ' +
  'join universities uni on uni.id = a.university_id';

type CommentRow = AuthorCols & {
  id: string; post_id: string; author_id: string; text: string; parent_id: string | null; reply_to_handle: string | null;
  created_at: Date; post_author_id: string; age_seconds: number; like_count: number; liked: boolean;
};

function commentJson(r: CommentRow, viewerId: string) {
  return {
    id: r.id,
    postId: r.post_id,
    author: authorJson(r),
    createdAt: r.created_at,
    ageSeconds: r.age_seconds,
    text: r.text,
    likeCount: r.like_count,
    likedByMe: r.liked,
    isMine: r.author_id === viewerId,
    isPostAuthor: r.author_id === r.post_author_id,
    parentId: r.parent_id,
    replyToHandle: r.reply_to_handle,
  };
}

export function registerPostRoutes(app: FastifyInstance, ctx: RouteContext): void {
  const { pool } = ctx;

  async function fetchPost(user: SignedInUser, id: string): Promise<PostRow | undefined> {
    const { rows } = await pool.query<PostRow>(
      `select ${POST_COLS} ${POST_FROM} where p.id = $3 and ${visible('p', 'a')}`,
      [user.id, user.university_id, id],
    );
    return rows[0];
  }

  /** Posts as the API returns them, with short-lived links to their photos and videos. */
  const present = (rows: PostRow[], user?: SignedInUser) => presentPosts(pool, ctx.storage, rows, user ? { id: user.id, universityId: user.university_id } : undefined);

  async function presentOne(user: SignedInUser, id: string) {
    const row = await fetchPost(user, id);
    if (!row) throw new HttpError(404, 'not_found', 'That post is not available.');
    return (await present([row], user))[0];
  }

  /** The post, if this student may see it. Everything else is "not found", so private posts are not revealed. */
  async function visiblePost(user: SignedInUser, id: string) {
    const { rows } = await pool.query<{ author_id: string; audience: string; poll_options: string[] | null; poll_open: boolean; event_id: string | null }>(
      `select p.author_id, p.audience, p.poll_options, (p.poll_ends_at is not null and p.poll_ends_at > now()) as poll_open, p.event_id from posts p join users a on a.id = p.author_id ` +
        `where p.id = $3 and ${visible('p', 'a')}`,
      [user.id, user.university_id, id],
    );
    if (!rows[0]) throw new HttpError(404, 'not_found', 'That post is not available.');
    return rows[0];
  }

  async function fetchComment(user: SignedInUser, id: string): Promise<CommentRow | undefined> {
    const { rows } = await pool.query<CommentRow>(`select ${COMMENT_COLS} ${COMMENT_FROM} where c.id = $2`, [user.id, id]);
    return rows[0];
  }

  /** The comment, if its post is visible to this student. */
  async function visibleComment(user: SignedInUser, id: string) {
    const { rows } = await pool.query<{ post_id: string; author_id: string; post_author_id: string }>(
      `select c.post_id, c.author_id, p.author_id as post_author_id from comments c join posts p on p.id = c.post_id where c.id = $2 and ${commentShown('c')}`,
      [user.id, id],
    );
    const row = rows[0];
    if (!row) throw new HttpError(404, 'not_found', 'That comment is not available.');
    await visiblePost(user, row.post_id);
    return row;
  }

  // ---- reading
  app.get('/posts', async (req) => {
    const user = await ctx.requireUser(req);
    const { filter, limit, before, author } = feedQuery.parse(req.query ?? {});
    const following =
      filter === 'following'
        ? 'and exists (select 1 from follows f2 where f2.follower_id = $1 and f2.followee_id = p.author_id)'
        : '';
    const params: unknown[] = [user.id, user.university_id];
    let older = '';
    if (author) {
      params.push(author);
      older = `and p.author_id = $${params.length} `;
    }
    if (before) {
      const anchor = await pool.query('select 1 from posts where id = $1', [before]);
      if (!anchor.rows[0]) throw new HttpError(404, 'not_found', 'That page of the feed is no longer available.');
      params.push(before);
      older += `and (p.created_at, p.id) < (select c.created_at, c.id from posts c where c.id = $${params.length})`;
    }
    // Home never fans out every community post to everyone: only communities the viewer joined and has not muted.
    // (Public community posts reach non-members through Discover and community pages, not Home.)
    const communityGate = author
      ? ''
      : "and (p.audience <> 'community' or exists (select 1 from community_members hm where hm.community_id = p.community_id and hm.user_id = $1 and hm.status = 'active' and hm.notification_level <> 'muted') and not exists (select 1 from community_mutes hmu where hmu.user_id = $1 and hmu.community_id = p.community_id) and not exists (select 1 from discover_feedback hf where hf.user_id = $1 and hf.kind = 'not_interested' and hf.post_id = p.id)) ";
    // Event announcements reach Home only when they are relevant to this person, so a busy campus cannot flood the feed:
    // the person follows the organizer, answered or saved the event, is the organizer, or the event belongs to a community
    // they joined (the community gate above already checked that). Upcoming and live events only, and never ones they
    // marked not interested or whose organizer they hid. Everyone else finds events in the Events tab and Discover.
    const eventGate = author
      ? ''
      : "and (p.event_id is null or (exists (select 1 from events fe where fe.id = p.event_id and fe.ends_at > now() and fe.status <> 'completed') " +
        "and not exists (select 1 from event_feedback evf where evf.user_id = $1 and evf.event_id = p.event_id) " +
        "and not exists (select 1 from discover_feedback ef where ef.user_id = $1 and ((ef.kind = 'not_interested' and ef.post_id = p.id) or (ef.kind = 'hide_author' and ef.author_id = p.author_id))) " +
        "and (p.audience = 'community' or p.author_id = $1 " +
        "or exists (select 1 from follows ef2 where ef2.follower_id = $1 and ef2.followee_id = p.author_id) " +
        "or exists (select 1 from event_registrations er where er.event_id = p.event_id and er.user_id = $1 and er.status = 'registered') " +
        "or exists (select 1 from event_interest ei where ei.event_id = p.event_id and ei.user_id = $1) " +
        "or exists (select 1 from saved_events es where es.event_id = p.event_id and es.user_id = $1)))) ";
    params.push(limit ?? 100);
    const { rows } = await pool.query<PostRow>(
      `select ${POST_COLS} ${POST_FROM} where ${visible('p', 'a')} ${communityGate} ${eventGate} ${following} ${older} order by p.created_at desc, p.id desc limit $${params.length}`,
      params,
    );
    return present(rows, user);
  });

  app.get('/posts/saved', async (req) => {
    const user = await ctx.requireUser(req);
    const { limit, before } = savedQuery.parse(req.query ?? {});
    const params: unknown[] = [user.id, user.university_id];
    let older = '';
    if (before) {
      const anchor = await pool.query('select 1 from post_saves where user_id = $1 and post_id = $2', [user.id, before]);
      if (!anchor.rows[0]) throw new HttpError(404, 'not_found', 'That page of saved posts is no longer available.');
      params.push(before);
      older = `and (sv.created_at, p.id) < (select s2.created_at, s2.post_id from post_saves s2 where s2.user_id = $1 and s2.post_id = $${params.length})`;
    }
    params.push(limit ?? 200);
    const { rows } = await pool.query<PostRow>(
      `select ${POST_COLS} ${POST_FROM} join post_saves sv on sv.post_id = p.id and sv.user_id = $1 ` +
        `where ${visible('p', 'a')} ${older} order by sv.created_at desc, p.id desc limit $${params.length}`,
      params,
    );
    return present(rows, user);
  });

  app.get('/posts/:id', async (req) => {
    const user = await ctx.requireUser(req);
    return presentOne(user, paramId(req));
  });

  // ---- writing
  app.post('/posts', async (req, reply) => {
    const user = await ctx.requireUser(req);
    const b = createSchema.parse(req.body ?? {});
    ctx.tooMany(`post:${user.id}`, 30, 3600);
    const mediaIds = b.mediaIds ?? [];
    if (new Set(mediaIds).size !== mediaIds.length) throw new HttpError(400, 'validation', 'The same file was added twice.');
    if (b.poll && mediaIds.length > 0) throw new HttpError(400, 'validation', "A poll can't have photos or a video.");
    if (b.poll && b.text === '') throw new HttpError(400, 'validation', 'Add a question for your poll.');
    if (b.text === '' && mediaIds.length === 0) throw new HttpError(400, 'validation', 'Write something first.');

    const postId = await withTx(pool, async (client) => {
      let role: string | null = null;
      if (b.communityId) role = (await assertMayPostInCommunity(client, user.id, user.university_id, b.communityId)).role;
      if (b.announcement && (!b.communityId || (role !== 'owner' && role !== 'moderator'))) {
        throw new HttpError(403, 'forbidden', 'Only community moderators can post announcements.');
      }
      if (b.announcement) ctx.tooMany(`announce:${b.communityId}`, 3, 86400);
      const created = await client.query<{ id: string }>(
        "insert into posts (author_id, text, audience, community_id, poll_options, poll_ends_at, is_announcement) values ($1, $2, $3, $4, $5, case when $5::text[] is null then null else now() + make_interval(hours => $6::int) end, $7) returning id",
        [user.id, b.text, b.communityId ? 'community' : b.audience, b.communityId ?? null, b.poll ? b.poll.options : null, b.poll ? b.poll.durationHours : 0, b.announcement === true],
      );
      const id = created.rows[0].id;
      // Fan-out happens in a background job (queued in this transaction), never inside the request.
      if (b.announcement) await enqueue(client, 'community.announce', { postId: id, communityId: b.communityId as string }, { dedupeKey: id });
      if (mediaIds.length > 0) {
        // Only files this student uploaded, finished uploading, and has not used before.
        const usable = await client.query<{ id: string; kind: string }>(
          "select id, kind from media where id = any($1::uuid[]) and owner_id = $2 and status = 'ready' and purpose = 'post' and post_id is null",
          [mediaIds, user.id],
        );
        if (usable.rows.length !== mediaIds.length) throw new HttpError(400, 'validation', 'One of the files is not available.');
        const kinds = new Set(usable.rows.map((r) => r.kind));
        if (kinds.size > 1 || (kinds.has('video') && mediaIds.length > 1)) {
          throw new HttpError(400, 'validation', 'Add up to 4 photos, or one video.');
        }
        for (let position = 0; position < mediaIds.length; position++) {
          await client.query('update media set post_id = $1, position = $2 where id = $3', [id, position, mediaIds[position]]);
        }
      }
      return id;
    });
    return reply.status(201).send(await presentOne(user, postId));
  });

  app.put('/posts/:id', async (req) => {
    const user = await ctx.requireUser(req);
    const id = paramId(req);
    const b = editSchema.parse(req.body ?? {});
    const post = await visiblePost(user, id);
    if (post.author_id !== user.id) throw new HttpError(403, 'forbidden', 'You can only change your own posts.');
    if (post.event_id) throw new HttpError(409, 'event_post', 'Edit the event itself; its announcement follows it.');
    if (b.text === '') {
      const hasMedia = await pool.query('select 1 from media where post_id = $1 limit 1', [id]);
      if (!hasMedia.rowCount) throw new HttpError(400, 'validation', 'Write something first.');
    }
    await pool.query('update posts set text = $2, edited_at = now() where id = $1', [id, b.text]);
    return presentOne(user, id);
  });

  app.delete('/posts/:id', async (req, reply) => {
    const user = await ctx.requireUser(req);
    const id = paramId(req);
    const post = await visiblePost(user, id);
    if (post.author_id !== user.id) throw new HttpError(403, 'forbidden', 'You can only delete your own posts.');
    const files = await pool.query<{ object_key: string; thumb_key: string | null }>('select object_key, thumb_key from media where post_id = $1', [id]);
    await withTx(pool, async (client) => {
      await client.query(
        `delete from notifications where (entity_type = 'post' and entity_id = $1)
            or (entity_type = 'comment' and entity_id in (select id from comments where post_id = $1))`, [id]);
      await client.query('delete from posts where id = $1', [id]); // likes, saves, votes, comments and media records cascade
    });
    if (ctx.storage && files.rowCount) await ctx.storage.remove(files.rows.flatMap((r) => (r.thumb_key ? [r.object_key, r.thumb_key] : [r.object_key]))).catch(() => undefined);
    return reply.status(204).send();
  });

  // ---- likes and saves (idempotent: repeating a request changes nothing)
  const toggles = [
    { segment: 'like', table: 'post_likes' },
    { segment: 'save', table: 'post_saves' },
    { segment: 'repost', table: 'post_reposts' },
  ] as const;
  for (const { segment, table } of toggles) {
    app.put(`/posts/:id/${segment}`, async (req, reply) => {
      const user = await ctx.requireUser(req);
      const id = paramId(req);
      ctx.tooMany(`${segment}:${user.id}`, 600, 3600);
      const post = await visiblePost(user, id);
      await withTx(pool, async (client) => {
        const res = await client.query(`insert into ${table} (post_id, user_id) values ($1, $2) on conflict do nothing`, [id, user.id]);
        if (segment === 'like' && res.rowCount) {
          await notify(client, { recipientId: post.author_id, actorId: user.id, type: 'like', entityType: 'post', entityId: id });
        }
      });
      return reply.status(204).send();
    });
    app.delete(`/posts/:id/${segment}`, async (req, reply) => {
      const user = await ctx.requireUser(req);
      const id = paramId(req);
      ctx.tooMany(`${segment}:${user.id}`, 600, 3600);
      await withTx(pool, async (client) => {
        await client.query(`delete from ${table} where post_id = $1 and user_id = $2`, [id, user.id]);
        if (segment === 'like') {
          const owner = await client.query<{ author_id: string }>('select author_id from posts where id = $1', [id]);
          if (owner.rows[0]) await retract(client, owner.rows[0].author_id, user.id, 'like', id);
        }
      });
      return reply.status(204).send();
    });
  }

  // ---- polls, sharing, reports
  app.post('/posts/:id/vote', async (req) => {
    const user = await ctx.requireUser(req);
    const id = paramId(req);
    const b = voteSchema.parse(req.body ?? {});
    ctx.tooMany(`vote:${user.id}`, 300, 3600);
    const post = await visiblePost(user, id);
    if (!post.poll_options) throw new HttpError(400, 'validation', 'This post has no poll.');
    if (b.optionIndex >= post.poll_options.length) throw new HttpError(400, 'validation', 'Unknown option.');
    if (!post.poll_open) throw new HttpError(409, 'poll_ended', 'This poll has ended.');
    try {
      await pool.query('insert into poll_votes (post_id, user_id, option_index) values ($1, $2, $3)', [id, user.id, b.optionIndex]);
    } catch (e) {
      if (isUniqueViolation(e)) throw new HttpError(409, 'already_voted', 'You already voted in this poll.');
      throw e;
    }
    return presentOne(user, id);
  });

  // The app reports the posts a student has actually looked at, in small batches. Each student counts once per post,
  // posts they cannot see are ignored, and an author looking at their own post is not a view.
  app.post('/posts/views', async (req, reply) => {
    const user = await ctx.requireUser(req);
    const b = viewsSchema.parse(req.body ?? {});
    ctx.tooMany(`views:${user.id}`, 120, 3600);
    await pool.query(
      `insert into post_views (post_id, user_id) select p.id, $1 from posts p join users a on a.id = p.author_id ` +
        `where p.id = any($3::uuid[]) and p.author_id <> $1 and ${visible('p', 'a')} on conflict do nothing`,
      [user.id, user.university_id, b.ids],
    );
    return reply.status(204).send();
  });

  app.post('/posts/:id/share', async (req, reply) => {
    const user = await ctx.requireUser(req);
    const id = paramId(req);
    ctx.tooMany(`share:${user.id}`, 120, 3600);
    const post = await visiblePost(user, id);
    if (post.audience !== 'campus') throw new HttpError(403, 'forbidden', 'Only campus-wide posts can be shared.');
    await pool.query('update posts set share_count = share_count + 1 where id = $1', [id]);
    return reply.status(204).send();
  });

  app.post('/posts/:id/report', async (req, reply) => {
    const user = await ctx.requireUser(req);
    const id = paramId(req);
    const b = reportSchema.parse(req.body ?? {});
    ctx.tooMany(`report:${user.id}`, 30, 3600);
    await visiblePost(user, id);
    await pool.query(
      'insert into post_reports (post_id, reporter_id, reason) values ($1, $2, $3) on conflict do nothing',
      [id, user.id, b.reason],
    );
    return reply.status(204).send();
  });

  // ---- comments
  app.get('/posts/:id/comments', async (req) => {
    const user = await ctx.requireUser(req);
    const id = paramId(req);
    await visiblePost(user, id);
    const { rows } = await pool.query<CommentRow>(
      `select ${COMMENT_COLS} ${COMMENT_FROM} where c.post_id = $2 and ${commentShown('c')} order by c.created_at asc, c.id asc limit 500`,
      [user.id, id],
    );
    await signAuthors(ctx.storage, rows);
    return rows.map((r) => commentJson(r, user.id));
  });

  app.post('/posts/:id/comments', async (req, reply) => {
    const user = await ctx.requireUser(req);
    const id = paramId(req);
    const b = commentSchema.parse(req.body ?? {});
    ctx.tooMany(`comment:${user.id}`, 90, 3600);
    const post = await visiblePost(user, id);

    let parentId: string | null = null;
    let replyToHandle: string | null = null;
    let replyToAuthor: string | null = null;
    if (b.replyToId) {
      const target = await pool.query<{ id: string; parent_id: string | null; handle: string; author_id: string }>(
        `select c.id, c.parent_id, a.handle, c.author_id from comments c join users a on a.id = c.author_id where c.id = $2 and c.post_id = $3 and ${commentShown('c')}`,
        [user.id, b.replyToId, id],
      );
      const t = target.rows[0];
      if (!t) throw new HttpError(404, 'not_found', 'The comment you are replying to is gone.');
      parentId = t.parent_id ?? t.id; // replies always hang under the top-level comment
      replyToHandle = t.handle;
      replyToAuthor = t.author_id;
    }
    const commentId = await withTx(pool, async (client) => {
      const created = await client.query<{ id: string }>(
        'insert into comments (post_id, author_id, parent_id, reply_to_handle, text) values ($1, $2, $3, $4, $5) returning id',
        [id, user.id, parentId, replyToHandle, b.text],
      );
      const cid = created.rows[0].id;
      // A reply tells the person replied to; a top-level comment tells the post's author. Never the comment text.
      if (replyToAuthor) await notify(client, { recipientId: replyToAuthor, actorId: user.id, type: 'reply', entityType: 'comment', entityId: cid, metadata: { postId: id } });
      else await notify(client, { recipientId: post.author_id, actorId: user.id, type: 'comment', entityType: 'comment', entityId: cid, metadata: { postId: id } });
      return cid;
    });
    const comment = await fetchComment(user, commentId);
    if (comment) await signAuthors(ctx.storage, [comment as CommentRow]);
    return reply.status(201).send(commentJson(comment as CommentRow, user.id));
  });

  async function setCommentLike(req: FastifyRequest, on: boolean): Promise<void> {
    const user = await ctx.requireUser(req);
    const id = paramId(req);
    ctx.tooMany(`clike:${user.id}`, 600, 3600);
    await visibleComment(user, id);
    if (on) {
      await pool.query('insert into comment_likes (comment_id, user_id) values ($1, $2) on conflict do nothing', [id, user.id]);
    } else {
      await pool.query('delete from comment_likes where comment_id = $1 and user_id = $2', [id, user.id]);
    }
  }
  app.put('/comments/:id/like', async (req, reply) => {
    await setCommentLike(req, true);
    return reply.status(204).send();
  });
  app.delete('/comments/:id/like', async (req, reply) => {
    await setCommentLike(req, false);
    return reply.status(204).send();
  });

  app.delete('/comments/:id', async (req, reply) => {
    const user = await ctx.requireUser(req);
    const id = paramId(req);
    const comment = await visibleComment(user, id);
    if (comment.author_id !== user.id && comment.post_author_id !== user.id) {
      throw new HttpError(403, 'forbidden', 'You can only delete your own comments, or comments on your posts.');
    }
    await withTx(pool, async (client) => {
      await client.query(
        "delete from notifications where entity_type = 'comment' and (entity_id = $1 or entity_id in (select id from comments where parent_id = $1))", [id]);
      await client.query('delete from comments where id = $1', [id]); // replies cascade
    });
    return reply.status(204).send();
  });

  app.post('/comments/:id/report', async (req, reply) => {
    const user = await ctx.requireUser(req);
    const id = paramId(req);
    const b = reportSchema.parse(req.body ?? {});
    ctx.tooMany(`creport:${user.id}`, 30, 3600);
    await visibleComment(user, id);
    await pool.query(
      'insert into comment_reports (comment_id, reporter_id, reason) values ($1, $2, $3) on conflict do nothing',
      [id, user.id, b.reason],
    );
    return reply.status(204).send();
  });
}
