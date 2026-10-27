import type { FastifyInstance } from 'fastify';
import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app';
import type { Config } from '../src/config';
import { createPool, migrate } from '../src/db';
import type { Mailer } from '../src/email';
import { createSharpProcessor } from '../src/imaging';
import { cleanupPendingMedia } from '../src/media';
import sharp from 'sharp';
import { MemoryStorage, createMemoryStorage } from '../src/storage';

const url = process.env.TEST_DATABASE_URL;

const JPEG = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0, 16, 0x4a, 0x46, 0x49, 0x46, 0, 1, 2, 3, 4, 5]);
const PNG = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0, 0, 0, 0, 0]);
const MP4 = Uint8Array.from([0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d, 0, 0, 0, 0]);

describe.skipIf(!url)('photos and videos (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  let app: FastifyInstance;
  let storage: MemoryStorage;
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
  beforeEach(() => { storage = createMemoryStorage(); app = buildApp({ pool, mailer, config, storage }); codes.clear(); });
  afterEach(async () => { await app.close(); });

  type Method = 'GET' | 'POST' | 'PUT' | 'DELETE';
  async function call(method: Method, path: string, token?: string, payload?: unknown, target: FastifyInstance = app) {
    const res = await target.inject({
      method, url: path, payload: payload as object | undefined,
      headers: token ? { authorization: `Bearer ${token}` } : undefined,
    });
    return { status: res.statusCode, body: res.body ? JSON.parse(res.body) : null };
  }

  async function student(universityId = 'lakeside') {
    const email = `m${Date.now()}x${n++}@${universityId}.example`;
    await call('POST', '/auth/register', undefined, { fullName: 'Media Student', email, universityId, password: 'correct-horse-9', acceptedTerms: true });
    const res = await call('POST', '/auth/verify-email', undefined, { email, code: codes.get(email) });
    expect(res.status).toBe(200);
    return { token: res.body.accessToken as string, id: res.body.user.id as string };
  }
  type Student = Awaited<ReturnType<typeof student>>;

  /** Asks for an upload link, "uploads" the bytes, and returns the media id and storage key. */
  async function upload(who: Student, kind: 'image' | 'video', purpose: 'post' | 'avatar' | 'cover', contentType: string, data: Uint8Array, sentAs = contentType) {
    const res = await call('POST', '/media/uploads', who.token, { kind, purpose, contentType, size: data.length });
    expect(res.status).toBe(201);
    expect(res.body.uploadUrl).toContain('https://upload.test/');
    expect(res.body.headers['Content-Type']).toBe(contentType);
    const { rows } = await pool.query<{ object_key: string }>('select object_key from media where id = $1', [res.body.mediaId]);
    storage.put(rows[0].object_key, data, sentAs);
    return { id: res.body.mediaId as string, key: rows[0].object_key };
  }
  const complete = (who: Student, id: string) => call('POST', `/media/${id}/complete`, who.token);
  const readyImage = async (who: Student, purpose: 'post' | 'avatar' | 'cover' = 'post') => {
    const file = await upload(who, 'image', purpose, 'image/jpeg', JPEG);
    expect((await complete(who, file.id)).status).toBe(200);
    return file;
  };

  it('health says whether uploads are available', async () => {
    expect((await call('GET', '/health')).body).toEqual({ status: 'ok', uploads: true });
    const bare = buildApp({ pool, mailer, config });
    const res = await call('GET', '/health', undefined, undefined, bare);
    await bare.close();
    expect(res.body).toEqual({ status: 'ok', uploads: false });
  });

  // ---- requesting an upload
  it('refuses unsupported or oversized uploads and profile videos', async () => {
    const a = await student();
    const ask = (body: object) => call('POST', '/media/uploads', a.token, body);
    expect((await ask({ kind: 'image', purpose: 'post', contentType: 'application/pdf', size: 10 })).status).toBe(400);
    expect((await ask({ kind: 'image', purpose: 'post', contentType: 'image/jpeg', size: 9 * 1024 * 1024 })).status).toBe(400);
    expect((await ask({ kind: 'video', purpose: 'post', contentType: 'video/mp4', size: 51 * 1024 * 1024 })).status).toBe(400);
    expect((await ask({ kind: 'video', purpose: 'avatar', contentType: 'video/mp4', size: 100 })).status).toBe(400);
    expect((await ask({ kind: 'image', purpose: 'post', contentType: 'image/jpeg', size: 0 })).status).toBe(400);
    expect((await call('POST', '/media/uploads', undefined, { kind: 'image', purpose: 'post', contentType: 'image/jpeg', size: 10 })).status).toBe(401);
  });

  it('answers 503 when the server has no storage', async () => {
    const bare = buildApp({ pool, mailer, config });
    const a = await student();
    const res = await call('POST', '/media/uploads', a.token, { kind: 'image', purpose: 'post', contentType: 'image/jpeg', size: 10 }, bare);
    await bare.close();
    expect(res.status).toBe(503);
    expect(res.body.error).toBe('storage_unavailable');
  });

  // ---- completing an upload
  it('completing before the file arrives fails, and works after', async () => {
    const a = await student();
    const res = await call('POST', '/media/uploads', a.token, { kind: 'image', purpose: 'post', contentType: 'image/jpeg', size: JPEG.length });
    const early = await complete(a, res.body.mediaId);
    expect(early.status).toBe(400);
    expect(early.body.error).toBe('upload_missing');
    const { rows } = await pool.query<{ object_key: string }>('select object_key from media where id = $1', [res.body.mediaId]);
    storage.put(rows[0].object_key, JPEG, 'image/jpeg');
    expect((await complete(a, res.body.mediaId)).status).toBe(200);
    expect((await complete(a, res.body.mediaId)).status).toBe(200); // repeating is harmless
  });

  it('a file that is not what it claims to be is rejected and deleted', async () => {
    const a = await student();
    const fake = await upload(a, 'image', 'post', 'image/jpeg', new TextEncoder().encode('<html><script>x</script></html>'));
    const res = await complete(a, fake.id);
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('invalid_media');
    expect(storage.objects.has(fake.key)).toBe(false);
    const left = await pool.query('select 1 from media where id = $1', [fake.id]);
    expect(left.rowCount).toBe(0);
  });

  it('a mismatched content type is rejected', async () => {
    const a = await student();
    const file = await upload(a, 'image', 'post', 'image/jpeg', PNG, 'image/png');
    expect((await complete(a, file.id)).status).toBe(400);
  });

  it('only the owner can complete an upload', async () => {
    const [a, b] = [await student(), await student()];
    const file = await upload(a, 'image', 'post', 'image/jpeg', JPEG);
    expect((await complete(b, file.id)).status).toBe(404);
  });

  // ---- posts with media
  it('a photo post shows its photos to the audience, with signed links', async () => {
    const [a, b, outsider] = [await student(), await student(), await student('other')];
    const first = await readyImage(a);
    const second = await readyImage(a);
    const created = await call('POST', '/posts', a.token, { text: '', mediaIds: [first.id, second.id] });
    expect(created.status).toBe(201);
    expect(created.body.text).toBe('');
    expect(created.body.media.map((m: { id: string }) => m.id)).toEqual([first.id, second.id]); // order kept
    expect(created.body.media[0].kind).toBe('image');
    expect(created.body.media[0].url).toContain(first.key);
    const seen = (await call('GET', '/posts', b.token)).body.find((p: { id: string }) => p.id === created.body.id);
    expect(seen.media).toHaveLength(2);
    expect((await call('GET', `/posts/${created.body.id}`, outsider.token)).status).toBe(404);
  });

  it('feed pages continue where the last one ended, without gaps or repeats', async () => {
    const [a, b] = [await student(), await student()];
    const made: string[] = [];
    for (let i = 0; i < 5; i++) made.push((await call('POST', '/posts', a.token, { text: `page post ${i}` })).body.id);
    // Other tests add newer posts at the top while this one runs, so compare only what is older than our newest post.
    const anchor = made[4];
    const all = (await call('GET', '/posts?limit=50', b.token)).body.map((p: { id: string }) => p.id) as string[];
    const below = all.slice(all.indexOf(anchor) + 1);
    expect(all.indexOf(anchor)).toBeGreaterThanOrEqual(0);
    const first = (await call('GET', `/posts?limit=2&before=${anchor}`, b.token)).body.map((p: { id: string }) => p.id);
    expect(first).toEqual(below.slice(0, 2));
    const second = (await call('GET', `/posts?limit=2&before=${first[1]}`, b.token)).body.map((p: { id: string }) => p.id);
    expect(second).toEqual(below.slice(2, 4));
    const third = (await call('GET', `/posts?limit=2&before=${second[1]}`, b.token)).body.map((p: { id: string }) => p.id);
    expect(third).toEqual(below.slice(4, 6));
    expect((await call('GET', '/posts?limit=2&before=00000000-0000-4000-8000-000000000000', b.token)).status).toBe(404);
    expect((await call('GET', '/posts?limit=500', b.token)).status).toBe(400);
  });

  it('lists one student\'s posts, newest first, in pages', async () => {
    const [a, b, c] = [await student(), await student(), await student()];
    const mine: string[] = [];
    for (let i = 0; i < 3; i++) mine.push((await call('POST', '/posts', a.token, { text: `by a ${i}` })).body.id);
    await call('POST', '/posts', b.token, { text: 'by b' });
    const all = (await call('GET', `/posts?author=${a.id}`, c.token)).body;
    expect(all.map((p: { id: string }) => p.id)).toEqual([...mine].reverse());
    expect(all.every((p: { author: { id: string } }) => p.author.id === a.id)).toBe(true);
    const first = (await call('GET', `/posts?author=${a.id}&limit=2`, c.token)).body.map((p: { id: string }) => p.id);
    const rest = (await call('GET', `/posts?author=${a.id}&limit=2&before=${first[1]}`, c.token)).body.map((p: { id: string }) => p.id);
    expect([...first, ...rest]).toEqual([...mine].reverse());
    expect((await call('GET', '/posts?author=not-an-id', c.token)).status).toBe(400);
  });

  it('saved posts come in pages, most recently saved first', async () => {
    const [a, b] = [await student(), await student()];
    const ids: string[] = [];
    for (let i = 0; i < 3; i++) ids.push((await call('POST', '/posts', a.token, { text: `to save ${i}` })).body.id);
    for (const id of ids) {
      expect((await call('PUT', `/posts/${id}/save`, b.token)).status).toBe(204);
      await new Promise((r) => setTimeout(r, 15)); // distinct save times
    }
    const all = (await call('GET', '/posts/saved', b.token)).body.map((p: { id: string }) => p.id);
    expect(all).toEqual([...ids].reverse());
    const first = (await call('GET', '/posts/saved?limit=2', b.token)).body.map((p: { id: string }) => p.id);
    expect(first).toEqual(all.slice(0, 2));
    const rest = (await call('GET', `/posts/saved?limit=2&before=${first[1]}`, b.token)).body.map((p: { id: string }) => p.id);
    expect(rest).toEqual(all.slice(2));
    expect((await call('GET', `/posts/saved?before=${ids[0]}`, a.token)).status).toBe(404); // a never saved it
  });

  it('remembers the pixel size of an upload and returns it with the post', async () => {
    const a = await student();
    const started = await call('POST', '/media/uploads', a.token, { kind: 'image', purpose: 'post', contentType: 'image/jpeg', size: JPEG.length, width: 1200, height: 1600 });
    expect(started.status).toBe(201);
    const { rows } = await pool.query<{ object_key: string }>('select object_key from media where id = $1', [started.body.mediaId]);
    storage.put(rows[0].object_key, JPEG, 'image/jpeg');
    expect((await complete(a, started.body.mediaId)).status).toBe(200);
    const post = await call('POST', '/posts', a.token, { text: 'sized', mediaIds: [started.body.mediaId] });
    expect(post.body.media[0]).toMatchObject({ width: 1200, height: 1600 });
    const unsized = await readyImage(a);
    const plain = await call('POST', '/posts', a.token, { text: 'unsized', mediaIds: [unsized.id] });
    expect(plain.body.media[0]).toMatchObject({ width: null, height: null });
    const ask = (body: object) => call('POST', '/media/uploads', a.token, { kind: 'image', purpose: 'post', contentType: 'image/jpeg', size: 10, ...body });
    expect((await ask({ width: 1200 })).status).toBe(400); // both or neither
    expect((await ask({ width: 0, height: 10 })).status).toBe(400);
    expect((await ask({ width: 99999, height: 10 })).status).toBe(400);
  });

  // ---- photo processing and limits
  describe('with photo processing switched on', () => {
    let processing: FastifyInstance;
    beforeEach(() => { processing = buildApp({ pool, mailer, config, storage, images: createSharpProcessor({ maxPixels: 20_000_000 }) }); });
    afterEach(async () => { await processing.close(); });

    async function sendPhoto(who: Student, bytes: Uint8Array, purpose: 'post' | 'avatar' = 'post', target = processing) {
      const started = await call('POST', '/media/uploads', who.token, { kind: 'image', purpose, contentType: 'image/jpeg', size: bytes.length }, target);
      expect(started.status).toBe(201);
      const { rows } = await pool.query<{ object_key: string }>('select object_key from media where id = $1', [started.body.mediaId]);
      storage.put(rows[0].object_key, bytes, 'image/jpeg');
      const done = await call('POST', `/media/${started.body.mediaId}/complete`, who.token, undefined, target);
      return { id: started.body.mediaId as string, originalKey: rows[0].object_key, done };
    }

    it('stores an upright, metadata-free WebP with a feed thumbnail and serves both', async () => {
      const a = await student();
      const raw = await sharp({ create: { width: 3000, height: 1500, channels: 3, background: '#2dd4bf' } })
        .jpeg().withMetadata({ exif: { IFD0: { Copyright: 'secret owner' } } }).toBuffer();
      const { id, originalKey, done } = await sendPhoto(a, raw);
      expect(done.status).toBe(200);
      const { rows } = await pool.query('select content_type, object_key, thumb_key, width, height, size_bytes, status from media where id = $1', [id]);
      expect(rows[0]).toMatchObject({ content_type: 'image/webp', width: 2048, height: 1024, status: 'ready' });
      expect(rows[0].object_key).toMatch(/\.webp$/);
      expect(rows[0].thumb_key).toMatch(/-t\.webp$/);
      expect(storage.objects.has(originalKey)).toBe(false); // the unprocessed upload is gone
      const full = await sharp(storage.objects.get(rows[0].object_key)!.bytes).metadata();
      const thumb = await sharp(storage.objects.get(rows[0].thumb_key)!.bytes).metadata();
      expect(full.exif).toBeUndefined();
      expect(thumb.width).toBe(1080);
      const post = await call('POST', '/posts', a.token, { text: 'processed', mediaIds: [id] }, processing);
      expect(post.body.media[0].url).toContain(rows[0].object_key);
      expect(post.body.media[0].thumbUrl).toContain(rows[0].thumb_key);
      expect(post.body.media[0]).toMatchObject({ width: 2048, height: 1024 });
      // deleting the post removes the thumbnail as well
      expect((await call('DELETE', `/posts/${post.body.id}`, a.token, undefined, processing)).status).toBe(204);
      expect(storage.objects.has(rows[0].object_key)).toBe(false);
      expect(storage.objects.has(rows[0].thumb_key)).toBe(false);
    });

    it('refuses a file that is not really a photo, and cleans it up', async () => {
      const a = await student();
      const { id, originalKey, done } = await sendPhoto(a, JPEG); // right first bytes, but not an image
      expect(done.status).toBe(400);
      expect(done.body.error).toBe('invalid_media');
      expect((await pool.query('select 1 from media where id = $1', [id])).rowCount).toBe(0);
      expect(storage.objects.has(originalKey)).toBe(false);
    });

    it('refuses photos with too many pixels', async () => {
      const a = await student();
      const strict = buildApp({ pool, mailer, config, storage, images: createSharpProcessor({ maxPixels: 1000 }) });
      try {
        const big = await sharp({ create: { width: 100, height: 100, channels: 3, background: '#fff' } }).jpeg().toBuffer();
        const { done } = await sendPhoto(a, big, 'post', strict);
        expect(done.status).toBe(400);
        expect(done.body.message).toMatch(/too large/);
      } finally {
        await strict.close();
      }
    });

    it('processed profile photos get no thumbnail', async () => {
      const a = await student();
      const raw = await sharp({ create: { width: 1200, height: 1200, channels: 3, background: '#fff' } }).jpeg().toBuffer();
      const { id, done } = await sendPhoto(a, raw, 'avatar');
      expect(done.status).toBe(200);
      const { rows } = await pool.query('select thumb_key, width from media where id = $1', [id]);
      expect(rows[0]).toMatchObject({ thumb_key: null, width: 512 });
    });
  });

  it('stops new uploads once a student has used up their storage', async () => {
    const a = await student();
    const full = (299 * 1024 * 1024);
    await pool.query(
      "insert into media (owner_id, kind, purpose, content_type, size_bytes, object_key, status) values ($1, 'image', 'post', 'image/webp', $2, $3, 'ready')",
      [a.id, full, `${a.id}/quota-${Date.now()}.webp`],
    );
    const ask = (size: number) => call('POST', '/media/uploads', a.token, { kind: 'image', purpose: 'post', contentType: 'image/jpeg', size });
    const over = await ask(2 * 1024 * 1024);
    expect(over.status).toBe(400);
    expect(over.body.error).toBe('storage_full');
    expect((await ask(1024)).status).toBe(201); // still room for a small one
  });

  it('a video post takes exactly one video', async () => {
    const a = await student();
    const clip = await upload(a, 'video', 'post', 'video/mp4', MP4);
    expect((await complete(a, clip.id)).status).toBe(200);
    const created = await call('POST', '/posts', a.token, { text: 'watch', mediaIds: [clip.id] });
    expect(created.status).toBe(201);
    expect(created.body.media[0].kind).toBe('video');

    const clip2 = await upload(a, 'video', 'post', 'video/mp4', MP4);
    const clip3 = await upload(a, 'video', 'post', 'video/mp4', MP4);
    await complete(a, clip2.id); await complete(a, clip3.id);
    expect((await call('POST', '/posts', a.token, { text: 'two', mediaIds: [clip2.id, clip3.id] })).status).toBe(400);
    const photo = await readyImage(a);
    expect((await call('POST', '/posts', a.token, { text: 'mixed', mediaIds: [clip2.id, photo.id] })).status).toBe(400);
  });

  it('media rules: no polls, no five photos, no empty posts, no borrowed or reused files', async () => {
    const [a, b] = [await student(), await student()];
    const mine = await readyImage(a);
    expect((await call('POST', '/posts', a.token, { text: 'q', poll: { options: ['a', 'b'] }, mediaIds: [mine.id] })).status).toBe(400);
    expect((await call('POST', '/posts', a.token, { text: '' })).status).toBe(400);
    expect((await call('POST', '/posts', a.token, { text: 'x', mediaIds: [mine.id, mine.id] })).status).toBe(400);
    const five = await Promise.all([1, 2, 3, 4, 5].map(() => readyImage(a)));
    expect((await call('POST', '/posts', a.token, { text: 'x', mediaIds: five.map((f) => f.id) })).status).toBe(400);
    // someone else's file
    expect((await call('POST', '/posts', b.token, { text: 'stolen', mediaIds: [mine.id] })).status).toBe(400);
    // a pending (never completed) file
    const pending = await call('POST', '/media/uploads', a.token, { kind: 'image', purpose: 'post', contentType: 'image/jpeg', size: 5 });
    expect((await call('POST', '/posts', a.token, { text: 'x', mediaIds: [pending.body.mediaId] })).status).toBe(400);
    // a file already used by a post
    expect((await call('POST', '/posts', a.token, { text: 'first use', mediaIds: [mine.id] })).status).toBe(201);
    expect((await call('POST', '/posts', a.token, { text: 'second use', mediaIds: [mine.id] })).status).toBe(400);
  });

  it('a post with media may have its text cleared, a plain post may not', async () => {
    const a = await student();
    const file = await readyImage(a);
    const withMedia = (await call('POST', '/posts', a.token, { text: 'caption', mediaIds: [file.id] })).body;
    expect((await call('PUT', `/posts/${withMedia.id}`, a.token, { text: '' })).status).toBe(200);
    const plain = (await call('POST', '/posts', a.token, { text: 'words' })).body;
    expect((await call('PUT', `/posts/${plain.id}`, a.token, { text: '' })).status).toBe(400);
  });

  it('deleting a post deletes its files', async () => {
    const a = await student();
    const file = await readyImage(a);
    const post = (await call('POST', '/posts', a.token, { text: 'temp', mediaIds: [file.id] })).body;
    expect(storage.objects.has(file.key)).toBe(true);
    expect((await call('DELETE', `/posts/${post.id}`, a.token)).status).toBe(204);
    expect(storage.objects.has(file.key)).toBe(false);
  });

  it('deleting an account deletes all its files', async () => {
    const a = await student();
    const used = await readyImage(a);
    await call('POST', '/posts', a.token, { text: 'x', mediaIds: [used.id] });
    const unused = await readyImage(a, 'avatar');
    await call('PUT', '/me/photos', a.token, { avatarMediaId: unused.id });
    expect((await call('POST', '/me/delete-account', a.token, { password: 'correct-horse-9' })).status).toBe(204);
    expect(storage.objects.size).toBe(0);
  });

  // ---- profile and cover photos
  it('profile and cover photos can be set, replaced and removed', async () => {
    const a = await student();
    expect((await call('GET', '/me', a.token)).body.avatarUrl).toBeNull();
    const first = await readyImage(a, 'avatar');
    const cover = await readyImage(a, 'cover');
    const set = await call('PUT', '/me/photos', a.token, { avatarMediaId: first.id, coverMediaId: cover.id });
    expect(set.status).toBe(200);
    expect(set.body.avatarUrl).toContain(first.key);
    expect(set.body.coverUrl).toContain(cover.key);
    expect(set.body.hasPhoto).toBe(true);

    const second = await readyImage(a, 'avatar');
    const replaced = await call('PUT', '/me/photos', a.token, { avatarMediaId: second.id });
    expect(replaced.body.avatarUrl).toContain(second.key);
    expect(replaced.body.coverUrl).toContain(cover.key); // left out, so unchanged
    expect(storage.objects.has(first.key)).toBe(false); // the old photo is gone

    const removed = await call('PUT', '/me/photos', a.token, { avatarMediaId: null });
    expect(removed.body.avatarUrl).toBeNull();
    expect(storage.objects.has(second.key)).toBe(false);
    expect((await call('GET', '/me', a.token)).body.coverUrl).toContain(cover.key);
  });

  it('a photo can only be used for the job it was uploaded for', async () => {
    const [a, b] = [await student(), await student()];
    const forPost = await readyImage(a, 'post');
    expect((await call('PUT', '/me/photos', a.token, { avatarMediaId: forPost.id })).status).toBe(400);
    const cover = await readyImage(a, 'cover');
    expect((await call('PUT', '/me/photos', a.token, { avatarMediaId: cover.id })).status).toBe(400);
    const avatar = await readyImage(a, 'avatar');
    expect((await call('PUT', '/me/photos', b.token, { avatarMediaId: avatar.id })).status).toBe(400); // not b's
  });

  // ---- housekeeping
  it('abandoned uploads are cleaned up after a day', async () => {
    const a = await student();
    const stale = await upload(a, 'image', 'post', 'image/jpeg', JPEG); // never completed
    const fresh = await upload(a, 'image', 'post', 'image/jpeg', JPEG);
    await pool.query("update media set created_at = now() - interval '2 days' where id = $1", [stale.id]);
    expect(await cleanupPendingMedia(pool, storage)).toBeGreaterThanOrEqual(1);
    expect(storage.objects.has(stale.key)).toBe(false);
    expect(storage.objects.has(fresh.key)).toBe(true);
  });
});
