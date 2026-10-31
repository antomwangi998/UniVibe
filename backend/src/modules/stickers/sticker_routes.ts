import type { FastifyInstance } from 'fastify';
import type { PoolClient } from 'pg';
import { makeNeed } from '../../admin_guard';
import type { Ctx } from '../../context';
import { withTx } from '../../db';
import { HttpError } from '../../errors';
import { decodeCursor, idParam, toPage, tsText } from '../../http';
import { signedUrl } from '../cards';
import { audit } from '../moderation';
import { userEntitlements } from '../monetization/subscriptions';
import {
  MAX_INSTALLED_PACKS, MAX_STICKERS_PER_PACK, PREMIUM_PACK_KEY, addStickerSchema, createPackSchema, likePattern, packListSchema,
  packStatusSchema, reorderSchema, stickerSearchSchema, updatePackSchema, webpIsAnimated,
} from './sticker_validation';

type PackRow = {
  id: string; slug: string; name: string; description: string; category: string; premium: boolean; featured: boolean;
  cover_key: string; cover_thumb_key: string; creator_id: string | null; creator_handle: string | null; license: string; source_attribution: string;
  sticker_count: number; install_status: string | null; pack_favourite: boolean | null; cursorT: string;
};
type StickerRow = { id: string; pack_id: string; label: string | null; keywords: string[]; object_key: string; thumb_key: string | null; width: number; height: number; animated: boolean };

const PACK_COLUMNS = `p.id, p.slug, p.name, p.description, p.category, p.premium, p.featured, p.cover_key, p.cover_thumb_key, p.creator_id,
  u.handle as creator_handle, p.license, p.source_attribution,
  (select count(*) from stickers s where s.pack_id = p.id and s.status = 'active')::int as sticker_count,
  up.status as install_status, up.pack_favourite, ${tsText('p.published_at')} as "cursorT"`;
const PACK_FROM = `sticker_packs p left join users u on u.id = p.creator_id left join user_sticker_packs up on up.pack_id = p.id and up.user_id = $1`;

export function stickerRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;
    const need = makeNeed(ctx);

    const packView = async (r: PackRow, entitled: boolean) => ({
      id: r.id, slug: r.slug, name: r.name, description: r.description, category: r.category, premium: r.premium, featured: r.featured,
      locked: r.premium && !entitled, stickerCount: r.sticker_count,
      creator: r.creator_id ? { id: r.creator_id, handle: r.creator_handle } : null, license: r.license, sourceAttribution: r.source_attribution,
      installed: r.install_status === 'installed', installedBefore: r.install_status !== null, favourite: Boolean(r.pack_favourite),
      coverUrl: await signedUrl(ctx, r.cover_key), coverThumbUrl: await signedUrl(ctx, r.cover_thumb_key),
    });
    /** Locked Premium packs show still thumbnails only; the animated file is withheld until the person is entitled. */
    const stickerView = async (s: StickerRow, locked: boolean) => ({
      id: s.id, packId: s.pack_id, label: s.label, keywords: s.keywords, width: s.width, height: s.height, animated: s.animated,
      thumbUrl: await signedUrl(ctx, s.thumb_key), url: locked ? null : await signedUrl(ctx, s.object_key),
    });
    const entitledToPremium = async (userId: string) => (await userEntitlements(pool, userId)).includes(PREMIUM_PACK_KEY);

    async function publishedPack(packId: string, userId: string): Promise<PackRow> {
      const p = (await pool.query<PackRow>(`select ${PACK_COLUMNS} from ${PACK_FROM} where p.id = $2 and p.status = 'published'`, [userId, packId])).rows[0];
      if (!p) throw new HttpError(404, 'not_found', 'Not found.');
      return p;
    }

    // ------------------------------------------------------------ store
    app.get('/stickers/packs', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`stk-browse:${me.id}`, 240, 60);
      const q = packListSchema.parse(req.query ?? {});
      const cur = decodeCursor(q.cursor);
      const params: unknown[] = [me.id];
      let where = "p.status = 'published' and exists (select 1 from stickers s where s.pack_id = p.id and s.status = 'active')";
      if (q.category) { params.push(q.category); where += ` and p.category = $${params.length}`; }
      if (q.q) { params.push(likePattern(q.q)); where += ` and (p.name ilike $${params.length} escape '\\' or p.description ilike $${params.length} escape '\\')`; }
      if (q.premium) where += q.premium === 'premium' ? ' and p.premium' : ' and not p.premium';
      if (q.featured) where += ' and p.featured';
      if (cur) { params.push(cur.t, cur.id); where += ` and (p.published_at, p.id) < ($${params.length - 1}::timestamptz, $${params.length}::uuid)`; }
      params.push(q.limit + 1);
      const rows = (await pool.query<PackRow>(`select ${PACK_COLUMNS} from ${PACK_FROM} where ${where} order by p.published_at desc, p.id desc limit $${params.length}`, params)).rows;
      const page = toPage(rows, q.limit);
      const entitled = await entitledToPremium(me.id);
      return { items: await Promise.all(page.items.map((r) => packView(r as PackRow, entitled))), nextCursor: page.nextCursor };
    });

    app.get('/stickers/packs/:id', async (req) => {
      const me = await ctx.auth(req);
      const p = await publishedPack(idParam((req.params as { id?: string }).id), me.id);
      const entitled = await entitledToPremium(me.id);
      const locked = p.premium && !entitled;
      const stickers = (await pool.query<StickerRow>(
        "select id, pack_id, label, keywords, object_key, thumb_key, width, height, animated from stickers where pack_id = $1 and status = 'active' order by position, id", [p.id])).rows;
      return { pack: await packView(p, entitled), stickers: await Promise.all(stickers.map((s) => stickerView(s, locked))) };
    });

    // ------------------------------------------------------------ install / uninstall / favourite pack
    app.put('/me/stickers/packs/:id', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`stk-write:${me.id}`, 120, 3600);
      const p = await publishedPack(idParam((req.params as { id?: string }).id), me.id);
      if (p.premium && !(await entitledToPremium(me.id))) throw new HttpError(403, 'premium_required', 'This sticker pack needs UniVibe Premium.');
      if (p.install_status !== 'installed') {
        const n = Number((await pool.query<{ n: string }>("select count(*) as n from user_sticker_packs where user_id = $1 and status = 'installed'", [me.id])).rows[0].n);
        if (n >= MAX_INSTALLED_PACKS) throw new HttpError(422, 'limit_reached', `You can install up to ${MAX_INSTALLED_PACKS} packs.`);
      }
      // Re-installing keeps the original acquired_via, so ownership is never rewritten.
      await pool.query(
        `insert into user_sticker_packs (user_id, pack_id, status, acquired_via) values ($1, $2, 'installed', $3)
         on conflict (user_id, pack_id) do update set status = 'installed', updated_at = now()`,
        [me.id, p.id, p.premium ? 'subscription' : 'free']);
      return { status: 'installed' };
    });

    app.delete('/me/stickers/packs/:id', async (req) => {
      const me = await ctx.auth(req);
      await pool.query("update user_sticker_packs set status = 'uninstalled', updated_at = now() where user_id = $1 and pack_id = $2", [me.id, idParam((req.params as { id?: string }).id)]);
      return { status: 'uninstalled' };
    });

    app.put('/stickers/packs/:id/favourite', async (req) => {
      const me = await ctx.auth(req);
      const p = await publishedPack(idParam((req.params as { id?: string }).id), me.id);
      if (p.install_status !== 'installed') throw new HttpError(422, 'sticker_not_installed', 'Install the pack first.');
      await pool.query('update user_sticker_packs set pack_favourite = true, updated_at = now() where user_id = $1 and pack_id = $2', [me.id, p.id]);
      return { status: 'ok' };
    });
    app.delete('/stickers/packs/:id/favourite', async (req) => {
      const me = await ctx.auth(req);
      await pool.query('update user_sticker_packs set pack_favourite = false, updated_at = now() where user_id = $1 and pack_id = $2', [me.id, idParam((req.params as { id?: string }).id)]);
      return { status: 'ok' };
    });

    // ------------------------------------------------------------ the picker: my installed packs with their stickers
    app.get('/me/stickers/packs', async (req) => {
      const me = await ctx.auth(req);
      const entitled = await entitledToPremium(me.id);
      const packs = (await pool.query<PackRow>(
        `select ${PACK_COLUMNS} from ${PACK_FROM} where up.status = 'installed' and p.status = 'published' order by up.installed_at, p.id`, [me.id])).rows;
      const ids = packs.map((p) => p.id);
      const stickers = ids.length ? (await pool.query<StickerRow>(
        "select id, pack_id, label, keywords, object_key, thumb_key, width, height, animated from stickers where pack_id = any($1::uuid[]) and status = 'active' order by pack_id, position, id", [ids])).rows : [];
      return {
        packs: await Promise.all(packs.map(async (p) => {
          const locked = p.premium && !entitled; // lapsed Premium: the pack stays installed, but cannot be sent
          return { ...(await packView(p, entitled)), stickers: await Promise.all(stickers.filter((s) => s.pack_id === p.id).map((s) => stickerView(s, locked))) };
        })),
      };
    });

    /** Search only inside the packs I have installed. */
    app.get('/me/stickers/search', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`stk-search:${me.id}`, 120, 60);
      const { q } = stickerSearchSchema.parse(req.query ?? {});
      const entitled = await entitledToPremium(me.id);
      const rows = (await pool.query<StickerRow & { premium: boolean }>(
        `select s.id, s.pack_id, s.label, s.keywords, s.object_key, s.thumb_key, s.width, s.height, s.animated, p.premium
           from stickers s join sticker_packs p on p.id = s.pack_id and p.status = 'published'
           join user_sticker_packs up on up.pack_id = p.id and up.user_id = $1 and up.status = 'installed'
          where s.status = 'active' and (s.label ilike $2 escape '\\' or exists (select 1 from unnest(s.keywords) k where k ilike $2 escape '\\'))
          order by s.position, s.id limit 60`, [me.id, likePattern(q)])).rows;
      return { items: await Promise.all(rows.map((r) => stickerView(r, r.premium && !entitled))) };
    });

    // ------------------------------------------------------------ favourite and recent stickers (installed packs only)
    const mineStickers = (where: string, order: string) => `select s.id, s.pack_id, s.label, s.keywords, s.object_key, s.thumb_key, s.width, s.height, s.animated, p.premium
        from stickers s join sticker_packs p on p.id = s.pack_id and p.status = 'published'
        join user_sticker_packs up on up.pack_id = p.id and up.user_id = $1 and up.status = 'installed'
        ${where} order by ${order} limit 60`;

    app.get('/me/stickers/favourites', async (req) => {
      const me = await ctx.auth(req);
      const entitled = await entitledToPremium(me.id);
      const rows = (await pool.query<StickerRow & { premium: boolean }>(mineStickers('join user_sticker_favourites f on f.sticker_id = s.id and f.user_id = $1 where s.status = \'active\'', 'f.created_at desc, s.id'), [me.id])).rows;
      return { items: await Promise.all(rows.map((r) => stickerView(r, r.premium && !entitled))) };
    });

    app.put('/me/stickers/favourites/:stickerId', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`stk-write:${me.id}`, 120, 3600);
      const sid = idParam((req.params as { stickerId?: string }).stickerId);
      const ok = (await pool.query(
        `select 1 from stickers s join user_sticker_packs up on up.pack_id = s.pack_id and up.user_id = $2 and up.status = 'installed'
          where s.id = $1 and s.status = 'active'`, [sid, me.id])).rowCount;
      if (!ok) throw new HttpError(404, 'not_found', 'Not found.');
      const n = Number((await pool.query<{ n: string }>('select count(*) as n from user_sticker_favourites where user_id = $1', [me.id])).rows[0].n);
      if (n >= 200) throw new HttpError(422, 'limit_reached', 'You can favourite up to 200 stickers.');
      await pool.query('insert into user_sticker_favourites (user_id, sticker_id) values ($1, $2) on conflict do nothing', [me.id, sid]);
      return { status: 'ok' };
    });
    app.delete('/me/stickers/favourites/:stickerId', async (req) => {
      const me = await ctx.auth(req);
      await pool.query('delete from user_sticker_favourites where user_id = $1 and sticker_id = $2', [me.id, idParam((req.params as { stickerId?: string }).stickerId)]);
      return { status: 'ok' };
    });

    app.get('/me/stickers/recent', async (req) => {
      const me = await ctx.auth(req);
      const entitled = await entitledToPremium(me.id);
      const rows = (await pool.query<StickerRow & { premium: boolean }>(mineStickers('join user_recent_stickers r on r.sticker_id = s.id and r.user_id = $1 where s.status = \'active\'', 'r.last_used_at desc, s.id'), [me.id])).rows;
      return { items: await Promise.all(rows.map((r) => stickerView(r, r.premium && !entitled))) };
    });

    // ------------------------------------------------------------ staff: packs (personalization.manage)
    /** Staff upload through /media with purpose "sticker" first; these routes only register what arrived and was processed. */
    async function readyStickerMedia(client: Pick<PoolClient, 'query'>, mediaId: string, userId: string) {
      const m = (await client.query<{ object_key: string; thumb_key: string | null; width: number | null; height: number | null; size_bytes: string | number }>(
        "select object_key, thumb_key, width, height, size_bytes from media where id = $1 and owner_id = $2 and kind = 'image' and purpose = 'sticker' and status = 'ready'", [mediaId, userId])).rows[0];
      if (!m || !m.width || !m.height || !m.thumb_key) throw new HttpError(422, 'validation', 'Upload the sticker first (purpose "sticker") and complete the upload.');
      return m;
    }

    app.post('/admin/stickers/packs', async (req, reply) => {
      const me = await need(req, 'personalization.manage');
      const b = createPackSchema.parse(req.body ?? {});
      const id = await withTx(pool, async (client) => {
        const cover = await readyStickerMedia(client, b.coverMediaId, me.id);
        try {
          const row = (await client.query<{ id: string }>(
            `insert into sticker_packs (slug, name, description, category, cover_key, cover_thumb_key, premium, license, source_attribution, created_by)
             values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) returning id`,
            [b.slug, b.name, b.description, b.category, cover.object_key, cover.thumb_key, b.premium, b.license, b.sourceAttribution, me.id])).rows[0];
          await audit(client, me.id, 'stickers.pack_create', 'sticker_pack', row.id, { premium: b.premium, license: b.license });
          return row.id;
        } catch (e) {
          if ((e as { code?: string }).code === '23505') throw new HttpError(409, 'duplicate', 'A pack with that slug already exists.');
          throw e;
        }
      });
      return reply.status(201).send({ id, status: 'draft' });
    });

    app.patch('/admin/stickers/packs/:id', async (req) => {
      const me = await need(req, 'personalization.manage');
      const id = idParam((req.params as { id?: string }).id);
      const b = updatePackSchema.parse(req.body ?? {});
      const sets: string[] = []; const params: unknown[] = [id];
      const map: Record<string, string> = { name: 'name', description: 'description', category: 'category', premium: 'premium', featured: 'featured', sortOrder: 'sort_order' };
      for (const [k, v] of Object.entries(b)) { params.push(v); sets.push(`${map[k]} = $${params.length}`); }
      const res = await pool.query(`update sticker_packs set ${sets.join(', ')}, updated_at = now() where id = $1 and status <> 'removed'`, params);
      if (!res.rowCount) throw new HttpError(404, 'not_found', 'Not found.');
      await audit(pool, me.id, 'stickers.pack_update', 'sticker_pack', id, { fields: Object.keys(b) });
      return { status: 'ok' };
    });

    app.post('/admin/stickers/packs/:id/stickers', async (req, reply) => {
      const me = await need(req, 'personalization.manage');
      const packId = idParam((req.params as { id?: string }).id);
      const b = addStickerSchema.parse(req.body ?? {});
      const out = await withTx(pool, async (client) => {
        const pack = (await client.query('select 1 from sticker_packs where id = $1 and status <> \'removed\' for update', [packId])).rows[0];
        if (!pack) throw new HttpError(404, 'not_found', 'Not found.');
        const n = (await client.query<{ n: string; next: number }>("select count(*) as n, coalesce(max(position), -1) + 1 as next from stickers where pack_id = $1 and status = 'active'", [packId])).rows[0];
        if (Number(n.n) >= MAX_STICKERS_PER_PACK) throw new HttpError(422, 'limit_reached', `A pack can hold up to ${MAX_STICKERS_PER_PACK} stickers.`);
        const m = await readyStickerMedia(client, b.mediaId, me.id);
        if (!ctx.storage) throw new HttpError(503, 'unavailable', 'Storage is not configured.');
        // Animated or not is read from the stored file itself, never from what the uploader claimed.
        const animated = webpIsAnimated(await ctx.storage.peek(m.object_key, 32));
        const row = (await client.query<{ id: string }>(
          `insert into stickers (pack_id, position, label, keywords, object_key, thumb_key, width, height, animated, size_bytes)
           values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) returning id`,
          [packId, n.next, b.label ?? null, b.keywords, m.object_key, m.thumb_key, m.width, m.height, animated, Number(m.size_bytes)])).rows[0];
        await audit(client, me.id, 'stickers.sticker_add', 'sticker', row.id, { packId, animated });
        return { id: row.id, animated };
      });
      return reply.status(201).send(out);
    });

    app.put('/admin/stickers/packs/:id/order', async (req) => {
      const me = await need(req, 'personalization.manage');
      const packId = idParam((req.params as { id?: string }).id);
      const b = reorderSchema.parse(req.body ?? {});
      return withTx(pool, async (client) => {
        const have = (await client.query<{ id: string }>("select id from stickers where pack_id = $1 and status = 'active' for update", [packId])).rows.map((r) => r.id);
        // The new order must be exactly the pack's current stickers: nothing missing, nothing from another pack.
        if (have.length !== b.stickerIds.length || !b.stickerIds.every((i) => have.includes(i))) throw new HttpError(422, 'validation', 'The order must list every sticker in the pack once.');
        await client.query('update stickers s set position = o.pos from (select unnest($1::uuid[]) as id, generate_subscripts($1::uuid[], 1) as pos) o where s.id = o.id and s.pack_id = $2', [b.stickerIds, packId]);
        await audit(client, me.id, 'stickers.pack_reorder', 'sticker_pack', packId, {});
        return { status: 'ok' };
      });
    });

    app.delete('/admin/stickers/:id', async (req) => {
      const me = await need(req, 'personalization.manage');
      const id = idParam((req.params as { id?: string }).id);
      // Soft removal: old messages keep pointing at it and show "unavailable".
      const res = await pool.query("update stickers set status = 'removed' where id = $1 and status = 'active'", [id]);
      if (!res.rowCount) throw new HttpError(404, 'not_found', 'Not found.');
      await audit(pool, me.id, 'stickers.sticker_remove', 'sticker', id, {});
      return { status: 'ok' };
    });

    app.patch('/admin/stickers/packs/:id/status', async (req) => {
      const me = await need(req, 'personalization.manage');
      const id = idParam((req.params as { id?: string }).id);
      const b = packStatusSchema.parse(req.body ?? {});
      return withTx(pool, async (client) => {
        const cur = (await client.query<{ status: string }>('select status from sticker_packs where id = $1 for update', [id])).rows[0];
        if (!cur) throw new HttpError(404, 'not_found', 'Not found.');
        if (cur.status === 'removed') throw new HttpError(422, 'validation', 'Removed content stays removed.');
        if (b.status === 'published') {
          const n = Number((await client.query<{ n: string }>("select count(*) as n from stickers where pack_id = $1 and status = 'active'", [id])).rows[0].n);
          if (n < 1) throw new HttpError(422, 'validation', 'Add at least one sticker before publishing.');
        }
        await client.query(
          "update sticker_packs set status = $2, published_at = case when $2 = 'published' then coalesce(published_at, now()) else published_at end, updated_at = now() where id = $1", [id, b.status]);
        await audit(client, me.id, 'stickers.pack_status', 'sticker_pack', id, { from: cur.status, to: b.status });
        return { status: 'ok' };
      });
    });

    /** Staff view of one pack in any state, with its stickers. */
    app.get('/admin/stickers/packs/:id', async (req) => {
      await need(req, 'personalization.manage');
      const id = idParam((req.params as { id?: string }).id);
      const pack = (await pool.query('select id, slug, name, category, premium, featured, status, license, source_attribution as "sourceAttribution" from sticker_packs where id = $1', [id])).rows[0];
      if (!pack) throw new HttpError(404, 'not_found', 'Not found.');
      const stickers = (await pool.query<StickerRow & { status: string }>("select id, pack_id, label, keywords, object_key, thumb_key, width, height, animated, status from stickers where pack_id = $1 order by position, id", [id])).rows;
      return { pack, stickers: await Promise.all(stickers.map(async (s) => ({ ...(await stickerView(s, false)), status: s.status }))) };
    });
  };
}
