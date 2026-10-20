import type { FastifyInstance, FastifyRequest } from 'fastify';
import type { PoolClient } from 'pg';
import { z } from 'zod';
import { blockedBetween, userVisible } from '../access';
import type { AuthUser, Ctx } from '../context';
import { withTx } from '../db';
import { HttpError } from '../errors';
import { decodeCursor, idParam, pageSchema, toPage, tsText } from '../http';
import type { Hub } from '../realtime';
import { POST_COLS, POST_FROM, type PostRow, presentPosts, visible } from '../posts';
import { CARD_COLUMNS, CARD_JOIN, type CardRow, signedUrl, toCard } from './cards';
import { type JoinState, isStaff, joinState } from './community_state';
import { audit } from './moderation';
import { notify, retract } from './notify';

type Q = Pick<PoolClient, 'query'>;

export const CATEGORIES = ['academics', 'clubs', 'sports', 'arts', 'tech', 'career', 'social', 'wellbeing', 'other'] as const;
const MAX_OWNED = 5;
const MAX_STAFF_NOTIFIED = 20;
const INVITE_DAYS = 14;

const nameSchema = z.string().trim().min(3, 'Use at least 3 characters.').max(40, 'Use at most 40 characters.')
  // Letters, numbers, spaces and light punctuation only: no control characters, no markup.
  .regex(/^[\p{L}\p{N}][\p{L}\p{N} &'.\-]*$/u, 'Use letters, numbers and spaces.');
const descriptionSchema = z.string().trim().min(10, 'Use at least 10 characters.').max(300);
const rulesSchema = z.array(z.string().trim().min(1).max(120)).max(8);

const settingsShape = {
  description: descriptionSchema,
  category: z.enum(CATEGORIES),
  visibility: z.enum(['public', 'private']),
  membershipPolicy: z.enum(['open', 'approval', 'invite_only']),
  discoverable: z.boolean(),
  postPermission: z.enum(['members', 'staff']),
  invitePermission: z.enum(['staff', 'members']),
  rules: rulesSchema,
  avatarMediaId: z.string().uuid().nullable(),
  coverMediaId: z.string().uuid().nullable(),
};

const createSchema = z.object({
  name: nameSchema,
  description: descriptionSchema,
  category: settingsShape.category,
  visibility: settingsShape.visibility.default('public'),
  membershipPolicy: settingsShape.membershipPolicy.optional(),
  discoverable: settingsShape.discoverable.default(true),
  postPermission: settingsShape.postPermission.default('members'),
  invitePermission: settingsShape.invitePermission.default('staff'),
  rules: rulesSchema.default([]),
  avatarMediaId: settingsShape.avatarMediaId.optional(),
  coverMediaId: settingsShape.coverMediaId.optional(),
  /** Generated once per form, so a retried submit returns the same community instead of making a second one. */
  clientKey: z.string().min(8).max(64).regex(/^[A-Za-z0-9_-]+$/).optional(),
}).strict();

const patchSchema = z.object(settingsShape).partial().strict();

/** Public communities are open or approval-based; private ones need approval or an invitation. */
function resolvePolicy(visibility: string, policy: string | undefined): string {
  const p = policy ?? (visibility === 'public' ? 'open' : 'approval');
  if (visibility === 'private' && p === 'open') throw new HttpError(422, 'validation', 'A private community cannot be open to everyone.');
  if (visibility === 'public' && p === 'invite_only') throw new HttpError(422, 'validation', 'A public community cannot be invitation-only.');
  return p;
}

const slugOf = (name: string) =>
  name.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'community';
const nameKey = (name: string) => name.toLowerCase().replace(/\s+/g, ' ').trim();
const likePattern = (s: string) => s.replace(/[\\%_]/g, (m) => `\\${m}`);
const isUnique = (e: unknown) => (e as { code?: string } | null)?.code === '23505';

interface CommunityRow {
  id: string; university_id: string; name: string; slug: string; description: string; category: string;
  visibility: string; membership_policy: string; discoverable: boolean; post_permission: string; invite_permission: string;
  rules: string[]; owner_id: string; status: string; member_count: number; created_at: Date; updated_at: Date;
  avatar_key: string | null; cover_key: string | null;
  my_role: string | null; my_suspended: boolean; my_level: string | null; pending: boolean; invited: boolean; muted: boolean;
  owner_handle: string; university_name: string;
}

/** One query shape for every community card and page. $1 = the viewer. */
const COMMUNITY_SELECT = `
  select c.*, uni.name as university_name, ow.handle as owner_handle,
         (select m.object_key from media m where m.id = c.avatar_media_id and m.status = 'ready') as avatar_key,
         (select m.object_key from media m where m.id = c.cover_media_id and m.status = 'ready') as cover_key,
         (select cm.role from community_members cm where cm.community_id = c.id and cm.user_id = $1 and cm.status = 'active') as my_role,
         exists (select 1 from community_members cm where cm.community_id = c.id and cm.user_id = $1 and cm.status = 'suspended') as my_suspended,
         (select cm.notification_level from community_members cm where cm.community_id = c.id and cm.user_id = $1) as my_level,
         exists (select 1 from community_mutes mu where mu.community_id = c.id and mu.user_id = $1) as muted,
         exists (select 1 from community_requests cr where cr.community_id = c.id and cr.user_id = $1 and cr.status = 'pending') as pending,
         exists (select 1 from community_invitations ci where ci.community_id = c.id and ci.invitee_id = $1 and ci.status = 'pending' and ci.expires_at > now()) as invited
    from communities c join universities uni on uni.id = c.university_id join users ow on ow.id = c.owner_id`;

/**
 * Who may see a community's public profile (name, description, counts): same university, not deleted, and either a
 * member, or public, or private-but-discoverable. A blocked owner makes the community invisible, like blocked people.
 */
const profileVisible = (c = 'c') =>
  `(${c}.university_id = $2 and ${c}.status <> 'deleted' and not ${blockedBetween('$1', `${c}.owner_id`)} and ` +
  `(${c}.visibility = 'public' or ${c}.discoverable or exists (select 1 from community_members vm where vm.community_id = ${c}.id and vm.user_id = $1 and vm.status = 'active') ` +
  `or exists (select 1 from community_invitations vi where vi.community_id = ${c}.id and vi.invitee_id = $1 and vi.status = 'pending' and vi.expires_at > now())))`;

export function communityJson(ctx: Ctx, r: CommunityRow, withDetails: boolean) {
  const isMember = r.my_role !== null;
  const state: JoinState = joinState({
    status: r.status, visibility: r.visibility, policy: r.membership_policy,
    role: r.my_role, suspended: r.my_suspended, pending: r.pending, invited: r.invited,
  });
  const canRead = r.visibility === 'public' || isMember;
  return {
    id: r.id, name: r.name, slug: r.slug, description: r.description, category: r.category,
    visibility: r.visibility, membershipPolicy: r.membership_policy, status: r.status,
    memberCount: r.member_count, createdAt: r.created_at,
    universityName: r.university_name,
    membership: state, myRole: r.my_role, myNotificationLevel: r.my_level, muted: r.muted,
    // Private communities show a profile to non-members but never their posts.
    canReadPosts: canRead && r.status !== 'suspended',
    canPost: isMember && r.status === 'active' && (r.post_permission === 'members' || isStaff(r.my_role)),
    canInvite: isMember && r.status === 'active' && (isStaff(r.my_role) || r.invite_permission === 'members'),
    canManage: r.my_role === 'owner', canModerate: isStaff(r.my_role),
    ...(withDetails ? {
      rules: r.rules, postPermission: r.post_permission, invitePermission: r.invite_permission,
      discoverable: r.discoverable, ownerHandle: r.owner_handle, updatedAt: r.updated_at,
    } : {}),
    _avatarKey: r.avatar_key, _coverKey: r.cover_key,
  };
}

async function present(ctx: Ctx, rows: CommunityRow[], withDetails = false) {
  return Promise.all(rows.map(async (r) => {
    const { _avatarKey, _coverKey, ...rest } = communityJson(ctx, r, withDetails);
    return { ...rest, avatarUrl: await signedUrl(ctx, _avatarKey), coverUrl: await signedUrl(ctx, _coverKey) };
  }));
}

export function communityRoutes(ctx: Ctx, hub: Hub) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;

    // ---------- helpers
    async function loadFor(me: AuthUser, id: string, q: Q = pool): Promise<CommunityRow> {
      const { rows } = await q.query<CommunityRow>(`${COMMUNITY_SELECT} where c.id = $3 and ${profileVisible()}`, [me.id, me.universityId, id]);
      if (!rows[0]) throw new HttpError(404, 'not_found', 'That community is not available.');
      return rows[0];
    }
    const routeId = (req: FastifyRequest) => idParam((req.params as { id: string }).id);

    /** Live, active staff role of the caller, or a 403/404. Authorization is always re-read from the database. */
    async function requireRole(me: AuthUser, id: string, allowed: ('owner' | 'moderator')[], q: Q = pool) {
      const c = await loadFor(me, id, q);
      if (!c.my_role || !(allowed as string[]).includes(c.my_role)) throw new HttpError(403, 'forbidden', 'You do not have permission to do that.');
      return c;
    }

    const recount = (q: Q, id: string) =>
      q.query("update communities set member_count = (select count(*) from community_members where community_id = $1 and status = 'active'), updated_at = now() where id = $1 returning member_count", [id])
        .then((r) => (r.rows[0]?.member_count as number) ?? 0);

    /** Pushes the new membership state to the person's other open apps. Fire-and-forget; the REST response is the truth. */
    const announce = (userId: string, communityId: string, membership: JoinState, memberCount: number) =>
      hub.send(userId, { type: 'community:membership', communityId, membership, memberCount });

    async function staffIds(q: Q, communityId: string): Promise<string[]> {
      const { rows } = await q.query<{ user_id: string }>(
        "select user_id from community_members where community_id = $1 and role in ('owner', 'moderator') and status = 'active' order by role desc, joined_at limit $2",
        [communityId, MAX_STAFF_NOTIFIED]);
      return rows.map((r) => r.user_id);
    }

    async function validateImage(q: Q, userId: string, mediaId: string | null | undefined) {
      if (!mediaId) return;
      const ok = await q.query(
        "select 1 from media where id = $1 and owner_id = $2 and status = 'ready' and kind = 'image' and purpose = 'community' and post_id is null",
        [mediaId, userId]);
      if (!ok.rowCount) throw new HttpError(422, 'validation', 'That image is not available. Upload it again.');
    }

    async function addMember(q: Q, communityId: string, userId: string): Promise<boolean> {
      const res = await q.query(
        "insert into community_members (community_id, user_id, role) values ($1, $2, 'member') on conflict (community_id, user_id) do nothing", [communityId, userId]);
      return (res.rowCount ?? 0) > 0;
    }

    // ---------- create
    app.post('/communities', async (req, reply) => {
      const me = await ctx.auth(req);
      const b = createSchema.parse(req.body ?? {});
      ctx.limit(`community-create:${me.id}`, 5, 3600);
      const policy = resolvePolicy(b.visibility, b.membershipPolicy);

      // A retried request (same form key) returns what the first attempt made.
      if (b.clientKey) {
        const again = await pool.query<{ id: string }>('select id from communities where owner_id = $1 and create_key = $2', [me.id, b.clientKey]);
        if (again.rows[0]) return reply.status(200).send((await present(ctx, [await loadFor(me, again.rows[0].id)], true))[0]);
      }

      try {
        const id = await withTx(pool, async (client) => {
          // Serialise this person's creations so the quota cannot be beaten by parallel requests.
          await client.query('select pg_advisory_xact_lock(hashtext($1))', [`community-quota:${me.id}`]);
          const owned = await client.query<{ n: string }>("select count(*) as n from communities where owner_id = $1 and status in ('active', 'archived', 'suspended')", [me.id]);
          if (Number(owned.rows[0].n) >= MAX_OWNED) throw new HttpError(429, 'quota_exceeded', `You can own up to ${MAX_OWNED} communities.`);
          await validateImage(client, me.id, b.avatarMediaId);
          await validateImage(client, me.id, b.coverMediaId);

          // Slug: the name's slug, with a short suffix if another community at this campus already has it.
          const base = slugOf(b.name);
          let slug = base;
          for (let i = 2; (await client.query('select 1 from communities where university_id = $1 and slug = $2 and status <> \'deleted\'', [me.universityId, slug])).rowCount; i++) slug = `${base}-${i}`;

          const made = await client.query<{ id: string }>(
            `insert into communities (university_id, name, name_key, slug, description, category, visibility, membership_policy, discoverable,
                                      post_permission, invite_permission, rules, avatar_media_id, cover_media_id, owner_id, create_key, member_count)
             values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, 1) returning id`,
            [me.universityId, b.name, nameKey(b.name), slug, b.description, b.category, b.visibility, policy, b.discoverable,
             b.postPermission, b.invitePermission, b.rules, b.avatarMediaId ?? null, b.coverMediaId ?? null, me.id, b.clientKey ?? null]);
          const cid = made.rows[0].id;
          await client.query("insert into community_members (community_id, user_id, role, notification_level) values ($1, $2, 'owner', 'all')", [cid, me.id]);
          await audit(client, me.id, 'community.create', 'community', cid, { visibility: b.visibility });
          return cid;
        });
        return reply.status(201).send((await present(ctx, [await loadFor(me, id)], true))[0]);
      } catch (e) {
        if (isUnique(e)) {
          // Either the name is taken, or two submits with the same key raced; the key case returns the winner.
          if (b.clientKey) {
            const winner = await pool.query<{ id: string }>('select id from communities where owner_id = $1 and create_key = $2', [me.id, b.clientKey]);
            if (winner.rows[0]) return reply.status(200).send((await present(ctx, [await loadFor(me, winner.rows[0].id)], true))[0]);
          }
          throw new HttpError(409, 'name_taken', 'A community with that name already exists at your university.');
        }
        throw e;
      }
    });

    // ---------- browse and search
    app.get('/communities', async (req) => {
      const me = await ctx.auth(req);
      const q = pageSchema.extend({
        q: z.string().trim().max(60).optional(),
        category: z.enum(CATEGORIES).optional(),
        scope: z.enum(['all', 'joined', 'public']).default('all'),
      }).parse(req.query);
      const cur = decodeCursor(q.cursor);
      const { rows } = await pool.query<CommunityRow & { cursorT: string }>(
        `${COMMUNITY_SELECT.replace('select c.*,', `select c.*, ${tsText('c.created_at')} as "cursorT",`)}
          where ${profileVisible()} and c.status in ('active', 'archived')
            and ($3::text is null or c.name ilike '%' || $3 || '%' escape '\\' or c.description ilike '%' || $3 || '%' escape '\\')
            and ($4::text is null or c.category = $4)
            and ($5 <> 'joined' or exists (select 1 from community_members jm where jm.community_id = c.id and jm.user_id = $1 and jm.status = 'active'))
            and ($5 <> 'public' or c.visibility = 'public')
            and ($6::timestamptz is null or (c.created_at, c.id) < ($6::timestamptz, $7::uuid))
          order by c.created_at desc, c.id desc limit $8`,
        [me.id, me.universityId, q.q ? likePattern(q.q) : null, q.category ?? null, q.scope, cur?.t ?? null, cur?.id ?? null, q.limit + 1]);
      const page = toPage(rows as unknown as (CommunityRow & { cursorT: string; id: string })[], q.limit);
      return { items: await present(ctx, page.items as unknown as CommunityRow[]), nextCursor: page.nextCursor };
    });

    app.get('/communities/:id', async (req) => (await present(ctx, [await loadFor(await ctx.auth(req), routeId(req))], true))[0]);

    // ---------- settings (owner only)
    app.patch('/communities/:id', async (req) => {
      const me = await ctx.auth(req);
      const id = routeId(req);
      const b = patchSchema.parse(req.body ?? {});
      const { approvedUsers, memberCount } = await withTx(pool, async (client) => {
        await client.query('select 1 from communities where id = $1 for update', [id]);
        const c = await requireRole(me, id, ['owner'], client);
        if (c.status === 'deleted' || c.status === 'suspended') throw new HttpError(409, 'community_unavailable', 'This community cannot be edited right now.');
        const visibility = b.visibility ?? c.visibility;
        // Changing visibility without saying how people join resets to the sensible default for that visibility.
        const policy = resolvePolicy(visibility, b.membershipPolicy ?? (b.visibility && b.visibility !== c.visibility ? undefined : c.membership_policy));
        if (b.avatarMediaId !== undefined) await validateImage(client, me.id, b.avatarMediaId);
        if (b.coverMediaId !== undefined) await validateImage(client, me.id, b.coverMediaId);
        await client.query(
          `update communities set description = coalesce($2, description), category = coalesce($3, category), visibility = $4, membership_policy = $5,
                  discoverable = coalesce($6, discoverable), post_permission = coalesce($7, post_permission), invite_permission = coalesce($8, invite_permission),
                  rules = coalesce($9, rules),
                  avatar_media_id = case when $10::boolean then $11::uuid else avatar_media_id end,
                  cover_media_id = case when $12::boolean then $13::uuid else cover_media_id end, updated_at = now()
            where id = $1`,
          [id, b.description ?? null, b.category ?? null, visibility, policy, b.discoverable ?? null, b.postPermission ?? null, b.invitePermission ?? null,
           b.rules ?? null, b.avatarMediaId !== undefined, b.avatarMediaId ?? null, b.coverMediaId !== undefined, b.coverMediaId ?? null]);
        // Opening a community up approves everyone who was waiting.
        let approved: string[] = [];
        if (policy === 'open') {
          const waiting = await client.query<{ id: string; user_id: string }>("select id, user_id from community_requests where community_id = $1 and status = 'pending' for update", [id]);
          for (const w of waiting.rows) {
            await addMember(client, id, w.user_id);
            await client.query("update community_requests set status = 'approved', decided_by = $2, decided_at = now() where id = $1", [w.id, me.id]);
            await notify(client, { recipientId: w.user_id, actorId: me.id, type: 'community_join_approved', entityType: 'community', entityId: id });
            approved.push(w.user_id);
          }
        }
        const count = await recount(client, id);
        await audit(client, me.id, 'community.update', 'community', id, { fields: Object.keys(b), visibility, policy });
        return { approvedUsers: approved, memberCount: count };
      });
      for (const u of approvedUsers) announce(u, id, 'joined', memberCount);
      return (await present(ctx, [await loadFor(me, id)], true))[0];
    });

    // ---------- delete (soft; posts disappear because a deleted community is unreadable)
    const deleteCommunity = async (req: FastifyRequest, reply: { status: (n: number) => { send: () => unknown } }) => {
      const me = await ctx.auth(req);
      const id = routeId(req);
      const { confirmName } = z.object({ confirmName: z.string() }).strict().parse(req.body ?? {});
      await withTx(pool, async (client) => {
        const c = await requireRole(me, id, ['owner'], client);
        if (nameKey(confirmName) !== nameKey(c.name)) throw new HttpError(422, 'confirmation_mismatch', 'Type the community name exactly to delete it.');
        await client.query("update communities set status = 'deleted', updated_at = now() where id = $1", [id]);
        await client.query("update community_requests set status = 'cancelled', decided_at = now() where community_id = $1 and status = 'pending'", [id]);
        await client.query("update community_invitations set status = 'revoked', decided_at = now() where community_id = $1 and status = 'pending'", [id]);
        await audit(client, me.id, 'community.delete', 'community', id, { memberCount: c.member_count });
      });
      return reply.status(204).send();
    };
    app.delete('/communities/:id', deleteCommunity);
    // Some HTTP clients (Android's HttpURLConnection) cannot send a body with DELETE, so the app uses this form.
    app.post('/communities/:id/delete', deleteCommunity);

    app.post('/communities/:id/archive', async (req) => {
      const me = await ctx.auth(req);
      const id = routeId(req);
      const { archived } = z.object({ archived: z.boolean() }).strict().parse(req.body ?? {});
      await withTx(pool, async (client) => {
        const c = await requireRole(me, id, ['owner'], client);
        if (c.status === 'suspended' || c.status === 'deleted') throw new HttpError(409, 'community_unavailable', 'This community cannot be changed right now.');
        await client.query('update communities set status = $2, updated_at = now() where id = $1', [id, archived ? 'archived' : 'active']);
        await audit(client, me.id, archived ? 'community.archive' : 'community.unarchive', 'community', id);
      });
      return (await present(ctx, [await loadFor(me, id)], true))[0];
    });

    // ---------- membership
    app.get('/communities/:id/membership', async (req) => {
      const c = await loadFor(await ctx.auth(req), routeId(req));
      return { communityId: c.id, membership: (await present(ctx, [c]))[0].membership, myRole: c.my_role, memberCount: c.member_count };
    });

    app.put('/communities/:id/membership', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = routeId(req);
      ctx.limit(`community-join:${me.id}`, 30, 3600);
      const result = await withTx(pool, async (client) => {
        // The community row lock serialises concurrent joins/approvals for one community.
        await client.query('select 1 from communities where id = $1 for update', [id]);
        const c = await loadFor(me, id, client);
        if (c.my_role) return { state: 'joined' as JoinState, count: c.member_count, created: false };
        if (c.my_suspended) throw new HttpError(403, 'suspended_member', 'You cannot rejoin this community right now.');
        if (c.status !== 'active') throw new HttpError(409, 'community_unavailable', 'This community is not accepting members.');

        const invite = await client.query<{ id: string }>(
          "select id from community_invitations where community_id = $1 and invitee_id = $2 and status = 'pending' and expires_at > now() for update", [id, me.id]);
        if (invite.rows[0] || c.membership_policy === 'open') {
          await addMember(client, id, me.id);
          if (invite.rows[0]) await client.query("update community_invitations set status = 'accepted', decided_at = now() where id = $1", [invite.rows[0].id]);
          await client.query("update community_requests set status = 'approved', decided_at = now() where community_id = $1 and user_id = $2 and status = 'pending'", [id, me.id]);
          const count = await recount(client, id);
          await audit(client, me.id, 'community.join', 'community', id, { via: invite.rows[0] ? 'invitation' : 'open' });
          return { state: 'joined' as JoinState, count, created: true };
        }
        if (c.membership_policy === 'invite_only') throw new HttpError(403, 'invite_only', 'This community is invitation-only.');

        // Approval needed: record one pending request (the unique index makes repeats harmless) and tell the staff.
        const made = await client.query("insert into community_requests (community_id, user_id) values ($1, $2) on conflict do nothing", [id, me.id]);
        if (made.rowCount) {
          for (const staff of await staffIds(client, id)) {
            await notify(client, { recipientId: staff, actorId: me.id, type: 'community_join_request', entityType: 'community', entityId: id, metadata: { communityId: id } });
          }
        }
        return { state: 'pending' as JoinState, count: c.member_count, created: false };
      });
      announce(me.id, id, result.state, result.count);
      return reply.status(result.state === 'pending' ? 202 : 200).send({ communityId: id, membership: result.state, memberCount: result.count });
    });

    app.delete('/communities/:id/membership', async (req) => {
      const me = await ctx.auth(req);
      const id = routeId(req);
      const result = await withTx(pool, async (client) => {
        await client.query('select 1 from communities where id = $1 for update', [id]);
        const c = await loadFor(me, id, client);
        if (c.my_role === 'owner') throw new HttpError(409, 'owner_must_transfer', 'Transfer ownership before leaving, or delete the community.');
        if (c.pending && !c.my_role) {
          await client.query("update community_requests set status = 'cancelled', decided_at = now() where community_id = $1 and user_id = $2 and status = 'pending'", [id, me.id]);
          for (const staff of await staffIds(client, id)) await retract(client, staff, me.id, 'community_join_request', id);
        } else if (c.my_role || c.my_suspended) {
          // A suspended member who leaves is simply gone; suspension does not survive leaving to dodge it
          // only for the owner's explicit removal, which keeps the audit trail.
          await client.query('delete from community_members where community_id = $1 and user_id = $2', [id, me.id]);
          await audit(client, me.id, 'community.leave', 'community', id);
        }
        const count = await recount(client, id);
        // Not loadFor: after leaving a private community the person may no longer be allowed to see it.
        const after = (await client.query<CommunityRow>(`${COMMUNITY_SELECT} where c.id = $3`, [me.id, me.universityId, id])).rows[0];
        const state = joinState({ status: after.status, visibility: after.visibility, policy: after.membership_policy, role: after.my_role, suspended: after.my_suspended, pending: after.pending, invited: after.invited });
        return { state, count };
      });
      announce(me.id, id, result.state, result.count);
      return { communityId: id, membership: result.state, memberCount: result.count };
    });

    app.patch('/communities/:id/membership', async (req) => {
      const me = await ctx.auth(req);
      const id = routeId(req);
      const b = z.object({ notificationLevel: z.enum(['all', 'highlights', 'mentions', 'announcements', 'muted']) }).strict().parse(req.body ?? {});
      const res = await pool.query("update community_members set notification_level = $3 where community_id = $1 and user_id = $2 and status = 'active'", [id, me.id, b.notificationLevel]);
      if (!res.rowCount) throw new HttpError(404, 'not_found', 'You are not a member of this community.');
      return { communityId: id, notificationLevel: b.notificationLevel };
    });

    // ---------- join requests (staff)
    app.get('/communities/:id/requests', async (req) => {
      const me = await ctx.auth(req);
      const id = routeId(req);
      await requireRole(me, id, ['owner', 'moderator']);
      const q = pageSchema.parse(req.query);
      const cur = decodeCursor(q.cursor);
      const { rows } = await pool.query(
        `select r.id, r.created_at, ${tsText('r.created_at')} as "cursorT", ${CARD_COLUMNS}
           from community_requests r join users u on u.id = r.user_id ${CARD_JOIN}
          where r.community_id = $1 and r.status = 'pending' and ${userVisible('u', '$2', '$3')}
            and ($4::timestamptz is null or (r.created_at, r.id) < ($4::timestamptz, $5::uuid))
          order by r.created_at desc, r.id desc limit $6`,
        [id, me.id, me.universityId, cur?.t ?? null, cur?.id ?? null, q.limit + 1]);
      const page = toPage(rows as (CardRow & { cursorT: string; id: string; created_at: Date })[], q.limit);
      return {
        items: await Promise.all(page.items.map(async (r) => ({ id: r.id, createdAt: r.created_at, user: await toCard(ctx, r as unknown as CardRow) }))),
        nextCursor: page.nextCursor,
      };
    });

    async function decide(req: FastifyRequest, approve: boolean) {
      const me = await ctx.auth(req);
      const id = routeId(req);
      const requestId = idParam((req.params as { requestId: string }).requestId);
      const out = await withTx(pool, async (client) => {
        await client.query('select 1 from communities where id = $1 for update', [id]);
        const c = await requireRole(me, id, ['owner', 'moderator'], client);
        if (approve && c.status !== 'active') throw new HttpError(409, 'community_unavailable', 'This community is not accepting members.');
        const r = await client.query<{ user_id: string }>("select user_id from community_requests where id = $1 and community_id = $2 and status = 'pending' for update", [requestId, id]);
        if (!r.rows[0]) throw new HttpError(404, 'not_found', 'That request is no longer pending.');
        const userId = r.rows[0].user_id;
        await client.query('update community_requests set status = $2, decided_by = $3, decided_at = now() where id = $1', [requestId, approve ? 'approved' : 'rejected', me.id]);
        for (const staff of await staffIds(client, id)) await retract(client, staff, userId, 'community_join_request', id);
        if (approve) {
          await addMember(client, id, userId);
          // The decision is announced as the community, not as the moderator, so a decline never names who declined.
          await client.query("delete from notifications where recipient_id = $1 and type in ('community_join_approved', 'community_join_declined') and entity_id = $2", [userId, id]);
          await notify(client, { recipientId: userId, actorId: null, type: 'community_join_approved', entityType: 'community', entityId: id, metadata: { communityId: id } });
        } else {
          await client.query("delete from notifications where recipient_id = $1 and type in ('community_join_approved', 'community_join_declined') and entity_id = $2", [userId, id]);
          await notify(client, { recipientId: userId, actorId: null, type: 'community_join_declined', entityType: 'community', entityId: id, metadata: { communityId: id } });
        }
        const count = await recount(client, id);
        await audit(client, me.id, approve ? 'community.request.approve' : 'community.request.reject', 'community', id, { requestId });
        return { userId, count };
      });
      announce(out.userId, id, approve ? 'joined' : 'request', out.count);
      return { id: requestId, status: approve ? 'approved' : 'rejected', memberCount: out.count };
    }
    app.post('/communities/:id/requests/:requestId/approve', (req) => decide(req, true));
    app.post('/communities/:id/requests/:requestId/reject', (req) => decide(req, false));

    // ---------- invitations
    app.post('/communities/:id/invitations', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = routeId(req);
      const { userId } = z.object({ userId: z.string().uuid() }).strict().parse(req.body ?? {});
      ctx.limit(`community-invite:${me.id}`, 40, 3600);
      const invitationId = await withTx(pool, async (client) => {
        const c = await loadFor(me, id, client);
        if (!c.my_role) throw new HttpError(403, 'forbidden', 'Only members can invite people.');
        if (c.status !== 'active') throw new HttpError(409, 'community_unavailable', 'This community is not accepting members.');
        if (!isStaff(c.my_role) && c.invite_permission !== 'members') throw new HttpError(403, 'forbidden', 'Only moderators can invite people to this community.');
        // The invitee must be someone the inviter may see; anything else looks like "no such person".
        const target = await client.query<{ id: string }>(`select u.id from users u where u.id = $3 and u.id <> $1 and ${userVisible('u', '$1', '$2')}`, [me.id, me.universityId, userId]);
        if (!target.rows[0]) throw new HttpError(404, 'not_found', 'Person not found.');
        const already = await client.query('select 1 from community_members where community_id = $1 and user_id = $2', [id, userId]);
        if (already.rowCount) throw new HttpError(409, 'already_member', 'That person is already a member.');
        await client.query("update community_invitations set status = 'expired', decided_at = now() where community_id = $1 and invitee_id = $2 and status = 'pending' and expires_at <= now()", [id, userId]);
        const made = await client.query<{ id: string }>(
          `insert into community_invitations (community_id, inviter_id, invitee_id, expires_at) values ($1, $2, $3, now() + make_interval(days => $4::int))
           on conflict do nothing returning id`, [id, me.id, userId, INVITE_DAYS]);
        if (!made.rows[0]) throw new HttpError(409, 'already_invited', 'That person already has a pending invitation.');
        // An earlier decided invitation must not swallow this notification through the dedupe index.
        await retract(client, userId, me.id, 'community_invitation', id);
        await notify(client, { recipientId: userId, actorId: me.id, type: 'community_invitation', entityType: 'community', entityId: id, metadata: { communityId: id, invitationId: made.rows[0].id } });
        await audit(client, me.id, 'community.invite', 'community', id, { invitee: userId });
        return made.rows[0].id;
      });
      return reply.status(201).send({ id: invitationId });
    });

    app.get('/community-invitations', async (req) => {
      const me = await ctx.auth(req);
      const q = pageSchema.parse(req.query);
      const cur = decodeCursor(q.cursor);
      const { rows } = await pool.query(
        `select i.id, i.expires_at, i.created_at, ${tsText('i.created_at')} as "cursorT", c.id as community_id, c.name, c.visibility, c.member_count
           from community_invitations i join communities c on c.id = i.community_id
          where i.invitee_id = $1 and i.status = 'pending' and i.expires_at > now() and c.status = 'active'
            and not ${blockedBetween('$1', 'c.owner_id')}
            and ($2::timestamptz is null or (i.created_at, i.id) < ($2::timestamptz, $3::uuid))
          order by i.created_at desc, i.id desc limit $4`,
        [me.id, cur?.t ?? null, cur?.id ?? null, q.limit + 1]);
      const page = toPage(rows as (Record<string, unknown> & { cursorT: string; id: string })[], q.limit);
      return {
        items: page.items.map((r) => ({ id: r.id, expiresAt: r.expires_at, createdAt: r.created_at, community: { id: r.community_id, name: r.name, visibility: r.visibility, memberCount: r.member_count } })),
        nextCursor: page.nextCursor,
      };
    });

    async function answerInvitation(req: FastifyRequest, accept: boolean) {
      const me = await ctx.auth(req);
      const invitationId = idParam((req.params as { invitationId: string }).invitationId);
      const out = await withTx(pool, async (client) => {
        const inv = await client.query<{ community_id: string; inviter_id: string | null; status: string; expired: boolean }>(
          'select community_id, inviter_id, status, expires_at <= now() as expired from community_invitations where id = $1 and invitee_id = $2 for update', [invitationId, me.id]);
        const i = inv.rows[0];
        if (!i) throw new HttpError(404, 'not_found', 'That invitation was not found.');
        if (i.status !== 'pending') throw new HttpError(409, 'invitation_closed', `This invitation was already ${i.status}.`);
        if (i.expired) {
          await client.query("update community_invitations set status = 'expired', decided_at = now() where id = $1", [invitationId]);
          return { error: new HttpError(410, 'invitation_expired', 'This invitation has expired.') };
        }
        await client.query('select 1 from communities where id = $1 for update', [i.community_id]);
        const status = (await client.query<{ status: string }>('select status from communities where id = $1', [i.community_id])).rows[0]?.status;
        if (accept && status !== 'active') throw new HttpError(409, 'community_unavailable', 'This community is not accepting members.');
        await client.query('update community_invitations set status = $2, decided_at = now() where id = $1', [invitationId, accept ? 'accepted' : 'declined']);
        let count = 0;
        if (accept) {
          await addMember(client, i.community_id, me.id);
          await client.query("update community_requests set status = 'approved', decided_at = now() where community_id = $1 and user_id = $2 and status = 'pending'", [i.community_id, me.id]);
          await audit(client, me.id, 'community.invite.accept', 'community', i.community_id);
        }
        count = await recount(client, i.community_id);
        await retract(client, me.id, i.inviter_id ?? me.id, 'community_invitation', i.community_id);
        return { communityId: i.community_id, count };
      });
      if ('error' in out && out.error) throw out.error; // after commit, so the expiry is saved
      const o = out as { communityId: string; count: number };
      announce(me.id, o.communityId, accept ? 'joined' : 'unavailable', o.count);
      return { id: invitationId, status: accept ? 'accepted' : 'declined', communityId: o.communityId, memberCount: o.count };
    }
    app.post('/community-invitations/:invitationId/accept', (req) => answerInvitation(req, true));
    app.post('/community-invitations/:invitationId/decline', (req) => answerInvitation(req, false));

    app.delete('/communities/:id/invitations/:invitationId', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = routeId(req);
      const invitationId = idParam((req.params as { invitationId: string }).invitationId);
      await requireRole(me, id, ['owner', 'moderator']);
      const res = await pool.query("update community_invitations set status = 'revoked', decided_at = now() where id = $1 and community_id = $2 and status = 'pending'", [invitationId, id]);
      if (!res.rowCount) throw new HttpError(404, 'not_found', 'That invitation is no longer pending.');
      return reply.status(204).send();
    });

    // ---------- members and roles
    app.get('/communities/:id/members', async (req) => {
      const me = await ctx.auth(req);
      const id = routeId(req);
      const c = await loadFor(me, id);
      // Member lists of private communities are for members only.
      if (c.visibility === 'private' && !c.my_role) throw new HttpError(403, 'members_only', 'Only members can see who is in this community.');
      const q = pageSchema.parse(req.query);
      const cur = decodeCursor(q.cursor);
      const { rows } = await pool.query(
        `select m.role, m.status, m.joined_at, ${tsText('m.joined_at')} as "cursorT", u.id, ${CARD_COLUMNS}
           from community_members m join users u on u.id = m.user_id ${CARD_JOIN}
          where m.community_id = $1 and (m.status = 'active' or $6::boolean) and ${userVisible('u', '$2', '$3')}
            and ($4::timestamptz is null or (m.joined_at, u.id) < ($4::timestamptz, $5::uuid))
          order by m.joined_at desc, u.id desc limit $7`,
        [id, me.id, me.universityId, cur?.t ?? null, cur?.id ?? null, isStaff(c.my_role), q.limit + 1]);
      const page = toPage(rows as (CardRow & { cursorT: string; id: string; role: string; status: string; joined_at: Date })[], q.limit);
      return {
        items: await Promise.all(page.items.map(async (r) => ({ user: await toCard(ctx, r as unknown as CardRow), role: r.role, status: r.status, joinedAt: r.joined_at }))),
        nextCursor: page.nextCursor,
      };
    });

    app.patch('/communities/:id/members/:userId/role', async (req) => {
      const me = await ctx.auth(req);
      const id = routeId(req);
      const userId = idParam((req.params as { userId: string }).userId);
      const { role } = z.object({ role: z.enum(['moderator', 'member']) }).strict().parse(req.body ?? {});
      await withTx(pool, async (client) => {
        await requireRole(me, id, ['owner'], client);
        if (userId === me.id) throw new HttpError(403, 'forbidden', 'You cannot change your own role.');
        const t = await client.query<{ role: string }>("select role from community_members where community_id = $1 and user_id = $2 and status = 'active' for update", [id, userId]);
        if (!t.rows[0]) throw new HttpError(404, 'not_found', 'That person is not an active member.');
        if (t.rows[0].role === 'owner') throw new HttpError(403, 'forbidden', 'The owner role changes only through a transfer.');
        if (t.rows[0].role !== role) {
          await client.query('update community_members set role = $3 where community_id = $1 and user_id = $2', [id, userId, role]);
          await notify(client, { recipientId: userId, actorId: me.id, type: 'community_role_changed', entityType: 'community', entityId: id, metadata: { communityId: id, role } });
          await audit(client, me.id, 'community.role', 'community', id, { target: userId, role });
        }
      });
      return { userId, role };
    });

    async function moderateMember(me: AuthUser, id: string, userId: string, action: 'remove' | 'suspend' | 'restore') {
      await withTx(pool, async (client) => {
        await client.query('select 1 from communities where id = $1 for update', [id]);
        const c = await requireRole(me, id, ['owner', 'moderator'], client);
        if (userId === me.id) throw new HttpError(403, 'forbidden', 'Use leave or transfer for yourself.');
        const t = await client.query<{ role: string; status: string }>('select role, status from community_members where community_id = $1 and user_id = $2 for update', [id, userId]);
        if (!t.rows[0]) throw new HttpError(404, 'not_found', 'That person is not a member.');
        // The owner can act on anyone; moderators only on plain members. Nobody acts on the owner.
        if (t.rows[0].role === 'owner' || (t.rows[0].role === 'moderator' && c.my_role !== 'owner')) throw new HttpError(403, 'forbidden', 'You cannot act on this member.');
        if (action === 'remove') await client.query('delete from community_members where community_id = $1 and user_id = $2', [id, userId]);
        else await client.query('update community_members set status = $3 where community_id = $1 and user_id = $2', [id, userId, action === 'suspend' ? 'suspended' : 'active']);
        if (action === 'suspend' || action === 'remove') await client.query("update community_requests set status = 'cancelled', decided_at = now() where community_id = $1 and user_id = $2 and status = 'pending'", [id, userId]);
        const count = await recount(client, id);
        // Administrative notices are always delivered and do not name the moderator.
        await notify(client, { recipientId: userId, actorId: null, type: 'community_member_removed', entityType: 'community', entityId: id, metadata: { communityId: id, action } });
        await audit(client, me.id, `community.member.${action}`, 'community', id, { target: userId });
        announce(userId, id, action === 'restore' ? 'joined' : 'unavailable', count);
      });
    }
    app.delete('/communities/:id/members/:userId', async (req, reply) => {
      await moderateMember(await ctx.auth(req), routeId(req), idParam((req.params as { userId: string }).userId), 'remove');
      return reply.status(204).send();
    });
    app.patch('/communities/:id/members/:userId/status', async (req) => {
      const userId = idParam((req.params as { userId: string }).userId);
      const { status } = z.object({ status: z.enum(['active', 'suspended']) }).strict().parse(req.body ?? {});
      await moderateMember(await ctx.auth(req), routeId(req), userId, status === 'suspended' ? 'suspend' : 'restore');
      return { userId, status };
    });

    // ---------- ownership transfer
    app.post('/communities/:id/transfer-ownership', async (req) => {
      const me = await ctx.auth(req);
      const id = routeId(req);
      const b = z.object({ userId: z.string().uuid(), confirmName: z.string() }).strict().parse(req.body ?? {});
      await withTx(pool, async (client) => {
        await client.query('select 1 from communities where id = $1 for update', [id]);
        const c = await requireRole(me, id, ['owner'], client);
        if (nameKey(b.confirmName) !== nameKey(c.name)) throw new HttpError(422, 'confirmation_mismatch', 'Type the community name exactly to transfer it.');
        if (b.userId === me.id) throw new HttpError(422, 'validation', 'Choose someone else.');
        await reassignOwner(client, id, b.userId, me.id, 'community.transfer');
      });
      return (await present(ctx, [await loadFor(me, id)], true))[0];
    });

    /** Moves ownership to an active member. The previous owner, if still a member, becomes a moderator. */
    async function reassignOwner(client: Q, communityId: string, newOwner: string, actor: string, auditAction: string) {
      const t = await client.query<{ role: string }>("select role from community_members where community_id = $1 and user_id = $2 and status = 'active' for update", [communityId, newOwner]);
      if (!t.rows[0]) throw new HttpError(422, 'validation', 'The new owner must be an active member.');
      const uni = await client.query('select 1 from communities c join users u on u.id = $2 and u.university_id = c.university_id where c.id = $1', [communityId, newOwner]);
      if (!uni.rowCount) throw new HttpError(422, 'validation', 'The new owner must be at the same university.');
      // Demote first so the one-owner unique index is never violated.
      await client.query("update community_members set role = 'moderator' where community_id = $1 and role = 'owner'", [communityId]);
      await client.query("update community_members set role = 'owner' where community_id = $1 and user_id = $2", [communityId, newOwner]);
      await client.query('update communities set owner_id = $2, updated_at = now() where id = $1', [communityId, newOwner]);
      await notify(client, { recipientId: newOwner, actorId: null, type: 'community_role_changed', entityType: 'community', entityId: communityId, metadata: { communityId, role: 'owner' } });
      await audit(client, actor, auditAction, 'community', communityId, { newOwner });
    }

    /** Recovery: a platform administrator can reassign an unreachable owner's community. Always audited. */
    app.post('/moderation/communities/:id/reassign-owner', async (req) => {
      const me = await ctx.auth(req);
      if (me.role !== 'admin') throw new HttpError(403, 'forbidden', 'This is only available to administrators.');
      const id = routeId(req);
      const { userId } = z.object({ userId: z.string().uuid() }).strict().parse(req.body ?? {});
      await withTx(pool, async (client) => {
        const c = await client.query('select 1 from communities where id = $1 and university_id = $2 and status <> \'deleted\' for update', [id, me.universityId]);
        if (!c.rowCount) throw new HttpError(404, 'not_found', 'Not found.');
        await reassignOwner(client, id, userId, me.id, 'community.recover_owner');
      });
      return { id, ownerId: userId };
    });

    /** Platform moderators can suspend a community (hides it and its posts everywhere) or lift the suspension. */
    app.post('/moderation/communities/:id/status', async (req) => {
      const me = await ctx.auth(req);
      if (me.role !== 'moderator' && me.role !== 'admin') throw new HttpError(403, 'forbidden', 'This is only available to moderators.');
      const id = routeId(req);
      const { status, note } = z.object({ status: z.enum(['suspended', 'active']), note: z.string().trim().max(500).default('') }).strict().parse(req.body ?? {});
      await withTx(pool, async (client) => {
        const res = await client.query("update communities set status = $3, updated_at = now() where id = $1 and university_id = $2 and status <> 'deleted'", [id, me.universityId, status]);
        if (!res.rowCount) throw new HttpError(404, 'not_found', 'Not found.');
        await audit(client, me.id, `community.${status === 'suspended' ? 'suspend' : 'unsuspend'}`, 'community', id, { note });
      });
      return { id, status };
    });

    // ---------- mute (hides this community's posts from the viewer's Discover and Home; the server keeps everything)
    app.put('/communities/:id/mute', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = routeId(req);
      await loadFor(me, id);
      await pool.query('insert into community_mutes (user_id, community_id) values ($1, $2) on conflict do nothing', [me.id, id]);
      return reply.status(204).send();
    });
    app.delete('/communities/:id/mute', async (req, reply) => {
      const me = await ctx.auth(req);
      await pool.query('delete from community_mutes where user_id = $1 and community_id = $2', [me.id, routeId(req)]);
      return reply.status(204).send();
    });

    // ---------- suggestions for Discover: interests and recent real activity, not raw member count; max 2 per category
    app.get('/communities/suggested', async (req) => {
      const me = await ctx.auth(req);
      ctx.limit(`community-suggest:${me.id}`, 120, 600);
      const withSignals = COMMUNITY_SELECT.replace('select c.*,', `select c.*,
        (select count(*) from users me, unnest(me.interests) i where me.id = $1 and length(i) >= 2
           and (position(lower(i) in lower(c.name)) > 0 or position(lower(i) in lower(c.description)) > 0 or lower(i) = c.category))::int as interest_n,
        (select count(*) from posts ap where ap.community_id = c.id and ap.status = 'active' and ap.created_at > now() - interval '14 days')::int as posts_n,
        (select count(distinct ap.author_id) from posts ap where ap.community_id = c.id and ap.status = 'active' and ap.created_at > now() - interval '14 days')::int as posters_n,`);
      const { rows } = await pool.query<CommunityRow & { interest_n: number; posts_n: number; posters_n: number }>(
        `${withSignals}
          where ${profileVisible()} and c.status = 'active' and c.membership_policy <> 'invite_only'
            and not exists (select 1 from community_members sm where sm.community_id = c.id and sm.user_id = $1)
            and not exists (select 1 from community_requests sr where sr.community_id = c.id and sr.user_id = $1 and sr.status = 'pending')
            and not exists (select 1 from community_mutes sx where sx.community_id = c.id and sx.user_id = $1)
          order by (2 * least((select count(*) from users me, unnest(me.interests) i where me.id = $1 and length(i) >= 2
                      and (position(lower(i) in lower(c.name)) > 0 or position(lower(i) in lower(c.description)) > 0 or lower(i) = c.category)), 3)
                    + ln(1 + (select count(*) from posts ap where ap.community_id = c.id and ap.status = 'active' and ap.created_at > now() - interval '14 days'))) desc,
                   c.created_at desc, c.id desc
          limit 60`,
        [me.id, me.universityId]);
      const perCategory = new Map<string, number>();
      const picked = rows.filter((r) => {
        const n = (perCategory.get(r.category) ?? 0) + 1;
        perCategory.set(r.category, n);
        return n <= 2;
      }).slice(0, 20);
      const reason = new Map(picked.map((r) => [r.id, r.interest_n > 0 ? 'interests' : r.posts_n >= 3 ? 'active' : null]));
      const items = await present(ctx, picked);
      // A reason is given only when the data supports it. Never "trending" or "popular".
      return { items: items.map((i) => ({ ...i, reason: reason.get(i.id) ?? null })), nextCursor: null };
    });

    // ---------- community feed and community moderation of content
    app.get('/communities/:id/posts', async (req) => {
      const me = await ctx.auth(req);
      const id = routeId(req);
      const c = await loadFor(me, id);
      if (c.visibility === 'private' && !c.my_role) throw new HttpError(403, 'members_only', 'Join this community to see its posts.');
      if (c.status === 'suspended') throw new HttpError(403, 'community_unavailable', 'This community is unavailable.');
      const q = pageSchema.parse(req.query);
      const cur = decodeCursor(q.cursor);
      // `visible` still applies: blocks, suspended authors and removed posts never show, even to members.
      const { rows } = await pool.query<PostRow & { cursorT: string }>(
        `select ${POST_COLS}, ${tsText('p.created_at')} as "cursorT" ${POST_FROM}
          where p.community_id = $3 and ${visible('p', 'a')}
            and ($4::timestamptz is null or (p.created_at, p.id) < ($4::timestamptz, $5::uuid))
          order by p.created_at desc, p.id desc limit $6`,
        [me.id, me.universityId, id, cur?.t ?? null, cur?.id ?? null, q.limit + 1]);
      const page = toPage(rows as unknown as (PostRow & { cursorT: string; id: string })[], q.limit);
      return { items: await presentPosts(pool, ctx.storage, page.items as unknown as PostRow[]), nextCursor: page.nextCursor };
    });

    app.delete('/communities/:id/posts/:postId', async (req, reply) => {
      const me = await ctx.auth(req);
      const id = routeId(req);
      const postId = idParam((req.params as { postId: string }).postId);
      await withTx(pool, async (client) => {
        await requireRole(me, id, ['owner', 'moderator'], client);
        const res = await client.query("update posts set status = 'removed' where id = $1 and community_id = $2 and status = 'active'", [postId, id]);
        if (!res.rowCount) throw new HttpError(404, 'not_found', 'That post was not found.');
        await audit(client, me.id, 'community.post.remove', 'post', postId, { communityId: id });
      });
      return reply.status(204).send();
    });
  };
}

/**
 * Called before an account is deleted: every community the person owns goes to its longest-serving moderator, then
 * its longest-serving member; with nobody left it is closed. Without this, deleting the owner would orphan the community.
 */
export async function releaseOwnedCommunities(client: Q, userId: string): Promise<void> {
  const owned = await client.query<{ id: string }>("select id from communities where owner_id = $1 and status <> 'deleted' for update", [userId]);
  for (const { id } of owned.rows) {
    const next = await client.query<{ user_id: string }>(
      `select m.user_id from community_members m join users u on u.id = m.user_id
        where m.community_id = $1 and m.user_id <> $2 and m.status = 'active'
        order by (m.role = 'moderator') desc, m.joined_at limit 1`, [id, userId]);
    if (next.rows[0]) {
      await client.query("update community_members set role = 'moderator' where community_id = $1 and role = 'owner'", [id]);
      await client.query("update community_members set role = 'owner' where community_id = $1 and user_id = $2", [id, next.rows[0].user_id]);
      await client.query('update communities set owner_id = $2, updated_at = now() where id = $1', [id, next.rows[0].user_id]);
      await audit(client, null, 'community.owner_account_deleted', 'community', id, { newOwner: next.rows[0].user_id });
    } else {
      await client.query("update communities set status = 'deleted', updated_at = now() where id = $1", [id]);
      await audit(client, null, 'community.closed_no_members', 'community', id);
    }
  }
}
