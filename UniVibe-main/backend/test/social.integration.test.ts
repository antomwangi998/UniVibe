import type { FastifyInstance } from 'fastify';
import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app';
import type { Config } from '../src/config';
import { createPool, migrate } from '../src/db';
import type { Mailer } from '../src/email';

const url = process.env.TEST_DATABASE_URL;

describe.skipIf(!url)('posts, comments and people (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  let app: FastifyInstance;
  const codes = new Map<string, string>();
  let n = 0;

  const mailer: Mailer = {
    sendVerification: async (to, _name, code) => { codes.set(to, code); },
    sendPasswordReset: async () => {},
  };
  const config: Config = {
    NODE_ENV: 'test', PORT: 0, DATABASE_URL: url ?? '',
    JWT_SECRET: 'j'.repeat(40), CODE_PEPPER: 'p'.repeat(40), EMAIL_FROM: 'UniVibe <test@example.com>',
  };

  beforeAll(async () => {
    pool = createPool(url as string);
    await migrate(pool);
    await pool.query("insert into universities (id, name, email_domains) values ('other', 'Other University', array['other.example']) on conflict do nothing");
  });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { app = buildApp({ pool, mailer, config }); codes.clear(); });
  afterEach(async () => { await app.close(); });

  type Method = 'GET' | 'POST' | 'PUT' | 'DELETE';
  async function call(method: Method, path: string, token?: string, payload?: unknown) {
    const res = await app.inject({
      method, url: path, payload: payload as object | undefined,
      headers: token ? { authorization: `Bearer ${token}` } : undefined,
    });
    return { status: res.statusCode, body: res.body ? JSON.parse(res.body) : null };
  }

  async function student(universityId = 'lakeside') {
    const email = `s${Date.now()}x${n++}@${universityId}.example`;
    await call('POST', '/auth/register', undefined, { fullName: 'Test Student', email, universityId, password: 'correct-horse-9', acceptedTerms: true });
    const res = await call('POST', '/auth/verify-email', undefined, { email, code: codes.get(email) });
    expect(res.status).toBe(200);
    return { token: res.body.accessToken as string, id: res.body.user.id as string, handle: res.body.user.handle as string };
  }
  type Student = Awaited<ReturnType<typeof student>>;

  const post = async (who: Student, body: unknown) => {
    const res = await call('POST', '/posts', who.token, body);
    expect(res.status).toBe(201);
    return res.body;
  };
  const feed = async (who: Student, filter = 'campus') => (await call('GET', `/posts?filter=${filter}`, who.token)).body as { id: string }[];

  // ---- creating and reading
  it('a campus post is visible to the same university only', async () => {
    const [a, b, outsider] = [await student(), await student(), await student('other')];
    const created = await post(a, { text: '  hello campus  ' });
    expect(created.text).toBe('hello campus');
    expect(created.audience).toBe('campus');
    expect(created.author.id).toBe(a.id);
    expect(created.likeCount).toBe(0);
    expect((await feed(b)).map((p) => p.id)).toContain(created.id);
    expect((await feed(a)).map((p) => p.id)).toContain(created.id);
    expect((await feed(outsider)).map((p) => p.id)).not.toContain(created.id);
    expect((await call('GET', `/posts/${created.id}`, outsider.token)).status).toBe(404);
  });

  it('rejects bad posts and needs a login', async () => {
    const a = await student();
    expect((await call('POST', '/posts', undefined, { text: 'x' })).status).toBe(401);
    expect((await call('POST', '/posts', a.token, { text: '   ' })).status).toBe(400);
    expect((await call('POST', '/posts', a.token, { text: 'x'.repeat(281) })).status).toBe(400);
    expect((await call('POST', '/posts', a.token, { text: 'q', poll: { options: ['only one'] } })).status).toBe(400);
    expect((await call('POST', '/posts', a.token, { text: 'q', poll: { options: ['Same', 'same'] } })).status).toBe(400);
    expect((await call('POST', '/posts', a.token, { text: 'q', audience: 'community' })).status).toBe(400);
    expect((await call('GET', '/posts/not-a-uuid', a.token)).status).toBe(404);
  });

  it('followers-only posts need a follow, and cannot be shared', async () => {
    const [author, fan, stranger] = [await student(), await student(), await student()];
    const secret = await post(author, { text: 'friends only', audience: 'followers' });
    expect((await feed(fan)).map((p) => p.id)).not.toContain(secret.id);
    expect((await call('GET', `/posts/${secret.id}`, fan.token)).status).toBe(404);
    expect((await call('PUT', `/students/${author.id}/follow`, fan.token)).status).toBe(204);
    expect((await feed(fan)).map((p) => p.id)).toContain(secret.id);
    expect((await feed(stranger)).map((p) => p.id)).not.toContain(secret.id);
    expect((await call('POST', `/posts/${secret.id}/share`, fan.token)).status).toBe(403);
    expect((await call('DELETE', `/students/${author.id}/follow`, fan.token)).status).toBe(204);
    expect((await feed(fan)).map((p) => p.id)).not.toContain(secret.id);
  });

  it('the Following filter shows only people you follow', async () => {
    const [a, b, c] = [await student(), await student(), await student()];
    const fromB = await post(b, { text: 'from b' });
    const fromC = await post(c, { text: 'from c' });
    await call('PUT', `/students/${b.id}/follow`, a.token);
    const ids = (await feed(a, 'following')).map((p) => p.id);
    expect(ids).toContain(fromB.id);
    expect(ids).not.toContain(fromC.id);
  });

  // ---- likes, saves, shares, reports
  it('likes are per student and repeating a request changes nothing', async () => {
    const [a, b] = [await student(), await student()];
    const p = await post(a, { text: 'like me' });
    await call('PUT', `/posts/${p.id}/like`, b.token);
    await call('PUT', `/posts/${p.id}/like`, b.token);
    await call('PUT', `/posts/${p.id}/like`, a.token);
    const seenByB = (await call('GET', `/posts/${p.id}`, b.token)).body;
    expect(seenByB.likeCount).toBe(2);
    expect(seenByB.likedByMe).toBe(true);
    await call('DELETE', `/posts/${p.id}/like`, b.token);
    await call('DELETE', `/posts/${p.id}/like`, b.token);
    const after = (await call('GET', `/posts/${p.id}`, b.token)).body;
    expect(after.likeCount).toBe(1);
    expect(after.likedByMe).toBe(false);
  });

  it('saved posts are private to each student', async () => {
    const [a, b] = [await student(), await student()];
    const p = await post(a, { text: 'keep this' });
    expect((await call('PUT', `/posts/${p.id}/save`, b.token)).status).toBe(204);
    expect(((await call('GET', '/posts/saved', b.token)).body as { id: string }[]).map((x) => x.id)).toEqual([p.id]);
    expect((await call('GET', '/posts/saved', a.token)).body).toEqual([]);
    await call('DELETE', `/posts/${p.id}/save`, b.token);
    expect((await call('GET', '/posts/saved', b.token)).body).toEqual([]);
  });

  it('shares are counted and reports are accepted once', async () => {
    const [a, b] = [await student(), await student()];
    const p = await post(a, { text: 'share me' });
    await call('POST', `/posts/${p.id}/share`, b.token);
    await call('POST', `/posts/${p.id}/share`, b.token);
    expect((await call('GET', `/posts/${p.id}`, a.token)).body.shareCount).toBe(2);
    expect((await call('POST', `/posts/${p.id}/report`, b.token, { reason: 'spam' })).status).toBe(204);
    expect((await call('POST', `/posts/${p.id}/report`, b.token, { reason: 'spam' })).status).toBe(204);
    expect((await call('POST', `/posts/${p.id}/report`, b.token, { reason: '' })).status).toBe(400);
  });

  // ---- polls
  it('polls take one vote per student and show live results', async () => {
    const [a, b] = [await student(), await student()];
    const p = await post(a, { text: 'Which day?', poll: { options: ['Sat', 'Sun'] } });
    expect(p.poll.options).toEqual([{ text: 'Sat', votes: 0 }, { text: 'Sun', votes: 0 }]);
    expect(p.poll.myVote).toBeNull();
    const voted = await call('POST', `/posts/${p.id}/vote`, b.token, { optionIndex: 1 });
    expect(voted.status).toBe(200);
    expect(voted.body.poll.options.map((o: { votes: number }) => o.votes)).toEqual([0, 1]);
    expect(voted.body.poll.myVote).toBe(1);
    expect((await call('POST', `/posts/${p.id}/vote`, b.token, { optionIndex: 0 })).status).toBe(409);
    expect((await call('POST', `/posts/${p.id}/vote`, a.token, { optionIndex: 5 })).status).toBe(400);
    expect((await call('GET', `/posts/${p.id}`, a.token)).body.poll.myVote).toBeNull(); // a has not voted
    const plain = await post(a, { text: 'no poll here' });
    expect((await call('POST', `/posts/${plain.id}/vote`, b.token, { optionIndex: 0 })).status).toBe(400);
  });

  it('polls run for at most 3 days and stop taking votes when they end', async () => {
    const [a, b] = [await student(), await student()];
    expect((await call('POST', '/posts', a.token, { text: 'q', poll: { options: ['a', 'b'], durationHours: 73 } })).status).toBe(400);
    expect((await call('POST', '/posts', a.token, { text: 'q', poll: { options: ['a', 'b'], durationHours: 0 } })).status).toBe(400);
    const p = await post(a, { text: 'Which day?', poll: { options: ['Sat', 'Sun'], durationHours: 72 } });
    expect(p.poll.ended).toBe(false);
    expect(p.poll.secondsLeft).toBeGreaterThan(71 * 3600);
    expect(p.poll.secondsLeft).toBeLessThanOrEqual(72 * 3600);
    const short = await post(a, { text: 'Quick one', poll: { options: ['x', 'y'] } }); // default: 24 hours
    expect(short.poll.secondsLeft).toBeLessThanOrEqual(24 * 3600);
    await call('POST', `/posts/${p.id}/vote`, b.token, { optionIndex: 0 });
    await pool.query("update posts set poll_ends_at = now() - interval '1 minute' where id = $1", [p.id]);
    const ended = (await call('GET', `/posts/${p.id}`, a.token)).body;
    expect(ended.poll.ended).toBe(true);
    expect(ended.poll.secondsLeft).toBe(0);
    expect(ended.poll.options.map((o: { votes: number }) => o.votes)).toEqual([1, 0]); // results stay
    const late = await call('POST', `/posts/${p.id}/vote`, a.token, { optionIndex: 1 });
    expect(late.status).toBe(409);
    expect(late.body.code ?? late.body.error).toBe('poll_ended');
  });

  // ---- reposts and views
  it('reposts toggle once per student and views count once per student', async () => {
    const [a, b, c] = [await student(), await student(), await student()];
    const p = await post(a, { text: 'worth a repost' });
    expect((await call('PUT', `/posts/${p.id}/repost`, b.token)).status).toBe(204);
    expect((await call('PUT', `/posts/${p.id}/repost`, b.token)).status).toBe(204); // repeating changes nothing
    const seen = (await call('GET', `/posts/${p.id}`, b.token)).body;
    expect(seen.repostCount).toBe(1);
    expect(seen.repostedByMe).toBe(true);
    expect((await call('GET', `/posts/${p.id}`, c.token)).body.repostedByMe).toBe(false);
    await call('DELETE', `/posts/${p.id}/repost`, b.token);
    expect((await call('GET', `/posts/${p.id}`, b.token)).body.repostCount).toBe(0);

    await call('POST', '/posts/views', b.token, { ids: [p.id] });
    await call('POST', '/posts/views', b.token, { ids: [p.id] });
    await call('POST', '/posts/views', c.token, { ids: [p.id] });
    await call('POST', '/posts/views', a.token, { ids: [p.id] }); // the author's own look is not a view
    expect((await call('GET', `/posts/${p.id}`, a.token)).body.viewCount).toBe(2);
    expect((await call('POST', '/posts/views', b.token, { ids: [] })).status).toBe(400);
  });

  // ---- editing and deleting
  it('only the author edits or deletes a post', async () => {
    const [a, b] = [await student(), await student()];
    const p = await post(a, { text: 'typo hre' });
    expect((await call('PUT', `/posts/${p.id}`, b.token, { text: 'hijack' })).status).toBe(403);
    expect((await call('DELETE', `/posts/${p.id}`, b.token)).status).toBe(403);
    const edited = await call('PUT', `/posts/${p.id}`, a.token, { text: 'typo here' });
    expect(edited.body.text).toBe('typo here');
    expect(edited.body.edited).toBe(true);
    expect((await call('PUT', `/posts/${p.id}`, a.token, { text: '  ' })).status).toBe(400);
    expect((await call('DELETE', `/posts/${p.id}`, a.token)).status).toBe(204);
    expect((await call('GET', `/posts/${p.id}`, a.token)).status).toBe(404);
  });

  // ---- comments
  it('comments thread under the top-level comment and carry who they answer', async () => {
    const [a, b, c] = [await student(), await student(), await student()];
    const p = await post(a, { text: 'discuss' });
    const top = (await call('POST', `/posts/${p.id}/comments`, b.token, { text: 'first' })).body;
    expect(top.parentId).toBeNull();
    expect(top.isMine).toBe(true);
    const reply1 = (await call('POST', `/posts/${p.id}/comments`, c.token, { text: 'reply', replyToId: top.id })).body;
    expect(reply1.parentId).toBe(top.id);
    expect(reply1.replyToHandle).toBe(b.handle);
    const reply2 = (await call('POST', `/posts/${p.id}/comments`, a.token, { text: 'reply to reply', replyToId: reply1.id })).body;
    expect(reply2.parentId).toBe(top.id); // still under the top-level comment
    expect(reply2.replyToHandle).toBe(c.handle);
    expect(reply2.isPostAuthor).toBe(true);

    const list = (await call('GET', `/posts/${p.id}/comments`, c.token)).body;
    expect(list.map((x: { id: string }) => x.id)).toEqual([top.id, reply1.id, reply2.id]);
    expect(list.find((x: { id: string }) => x.id === reply1.id).isMine).toBe(true);
    expect((await call('GET', `/posts/${p.id}`, a.token)).body.commentCount).toBe(3);
    expect((await call('POST', `/posts/${p.id}/comments`, b.token, { text: 'x', replyToId: '00000000-0000-4000-8000-000000000000' })).status).toBe(404);
    expect((await call('POST', `/posts/${p.id}/comments`, b.token, { text: '' })).status).toBe(400);
  });

  it('comment likes, reports and deletion rules', async () => {
    const [a, b, c] = [await student(), await student(), await student()];
    const p = await post(a, { text: 'discuss' });
    const top = (await call('POST', `/posts/${p.id}/comments`, b.token, { text: 'mine' })).body;
    await call('POST', `/posts/${p.id}/comments`, c.token, { text: 'a reply', replyToId: top.id });

    await call('PUT', `/comments/${top.id}/like`, c.token);
    await call('PUT', `/comments/${top.id}/like`, c.token);
    let seen = (await call('GET', `/posts/${p.id}/comments`, c.token)).body.find((x: { id: string }) => x.id === top.id);
    expect(seen.likeCount).toBe(1);
    expect(seen.likedByMe).toBe(true);
    await call('DELETE', `/comments/${top.id}/like`, c.token);
    seen = (await call('GET', `/posts/${p.id}/comments`, c.token)).body.find((x: { id: string }) => x.id === top.id);
    expect(seen.likeCount).toBe(0);

    expect((await call('POST', `/comments/${top.id}/report`, c.token, { reason: 'rude' })).status).toBe(204);
    expect((await call('DELETE', `/comments/${top.id}`, c.token)).status).toBe(403); // neither author nor post author
    expect((await call('DELETE', `/comments/${top.id}`, a.token)).status).toBe(204); // the post's author may moderate
    const left = (await call('GET', `/posts/${p.id}/comments`, a.token)).body;
    expect(left).toEqual([]); // the reply went with its parent
  });

  it('comments on a private post are hidden from outsiders', async () => {
    const [a, stranger] = [await student(), await student()];
    const p = await post(a, { text: 'secret', audience: 'followers' });
    expect((await call('GET', `/posts/${p.id}/comments`, stranger.token)).status).toBe(404);
    expect((await call('POST', `/posts/${p.id}/comments`, stranger.token, { text: 'hi' })).status).toBe(404);
  });

  // ---- people
  it('stats, profiles and follow lists', async () => {
    const [a, b, outsider] = [await student(), await student(), await student('other')];
    await post(a, { text: 'one' });
    await post(a, { text: 'two' });
    await call('PUT', `/students/${a.id}/follow`, b.token);
    await call('PUT', `/students/${b.id}/follow`, a.token);
    const saved = await post(b, { text: 'save me' });
    await call('PUT', `/posts/${saved.id}/save`, a.token);

    expect((await call('GET', '/me/stats', a.token)).body).toEqual({ posts: 2, followers: 1, following: 1, saved: 1 });
    const profile = (await call('GET', `/students/${a.id}`, b.token)).body;
    expect(profile.student.id).toBe(a.id);
    expect(profile.posts).toBe(2);
    expect(profile.followers).toBe(1);
    expect(profile.followedByMe).toBe(true);
    expect((await call('GET', `/students/${a.id}`, outsider.token)).status).toBe(404);

    const followers = (await call('GET', '/me/followers', a.token)).body;
    expect(followers.map((f: { student: { id: string } }) => f.student.id)).toEqual([b.id]);
    expect(followers[0].isFollowing).toBe(true); // a follows b back
    const following = (await call('GET', '/me/following', a.token)).body;
    expect(following.map((f: { student: { id: string } }) => f.student.id)).toEqual([b.id]);
  });

  it('cannot follow yourself, an outsider or a stranger id', async () => {
    const [a, outsider] = [await student(), await student('other')];
    expect((await call('PUT', `/students/${a.id}/follow`, a.token)).status).toBe(400);
    expect((await call('PUT', `/students/${outsider.id}/follow`, a.token)).status).toBe(404);
    expect((await call('PUT', '/students/00000000-0000-4000-8000-000000000000/follow', a.token)).status).toBe(404);
  });

  it('deleting an account removes its posts', async () => {
    const [a, b] = [await student(), await student()];
    const p = await post(a, { text: 'bye' });
    expect((await call('POST', '/me/delete-account', a.token, { password: 'correct-horse-9' })).status).toBe(204);
    expect((await call('GET', `/posts/${p.id}`, b.token)).status).toBe(404);
  });
});
