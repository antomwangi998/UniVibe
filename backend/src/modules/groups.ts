import type { FastifyInstance, FastifyRequest } from 'fastify';
import { z } from 'zod';
import type { Ctx } from '../context';
import { withTx } from '../db';
import { HttpError } from '../errors';
import { idParam } from '../http';
import type { Hub } from '../realtime';
import { audience, membership } from './messaging';
import { REPORT_REASONS } from './moderation';

const infoSchema = z.object({
  title: z.string().trim().min(1).max(40).optional(),
  description: z.string().trim().max(300).optional(),
  // A group photo: an uploaded, ready image made for this purpose. null removes the photo.
  photoMediaId: z.string().uuid().nullable().optional(),
}).strict();
const permissionsSchema = z.object({
  onlyAdminsCanMessage: z.boolean(), onlyAdminsCanEditInfo: z.boolean(), onlyAdminsCanAddMembers: z.boolean(),
}).partial().strict();
const roleSchema = z.object({ role: z.enum(['admin', 'member']) }).strict();
const reportSchema = z.object({ reason: z.enum(REPORT_REASONS), details: z.string().trim().max(500).default('') }).strict();

/** Group profile and rules: name, description, photo, who may do what, roles, and reporting a group. */
export function groupRoutes(ctx: Ctx, hub: Hub) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;
    const param = (req: FastifyRequest, name = 'id') => idParam((req.params as Record<string, string>)[name]);

    async function groupMember(id: string, userId: string) {
      const m = await membership(pool, id, userId);
      if (m.kind !== 'group') throw new HttpError(400, 'validation', 'That only applies to groups.');
      return m;
    }
    const everyone = async (id: string) =>
      (await pool.query<{ user_id: string }>('select user_id from conversation_members where conversation_id = $1', [id])).rows.map((r) => r.user_id);

    // PUT is accepted too: some HTTP stacks (older Android clients) cannot send PATCH.
    app.route({ method: ['PATCH', 'PUT'], url: '/conversations/:id/info', handler: async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const b = infoSchema.parse(req.body ?? {});
      const mine = await groupMember(id, me.id);
      const rules = (await pool.query<{ e: boolean }>('select only_admins_can_edit_info as e from conversations where id = $1', [id])).rows[0];
      if (rules.e && mine.role === 'member') throw new HttpError(403, 'forbidden', 'Only admins can change the group info.');
      const removedKey = await withTx(pool, async (client) => {
        if (b.photoMediaId) {
          const ok = await client.query(
            "select 1 from media where id = $1 and owner_id = $2 and purpose = 'group' and kind = 'image' and status = 'ready' and message_id is null and post_id is null",
            [b.photoMediaId, me.id]);
          if (!ok.rowCount) throw new HttpError(422, 'invalid_media', 'That photo is not available.');
        }
        const before = (await client.query<{ photo_media_id: string | null; key: string | null }>(
          'select c.photo_media_id, m.object_key as key from conversations c left join media m on m.id = c.photo_media_id where c.id = $1 for update of c', [id])).rows[0];
        await client.query(
          `update conversations set title = coalesce($2, title),
              description = case when $3::boolean then $4 else description end,
              photo_media_id = case when $5::boolean then $6::uuid else photo_media_id end
            where id = $1`,
          [id, b.title ?? null, b.description !== undefined, b.description === undefined ? null : (b.description || null), b.photoMediaId !== undefined, b.photoMediaId ?? null]);
        if (b.photoMediaId !== undefined && before.photo_media_id && before.photo_media_id !== b.photoMediaId) {
          await client.query('delete from media where id = $1', [before.photo_media_id]);
          return before.key;
        }
        return null;
      });
      if (removedKey) await ctx.storage?.remove([removedKey]).catch(() => undefined); // the old picture is not kept
      hub.sendMany(await everyone(id), { type: 'conversation:updated', conversationId: id });
      return reply.status(204).send();
    } });

    app.route({ method: ['PATCH', 'PUT'], url: '/conversations/:id/permissions', handler: async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const b = permissionsSchema.parse(req.body ?? {});
      const mine = await groupMember(id, me.id);
      if (mine.role === 'member') throw new HttpError(403, 'forbidden', 'Only admins can change the group rules.');
      await pool.query(
        `update conversations set
            only_admins_can_message = coalesce($2::boolean, only_admins_can_message),
            only_admins_can_edit_info = coalesce($3::boolean, only_admins_can_edit_info),
            only_admins_can_add_members = coalesce($4::boolean, only_admins_can_add_members)
          where id = $1`,
        [id, b.onlyAdminsCanMessage ?? null, b.onlyAdminsCanEditInfo ?? null, b.onlyAdminsCanAddMembers ?? null]);
      hub.sendMany(await everyone(id), { type: 'conversation:updated', conversationId: id });
      return reply.status(204).send();
    } });

    app.route({ method: ['PATCH', 'PUT'], url: '/conversations/:id/members/:userId/role', handler: async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const targetId = param(req, 'userId');
      const b = roleSchema.parse(req.body ?? {});
      const mine = await groupMember(id, me.id);
      if (mine.role !== 'owner') throw new HttpError(403, 'forbidden', 'Only the owner can make or remove admins.');
      const res = await pool.query(
        "update conversation_members set role = $3 where conversation_id = $1 and user_id = $2 and role <> 'owner'", [id, targetId, b.role]);
      if (!res.rowCount) throw new HttpError(404, 'not_found', 'Not found.'); // not a member, or the owner (whose role changes only by leaving)
      hub.sendMany(await everyone(id), { type: 'conversation:updated', conversationId: id });
      return reply.status(204).send();
    } });

    app.post('/conversations/:id/report', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = param(req);
      const b = reportSchema.parse(req.body ?? {});
      ctx.limit(`report:${me.id}`, 20, 3600);
      await groupMember(id, me.id);
      await pool.query(
        'insert into conversation_reports (conversation_id, reporter_id, reason, details) values ($1, $2, $3, $4) on conflict do nothing', [id, me.id, b.reason, b.details]);
      return reply.status(204).send();
    });
  };
}
