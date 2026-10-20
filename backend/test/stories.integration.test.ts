import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { purgeExpiredStories } from '../src/modules/stories';
import { harness, url } from './helpers';

describe.skipIf(!url)('stories (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';

  beforeAll(async () => {
    pool = createPool(url as string);
    await migrate(pool);
    await pool.query("insert into universities (id, name, email_domains) values ('other', 'Other University', array['other.example']) on conflict do nothing");
  });
  afterAll(async () => { await pool.end(); });
  beforeEach(async () => { await pool.query('delete from stories'); h.make(); });
  afterEach(async () => { await h.close(); });

  const post = (token: string, body: unknown) => call('POST', `${V1}/stories`, token, body);
  const tray = async (token: string) => (await call('GET', `${V1}/stories`, token)).body;
  const of = async (viewer: string, author: string) => call('GET', `${V1}/users/${author}/stories`, viewer);

  it('requires sign-in and validates what a Story may contain', async () => {
    const a = await h.student();
    expect((await call('GET', `${V1}/stories`)).status).toBe(401);
    expect((await post(a.token, { kind: 'text', text: '   ' })).status).toBe(400);
    expect((await post(a.token, { kind: 'text', text: 'x'.repeat(281) })).status).toBe(400);
    expect((await post(a.token, { kind: 'text', text: '😀'.repeat(280) })).status).toBe(201); // emoji count as one
    expect((await post(a.token, { kind: 'text', text: 'hi', caption: 'no' })).status).toBe(400);
    expect((await post(a.token, { kind: 'photo' })).status).toBe(400); // needs a file
    expect((await post(a.token, { kind: 'photo', mediaId: '00000000-0000-4000-8000-000000000000' })).status).toBe(400);
    expect((await post(a.token, { kind: 'text', text: 'hi', authorId: a.id })).status).toBe(400); // author always comes from the token
    expect((await post(a.token, { kind: 'text', text: 'hi', audience: 'public' })).status).toBe(400);
  });

  it('a text Story shows in the tray, is viewed once per viewer, and the ring state follows', async () => {
    const a = await h.student();
    const b = await h.student();
    const made = await post(a.token, { kind: 'text', text: 'Exams done 🎉', background: 'indigo' });
    expect(made.status).toBe(201);
    expect(made.body).toMatchObject({ kind: 'text', text: 'Exams done 🎉', background: 'indigo', mediaUrl: null, viewedByMe: true });
    const t = (await tray(b.token)).items.find((g: { author: { id: string } }) => g.author.id === a.id);
    expect(t).toMatchObject({ count: 1, hasUnviewed: true, isMine: false });
    const list = await of(b.token, a.id);
    expect(list.body.items).toHaveLength(1);
    expect(list.body.items[0].viewedByMe).toBe(false);
    // Reporting the view repeatedly records one view and flips the ring to "viewed".
    for (let i = 0; i < 3; i++) expect((await call('POST', `${V1}/stories/${made.body.id}/view`, b.token)).status).toBe(204);
    expect((await pool.query('select count(*)::int as n from story_views where story_id = $1', [made.body.id])).rows[0].n).toBe(1);
    expect((await tray(b.token)).items.find((g: { author: { id: string } }) => g.author.id === a.id).hasUnviewed).toBe(false);
    // The author's own view is not counted, and my own Story leads the tray.
    await call('POST', `${V1}/stories/${made.body.id}/view`, a.token);
    expect((await pool.query('select count(*)::int as n from story_views where story_id = $1', [made.body.id])).rows[0].n).toBe(1);
    expect((await tray(a.token)).items[0]).toMatchObject({ isMine: true });
  });

  it('a photo Story needs the author’s own finished upload and uses it only once', async () => {
    const a = await h.student();
    const b = await h.student();
    const img = await h.image(a.token, 'story');
    const made = await post(a.token, { kind: 'photo', mediaId: img.id, caption: 'view from the library', clientId: 'c1' });
    expect(made.status).toBe(201);
    expect(made.body.kind).toBe('photo');
    expect(made.body.mediaUrl).toContain(img.key.split('.')[0].split('/')[0]); // a signed link, minted only on read
    expect(made.body.caption).toBe('view from the library');
    expect((await post(a.token, { kind: 'photo', mediaId: img.id })).status).toBe(409); // already used
    const theirs = await h.image(b.token, 'story');
    expect((await post(a.token, { kind: 'photo', mediaId: theirs.id })).status).toBe(400); // someone else's file
    const asPost = await h.image(a.token, 'post');
    expect((await post(a.token, { kind: 'photo', mediaId: asPost.id })).status).toBe(400); // wrong purpose
    // A retry with the same clientId returns the same Story instead of making a second.
    const again = await post(a.token, { kind: 'photo', mediaId: img.id, clientId: 'c1' });
    expect(again.status).toBe(200);
    expect(again.body.id).toBe(made.body.id);
    expect((await pool.query('select count(*)::int as n from stories where author_id = $1', [a.id])).rows[0].n).toBe(1);
  });

  it('video Stories need a length and respect the limit at upload time', async () => {
    const a = await h.student();
    const ask = (body: object) => call('POST', '/media/uploads', a.token, { kind: 'video', purpose: 'story', contentType: 'video/mp4', size: 1000, ...body });
    expect((await ask({})).status).toBe(400); // no length
    expect((await ask({ durationMs: 31_000 })).status).toBe(400); // too long
    expect((await ask({ durationMs: 30_000 })).status).toBe(201);
    expect((await call('POST', '/media/uploads', a.token, { kind: 'image', purpose: 'story', contentType: 'image/jpeg', size: 1000, durationMs: 5000 })).status).toBe(400);
  });

  it('audience, blocks, suspension and other universities are enforced on every read', async () => {
    const author = await h.student();
    const follower = await h.student();
    const stranger = await h.student();
    const outsider = await h.student('other.example', 'other');
    await call('PUT', `/students/${author.id}/follow`, follower.token);
    const s = await post(author.token, { kind: 'text', text: 'friends only', audience: 'followers' });
    expect((await of(follower.token, author.id)).status).toBe(200);
    for (const v of [stranger, outsider]) {
      expect((await of(v.token, author.id)).status).toBe(404);
      expect((await call('POST', `${V1}/stories/${s.body.id}/view`, v.token)).status).toBe(404);
      expect((await tray(v.token)).items.some((g: { author: { id: string } }) => g.author.id === author.id)).toBe(false);
    }
    // Losing the follow takes access away at once, even though the follower had seen it.
    await call('DELETE', `/students/${author.id}/follow`, follower.token);
    expect((await of(follower.token, author.id)).status).toBe(404);
    await call('PUT', `/students/${author.id}/follow`, follower.token);
    expect((await call('POST', `${V1}/users/${follower.id}/block`, author.token)).status).toBe(204);
    expect((await of(follower.token, author.id)).status).toBe(404);
    await pool.query("update users set suspended_until = now() + interval '1 day' where id = $1", [author.id]);
    expect((await of(stranger.token, author.id)).status).toBe(404);
  });

  it('expiry uses the server clock: an expired Story cannot be listed, viewed or counted', async () => {
    const a = await h.student();
    const b = await h.student();
    const s = await post(a.token, { kind: 'text', text: 'brief' });
    expect(Date.parse(s.body.expiresAt) - Date.parse(s.body.createdAt)).toBe(24 * 3600 * 1000);
    await pool.query("update stories set expires_at = now() - interval '1 second' where id = $1", [s.body.id]);
    expect((await of(b.token, a.id)).status).toBe(404);
    expect((await call('POST', `${V1}/stories/${s.body.id}/view`, b.token)).status).toBe(404);
    expect((await tray(b.token)).items.some((g: { author: { id: string } }) => g.author.id === a.id)).toBe(false);
    expect((await tray(a.token)).items).toEqual([]);
  });

  it('only the author can delete, and the file goes with the Story', async () => {
    const a = await h.student();
    const b = await h.student();
    const img = await h.image(a.token, 'story');
    const s = await post(a.token, { kind: 'photo', mediaId: img.id });
    expect(h.storage.objects.has(img.key)).toBe(true);
    expect((await call('DELETE', `${V1}/stories/${s.body.id}`, b.token)).status).toBe(404);
    expect((await of(b.token, a.id)).status).toBe(200);
    expect((await call('DELETE', `${V1}/stories/${s.body.id}`, a.token)).status).toBe(204);
    expect((await call('DELETE', `${V1}/stories/${s.body.id}`, a.token)).status).toBe(404);
    expect((await of(b.token, a.id)).status).toBe(404);
    expect((await pool.query('select 1 from media where id = $1', [img.id])).rowCount).toBe(0);
    expect(h.storage.objects.has(img.key)).toBe(false);
  });

  it('cleanup removes Stories (and their files) that expired over an hour ago, and is safe to repeat', async () => {
    const a = await h.student();
    const img = await h.image(a.token, 'story');
    const old = await post(a.token, { kind: 'photo', mediaId: img.id });
    const recent = await post(a.token, { kind: 'text', text: 'just expired' });
    await pool.query("update stories set expires_at = now() - interval '2 hours' where id = $1", [old.body.id]);
    await pool.query("update stories set expires_at = now() - interval '5 minutes' where id = $1", [recent.body.id]);
    // Without storage the files cannot be deleted, so nothing is removed and it can be retried later.
    expect((await purgeExpiredStories(pool, null)).stories).toBe(0);
    expect((await purgeExpiredStories(pool, h.storage)).stories).toBe(1);
    expect(h.storage.objects.has(img.key)).toBe(false);
    expect((await pool.query('select 1 from stories where id = $1', [old.body.id])).rowCount).toBe(0);
    expect((await pool.query('select 1 from stories where id = $1', [recent.body.id])).rowCount).toBe(1); // within the hour: kept
    expect((await purgeExpiredStories(pool, h.storage)).stories).toBe(0);
  });

});
