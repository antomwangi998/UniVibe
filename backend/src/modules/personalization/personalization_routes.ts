import type { FastifyInstance } from 'fastify';
import type { Pool } from 'pg';
import { z } from 'zod';
import { makeNeed } from '../../admin_guard';
import type { Ctx } from '../../context';
import { withTx } from '../../db';
import { HttpError } from '../../errors';
import { decodeCursor, idParam, toPage, tsText } from '../../http';
import { signedUrl } from '../cards';
import { audit } from '../moderation';
import { userEntitlements } from '../monetization/subscriptions';
import {
  assetListSchema, assetStatusSchema, chatAppearanceSchema, collectionItemSchema, collectionNameSchema,
  isPremiumChatId, likePattern, registerAssetSchema,
} from './personalization_validation';

const PREMIUM_ASSETS = 'premium.wallpaper_collections';
const MAX_COLLECTIONS = 30;
const MAX_ITEMS_PER_COLLECTION = 500;

type AssetRow = {
  id: string; title: string; description: string; source_attribution: string; license: string; premium: boolean;
  destinations: string[]; width: number; height: number; thumb_key: string; object_key: string;
  category_slug: string; category_name: string; creator_id: string | null; creator_handle: string | null;
  favourited: boolean; cursorT: string;
};

const ASSET_COLUMNS = `a.id, a.title, a.description, a.source_attribution, a.license, a.premium, a.destinations, a.width, a.height,
  a.thumb_key, a.object_key, c.slug as category_slug, c.name as category_name, a.creator_id, u.handle as creator_handle,
  exists (select 1 from theme_favourites f where f.asset_id = a.id and f.user_id = $1) as favourited,
  ${tsText('a.published_at')} as "cursorT"`;
const ASSET_FROM = `theme_assets a join theme_categories c on c.id = a.category_id left join users u on u.id = a.creator_id`;

/**
 * Everyone may SEE the preview. The full-size image is only linked when it is free or the person is entitled,
 * so a locked Premium image can be previewed but not saved or applied. The server decides, never the app.
 */
async function assetView(ctx: Ctx, r: AssetRow, entitled: boolean) {
  const locked = r.premium && !entitled;
  return {
    id: r.id, title: r.title, description: r.description, category: { slug: r.category_slug, name: r.category_name },
    creator: r.creator_id ? { id: r.creator_id, handle: r.creator_handle } : null,
    sourceAttribution: r.source_attribution, license: r.license, premium: r.premium, locked,
    destinations: r.destinations, width: r.width, height: r.height, favourited: r.favourited,
    thumbUrl: await signedUrl(ctx, r.thumb_key),
    fullUrl: locked ? null : await signedUrl(ctx, r.object_key),
  };
}

async function publishedAsset(pool: Pool, assetId: string, userId: string): Promise<AssetRow> {
  const a = (await pool.query<AssetRow>(`select ${ASSET_COLUMNS} from ${ASSET_FROM} where a.id = $2 and a.status = 'published'`, [userId, assetId])).rows[0];
  if (!a) throw new HttpError(404, 'not_found', 'Not found.');
  return a;
}

function needPremiumFor(a: { premium: boolean }, keys: readonly string[]) {
  if (a.premium && !keys.includes(PREMIUM_ASSETS)) throw new HttpError(403, 'premium_required', 'This needs UniVibe Premium.');
}

export function personalizationRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;
    const need = makeNeed(ctx);

    // ------------------------------------------------------------ discovery
    app.get('/personalization/categories', async (req, reply) => {
      const me = await ctx.auth(req);
      ctx.limit(`pz-categories:${me.id}`, 240, 60);
      reply.header('Cache-Control', 'private, max-age=300');
      const { rows } = await pool.query('select slug, name from theme_categories order by sort_order, name');
      return { categories: rows };
    });

    app.get('/personalization/assets', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`pz-browse:${me.id}`, 240, 60);
      const q = assetListSchema.parse(req.query ?? {});
      const cur = decodeCursor(q.cursor);
      const params: unknown[] = [me.id];
      let where = "a.status = 'published'";
      if (q.category) { params.push(q.category); where += ` and c.slug = $${params.length}`; }
      if (q.q) { params.push(likePattern(q.q)); where += ` and (a.title ilike $${params.length} escape '\\' or a.description ilike $${params.length} escape '\\')`; }
      if (q.premium) where += q.premium === 'premium' ? ' and a.premium' : ' and not a.premium';
      if (q.destination) { params.push(q.destination); where += ` and $${params.length} = any (a.destinations)`; }
      if (cur) { params.push(cur.t, cur.id); where += ` and (a.published_at, a.id) < ($${params.length - 1}::timestamptz, $${params.length}::uuid)`; }
      params.push(q.limit + 1);
      const rows = (await pool.query<AssetRow>(
        `select ${ASSET_COLUMNS} from ${ASSET_FROM} where ${where} order by a.published_at desc, a.id desc limit $${params.length}`, params)).rows;
      const page = toPage(rows, q.limit);
      const entitled = (await userEntitlements(pool, me.id)).includes(PREMIUM_ASSETS);
      return { items: await Promise.all(page.items.map((r) => assetView(ctx, r as AssetRow, entitled))), nextCursor: page.nextCursor };
    });

    app.get('/personalization/assets/:id', async (req) => {
      const me = await ctx.auth(req);
      const a = await publishedAsset(pool, idParam((req.params as { id?: string }).id), me.id);
      const entitled = (await userEntitlements(pool, me.id)).includes(PREMIUM_ASSETS);
      const related = (await pool.query<AssetRow>(
        `select ${ASSET_COLUMNS} from ${ASSET_FROM} where a.status = 'published' and c.slug = $2 and a.id <> $3 order by a.published_at desc, a.id desc limit 8`,
        [me.id, a.category_slug, a.id])).rows;
      return { asset: await assetView(ctx, a, entitled), related: await Promise.all(related.map((r) => assetView(ctx, r, entitled))) };
    });

    // ------------------------------------------------------------ favourites (saving a Premium image needs Premium)
    app.put('/personalization/assets/:id/favourite', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`pz-write:${me.id}`, 120, 3600);
      const a = await publishedAsset(pool, idParam((req.params as { id?: string }).id), me.id);
      needPremiumFor(a, await userEntitlements(pool, me.id));
      await pool.query('insert into theme_favourites (user_id, asset_id) values ($1, $2) on conflict do nothing', [me.id, a.id]);
      return { status: 'ok' };
    });

    app.delete('/personalization/assets/:id/favourite', async (req) => {
      const me = await ctx.auth(req);
      await pool.query('delete from theme_favourites where user_id = $1 and asset_id = $2', [me.id, idParam((req.params as { id?: string }).id)]);
      return { status: 'ok' };
    });

    app.get('/me/personalization/favourites', async (req) => {
      const me = await ctx.auth(req);
      const q = assetListSchema.pick({ limit: true, cursor: true }).parse(req.query ?? {});
      const cur = decodeCursor(q.cursor);
      const params: unknown[] = [me.id];
      let where = "a.status = 'published' and fav.user_id = $1";
      if (cur) { params.push(cur.t, cur.id); where += ` and (fav.created_at, a.id) < ($2::timestamptz, $3::uuid)`; }
      params.push(q.limit + 1);
      const rows = (await pool.query<AssetRow>(
        `select ${ASSET_COLUMNS.replace(tsText('a.published_at'), tsText('fav.created_at'))} from ${ASSET_FROM} join theme_favourites fav on fav.asset_id = a.id
          where ${where} order by fav.created_at desc, a.id desc limit $${params.length}`, params)).rows;
      const page = toPage(rows, q.limit);
      const entitled = (await userEntitlements(pool, me.id)).includes(PREMIUM_ASSETS);
      return { items: await Promise.all(page.items.map((r) => assetView(ctx, r as AssetRow, entitled))), nextCursor: page.nextCursor };
    });

    // ------------------------------------------------------------ private collections
    /** A collection that is not mine answers exactly like one that does not exist. */
    async function myCollection(collectionId: string, userId: string) {
      const c = (await pool.query<{ id: string; name: string }>('select id, name from theme_collections where id = $1 and user_id = $2 and deleted_at is null', [collectionId, userId])).rows[0];
      if (!c) throw new HttpError(404, 'not_found', 'Not found.');
      return c;
    }

    app.get('/me/personalization/collections', async (req) => {
      const me = await ctx.auth(req);
      const { rows } = await pool.query(
        `select c.id, c.name, c.created_at as "createdAt", (select count(*) from theme_collection_items i join theme_assets a on a.id = i.asset_id and a.status = 'published' where i.collection_id = c.id)::int as "itemCount"
           from theme_collections c where c.user_id = $1 and c.deleted_at is null order by c.created_at desc`, [me.id]);
      return { collections: rows };
    });

    app.post('/me/personalization/collections', async (req, reply) => {
      const me = await ctx.auth(req);
      ctx.limit(`pz-write:${me.id}`, 120, 3600);
      const b = collectionNameSchema.parse(req.body ?? {});
      const n = Number((await pool.query<{ n: string }>('select count(*) as n from theme_collections where user_id = $1 and deleted_at is null', [me.id])).rows[0].n);
      if (n >= MAX_COLLECTIONS) throw new HttpError(422, 'limit_reached', `You can have up to ${MAX_COLLECTIONS} collections.`);
      try {
        const row = (await pool.query('insert into theme_collections (user_id, name) values ($1, $2) returning id, name', [me.id, b.name])).rows[0];
        return reply.status(201).send({ collection: row });
      } catch (e) {
        if ((e as { code?: string }).code === '23505') throw new HttpError(409, 'duplicate', 'You already have a collection with that name.');
        throw e;
      }
    });

    app.patch('/me/personalization/collections/:id', async (req) => {
      const me = await ctx.auth(req);
      const c = await myCollection(idParam((req.params as { id?: string }).id), me.id);
      const b = collectionNameSchema.parse(req.body ?? {});
      try {
        await pool.query('update theme_collections set name = $2, updated_at = now() where id = $1', [c.id, b.name]);
      } catch (e) {
        if ((e as { code?: string }).code === '23505') throw new HttpError(409, 'duplicate', 'You already have a collection with that name.');
        throw e;
      }
      return { collection: { id: c.id, name: b.name } };
    });

    app.delete('/me/personalization/collections/:id', async (req) => {
      const me = await ctx.auth(req);
      const c = await myCollection(idParam((req.params as { id?: string }).id), me.id);
      await pool.query('update theme_collections set deleted_at = now() where id = $1', [c.id]); // soft delete
      return { status: 'ok' };
    });

    app.get('/me/personalization/collections/:id/items', async (req) => {
      const me = await ctx.auth(req);
      const c = await myCollection(idParam((req.params as { id?: string }).id), me.id);
      const rows = (await pool.query<AssetRow>(
        `select ${ASSET_COLUMNS} from ${ASSET_FROM} join theme_collection_items i on i.asset_id = a.id
          where i.collection_id = $2 and a.status = 'published' order by i.added_at desc, a.id desc limit ${MAX_ITEMS_PER_COLLECTION}`, [me.id, c.id])).rows;
      const entitled = (await userEntitlements(pool, me.id)).includes(PREMIUM_ASSETS);
      return { collection: c, items: await Promise.all(rows.map((r) => assetView(ctx, r, entitled))) };
    });

    app.put('/me/personalization/collections/:id/items', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`pz-write:${me.id}`, 120, 3600);
      const c = await myCollection(idParam((req.params as { id?: string }).id), me.id);
      const b = collectionItemSchema.parse(req.body ?? {});
      const a = await publishedAsset(pool, b.assetId, me.id);
      needPremiumFor(a, await userEntitlements(pool, me.id));
      const n = Number((await pool.query<{ n: string }>('select count(*) as n from theme_collection_items where collection_id = $1', [c.id])).rows[0].n);
      if (n >= MAX_ITEMS_PER_COLLECTION) throw new HttpError(422, 'limit_reached', 'That collection is full.');
      await pool.query('insert into theme_collection_items (collection_id, asset_id) values ($1, $2) on conflict do nothing', [c.id, a.id]);
      return { status: 'ok' };
    });

    app.delete('/me/personalization/collections/:id/items/:assetId', async (req) => {
      const me = await ctx.auth(req);
      const p = req.params as { id?: string; assetId?: string };
      const c = await myCollection(idParam(p.id), me.id);
      await pool.query('delete from theme_collection_items where collection_id = $1 and asset_id = $2', [c.id, idParam(p.assetId)]);
      return { status: 'ok' };
    });

    // ------------------------------------------------------------ my own chat appearance
    type AppRow = { theme_id: string; wallpaper_id: string; wallpaper_asset_id: string | null; blur: number; dim: number; bubble_shape: string };
    const toApp = (r: AppRow | undefined) => ({
      themeId: r?.theme_id ?? 'default', wallpaperId: r?.wallpaper_id ?? 'none', wallpaperAssetId: r?.wallpaper_asset_id ?? null,
      blur: r?.blur ?? 0, dim: r?.dim ?? 0, bubbleShape: r?.bubble_shape ?? 'rounded',
    });

    async function memberOf(conversationId: string, userId: string) {
      const m = (await pool.query('select 1 from conversation_members where conversation_id = $1 and user_id = $2', [conversationId, userId])).rows[0];
      if (!m) throw new HttpError(404, 'not_found', 'Not found.');
    }

    /** My saved choice for one chat (or my default), plus what is actually shown now. A lapsed Premium look is kept, just not shown. */
    app.get('/me/chat-appearance', async (req) => {
      const me = await ctx.auth(req);
      const { conversationId } = z.object({ conversationId: z.string().uuid().optional() }).strict().parse(req.query ?? {});
      if (conversationId) await memberOf(conversationId, me.id);
      const row = (await pool.query<AppRow>(
        `select theme_id, wallpaper_id, wallpaper_asset_id, blur, dim, bubble_shape from user_chat_appearance
          where user_id = $1 and (conversation_id = $2 or conversation_id is null) order by conversation_id nulls last limit 1`, [me.id, conversationId ?? null])).rows[0];
      const saved = toApp(row);
      const keys = await userEntitlements(pool, me.id);
      const chatOk = keys.includes('premium.chat_themes');
      let asset = null;
      let assetOk = false;
      if (saved.wallpaperAssetId) {
        const a = (await pool.query<AssetRow>(`select ${ASSET_COLUMNS} from ${ASSET_FROM} where a.id = $2 and a.status = 'published'`, [me.id, saved.wallpaperAssetId])).rows[0];
        if (a) { assetOk = !a.premium || keys.includes(PREMIUM_ASSETS); asset = await assetView(ctx, a, keys.includes(PREMIUM_ASSETS)); }
      }
      const shown = {
        ...saved,
        themeId: isPremiumChatId(saved.themeId) && !chatOk ? 'default' : saved.themeId,
        wallpaperId: isPremiumChatId(saved.wallpaperId) && !chatOk ? 'none' : saved.wallpaperId,
        wallpaperAssetId: assetOk ? saved.wallpaperAssetId : null,
      };
      return { saved, shown, wallpaper: assetOk ? asset : null };
    });

    app.put('/me/chat-appearance', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`chat-appearance:${me.id}`, 120, 3600);
      const b = chatAppearanceSchema.parse(req.body ?? {});
      const conversationId = b.conversationId ?? null;
      if (conversationId) await memberOf(conversationId, me.id);
      const keys = await userEntitlements(pool, me.id);
      if ((isPremiumChatId(b.themeId) || isPremiumChatId(b.wallpaperId)) && !keys.includes('premium.chat_themes')) {
        throw new HttpError(403, 'premium_required', 'These options need UniVibe Premium.');
      }
      if (b.wallpaperAssetId) {
        const a = (await pool.query<{ premium: boolean; destinations: string[] }>("select premium, destinations from theme_assets where id = $1 and status = 'published'", [b.wallpaperAssetId])).rows[0];
        if (!a || !a.destinations.includes('chat_background')) throw new HttpError(422, 'validation', 'That image cannot be used as a chat background.');
        needPremiumFor(a, keys);
      }
      await pool.query(
        `insert into user_chat_appearance (user_id, conversation_id, theme_id, wallpaper_id, wallpaper_asset_id, blur, dim, bubble_shape)
         values ($1, $2, $3, $4, $5, $6, $7, $8)
         on conflict (user_id, (coalesce(conversation_id, '00000000-0000-0000-0000-000000000000'::uuid)))
         do update set theme_id = $3, wallpaper_id = $4, wallpaper_asset_id = $5, blur = $6, dim = $7, bubble_shape = $8, updated_at = now()`,
        [me.id, conversationId, b.themeId, b.wallpaperId, b.wallpaperAssetId ?? null, b.blur, b.dim, b.bubbleShape]);
      return { saved: { themeId: b.themeId, wallpaperId: b.wallpaperId, wallpaperAssetId: b.wallpaperAssetId ?? null, blur: b.blur, dim: b.dim, bubbleShape: b.bubbleShape } };
    });

    app.delete('/me/chat-appearance', async (req) => {
      const me = await ctx.auth(req);
      const { conversationId } = z.object({ conversationId: z.string().uuid().optional() }).strict().parse(req.query ?? {});
      await pool.query(
        "delete from user_chat_appearance where user_id = $1 and coalesce(conversation_id, '00000000-0000-0000-0000-000000000000'::uuid) = coalesce($2::uuid, '00000000-0000-0000-0000-000000000000'::uuid)",
        [me.id, conversationId ?? null]);
      return { status: 'ok' };
    });

    // ------------------------------------------------------------ staff: catalogue (personalization.manage)
    /** Registers an image the staff member already uploaded through /media (purpose "cover"). Nothing is trusted from the request but the media id. */
    app.post('/admin/personalization/assets', async (req, reply) => {
      const me = await need(req, 'personalization.manage');
      const b = registerAssetSchema.parse(req.body ?? {});
      const out = await withTx(pool, async (client) => {
        const m = (await client.query<{ object_key: string; width: number | null; height: number | null }>(
          "select object_key, width, height from media where id = $1 and owner_id = $2 and kind = 'image' and purpose = 'cover' and status = 'ready'", [b.mediaId, me.id])).rows[0];
        if (!m || !m.width || !m.height) throw new HttpError(422, 'validation', 'Upload the image first (a ready cover photo with known size).');
        const cat = (await client.query<{ id: string }>('select id from theme_categories where slug = $1', [b.categorySlug])).rows[0];
        if (!cat) throw new HttpError(422, 'validation', 'Unknown category.');
        // Created as a draft. Publishing is a separate, audited step so nothing goes live by accident.
        const row = (await client.query<{ id: string }>(
          `insert into theme_assets (category_id, title, description, source_attribution, license, object_key, thumb_key, width, height, premium, destinations, created_by)
           values ($1, $2, $3, $4, $5, $6, $6, $7, $8, $9, $10, $11) returning id`,
          [cat.id, b.title, b.description, b.sourceAttribution, b.license, m.object_key, m.width, m.height, b.premium, b.destinations, me.id])).rows[0];
        await audit(client, me.id, 'personalization.asset_create', 'theme_asset', row.id, { license: b.license, premium: b.premium });
        return row;
      });
      return reply.status(201).send({ id: out.id, status: 'draft' });
    });

    app.patch('/admin/personalization/assets/:id/status', async (req) => {
      const me = await need(req, 'personalization.manage');
      const id = idParam((req.params as { id?: string }).id);
      const b = assetStatusSchema.parse(req.body ?? {});
      return withTx(pool, async (client) => {
        const cur = (await client.query<{ status: string }>('select status from theme_assets where id = $1 for update', [id])).rows[0];
        if (!cur) throw new HttpError(404, 'not_found', 'Not found.');
        if (cur.status === 'removed') throw new HttpError(422, 'validation', 'Removed content stays removed.');
        await client.query(
          "update theme_assets set status = $2, published_at = case when $2 = 'published' then coalesce(published_at, now()) else published_at end, updated_at = now() where id = $1", [id, b.status]);
        await audit(client, me.id, 'personalization.asset_status', 'theme_asset', id, { from: cur.status, to: b.status });
        return { status: 'ok' };
      });
    });
  };
}
