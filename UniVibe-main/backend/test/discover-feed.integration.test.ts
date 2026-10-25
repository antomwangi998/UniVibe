import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, url } from './helpers';

describe.skipIf(!url)('discover content feed (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const D = '/api/v1/discover';

  beforeAll(async () => {
    pool = createPool(url as string);
    await migrate(pool);
    await pool.query("insert into universities (id, name, email_domains) values ('other', 'Other University', array['other.example']) on conflict do nothing");
  });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  let n = 0;
  const mark = () => `m${Date.now().toString(36)}${n++}`; // unique, lowercase, safe inside a hashtag
  type Tok = { token: string; id: string };
  const post = async (s: Tok, text: string, audience: 'campus' | 'followers' = 'campus') => {
    const res = await call('POST', '/posts', s.token, { text, audience });
    expect(res.status, JSON.stringify(res.body)).toBe(201);
    return res.body.id as string;
  };
  const like = (s: Tok, postId: string) => call('PUT', `/posts/${postId}/like`, s.token);
  const comment = (s: Tok, postId: string, text = 'nice') => call('POST', `/posts/${postId}/comments`, s.token, { text });
  const students = async (count: number) => Promise.all(Array.from({ length: count }, () => h.student()));
  const walk = async (path: string, token: string, extra = '', limit = 30) => {
    const items: { id: string; reason: string | null; author: { id: string }; text: string }[] = [];
    let cursor: string | null = null;
    for (let i = 0; i < 40; i++) {
      const res: { status: number; body: { items: typeof items; nextCursor: string | null } } =
        await call('GET', `${D}/${path}?limit=${limit}${extra}${cursor ? `&cursor=${cursor}` : ''}`, token);
      expect(res.status, JSON.stringify(res.body)).toBe(200);
      items.push(...res.body.items);
      cursor = res.body.nextCursor;
      if (!cursor) break;
    }
    return items;
  };
  const textsOf = (items: { text: string }[], m: string) => items.filter((i) => i.text.includes(m)).map((i) => i.text);

  it('every endpoint needs a signed-in student', async () => {
    for (const path of ['feed', 'trending', 'photos', 'videos', 'topics', 'topics/abc']) {
      expect((await call('GET', `${D}/${path}`)).status, path).toBe(401);
    }
    expect((await call('POST', `${D}/feedback`, undefined, { kind: 'mute_topic', topic: 'abc' })).status).toBe(401);
  });

  it('For You shows only what the student may see, never their own posts, and explains only real signals', async () => {
    const [viewer, followed, plain, blocked, blocker, outsider] = await Promise.all([h.student(), h.student(), h.student(), h.student(), h.student(), h.student('other.example', 'other')]);
    const m = mark();
    await call('PUT', `/students/${followed.id}/follow`, viewer.token);
    await post(viewer, `${m} own post`);
    const pFollowed = await post(followed, `${m} from followed`);
    await post(plain, `${m} plain campus post`);
    await post(plain, `${m} followers only`, 'followers');
    await post(outsider, `${m} other university`);
    const pBlocked = await post(blocked, `${m} from someone I blocked`);
    await post(blocker, `${m} from someone who blocked me`);
    await call('POST', `/api/v1/users/${blocked.id}/block`, viewer.token);
    await call('POST', `/api/v1/users/${viewer.id}/block`, blocker.token);
    const removed = await post(plain, `${m} removed by moderators`);
    await pool.query("update posts set status = 'removed' where id = $1", [removed]);

    const items = await walk('feed', viewer.token);
    expect(textsOf(items, m).sort()).toEqual([`${m} from followed`, `${m} plain campus post`].sort());
    expect(items.find((i) => i.id === pFollowed)?.reason).toBe('following');
    expect(items.find((i) => i.text === `${m} plain campus post`)?.reason).toBeNull(); // nothing to claim, so nothing claimed
    expect(items.some((i) => i.id === pBlocked)).toBe(false);
  });

  it('suspended authors disappear from Discover', async () => {
    const [viewer, author] = await students(2);
    const m = mark();
    await post(author, `${m} before suspension`);
    expect(textsOf(await walk('feed', viewer.token), m)).toHaveLength(1);
    await pool.query("update users set suspended_until = now() + interval '1 day' where id = $1", [author.id]);
    expect(textsOf(await walk('feed', viewer.token), m)).toHaveLength(0);
  });

  it('pages are stable: no repeats, nothing skipped, even while new posts arrive', async () => {
    const viewer = await h.student();
    const authors = await students(5);
    const m = mark();
    const ids: string[] = [];
    for (const a of authors) ids.push(await post(a, `${m} post`));
    const first = await call('GET', `${D}/feed?limit=2`, viewer.token);
    expect(first.body.items.length).toBe(2);
    expect(first.body.nextCursor).toBeTruthy();
    // a new post lands between page 1 and page 2: the ranking of this feed must not shift under the reader
    const late = await post(authors[0], `${m} arrived late`);
    const seen: string[] = first.body.items.map((i: { id: string }) => i.id);
    let cursor: string | null = first.body.nextCursor;
    for (let i = 0; cursor && i < 40; i++) {
      const res: { body: { items: { id: string }[]; nextCursor: string | null } } = await call('GET', `${D}/feed?limit=2&cursor=${cursor}`, viewer.token);
      seen.push(...res.body.items.map((x) => x.id));
      cursor = res.body.nextCursor;
    }
    expect(new Set(seen).size).toBe(seen.length);
    expect(seen).not.toContain(late);
    for (const id of ids) expect(seen).toContain(id);
    expect((await call('GET', `${D}/feed?cursor=not-a-cursor`, viewer.token)).status).toBe(400);
    expect((await call('GET', `${D}/feed?limit=0`, viewer.token)).status).toBe(400);
  });

  it('one author cannot fill the feed', async () => {
    const [viewer, spammer] = await students(2);
    const m = mark();
    for (let i = 0; i < 6; i++) await post(spammer, `${m} spam ${i}`);
    expect(textsOf(await walk('feed', viewer.token), m).length).toBe(3);
    expect(textsOf(await walk('trending', viewer.token), m).length).toBeLessThanOrEqual(2);
  });

  it('engagement from different people ranks a post higher, new posts still appear, and one account cannot pump a post', async () => {
    const [viewer, a, b, fans1, fans2, fans3] = await students(6);
    const m = mark();
    const quiet = await post(a, `${m} quiet`);
    const popular = await post(b, `${m} popular`);
    for (const f of [fans1, fans2, fans3]) await like(f, popular);
    await comment(fans1, popular);
    for (let i = 0; i < 5; i++) await like(a, quiet); // repeating changes nothing (one like per person)
    await like(a, quiet);
    await like(b, popular); // an author liking their own post counts for nothing
    const order = (await walk('feed', viewer.token)).filter((i) => i.text.includes(m)).map((i) => i.id);
    expect(order).toEqual([popular, quiet]); // quiet is still visible: no engagement is not "invisible"
  });

  it('Trending needs two different people, respects blocks and removals even when cached, and is per university', async () => {
    const [viewer, other, author, fan1, fan2, fan3] = await students(6);
    const outsider = await h.student('other.example', 'other');
    const m = mark();
    const lonely = await post(author, `${m} one fan`);
    await like(fan1, lonely);
    const hot = await post(author, `${m} two fans`);
    await like(fan1, hot); await like(fan2, hot);
    const hotter = await post(other, `${m} three fans`);
    await like(fan1, hotter); await like(fan2, hotter); await like(fan3, hotter);

    const t1 = await walk('trending', viewer.token);
    expect(textsOf(t1, m)).toEqual([`${m} three fans`, `${m} two fans`]); // lonely never trends; more people rank higher
    expect(t1.every((i) => i.reason === 'trending_campus')).toBe(true);
    // the ranking is now cached; a block must still apply instantly, for this student only
    await call('POST', `/api/v1/users/${author.id}/block`, viewer.token);
    expect(textsOf(await walk('trending', viewer.token), m)).toEqual([`${m} three fans`]);
    expect(textsOf(await walk('trending', fan3.token), m)).toEqual([`${m} three fans`, `${m} two fans`]);
    // a moderator removal beats the cache too
    await pool.query("update posts set status = 'removed' where id = $1", [hotter]);
    expect(textsOf(await walk('trending', fan3.token), m)).toEqual([`${m} two fans`]);
    // another university never sees this campus
    expect(textsOf(await walk('trending', outsider.token), m)).toEqual([]);
    expect((await call('GET', `${D}/trending?window=7d`, viewer.token)).status).toBe(200);
    expect((await call('GET', `${D}/trending?window=1y`, viewer.token)).status).toBe(400);
  });

  it('photos and videos only list ready media of that kind from visible campus posts', async () => {
    const [viewer, author] = await students(2);
    const m = mark();
    const withPhoto = await post(author, `${m} photo`);
    const withVideo = await post(author, `${m} video`);
    const pending = await post(author, `${m} still processing`);
    const textOnly = await post(author, `${m} words only`);
    const add = (postId: string, kind: 'image' | 'video', status: 'ready' | 'pending') =>
      pool.query(
        `insert into media (owner_id, kind, purpose, content_type, size_bytes, object_key, status, post_id) values ($1, $2, 'post', $3, 10, $4, $5, $6)`,
        [author.id, kind, kind === 'image' ? 'image/jpeg' : 'video/mp4', `k/${postId}/${kind}`, status, postId],
      );
    await add(withPhoto, 'image', 'ready'); await add(withVideo, 'video', 'ready'); await add(pending, 'image', 'pending');
    const photos = (await walk('photos', viewer.token)).map((i) => i.id);
    const videos = (await walk('videos', viewer.token)).map((i) => i.id);
    expect(photos).toContain(withPhoto); expect(photos).not.toContain(withVideo);
    expect(photos).not.toContain(pending); expect(photos).not.toContain(textOnly);
    expect(videos).toContain(withVideo); expect(videos).not.toContain(withPhoto);
    await call('POST', `/api/v1/users/${author.id}/block`, viewer.token);
    expect((await walk('photos', viewer.token)).map((i) => i.id)).not.toContain(withPhoto);
  });

  it('topics need two different authors, ignore case, and a hashtag never opens up private posts', async () => {
    const [viewer, a, b, solo] = await students(4);
    const shared = `tg${mark()}`;
    const only = `tg${mark()}`;
    await post(a, `Studying for finals #${shared.toUpperCase()}`);
    await post(b, `anyone up? #${shared}`);
    await post(b, `members only #${shared}`, 'followers');
    for (let i = 0; i < 4; i++) await post(solo, `me again #${only}`);
    const topics = (await call('GET', `${D}/topics`, viewer.token)).body.items as { tag: string; posts: number; authors: number }[];
    const t = topics.find((x) => x.tag === shared);
    expect(t).toMatchObject({ authors: 2, posts: 2 }); // the followers-only post is not counted
    expect(topics.some((x) => x.tag === only)).toBe(false); // one account repeating itself is not a topic

    const inTopic = await walk(`topics/${shared}`, viewer.token);
    expect(inTopic.map((i) => i.text).sort()).toEqual([`Studying for finals #${shared.toUpperCase()}`, `anyone up? #${shared}`].sort());
    expect((await call('GET', `${D}/topics/${encodeURIComponent('bad tag!')}`, viewer.token)).status).toBe(404);
    expect((await call('GET', `${D}/topics/x`, viewer.token)).status).toBe(404);
    // a stranger from another university sees nothing under the same tag
    const outsider = await h.student('other.example', 'other');
    expect((await walk(`topics/${shared}`, outsider.token))).toEqual([]);
  });

  it('feedback is saved, private, repeatable, reversible, and shapes only the student who gave it', async () => {
    const [viewer, other, author, second] = await students(4);
    const m = mark();
    const tag = `tg${mark()}`;
    const p1 = await post(author, `${m} one`);
    const p2 = await post(second, `${m} two #${tag}`);
    const p3 = await post(second, `${m} three`);
    const ids = async (s: Tok) => (await walk('feed', s.token)).filter((i) => i.text.includes(m)).map((i) => i.id);
    expect((await ids(viewer)).sort()).toEqual([p1, p2, p3].sort());

    // not interested: that post goes, the rest stay; saved once however often it is sent
    expect((await call('POST', `${D}/feedback`, viewer.token, { kind: 'not_interested', postId: p1 })).status).toBe(204);
    expect((await call('POST', `${D}/feedback`, viewer.token, { kind: 'not_interested', postId: p1 })).status).toBe(204);
    expect(Number((await pool.query('select count(*) from discover_feedback where user_id = $1', [viewer.id])).rows[0].count)).toBe(1);
    expect((await ids(viewer)).sort()).toEqual([p2, p3].sort());
    expect((await ids(other)).sort()).toEqual([p1, p2, p3].sort()); // nobody else is affected

    // mute topic (case and # are normalised), then hide author
    expect((await call('POST', `${D}/feedback`, viewer.token, { kind: 'mute_topic', topic: `#${tag.toUpperCase()}` })).status).toBe(204);
    expect(await ids(viewer)).toEqual([p3]);
    expect((await call('POST', `${D}/feedback`, viewer.token, { kind: 'hide_author', authorId: second.id })).status).toBe(204);
    expect(await ids(viewer)).toEqual([]);
    // undo brings them back
    await call('POST', `${D}/feedback/undo`, viewer.token, { kind: 'hide_author', authorId: second.id });
    await call('POST', `${D}/feedback/undo`, viewer.token, { kind: 'mute_topic', topic: tag });
    await call('POST', `${D}/feedback/undo`, viewer.token, { kind: 'not_interested', postId: p1 });
    expect((await ids(viewer)).sort()).toEqual([p1, p2, p3].sort());
  });

  it('feedback validates its input and cannot be used to probe other universities or hidden posts', async () => {
    const [viewer, author] = await students(2);
    const outsider = await h.student('other.example', 'other');
    const priv = await post(author, 'private to followers', 'followers');
    const fb = (body: unknown) => call('POST', `${D}/feedback`, viewer.token, body);
    expect((await fb({})).status).toBe(400);
    expect((await fb({ kind: 'nonsense' })).status).toBe(400);
    expect((await fb({ kind: 'mute_topic', topic: 'x' })).status).toBe(400);
    expect((await fb({ kind: 'mute_topic', topic: "a'; drop table users;--" })).status).toBe(400);
    expect((await fb({ kind: 'not_interested', postId: 'nope' })).status).toBe(400);
    expect((await fb({ kind: 'not_interested', postId: priv })).status).toBe(404);
    expect((await fb({ kind: 'hide_author', authorId: viewer.id })).status).toBe(400);
    expect((await fb({ kind: 'hide_author', authorId: outsider.id })).status).toBe(404);
    expect((await pool.query('select count(*) from discover_feedback where user_id = $1', [viewer.id])).rows[0].count).toBe('0');
  });
});
