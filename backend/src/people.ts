import type { FastifyInstance } from 'fastify';
import { HttpError } from './errors';
import { AUTHOR_COLS, AuthorCols, RouteContext, authorJson, paramId } from './shared';

const PEOPLE_FROM = 'from users a join universities uni on uni.id = a.university_id';

export function registerPeopleRoutes(app: FastifyInstance, ctx: RouteContext): void {
  const { pool } = ctx;

  /** Counts for the signed-in student's own profile. */
  app.get('/me/stats', async (req) => {
    const user = await ctx.requireUser(req);
    const { rows } = await pool.query<{ posts: number; followers: number; following: number; saved: number }>(
      'select (select count(*) from posts where author_id = $1)::int as posts, ' +
        '(select count(*) from follows where followee_id = $1)::int as followers, ' +
        '(select count(*) from follows where follower_id = $1)::int as following, ' +
        '(select count(*) from post_saves where user_id = $1)::int as saved',
      [user.id],
    );
    return rows[0];
  });

  /** Another student's public profile. Only students of the same university can see each other. */
  app.get('/students/:id', async (req) => {
    const user = await ctx.requireUser(req);
    const id = paramId(req);
    const { rows } = await pool.query<
      AuthorCols & { bio: string; posts: number; followers: number; following: number; followed_by_me: boolean }
    >(
      `select ${AUTHOR_COLS}, a.bio, ` +
        // Only the posts this viewer is allowed to see are counted.
        '(select count(*) from posts p where p.author_id = a.id and (p.audience = \'campus\' or a.id = $1 or ' +
        'exists (select 1 from follows f where f.follower_id = $1 and f.followee_id = a.id)))::int as posts, ' +
        '(select count(*) from follows where followee_id = a.id)::int as followers, ' +
        '(select count(*) from follows where follower_id = a.id)::int as following, ' +
        'exists (select 1 from follows where follower_id = $1 and followee_id = a.id) as followed_by_me ' +
        `${PEOPLE_FROM} where a.id = $3 and a.university_id = $2 and a.email_verified_at is not null`,
      [user.id, user.university_id, id],
    );
    const r = rows[0];
    if (!r) throw new HttpError(404, 'not_found', 'That student was not found.');
    return {
      student: authorJson(r),
      bio: r.bio,
      posts: r.posts,
      followers: r.followers,
      following: r.following,
      followedByMe: r.followed_by_me,
    };
  });

  app.put('/students/:id/follow', async (req, reply) => {
    const user = await ctx.requireUser(req);
    const id = paramId(req);
    ctx.tooMany(`follow:${user.id}`, 200, 3600);
    if (id === user.id) throw new HttpError(400, 'validation', "You can't follow yourself.");
    const target = await pool.query(
      'select 1 from users where id = $1 and university_id = $2 and email_verified_at is not null',
      [id, user.university_id],
    );
    if (!target.rowCount) throw new HttpError(404, 'not_found', 'That student was not found.');
    await pool.query('insert into follows (follower_id, followee_id) values ($1, $2) on conflict do nothing', [user.id, id]);
    return reply.status(204).send();
  });

  app.delete('/students/:id/follow', async (req, reply) => {
    const user = await ctx.requireUser(req);
    const id = paramId(req);
    ctx.tooMany(`follow:${user.id}`, 200, 3600);
    await pool.query('delete from follows where follower_id = $1 and followee_id = $2', [user.id, id]);
    return reply.status(204).send();
  });

  app.get('/me/followers', async (req) => {
    const user = await ctx.requireUser(req);
    const { rows } = await pool.query<AuthorCols & { following_back: boolean }>(
      `select ${AUTHOR_COLS}, exists (select 1 from follows f2 where f2.follower_id = $1 and f2.followee_id = a.id) as following_back ` +
        'from follows f join users a on a.id = f.follower_id join universities uni on uni.id = a.university_id ' +
        'where f.followee_id = $1 order by f.created_at desc limit 200',
      [user.id],
    );
    return rows.map((r) => ({ student: authorJson(r), isFollowing: r.following_back }));
  });

  app.get('/me/following', async (req) => {
    const user = await ctx.requireUser(req);
    const { rows } = await pool.query<AuthorCols>(
      `select ${AUTHOR_COLS} ` +
        'from follows f join users a on a.id = f.followee_id join universities uni on uni.id = a.university_id ' +
        'where f.follower_id = $1 order by f.created_at desc limit 200',
      [user.id],
    );
    return rows.map((r) => ({ student: authorJson(r), isFollowing: true }));
  });
}
