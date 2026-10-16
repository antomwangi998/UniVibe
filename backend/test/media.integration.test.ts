import type { FastifyInstance } from 'fastify';
import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app';
import type { Config } from '../src/config';
import { createPool, migrate } from '../src/db';
import type { Mailer } from '../src/email';
import { cleanupPendingMedia } from '../src/media';
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
