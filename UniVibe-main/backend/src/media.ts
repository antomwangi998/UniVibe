import { randomUUID } from 'crypto';
import type { FastifyInstance } from 'fastify';
import type { Pool } from 'pg';
import { z } from 'zod';
import { withTx } from './db';
import { HttpError } from './errors';
import { RouteContext, paramId } from './shared';
import type { Storage } from './storage';

export const MEDIA_LIMITS = {
  image: { maxBytes: 8 * 1024 * 1024, types: ['image/jpeg', 'image/png', 'image/webp'] },
  video: { maxBytes: 50 * 1024 * 1024, types: ['video/mp4', 'video/quicktime', 'video/webm'] },
} as const;

const EXTENSIONS: Record<string, string> = {
  'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp',
  'video/mp4': 'mp4', 'video/quicktime': 'mov', 'video/webm': 'webm',
};

/** Does the start of the file look like what it claims to be? A wrong label is not enough to get a file accepted. */
export function looksLike(contentType: string, b: Uint8Array): boolean {
  const ascii = (from: number, to: number) => String.fromCharCode(...Array.from(b.slice(from, to)));
  switch (contentType) {
    case 'image/jpeg': return b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff;
    case 'image/png': return b.length >= 8 && b[0] === 0x89 && ascii(1, 4) === 'PNG';
    case 'image/webp': return b.length >= 12 && ascii(0, 4) === 'RIFF' && ascii(8, 12) === 'WEBP';
    case 'video/mp4':
    case 'video/quicktime': return b.length >= 12 && ascii(4, 8) === 'ftyp';
    case 'video/webm': return b.length >= 4 && b[0] === 0x1a && b[1] === 0x45 && b[2] === 0xdf && b[3] === 0xa3;
    default: return false;
  }
}

const uploadSchema = z.object({
  kind: z.enum(['image', 'video']),
  purpose: z.enum(['post', 'avatar', 'cover', 'message', 'event']),
  contentType: z.string().max(100),
  size: z.number().int().positive(),
});
const photosSchema = z.object({
  avatarMediaId: z.string().uuid().nullable().optional(),
  coverMediaId: z.string().uuid().nullable().optional(),
});

export const UPLOAD_LINK_SECONDS = 600;
export const READ_LINK_SECONDS = 3600;

function needStorage(ctx: RouteContext): Storage {
  if (!ctx.storage) throw new HttpError(503, 'storage_unavailable', 'Photo and video uploads are not available right now.');
  return ctx.storage;
}

/** Removes uploads that were started but never completed (an app closed mid-upload, a failed post). */
export async function cleanupPendingMedia(pool: Pool, storage: Storage): Promise<number> {
  const { rows } = await pool.query<{ object_key: string }>(
    "delete from media where status = 'pending' and created_at < now() - interval '1 day' returning object_key",
  );
  if (rows.length > 0) await storage.remove(rows.map((r) => r.object_key)).catch(() => undefined);
  return rows.length;
}

export function registerMediaRoutes(app: FastifyInstance, ctx: RouteContext): void {
  const { pool } = ctx;

  /** Step 1: the app says what it wants to upload and gets a short-lived link to send the file to. */
  app.post('/media/uploads', async (req, reply) => {
    const user = await ctx.requireUser(req);
    const storage = needStorage(ctx);
    const b = uploadSchema.parse(req.body ?? {});
    ctx.tooMany(`media:${user.id}`, 60, 3600);
    const limit = MEDIA_LIMITS[b.kind];
    if (!(limit.types as readonly string[]).includes(b.contentType)) {
      throw new HttpError(400, 'validation', 'That file type is not supported.');
    }
    if (b.size > limit.maxBytes) {
      throw new HttpError(400, 'validation', `That file is too large (the limit is ${limit.maxBytes / (1024 * 1024)} MB).`);
    }
    if (b.kind === 'video' && b.purpose !== 'post') {
      throw new HttpError(400, 'validation', 'Profile and cover photos must be images.');
    }
    const key = `${user.id}/${randomUUID()}.${EXTENSIONS[b.contentType]}`;
    const created = await pool.query<{ id: string }>(
      'insert into media (owner_id, kind, purpose, content_type, size_bytes, object_key) values ($1, $2, $3, $4, $5, $6) returning id',
      [user.id, b.kind, b.purpose, b.contentType, b.size, key],
    );
    const uploadUrl = await storage.presignUpload(key, b.contentType, UPLOAD_LINK_SECONDS);
    return reply.status(201).send({
      mediaId: created.rows[0].id,
      uploadUrl,
      headers: { 'Content-Type': b.contentType },
      expiresIn: UPLOAD_LINK_SECONDS,
    });
  });

  /** Step 2: after the upload, the server checks what actually arrived before the file can be used anywhere. */
  app.post('/media/:id/complete', async (req) => {
    const user = await ctx.requireUser(req);
    const storage = needStorage(ctx);
    const id = paramId(req);
    const { rows } = await pool.query<{ kind: 'image' | 'video'; content_type: string; object_key: string; status: string }>(
      'select kind, content_type, object_key, status from media where id = $1 and owner_id = $2',
      [id, user.id],
    );
    const m = rows[0];
    if (!m) throw new HttpError(404, 'not_found', 'That upload was not found.');
    if (m.status === 'ready') return { id, kind: m.kind };

    const reject = async (message: string): Promise<never> => {
      await pool.query('delete from media where id = $1', [id]);
      await storage.remove([m.object_key]).catch(() => undefined);
      throw new HttpError(400, 'invalid_media', message);
    };

    const head = await storage.head(m.object_key);
    if (!head) throw new HttpError(400, 'upload_missing', 'The file did not arrive. Please try again.');
    if (head.size <= 0 || head.size > MEDIA_LIMITS[m.kind].maxBytes) return reject('That file is too large.');
    if ((head.contentType ?? '').split(';')[0].trim() !== m.content_type) return reject('The file type does not match.');
    if (!looksLike(m.content_type, await storage.peek(m.object_key, 16))) return reject('That is not a valid photo or video.');

    await pool.query("update media set status = 'ready', size_bytes = $2 where id = $1", [id, head.size]);
    return { id, kind: m.kind };
  });

  /** Sets or removes the signed-in student's profile and cover photos. A key left out stays as it is; null removes. */
  app.put('/me/photos', async (req) => {
    const user = await ctx.requireUser(req);
    const b = photosSchema.parse(req.body ?? {});
    const fields = [
      { column: 'avatar_media_id', purpose: 'avatar', value: b.avatarMediaId },
      { column: 'cover_media_id', purpose: 'cover', value: b.coverMediaId },
    ] as const;
    const replacedKeys: string[] = [];
    await withTx(pool, async (client) => {
      for (const f of fields) {
        if (f.value === undefined) continue;
        const current = await client.query<{ id: string | null; key: string | null }>(
          `select u.${f.column} as id, m.object_key as key from users u left join media m on m.id = u.${f.column} where u.id = $1`,
          [user.id],
        );
        if (f.value !== null) {
          const usable = await client.query(
            "select 1 from media where id = $1 and owner_id = $2 and status = 'ready' and kind = 'image' and purpose = $3 and post_id is null",
            [f.value, user.id, f.purpose],
          );
          if (!usable.rowCount) throw new HttpError(400, 'validation', 'That photo is not available.');
        }
        await client.query(`update users set ${f.column} = $2 where id = $1`, [user.id, f.value]);
        const old = current.rows[0];
        if (old?.id && old.id !== f.value) {
          await client.query('delete from media where id = $1', [old.id]);
          if (old.key) replacedKeys.push(old.key);
        }
      }
    });
    if (replacedKeys.length > 0 && ctx.storage) await ctx.storage.remove(replacedKeys).catch(() => undefined);
    return ctx.userView(user.id);
  });
}
