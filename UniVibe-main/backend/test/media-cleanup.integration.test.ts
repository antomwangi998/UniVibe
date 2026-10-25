import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { purgeUnusedMedia } from '../src/media_cleanup';
import { harness, url } from './helpers';

describe.skipIf(!url)('unused media cleanup (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;

  beforeAll(async () => {
    pool = createPool(url as string);
    await migrate(pool);
  });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  const age = (id: string, hours: number) =>
    pool.query("update media set created_at = now() - make_interval(hours => $2::int) where id = $1", [id, hours]);
  const exists = async (id: string) => (await pool.query('select 1 from media where id = $1', [id])).rowCount === 1;

  it('removes an old unused upload and its file, keeps a recent one, and finds nothing the second time', async () => {
    const a = await h.student();
    const old = await h.image(a.token, 'post');
    const recent = await h.image(a.token, 'post');
    await age(old.id, 30);
    await age(recent.id, 2); // still inside the 24 hour grace period

    const first = await purgeUnusedMedia(pool, h.storage);
    expect(first.media).toBeGreaterThanOrEqual(1);
    expect(await exists(old.id)).toBe(false);
    expect(h.storage.objects.has(old.key)).toBe(false);
    expect(await exists(recent.id)).toBe(true);
    expect(h.storage.objects.has(recent.key)).toBe(true);

    expect((await purgeUnusedMedia(pool, h.storage)).media).toBe(0);
  });

  it('never touches media that is in use, however old', async () => {
    const a = await h.student();
    const onPost = await h.image(a.token, 'post');
    expect((await call('POST', '/posts', a.token, { text: '', mediaIds: [onPost.id] })).status).toBe(201);
    const avatar = await h.image(a.token, 'avatar');
    expect((await call('PUT', '/me/photos', a.token, { avatarMediaId: avatar.id })).status).toBe(200);
    const storyImage = await h.image(a.token, 'story');
    expect((await call('POST', '/api/v1/stories', a.token, { kind: 'photo', mediaId: storyImage.id })).status).toBe(201);

    for (const m of [onPost, avatar, storyImage]) await age(m.id, 24 * 10);
    await purgeUnusedMedia(pool, h.storage);

    for (const m of [onPost, avatar, storyImage]) {
      expect(await exists(m.id)).toBe(true);
      expect(h.storage.objects.has(m.key)).toBe(true);
    }
  });

  it('protects anything that references media, including tables added later', async () => {
    const a = await h.student();
    const m = await h.image(a.token, 'post');
    await pool.query('create table if not exists gc_test_refs (media_id uuid references media(id) on delete cascade)');
    try {
      await pool.query('insert into gc_test_refs (media_id) values ($1)', [m.id]);
      await age(m.id, 48);
      await purgeUnusedMedia(pool, h.storage);
      expect(await exists(m.id)).toBe(true); // something points at it

      await pool.query('delete from gc_test_refs where media_id = $1', [m.id]);
      await purgeUnusedMedia(pool, h.storage);
      expect(await exists(m.id)).toBe(false); // nothing does any more
    } finally {
      await pool.query('drop table if exists gc_test_refs');
    }
  });

  it('honours the grace period setting and the batch size, and leaves unfinished uploads to the other sweep', async () => {
    const a = await h.student();
    const one = await h.image(a.token, 'post');
    await age(one.id, 5);
    await purgeUnusedMedia(pool, h.storage); // default 24 hours: too new
    expect(await exists(one.id)).toBe(true);
    await purgeUnusedMedia(pool, h.storage, { graceHours: 4 });
    expect(await exists(one.id)).toBe(false);

    const up = await call('POST', '/media/uploads', a.token, { kind: 'image', purpose: 'post', contentType: 'image/jpeg', size: 10 });
    await age(up.body.mediaId, 100); // still pending: not this job's business
    await purgeUnusedMedia(pool, h.storage);
    expect(await exists(up.body.mediaId)).toBe(true);

    const two = await h.image(a.token, 'post');
    const three = await h.image(a.token, 'post');
    await age(two.id, 30);
    await age(three.id, 30);
    const r = await purgeUnusedMedia(pool, h.storage, { batch: 1, maxBatches: 1 });
    expect(r.media).toBeLessThanOrEqual(1);
  });

  it('keeps the file queued when storage is unavailable, and removes it once storage is back', async () => {
    const a = await h.student();
    const m = await h.image(a.token, 'post');
    await age(m.id, 30);
    const before = await purgeUnusedMedia(pool, null); // no storage: rows go, files are queued
    expect(before.media).toBeGreaterThanOrEqual(1);
    expect(await exists(m.id)).toBe(false);
    expect(h.storage.objects.has(m.key)).toBe(true);
    expect((await pool.query('select 1 from storage_garbage where object_key = $1', [m.key])).rowCount).toBe(1);

    await purgeUnusedMedia(pool, h.storage);
    expect(h.storage.objects.has(m.key)).toBe(false);
    expect((await pool.query('select 1 from storage_garbage where object_key = $1', [m.key])).rowCount).toBe(0);
  });
});
