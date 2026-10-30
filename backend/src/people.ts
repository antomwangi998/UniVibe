import type { FastifyInstance } from 'fastify';
import { userVisible } from './access';
import { withTx } from './db';
import { HttpError } from './errors';
import { notify, retract } from './modules/notify';
import { publicLookFor } from './modules/monetization/profile_look_routes';
import { AUTHOR_COLS, AuthorCols, RouteContext, authorJson, paramId, signAuthors } from './shared';

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

  /** Another student's public profile. Same-university students always see each other; other campuses see you unless you opted out. */
  app.get('/students/:id', async (req) => {
    const user = await ctx.requireUser(req);
    const id = paramId(req);
    const { rows } = await pool.query<
      AuthorCols & { bio: string; posts: number; followers: number; following: number; followed_by_me: boolean }
    >(
      `select ${AUTHOR_COLS}, a.bio, ` +
        // Only the posts this viewer is allowed to see are counted.
        '(select count(*) from posts p where p.author_id = a.id and p.status = \'active\' and (p.audience = \'all\' or (p.audience = \'campus\' and a.university_id = $2) or a.id = $1 or ' +
        'exists (select 1 from follows f where f.follower_id = $1 and f.followee_id = a.id)))::int as posts, ' +
        '(select count(*) from follows where followee_id = a.id)::int as followers, ' +
        '(select count(*) from follows where follower_id = a.id)::int as following, ' +
        'exists (select 1 from follows where follower_id = $1 and followee_id = a.id) as followed_by_me ' +
        `${PEOPLE_FROM} where a.id = $3 and ${userVisible('a', '$1', '$2')}`,
      [user.id, user.university_id, id],
    );
    const r = rows[0];
    if (!r) throw new HttpError(404, 'not_found', 'That student was not found.');
    await signAuthors(ctx.storage, [r]);
    return {
      student: authorJson(r),
      bio: r.bio,
      posts: r.posts,
      followers: r.followers,
      following: r.following,
      followedByMe: r.followed_by_me,
      // A Premium look, only while its owner's Premium is live. Null for everyone else.
      look: await publicLookFor(pool, id),
    };
  });

  app.put('/students/:id/follow', async (req, reply) => {
    const user = await ctx.requireUser(req);
    const id = paramId(req);
    ctx.tooMany(`follow:${user.id}`, 200, 3600);
    if (id === user.id) throw new HttpError(400, 'validation', "You can't follow yourself.");
    // Blocked pairs and suspended students look like "not found", so a block cannot be probed through this route.
    const target = await pool.query(
      `select 1 from users u where u.id = $1 and ${userVisible('u', '$3', '$2')}`,
      [id, user.university_id, user.id],
    );
    if (!target.rowCount) throw new HttpError(404, 'not_found', 'That student was not found.');
    await withTx(pool, async (client) => {
      const made = await client.query('insert into follows (follower_id, followee_id) values ($1, $2) on conflict do nothing', [user.id, id]);
      if (made.rowCount) await notify(client, { recipientId: id, actorId: user.id, type: 'follow', entityType: 'user', entityId: user.id });
    });
    return reply.status(204).send();
  });

  app.delete('/students/:id/follow', async (req, reply) => {
    const user = await ctx.requireUser(req);
    const id = paramId(req);
    ctx.tooMany(`follow:${user.id}`, 200, 3600);
    await withTx(pool, async (client) => {
      await client.query('delete from follows where follower_id = $1 and followee_id = $2', [user.id, id]);
      await retract(client, id, user.id, 'follow', user.id);
    });
    return reply.status(204).send();
  });

  app.get('/me/followers', async (req) => {
    const user = await ctx.requireUser(req);
    const { rows } = await pool.query<AuthorCols & { following_back: boolean }>(
      `select ${AUTHOR_COLS}, exists (select 1 from follows f2 where f2.follower_id = $1 and f2.followee_id = a.id) as following_back ` +
        'from follows f join users a on a.id = f.follower_id join universities uni on uni.id = a.university_id ' +
        `where f.followee_id = $1 and ${userVisible('a', '$1', '$2')} order by f.created_at desc limit 200`,
      [user.id, user.university_id],
    );
    await signAuthors(ctx.storage, rows);
    return rows.map((r) => ({ student: authorJson(r), isFollowing: r.following_back }));
  });

  app.get('/me/following', async (req) => {
    const user = await ctx.requireUser(req);
    const { rows } = await pool.query<AuthorCols>(
      `select ${AUTHOR_COLS} ` +
        'from follows f join users a on a.id = f.followee_id join universities uni on uni.id = a.university_id ' +
        `where f.follower_id = $1 and ${userVisible('a', '$1', '$2')} order by f.created_at desc limit 200`,
      [user.id, user.university_id],
    );
    await signAuthors(ctx.storage, rows);
    return rows.map((r) => ({ student: authorJson(r), isFollowing: true }));
  });

  /**
   * Another student's followers / following. Same rules as their profile: only same-university, verified, unsuspended
   * students, and a blocked pair looks like "not found". Entries you have blocked (or who blocked you) are left out.
   */
  async function assertProfileVisible(viewerId: string, universityId: string, targetId: string) {
    const target = await pool.query(
      `select 1 from users u where u.id = $1 and ${userVisible('u', '$3', '$2')}`,
      [targetId, universityId, viewerId],
    );
    if (!target.rowCount) throw new HttpError(404, 'not_found', 'That student was not found.');
  }

  for (const kind of ['followers', 'following'] as const) {
    app.get(`/students/:id/${kind}`, async (req) => {
      const user = await ctx.requireUser(req);
      const id = paramId(req);
      ctx.tooMany(`follow-list:${user.id}`, 120, 600);
      await assertProfileVisible(user.id, user.university_id, id);
      // followers: rows where the target is followed; following: rows the target follows.
      const [listed, anchor] = kind === 'followers' ? ['f.follower_id', 'f.followee_id'] : ['f.followee_id', 'f.follower_id'];
      const { rows } = await pool.query<AuthorCols & { followed_by_me: boolean; is_me: boolean }>(
        `select ${AUTHOR_COLS}, (a.id = $1) as is_me, ` +
          'exists (select 1 from follows f2 where f2.follower_id = $1 and f2.followee_id = a.id) as followed_by_me ' +
          `from follows f join users a on a.id = ${listed} join universities uni on uni.id = a.university_id ` +
          `where ${anchor} = $3 and ${userVisible('a', '$1', '$2')} order by f.created_at desc limit 200`,
        [user.id, user.university_id, id],
      );
      await signAuthors(ctx.storage, rows);
      return rows.map((r) => ({ student: authorJson(r), isFollowing: r.followed_by_me, isMe: r.is_me }));
    });
  }
}
