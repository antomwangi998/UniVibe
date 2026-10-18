import type { FastifyInstance, FastifyRequest } from 'fastify';
import { blockedBetween, notSuspended } from './access';
import { notify, retract } from './modules/notify';
import { z } from 'zod';
import { withTx } from './db';
import { HttpError } from './errors';
import { READ_LINK_SECONDS } from './media';
import { AUTHOR_COLS, AuthorCols, RouteContext, SignedInUser, authorJson, isUniqueViolation, paramId, signAuthors } from './shared';

const TEXT_MAX = 280;
const text = z.string().trim().min(1, 'Write something first.').max(TEXT_MAX, `Keep it under ${TEXT_MAX} characters.`);

const pollSchema = z.object({
  options: z
    .array(z.string().trim().min(1, 'Poll options cannot be empty.').max(40, 'Keep poll options under 40 characters.'))
    .min(2, 'A poll needs at least 2 options.')
    .max(4, 'A poll can have at most 4 options.')
    .refine((o) => new Set(o.map((s) => s.toLowerCase())).size === o.length, 'Poll options must be different.'),
});
// A post may have no text when it carries photos or a video.
const maybeText = z.string().trim().max(TEXT_MAX, `Keep it under ${TEXT_MAX} characters.`);
const createSchema = z.object({
  text: maybeText.default(''),
  audience: z.enum(['campus', 'followers']).default('campus'),
  poll: pollSchema.optional(),
  mediaIds: z.array(z.string().uuid()).max(4, 'You can add up to 4 photos.').optional(),
});
const editSchema = z.object({ text: maybeText });
const voteSchema = z.object({ optionIndex: z.number().int().min(0).max(3) });
const reportSchema = z.object({ reason: z.string().trim().min(1).max(200) });
const feedQuery = z.object({
  filter: z.enum(['campus', 'following']).default('campus'),
  /** Page size. Without it the feed behaves as before (up to 100 posts at once). */
  limit: z.coerce.number().int().min(1).max(50).optional(),
  /** Id of the last post the app already has; the page continues with the posts just older than it. */
  before: z.string().uuid().optional(),
});
const commentSchema = z.object({ text, replyToId: z.string().uuid().optional() });

/**
 * Who may see a post. $1 = the viewer's id, $2 = the viewer's university id.
 * Authors always see their own posts. Campus posts stay inside the author's university.
 * Followers-only posts need a follow. The server is the only place this is enforced.
 */
export const visible = (p: string, author: string) =>
  `(${p}.status = 'active' and (${p}.author_id = $1 or (` +
  `((${p}.audience = 'campus' and ${author}.university_id = $2) or ` +
  `(${p}.audience = 'followers' and exists (select 1 from follows f where f.follower_id = $1 and f.followee_id = ${p}.author_id))) ` +
  `and ${notSuspended(author)} and not ${blockedBetween('$1', `${p}.author_id`)})))`;

/** Comments the viewer may see: not removed, and not written by someone blocked in either direction. */
export const commentShown = (c: string) =>
  `(${c}.status = 'active' and (${c}.author_id = $1 or not ${blockedBetween('$1', `${c}.author_id`)}))`;

const POST_COLS =
  'p.id, p.text, p.audience, p.share_count, p.poll_options, p.created_at, p.edited_at, ' +
  'floor(extract(epoch from (now() - p.created_at)))::int as age_seconds, ' +
  AUTHOR_COLS +
  ', (select count(*) from post_likes l where l.post_id = p.id)::int as like_count' +
  `, (select count(*) from comments c where c.post_id = p.id and ${commentShown('c')})::int as comment_count` +
  ', exists (select 1 from post_likes l where l.post_id = p.id and l.user_id = $1) as liked' +
  ', exists (select 1 from post_saves s where s.post_id = p.id and s.user_id = $1) as saved' +
  ', (select v.option_index from poll_votes v where v.post_id = p.id and v.user_id = $1) as my_vote' +
  ", (select coalesce(json_object_agg(t.option_index, t.n), '{}'::json) from " +
  '(select option_index, count(*)::int as n from poll_votes where post_id = p.id group by option_index) t) as vote_counts';

const POST_FROM = 'from posts p join users a on a.id = p.author_id join universities uni on uni.id = a.university_id';

type PostRow = AuthorCols & {
  id: string; text: string; audience: string; share_count: number; poll_options: string[] | null;
  created_at: Date; edited_at: Date | null; age_seconds: number;
  like_count: number; comment_count: number; liked: boolean; saved: boolean;
  my_vote: number | null; vote_counts: Record<string, number> | null;
};

type MediaOut = { id: string; kind: string; url: string; thumbUrl: string | null; width: number | null; height: number | null };

function postJson(r: PostRow, media: MediaOut[]) {
  const counts = r.vote_counts ?? {};
  return {
    id: r.id,
    media,
    author: authorJson(r),
    createdAt: r.created_at,
    ageSeconds: r.age_seconds,
    text: r.text,
    audience: r.audience,
    likeCount: r.like_count,
    commentCount: r.comment_count,
    shareCount: r.share_count,
    likedByMe: r.liked,
    savedByMe: r.saved,
    edited: r.edited_at !== null,
    poll: r.poll_options
      ? { options: r.poll_options.map((t, i) => ({ text: t, votes: counts[String(i)] ?? 0 })), myVote: r.my_vote }
      : null,
  };
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
  async function present(rows: PostRow[]) {
    if (rows.length === 0) return [];
    await signAuthors(ctx.storage, rows);
    const byPost = new Map<string, MediaOut[]>();
    if (ctx.storage) {
      const { rows: files } = await pool.query<{ post_id: string; id: string; kind: string; object_key: string; thumb_key: string | null; width: number | null; height: number | null }>(
        "select post_id, id, kind, object_key, thumb_key, width, height from media where post_id = any($1::uuid[]) and status = 'ready' order by position",
        [rows.map((r) => r.id)],
      );
      for (const f of files) {
        const url = await ctx.storage.signedUrl(f.object_key, READ_LINK_SECONDS);
        const thumbUrl = f.thumb_key ? await ctx.storage.signedUrl(f.thumb_key, READ_LINK_SECONDS) : null;
        byPost.set(f.post_id, [...(byPost.get(f.post_id) ?? []), { id: f.id, kind: f.kind, url, thumbUrl, width: f.width, height: f.height }]);
      }
    }
    return rows.map((r) => postJson(r, byPost.get(r.id) ?? []));
  }

  async function presentOne(user: SignedInUser, id: string) {
    const row = await fetchPost(user, id);
    if (!row) throw new HttpError(404, 'not_found', 'That post is not available.');
    return (await present([row]))[0];
  }

  /** The post, if this student may see it. Everything else is "not found", so private posts are not revealed. */
  async function visiblePost(user: SignedInUser, id: string) {
    const { rows } = await pool.query<{ author_id: string; audience: string; poll_options: string[] | null }>(
      `select p.author_id, p.audience, p.poll_options from posts p join users a on a.id = p.author_id ` +
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
    const { filter, limit, before } = feedQuery.parse(req.query ?? {});
    const following =
      filter === 'following'
        ? 'and exists (select 1 from follows f2 where f2.follower_id = $1 and f2.followee_id = p.author_id)'
        : '';
    const params: unknown[] = [user.id, user.university_id];
    let older = '';
    if (before) {
      const anchor = await pool.query('select 1 from posts where id = $1', [before]);
      if (!anchor.rows[0]) throw new HttpError(404, 'not_found', 'That page of the feed is no longer available.');
      params.push(before);
      older = `and (p.created_at, p.id) < (select c.created_at, c.id from posts c where c.id = $${params.length})`;
    }
    params.push(limit ?? 100);
    const { rows } = await pool.query<PostRow>(
      `select ${POST_COLS} ${POST_FROM} where ${visible('p', 'a')} ${following} ${older} order by p.created_at desc, p.id desc limit $${params.length}`,
      params,
    );
    return present(rows);
  });

  app.get('/posts/saved', async (req) => {
    const user = await ctx.requireUser(req);
    const { rows } = await pool.query<PostRow>(
      `select ${POST_COLS} ${POST_FROM} join post_saves sv on sv.post_id = p.id and sv.user_id = $1 ` +
        `where ${visible('p', 'a')} order by sv.created_at desc limit 200`,
      [user.id, user.university_id],
    );
    return present(rows);
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
      const created = await client.query<{ id: string }>(
        'insert into posts (author_id, text, audience, poll_options) values ($1, $2, $3, $4) returning id',
        [user.id, b.text, b.audience, b.poll ? b.poll.options : null],
      );
      const id = created.rows[0].id;
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
    try {
      await pool.query('insert into poll_votes (post_id, user_id, option_index) values ($1, $2, $3)', [id, user.id, b.optionIndex]);
    } catch (e) {
      if (isUniqueViolation(e)) throw new HttpError(409, 'already_voted', 'You already voted in this poll.');
      throw e;
    }
    return presentOne(user, id);
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
