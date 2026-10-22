import type { FastifyInstance, FastifyRequest } from 'fastify';
import type { PoolClient } from 'pg';
import { z } from 'zod';
import { blockedBetween } from '../access';
import type { AuthUser, Ctx } from '../context';
import { withTx } from '../db';
import { HttpError } from '../errors';
import { decodeCursor, idParam, pageSchema, toPage, tsText } from '../http';
import { enqueue } from '../jobs';
import type { Hub } from '../realtime';
import { CARD_COLUMNS, CARD_JOIN, type CardRow, signedUrl, toCard } from './cards';
import { likePattern } from './chat_extras';
import {
  CHANNEL_NAME_RE, CHANNEL_TYPES, type ChannelPermissions, type ChannelPolicy, type ChannelType, type CommunityRole, type ReadOnlyReason,
  canAcceptAnswer, channelPermissions, channelSlug, isStaffRole, mayDeleteChannel, messageShapeError, normalizeSettings,
} from './channel_access';
import { audit } from './moderation';

type Q = Pick<PoolClient, 'query'>;

const MAX_CHANNELS = 30;
const MAX_GROUPS = 8;
const MAX_PINS = 20;
const REACTIONS = ['heart', 'like', 'laugh', 'fire', 'sad'] as const;

const READ_ONLY_TEXT: Record<ReadOnlyReason, string> = {
  not_a_member: 'Join this community to take part in its channels.',
  suspended: 'Your membership in this community is restricted.',
  community_inactive: 'This community is read-only right now.',
  channel_archived: 'This channel is archived.',
  channel_locked: 'This channel is locked.',
  staff_only: 'Only moderators can post in this channel.',
};

// ---------- rows

interface ChannelRow {
  id: string; community_id: string; group_id: string | null; name: string; description: string; type: ChannelType;
  view_role: 'members' | 'staff'; post_role: 'members' | 'staff'; locked: boolean; replies_enabled: boolean; allow_attachments: boolean;
  position: number; message_seq: string; last_message_at: Date | null; archived_at: Date | null; created_at: Date;
  community_status: string; my_role: CommunityRole | null; my_member_status: 'active' | 'suspended' | null;
}

const policyOf = (c: ChannelRow): ChannelPolicy => ({
  type: c.type, viewRole: c.view_role, postRole: c.post_role, locked: c.locked, archived: c.archived_at !== null,
  repliesEnabled: c.replies_enabled, allowAttachments: c.allow_attachments,
});
const permsOf = (c: ChannelRow): ChannelPermissions => channelPermissions({ role: c.my_role, memberStatus: c.my_member_status }, c.community_status, policyOf(c));

/** One query shape for every channel read. $1 = the viewer. Authorization is always re-read from the database. */
const CHANNEL_SELECT = `
  select ch.*, c.status as community_status, cm.role as my_role, cm.status as my_member_status
    from community_channels ch join communities c on c.id = ch.community_id
    left join community_members cm on cm.community_id = c.id and cm.user_id = $1`;

interface MsgRow extends CardRow {
  id: string; channel_id: string; seq: string; author_id: string | null; title: string | null; content: string;
  status: 'active' | 'deleted' | 'removed'; created_at: Date; edited_at: Date | null; pinned_at: Date | null;
  accepted_answer_id: string | null; reply_to_id: string | null;
  reply_status: string | null; reply_author_id: string | null; reply_content: string | null; reply_title: string | null; reply_hidden: boolean | null;
  reply_count: number; is_accepted: boolean; author_role: string | null; cursorT: string;
}

/** Every message read uses this shape. `viewer` is the placeholder holding the viewer id (used to hide blocked people). */
const msgSelect = (viewer: string) => `
  select m.id, m.channel_id, m.seq, m.author_id, m.title, m.content, m.status, m.created_at, m.edited_at, m.pinned_at,
         m.accepted_answer_id, m.reply_to_id, ${tsText('m.created_at')} as "cursorT",
         r.status as reply_status, r.author_id as reply_author_id, left(r.content, 120) as reply_content, r.title as reply_title,
         (r.id is not null and ${blockedBetween(viewer, 'r.author_id')}) as reply_hidden,
         (select count(*)::int from community_channel_messages x where x.reply_to_id = m.id and x.status = 'active') as reply_count,
         exists (select 1 from community_channel_messages q where q.id = m.reply_to_id and q.accepted_answer_id = m.id) as is_accepted,
         (select cm.role from community_members cm join community_channels hc on hc.community_id = cm.community_id
           where hc.id = m.channel_id and cm.user_id = m.author_id and cm.status = 'active') as author_role,
         ${CARD_COLUMNS}
    from community_channel_messages m
    left join community_channel_messages r on r.id = m.reply_to_id
    left join users u on u.id = m.author_id ${CARD_JOIN}`;

export interface ChannelMessageView {
  id: string; channelId: string; seq: number;
  author: Awaited<ReturnType<typeof toCard>> | null; authorRole: string | null;
  title: string | null; content: string | null; deleted: boolean; removedByModerators: boolean;
  createdAt: Date; editedAt: Date | null; pinned: boolean; pinnedAt: Date | null;
  replyCount: number;
  replyTo: { id: string; authorId: string | null; preview: string | null; deleted: boolean } | null;
  acceptedAnswerId: string | null; isAcceptedAnswer: boolean;
  media: { id: string; kind: string; contentType: string; url: string | null; width: number | null; height: number | null; fileName: string | null; sizeBytes: number | null }[];
  /** Counts per reaction, e.g. { heart: 2 }. `myReactions` is present only on responses made for one viewer. */
  reactions: Record<string, number>;
  myReactions?: string[];
}

interface MediaRow { id: string; channel_message_id: string; kind: string; content_type: string; object_key: string; width: number | null; height: number | null; file_name: string | null; size_bytes: string | null }

/** Turns rows into the JSON the app renders. Pass viewerId = null for a viewer-neutral copy (what is broadcast over the socket). */
async function presentMessages(ctx: Ctx, rows: MsgRow[], viewerId: string | null): Promise<ChannelMessageView[]> {
  const ids = rows.map((r) => r.id);
  const reacts = ids.length
    ? (await ctx.pool.query<{ message_id: string; reaction: string; n: number; mine: boolean }>(
        `select message_id, reaction, count(*)::int as n, coalesce(bool_or(user_id = $2::uuid), false) as mine
           from community_channel_reactions where message_id = any($1::uuid[]) group by message_id, reaction`, [ids, viewerId])).rows
    : [];
  const media = ids.length
    ? (await ctx.pool.query<MediaRow>(
        `select id, channel_message_id, kind, content_type, object_key, width, height, file_name, size_bytes
           from media where channel_message_id = any($1::uuid[]) and status = 'ready' order by position`, [ids])).rows
    : [];
  const mediaViews = await Promise.all(media.map(async (m) => ({
    messageId: m.channel_message_id,
    view: {
      id: m.id, kind: m.kind, contentType: m.content_type, url: await signedUrl(ctx, m.object_key), width: m.width, height: m.height,
      fileName: m.file_name, sizeBytes: m.kind === 'document' && m.size_bytes !== null ? Number(m.size_bytes) : null,
    },
  })));
  return Promise.all(rows.map(async (r) => {
    const gone = r.status !== 'active';
    const mine = reacts.filter((x) => x.message_id === r.id && x.mine).map((x) => x.reaction);
    const replyVisible = r.reply_status === 'active' && !r.reply_hidden;
    return {
      id: r.id, channelId: r.channel_id, seq: Number(r.seq),
      author: r.author_id ? await toCard(ctx, r) : null, authorRole: r.author_role,
      title: gone ? null : r.title, content: gone ? null : r.content, deleted: gone, removedByModerators: r.status === 'removed',
      createdAt: r.created_at, editedAt: r.edited_at, pinned: !gone && r.pinned_at !== null, pinnedAt: gone ? null : r.pinned_at,
      replyCount: r.reply_count,
      replyTo: r.reply_to_id
        ? { id: r.reply_to_id, authorId: r.reply_hidden ? null : r.reply_author_id, preview: replyVisible ? String(r.reply_title ?? r.reply_content ?? '').slice(0, 80) : null, deleted: r.reply_status !== null && r.reply_status !== 'active' }
        : null,
      acceptedAnswerId: gone ? null : r.accepted_answer_id, isAcceptedAnswer: !gone && r.is_accepted,
      media: gone ? [] : mediaViews.filter((m) => m.messageId === r.id).map((m) => m.view),
      reactions: gone ? {} : reacts.filter((x) => x.message_id === r.id).reduce<Record<string, number>>((acc, x) => { acc[x.reaction] = x.n; return acc; }, {}),
      ...(viewerId ? { myReactions: gone ? [] : mine } : {}),
    };
  }));
}

// ---------- input

const sendSchema = z.object({
  title: z.string().trim().max(120).optional(),
  content: z.string().trim().max(4000).default(''),
  clientId: z.string().min(1).max(64).optional(),
  replyToId: z.string().uuid().optional(),
  mediaIds: z.array(z.string().uuid()).max(4).default([]),
}).strict();
const editSchema = z.object({ title: z.string().trim().max(120).optional(), content: z.string().trim().max(4000).optional() }).strict();

const role2 = z.enum(['members', 'staff']);
const createChannelSchema = z.object({
  name: z.string().trim().min(1).max(40),
  description: z.string().trim().max(200).default(''),
  type: z.enum(CHANNEL_TYPES).default('text'),
  groupId: z.string().uuid().nullable().optional(),
  viewRole: role2.optional(), postRole: role2.optional(),
  locked: z.boolean().optional(), repliesEnabled: z.boolean().optional(), allowAttachments: z.boolean().optional(),
}).strict();
const patchChannelSchema = z.object({
  name: z.string().trim().min(1).max(40), description: z.string().trim().max(200),
  groupId: z.string().uuid().nullable(), position: z.number().int().min(0).max(1000),
  viewRole: role2, postRole: role2,
  locked: z.boolean(), repliesEnabled: z.boolean(), allowAttachments: z.boolean(), archived: z.boolean(),
}).partial().strict();
const groupSchema = z.object({ name: z.string().trim().min(1).max(30), position: z.number().int().min(0).max(100).optional() }).strict();
const orderSchema = z.object({
  groups: z.array(z.string().uuid()).max(MAX_GROUPS).default([]),
  channels: z.array(z.object({ id: z.string().uuid(), groupId: z.string().uuid().nullable() }).strict()).max(MAX_CHANNELS),
}).strict();
const listSchema = pageSchema.extend({ afterSeq: z.coerce.number().int().min(0).optional() });
const searchSchema = pageSchema.extend({
  q: z.string().trim().min(2).max(80).optional(),
  authorId: z.string().uuid().optional(),
  from: z.coerce.date().optional(), to: z.coerce.date().optional(),
  hasAttachments: z.literal('true').optional(), pinned: z.literal('true').optional(),
});
const isUnique = (e: unknown) => (e as { code?: string } | null)?.code === '23505';

/** The two starter channels every community gets. Idempotent. */
export async function createDefaultChannels(q: Q, communityId: string, ownerId: string): Promise<void> {
  await q.query(
    `insert into community_channels (community_id, name, description, type, post_role, replies_enabled, position, created_by)
     values ($1, 'announcements', 'Official updates from the moderators.', 'announcement', 'staff', false, 0, $2),
            ($1, 'general', 'Talk about anything related to the community.', 'text', 'members', true, 1, $2)
     on conflict (community_id, name) do nothing`, [communityId, ownerId]);
}

export function communityChannelRoutes(ctx: Ctx, hub: Hub) {
  return async (app: FastifyInstance) => {
    const { pool } = ctx;

    // ---------- helpers
    const communityParam = (req: FastifyRequest) => idParam((req.params as { id: string }).id);
    const channelParam = (req: FastifyRequest) => idParam((req.params as { channelId: string }).channelId);
    const messageParam = (req: FastifyRequest) => idParam((req.params as { messageId: string }).messageId);

    function deny(p: ChannelPermissions): never {
      if (p.readOnlyReason) throw new HttpError(403, p.readOnlyReason, READ_ONLY_TEXT[p.readOnlyReason]);
      throw new HttpError(403, 'forbidden', 'You do not have permission to do that.');
    }

    /** The community-level gate: visible community, active member. Outsiders of private communities get "not found". */
    async function gate(me: AuthUser, communityId: string, q: Q = pool) {
      const { rows } = await q.query<{ status: string; visibility: string; role: CommunityRole | null; member_status: 'active' | 'suspended' | null }>(
        `select c.status, c.visibility, cm.role, cm.status as member_status
           from communities c left join community_members cm on cm.community_id = c.id and cm.user_id = $1
          where c.id = $2 and c.university_id = $3 and c.status <> 'deleted'`, [me.id, communityId, me.universityId]);
      const c = rows[0];
      if (!c || c.status === 'suspended' || (c.visibility === 'private' && !c.member_status)) throw new HttpError(404, 'not_found', 'That community is not available.');
      if (c.member_status === 'suspended') throw new HttpError(403, 'suspended', READ_ONLY_TEXT.suspended);
      if (c.member_status !== 'active') throw new HttpError(403, 'not_a_member', READ_ONLY_TEXT.not_a_member);
      return c;
    }
    async function requireStaff(me: AuthUser, communityId: string, q: Q = pool) {
      const c = await gate(me, communityId, q);
      if (!isStaffRole(c.role)) throw new HttpError(403, 'forbidden', 'Only moderators can do that.');
      if (c.status !== 'active') throw new HttpError(403, 'community_inactive', READ_ONLY_TEXT.community_inactive);
      return c;
    }

    /** A channel the viewer may see, with what they may do in it. Everything else looks like "not found". */
    async function loadChannel(me: AuthUser, communityId: string, channelId: string, q: Q = pool, lock = false) {
      const { rows } = await q.query<ChannelRow>(
        `${CHANNEL_SELECT} where ch.id = $2 and ch.community_id = $3 and c.university_id = $4 and c.status <> 'deleted' ${lock ? 'for update of ch' : ''}`,
        [me.id, channelId, communityId, me.universityId]);
      const ch = rows[0];
      const perms = ch ? permsOf(ch) : null;
      if (!ch || !perms || !perms.view) throw new HttpError(404, 'not_found', 'That channel is not available.');
      return { ch, perms };
    }

    /** Online people who may see this channel's content right now (computed from the database on every event). */
    async function audienceOf(ch: { community_id: string; view_role: string }, actorId: string | null): Promise<string[]> {
      const online = hub.onlineUserIds();
      if (!online.length) return [];
      const { rows } = await pool.query<{ user_id: string }>(
        `select m.user_id from community_members m
          where m.community_id = $1 and m.status = 'active' and m.user_id = any($4::uuid[])
            and (m.role in ('owner', 'moderator') or $2::text = 'members')
            and ($3::uuid is null or not ${blockedBetween('m.user_id', '$3::uuid')})`,
        [ch.community_id, ch.view_role, actorId, online]);
      return rows.map((r) => r.user_id);
    }

    /** Layout changes carry only ids, so every active member may hear them; each app then re-reads what it may see. */
    async function announceChange(communityId: string, channelId: string | null, kind: 'created' | 'updated' | 'deleted' | 'reordered' | 'archived') {
      const online = hub.onlineUserIds();
      if (!online.length) return;
      const { rows } = await pool.query<{ user_id: string }>(
        "select user_id from community_members where community_id = $1 and status = 'active' and user_id = any($2::uuid[])", [communityId, online]);
      hub.sendMany(rows.map((r) => r.user_id), { type: 'channel:changed', communityId, channelId, kind });
    }

    async function loadMessages(me: AuthUser | null, ids: string[], channelId: string): Promise<ChannelMessageView[]> {
      if (!ids.length) return [];
      const { rows } = await pool.query<MsgRow>(`${msgSelect('$1')} where m.channel_id = $2 and m.id = any($3::uuid[])`, [me?.id ?? null, channelId, ids]);
      const order = new Map(ids.map((id, i) => [id, i] as const));
      rows.sort((a, b) => (order.get(a.id) ?? 0) - (order.get(b.id) ?? 0));
      return presentMessages(ctx, rows, me?.id ?? null);
    }

    async function emitUpdated(ch: ChannelRow, ids: string[], actorId: string | null) {
      const views = await loadMessages(null, ids, ch.id);
      if (!views.length) return;
      const to = await audienceOf(ch, actorId);
      for (const message of views) hub.sendMany(to, { type: 'channel:message:updated', communityId: ch.community_id, channelId: ch.id, message });
    }

    async function loadOwn(q: Q, channelId: string, messageId: string, lock = false) {
      const { rows } = await q.query<{ id: string; author_id: string | null; status: string; reply_to_id: string | null; pinned_at: Date | null; accepted_answer_id: string | null; title: string | null; content: string; created_at: Date }>(
        `select id, author_id, status, reply_to_id, pinned_at, accepted_answer_id, title, content, created_at
           from community_channel_messages where id = $1 and channel_id = $2 ${lock ? 'for update' : ''}`, [messageId, channelId]);
      if (!rows[0]) throw new HttpError(404, 'not_found', 'That message is not available.');
      return rows[0];
    }

    // ================= channels and groups

    app.get('/communities/:id/channels', async (req) => {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const community = await gate(me, communityId);
      const { rows } = await pool.query<ChannelRow & { last_read_seq: string | null; unread: number; pinned_count: number }>(
        `${CHANNEL_SELECT.replace('select ch.*,', `select ch.*, rd.last_read_seq,
            (select count(*)::int from (select 1 from community_channel_messages x where x.channel_id = ch.id and x.status = 'active'
               and x.seq > coalesce(rd.last_read_seq, 0) and x.author_id is distinct from $1 limit 100) t) as unread,
            (select count(*)::int from community_channel_messages p where p.channel_id = ch.id and p.status = 'active' and p.pinned_at is not null) as pinned_count,`)
          .replace('left join community_members cm', 'left join community_channel_reads rd on rd.channel_id = ch.id and rd.user_id = $1 left join community_members cm')}
          where ch.community_id = $2 order by ch.position, ch.created_at, ch.id`, [me.id, communityId]);
      const groups = (await pool.query<{ id: string; name: string; position: number }>(
        'select id, name, position from community_channel_groups where community_id = $1 order by position, created_at, id', [communityId])).rows;
      const channels = rows.flatMap((r) => {
        const p = permsOf(r);
        if (!p.view) return [];
        return [{
          id: r.id, groupId: r.group_id, name: r.name, description: r.description, type: r.type, position: r.position,
          viewRole: r.view_role, postRole: r.post_role, locked: r.locked, archived: r.archived_at !== null,
          repliesEnabled: r.replies_enabled, allowAttachments: r.allow_attachments,
          unreadCount: r.unread, pinnedCount: r.pinned_count, lastMessageAt: r.last_message_at,
          permissions: { send: p.send, reply: p.reply, react: p.react, attach: p.attach, pin: p.pin, manage: p.manage, readOnlyReason: p.readOnlyReason },
        }];
      });
      return { groups, channels, canManage: isStaffRole(community.role) && community.status === 'active', canDelete: mayDeleteChannel(community.role) };
    });

    app.post('/communities/:id/channels', async (req, reply) => {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const b = createChannelSchema.parse(req.body ?? {});
      ctx.limit(`channel-manage:${me.id}`, 30, 3600);
      const name = channelSlug(b.name);
      if (!CHANNEL_NAME_RE.test(name)) throw new HttpError(400, 'validation', 'Use letters and numbers for the channel name.');
      const s = normalizeSettings({ type: b.type, viewRole: b.viewRole, postRole: b.postRole, locked: b.locked, repliesEnabled: b.repliesEnabled, allowAttachments: b.allowAttachments });
      try {
        const id = await withTx(pool, async (client) => {
          await client.query('select pg_advisory_xact_lock(hashtext($1))', [`channels:${communityId}`]);
          await requireStaff(me, communityId, client);
          const n = await client.query<{ n: number; next: number }>('select count(*)::int as n, coalesce(max(position), -1) + 1 as next from community_channels where community_id = $1', [communityId]);
          if (n.rows[0].n >= MAX_CHANNELS) throw new HttpError(429, 'quota_exceeded', `A community can have up to ${MAX_CHANNELS} channels.`);
          if (b.groupId && !(await client.query('select 1 from community_channel_groups where id = $1 and community_id = $2', [b.groupId, communityId])).rowCount) {
            throw new HttpError(422, 'validation', 'That channel group does not exist.');
          }
          const made = await client.query<{ id: string }>(
            `insert into community_channels (community_id, group_id, name, description, type, view_role, post_role, locked, replies_enabled, allow_attachments, position, created_by)
             values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12) returning id`,
            [communityId, b.groupId ?? null, name, b.description, s.type, s.viewRole, s.postRole, s.locked, s.repliesEnabled, s.allowAttachments, n.rows[0].next, me.id]);
          await audit(client, me.id, 'channel.create', 'community', communityId, { channelId: made.rows[0].id, type: s.type });
          return made.rows[0].id;
        });
        await announceChange(communityId, id, 'created');
        const { ch, perms } = await loadChannel(me, communityId, id);
        return reply.status(201).send({ id: ch.id, name: ch.name, type: ch.type, position: ch.position, permissions: perms });
      } catch (e) {
        if (isUnique(e)) throw new HttpError(409, 'name_taken', 'This community already has a channel with that name.');
        throw e;
      }
    });

    app.patch('/communities/:id/channels/:channelId', async (req) => {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const channelId = channelParam(req);
      const b = patchChannelSchema.parse(req.body ?? {});
      ctx.limit(`channel-manage:${me.id}`, 60, 3600);
      try {
        const kind = await withTx(pool, async (client) => {
          await requireStaff(me, communityId, client);
          const { ch } = await loadChannel(me, communityId, channelId, client, true);
          const name = b.name === undefined ? ch.name : channelSlug(b.name);
          if (!CHANNEL_NAME_RE.test(name)) throw new HttpError(400, 'validation', 'Use letters and numbers for the channel name.');
          // The type cannot change: existing messages were written under its rules.
          const s = normalizeSettings({
            type: ch.type, viewRole: b.viewRole ?? ch.view_role, postRole: b.postRole ?? ch.post_role, locked: b.locked ?? ch.locked,
            repliesEnabled: b.repliesEnabled ?? ch.replies_enabled, allowAttachments: b.allowAttachments ?? ch.allow_attachments,
          });
          const groupId = b.groupId === undefined ? ch.group_id : b.groupId;
          if (groupId && !(await client.query('select 1 from community_channel_groups where id = $1 and community_id = $2', [groupId, communityId])).rowCount) {
            throw new HttpError(422, 'validation', 'That channel group does not exist.');
          }
          const archivedAt = b.archived === undefined ? ch.archived_at : b.archived ? ch.archived_at ?? new Date() : null;
          await client.query(
            `update community_channels set name = $3, description = $4, group_id = $5, position = $6, view_role = $7, post_role = $8, locked = $9,
                    replies_enabled = $10, allow_attachments = $11, archived_at = $12, updated_at = now() where id = $1 and community_id = $2`,
            [channelId, communityId, name, b.description ?? ch.description, groupId, b.position ?? ch.position, s.viewRole, s.postRole, s.locked, s.repliesEnabled, s.allowAttachments, archivedAt]);
          await audit(client, me.id, 'channel.update', 'community', communityId, { channelId, changed: Object.keys(b) });
          return b.archived === true && ch.archived_at === null ? ('archived' as const) : ('updated' as const);
        });
        await announceChange(communityId, channelId, kind);
        const { ch, perms } = await loadChannel(me, communityId, channelId);
        return { id: ch.id, name: ch.name, description: ch.description, type: ch.type, archived: ch.archived_at !== null, permissions: perms };
      } catch (e) {
        if (isUnique(e)) throw new HttpError(409, 'name_taken', 'This community already has a channel with that name.');
        throw e;
      }
    });

    app.delete('/communities/:id/channels/:channelId', async (req, reply) => {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const channelId = channelParam(req);
      ctx.limit(`channel-manage:${me.id}`, 30, 3600);
      await withTx(pool, async (client) => {
        const c = await requireStaff(me, communityId, client);
        if (!mayDeleteChannel(c.role)) throw new HttpError(403, 'forbidden', 'Only the owner can delete a channel. Moderators can archive it instead.');
        await loadChannel(me, communityId, channelId, client, true);
        // Attachments go with the channel: queue their files for removal in the same transaction.
        await client.query(
          `insert into storage_garbage (object_key)
           select k from (select m.object_key as k from media m join community_channel_messages x on x.id = m.channel_message_id where x.channel_id = $1
                          union select m.thumb_key from media m join community_channel_messages x on x.id = m.channel_message_id where x.channel_id = $1 and m.thumb_key is not null) t
           on conflict do nothing`, [channelId]);
        await client.query('delete from community_channels where id = $1 and community_id = $2', [channelId, communityId]);
        await audit(client, me.id, 'channel.delete', 'community', communityId, { channelId });
      });
      await announceChange(communityId, channelId, 'deleted');
      return reply.status(204).send();
    });

    app.put('/communities/:id/channel-order', async (req) => {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const b = orderSchema.parse(req.body ?? {});
      ctx.limit(`channel-manage:${me.id}`, 60, 3600);
      await withTx(pool, async (client) => {
        await requireStaff(me, communityId, client);
        await client.query('select pg_advisory_xact_lock(hashtext($1))', [`channels:${communityId}`]);
        if (b.groups.length) {
          const g = await client.query(
            `update community_channel_groups g set position = x.ord - 1 from unnest($2::uuid[]) with ordinality as x(id, ord) where g.id = x.id and g.community_id = $1`,
            [communityId, b.groups]);
          if (g.rowCount !== b.groups.length) throw new HttpError(422, 'validation', 'One of those channel groups does not exist.');
        }
        if (b.channels.length) {
          const groupIds = b.channels.map((c) => c.groupId).filter((x): x is string => x !== null);
          if (groupIds.length) {
            const ok = await client.query('select count(*)::int as n from community_channel_groups where community_id = $1 and id = any($2::uuid[])', [communityId, [...new Set(groupIds)]]);
            if (ok.rows[0].n !== new Set(groupIds).size) throw new HttpError(422, 'validation', 'One of those channel groups does not exist.');
          }
          const c = await client.query(
            `update community_channels ch set position = x.ord - 1, group_id = x.gid
               from unnest($2::uuid[], $3::uuid[]) with ordinality as x(id, gid, ord) where ch.id = x.id and ch.community_id = $1`,
            [communityId, b.channels.map((x) => x.id), b.channels.map((x) => x.groupId)]);
          if (c.rowCount !== b.channels.length) throw new HttpError(422, 'validation', 'One of those channels does not exist.');
        }
        await audit(client, me.id, 'channel.reorder', 'community', communityId, {});
      });
      await announceChange(communityId, null, 'reordered');
      return { ok: true };
    });

    app.post('/communities/:id/channel-groups', async (req, reply) => {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const b = groupSchema.parse(req.body ?? {});
      ctx.limit(`channel-manage:${me.id}`, 30, 3600);
      try {
        const group = await withTx(pool, async (client) => {
          await client.query('select pg_advisory_xact_lock(hashtext($1))', [`channels:${communityId}`]);
          await requireStaff(me, communityId, client);
          const n = await client.query<{ n: number; next: number }>('select count(*)::int as n, coalesce(max(position), -1) + 1 as next from community_channel_groups where community_id = $1', [communityId]);
          if (n.rows[0].n >= MAX_GROUPS) throw new HttpError(429, 'quota_exceeded', `A community can have up to ${MAX_GROUPS} channel groups.`);
          const made = await client.query<{ id: string; name: string; position: number }>(
            'insert into community_channel_groups (community_id, name, position) values ($1, $2, $3) returning id, name, position', [communityId, b.name, b.position ?? n.rows[0].next]);
          await audit(client, me.id, 'channel_group.create', 'community', communityId, { groupId: made.rows[0].id });
          return made.rows[0];
        });
        await announceChange(communityId, null, 'created');
        return reply.status(201).send(group);
      } catch (e) {
        if (isUnique(e)) throw new HttpError(409, 'name_taken', 'This community already has a group with that name.');
        throw e;
      }
    });

    app.patch('/communities/:id/channel-groups/:groupId', async (req) => {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const groupId = idParam((req.params as { groupId: string }).groupId);
      const b = groupSchema.partial().strict().parse(req.body ?? {});
      ctx.limit(`channel-manage:${me.id}`, 60, 3600);
      try {
        const updated = await withTx(pool, async (client) => {
          await requireStaff(me, communityId, client);
          const res = await client.query<{ id: string; name: string; position: number }>(
            'update community_channel_groups set name = coalesce($3, name), position = coalesce($4, position) where id = $1 and community_id = $2 returning id, name, position',
            [groupId, communityId, b.name ?? null, b.position ?? null]);
          if (!res.rowCount) throw new HttpError(404, 'not_found', 'That channel group is not available.');
          await audit(client, me.id, 'channel_group.update', 'community', communityId, { groupId });
          return res.rows[0];
        });
        await announceChange(communityId, null, 'updated');
        return updated;
      } catch (e) {
        if (isUnique(e)) throw new HttpError(409, 'name_taken', 'This community already has a group with that name.');
        throw e;
      }
    });

    app.delete('/communities/:id/channel-groups/:groupId', async (req, reply) => {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const groupId = idParam((req.params as { groupId: string }).groupId);
      await withTx(pool, async (client) => {
        await requireStaff(me, communityId, client);
        // Its channels stay (the foreign key sets them to "no group").
        const res = await client.query('delete from community_channel_groups where id = $1 and community_id = $2', [groupId, communityId]);
        if (!res.rowCount) throw new HttpError(404, 'not_found', 'That channel group is not available.');
        await audit(client, me.id, 'channel_group.delete', 'community', communityId, { groupId });
      });
      await announceChange(communityId, null, 'updated');
      return reply.status(204).send();
    });

    // ================= messages

    app.get('/communities/:id/channels/:channelId/messages', async (req) => {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const channelId = channelParam(req);
      const q = listSchema.parse(req.query);
      const { ch } = await loadChannel(me, communityId, channelId);
      // In question and announcement channels the timeline shows only the top-level items; answers and comments live in the thread.
      const top = ch.type === 'qa' || ch.type === 'announcement' ? 'and m.reply_to_id is null' : '';
      const hidden = `not ${blockedBetween('$1', 'm.author_id')}`;
      if (q.afterSeq !== undefined) {
        // Catch-up after a reconnect: everything newer than what the app already has, oldest first.
        const { rows } = await pool.query<MsgRow>(
          `${msgSelect('$1')} where m.channel_id = $2 ${top} and ${hidden} and m.seq > $3 order by m.seq asc limit 101`, [me.id, channelId, q.afterSeq]);
        return { items: await presentMessages(ctx, rows.slice(0, 100), me.id), hasMore: rows.length > 100 };
      }
      const cur = decodeCursor(q.cursor);
      const { rows } = await pool.query<MsgRow>(
        `${msgSelect('$1')} where m.channel_id = $2 ${top} and ${hidden}
            and ($3::timestamptz is null or (m.created_at, m.id) < ($3::timestamptz, $4::uuid))
          order by m.created_at desc, m.id desc limit $5`, [me.id, channelId, cur?.t ?? null, cur?.id ?? null, q.limit + 1]);
      const page = toPage(rows, q.limit);
      return { items: await presentMessages(ctx, page.items as unknown as MsgRow[], me.id), nextCursor: page.nextCursor, latestSeq: Number(ch.message_seq) };
    });

    app.post('/communities/:id/channels/:channelId/messages', async (req, reply) => {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const channelId = channelParam(req);
      const b = sendSchema.parse(req.body ?? {});
      ctx.limit(`channel-msg:${me.id}`, 30, 60);

      const { id, created, ch } = await withTx(pool, async (client) => {
        // The channel row is locked for the whole send: senders take turns, so message numbers follow commit order.
        const { ch, perms } = await loadChannel(me, communityId, channelId, client, true);
        const isReply = b.replyToId !== undefined;
        if (isReply ? !perms.reply : !perms.send) deny(perms);
        if (b.mediaIds.length && !perms.attach) throw new HttpError(403, 'attachments_disabled', 'Attachments are turned off in this channel.');

        if (b.clientId) {
          const again = await client.query<{ id: string }>('select id from community_channel_messages where channel_id = $1 and author_id = $2 and client_id = $3', [channelId, me.id, b.clientId]);
          if (again.rows[0]) return { id: again.rows[0].id, created: false, ch };
        }
        if (b.replyToId) {
          const target = await client.query<{ status: string; reply_to_id: string | null }>('select status, reply_to_id from community_channel_messages where id = $1 and channel_id = $2', [b.replyToId, channelId]);
          if (!target.rows[0]) throw new HttpError(422, 'validation', 'The message you are replying to is not in this channel.');
          if (target.rows[0].status !== 'active') throw new HttpError(422, 'validation', 'That message was deleted, so it cannot be replied to.');
          // Answers belong to the question, not to other answers.
          if (ch.type === 'qa' && target.rows[0].reply_to_id) throw new HttpError(422, 'validation', 'Reply to the question, not to an answer.');
        }
        const problem = messageShapeError({ type: ch.type, content: b.content, title: b.title ?? null, attachments: b.mediaIds.length, isReply });
        if (problem) throw new HttpError(400, 'validation', problem);

        const next = await client.query<{ message_seq: string }>(
          'update community_channels set message_seq = message_seq + 1, last_message_at = now() where id = $1 returning message_seq', [channelId]);
        const seq = next.rows[0].message_seq;
        // created_at is taken now, under the channel lock (not at transaction start), so time order matches message-number order.
        const ins = await client.query<{ id: string }>(
          `insert into community_channel_messages (channel_id, seq, author_id, client_id, title, content, reply_to_id, created_at)
           values ($1, $2, $3, $4, $5, $6, $7, clock_timestamp()) returning id`,
          [channelId, seq, me.id, b.clientId ?? null, b.title ? b.title : null, b.content, b.replyToId ?? null]);
        const messageId = ins.rows[0].id;
        if (b.mediaIds.length) {
          const attached = await client.query(
            `update media m set channel_message_id = $1, position = x.ord - 1
               from unnest($2::uuid[]) with ordinality as x(id, ord)
              where m.id = x.id and m.owner_id = $3 and m.purpose = 'message' and m.kind in ('image', 'document') and m.status = 'ready'
                and m.message_id is null and m.channel_message_id is null and m.post_id is null`,
            [messageId, b.mediaIds, me.id]);
          if (attached.rowCount !== b.mediaIds.length) throw new HttpError(422, 'invalid_media', 'One of the attachments is not available.');
        }
        // The sender has, by definition, read up to their own message.
        await client.query(
          `insert into community_channel_reads (channel_id, user_id, last_read_seq) values ($1, $2, $3)
           on conflict (channel_id, user_id) do update set last_read_seq = greatest(community_channel_reads.last_read_seq, excluded.last_read_seq), updated_at = now()`,
          [channelId, me.id, seq]);
        // Announcements notify members through the job queue, written with the message (outbox).
        if (ch.type === 'announcement' && !isReply) await enqueue(client, 'channel.announce', { messageId, channelId, communityId }, { dedupeKey: messageId });
        return { id: messageId, created: true, ch };
      });

      // Stored first, announced second: nobody is told about a message that failed to save.
      if (created) {
        const neutral = (await loadMessages(null, [id], channelId))[0];
        if (neutral) hub.sendMany(await audienceOf(ch, me.id), { type: 'channel:message', communityId, channelId, message: neutral });
      }
      const message = (await loadMessages(me, [id], channelId))[0];
      return reply.status(created ? 201 : 200).send(message);
    });

    app.patch('/communities/:id/channels/:channelId/messages/:messageId', async (req) => {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const channelId = channelParam(req);
      const messageId = messageParam(req);
      const b = editSchema.parse(req.body ?? {});
      if (b.content === undefined && b.title === undefined) throw new HttpError(400, 'validation', 'Nothing to change.');
      ctx.limit(`channel-edit:${me.id}`, 30, 60);
      const ch = await withTx(pool, async (client) => {
        const { ch, perms } = await loadChannel(me, communityId, channelId, client);
        const m = await loadOwn(client, channelId, messageId, true);
        // Only the author edits their words; moderators can delete, not rewrite.
        if (m.status !== 'active' || m.author_id !== me.id) throw new HttpError(404, 'not_found', 'That message is not available.');
        if (!perms.send && !perms.reply) deny(perms);
        const files = (await client.query<{ n: number }>("select count(*)::int as n from media where channel_message_id = $1 and status = 'ready'", [messageId])).rows[0].n;
        const title = b.title === undefined ? m.title : b.title || null;
        const content = b.content ?? m.content;
        const problem = messageShapeError({ type: ch.type, content, title, attachments: files, isReply: m.reply_to_id !== null });
        if (problem) throw new HttpError(400, 'validation', problem);
        await client.query('update community_channel_messages set title = $2, content = $3, edited_at = now() where id = $1', [messageId, title, content]);
        return ch;
      });
      await emitUpdated(ch, [messageId], me.id);
      return (await loadMessages(me, [messageId], channelId))[0];
    });

    app.delete('/communities/:id/channels/:channelId/messages/:messageId', async (req, reply) => {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const channelId = channelParam(req);
      const messageId = messageParam(req);
      ctx.limit(`channel-delete:${me.id}`, 60, 60);
      const { ch, wasAccepted, question } = await withTx(pool, async (client) => {
        const { ch, perms } = await loadChannel(me, communityId, channelId, client);
        const m = await loadOwn(client, channelId, messageId, true);
        if (m.status !== 'active') throw new HttpError(404, 'not_found', 'That message is not available.');
        const own = m.author_id === me.id;
        if (!(own ? perms.deleteOwn : perms.deleteAny)) deny(perms);
        // The row stays so replies keep pointing at it; its words, title, pin and files go.
        await client.query("update community_channel_messages set status = 'deleted', content = '', title = null, pinned_at = null, pinned_by = null, deleted_at = now(), deleted_by = $2 where id = $1", [messageId, me.id]);
        const accepted = await client.query<{ id: string }>('update community_channel_messages set accepted_answer_id = null where accepted_answer_id = $1 returning id', [messageId]);
        // A deleted question can no longer be "resolved" by an answer either.
        await client.query('update community_channel_messages set accepted_answer_id = null where id = $1', [messageId]);
        const gone = await client.query<{ object_key: string; thumb_key: string | null }>('delete from media where channel_message_id = $1 returning object_key, thumb_key', [messageId]);
        const files = gone.rows.flatMap((r) => [r.object_key, r.thumb_key]).filter((k): k is string => Boolean(k));
        if (files.length) await client.query('insert into storage_garbage (object_key) select unnest($1::text[]) on conflict do nothing', [files]);
        await audit(client, me.id, own ? 'channel_message.delete_own' : 'channel_message.delete', 'channel_message', messageId, { channelId, communityId });
        return { ch, wasAccepted: accepted.rowCount ? accepted.rows[0].id : null, question: m.reply_to_id };
      });
      hub.sendMany(await audienceOf(ch, null), { type: 'channel:message:deleted', communityId, channelId, messageId });
      // The question that had this answer is no longer resolved; its card changes too.
      if (wasAccepted) await emitUpdated(ch, [wasAccepted], null);
      else if (question) await emitUpdated(ch, [question], null);
      return reply.status(204).send();
    });

    app.get('/communities/:id/channels/:channelId/messages/:messageId/thread', async (req) => {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const channelId = channelParam(req);
      const messageId = messageParam(req);
      const q = pageSchema.parse(req.query);
      await loadChannel(me, communityId, channelId);
      const rootRows = await pool.query<MsgRow>(`${msgSelect('$1')} where m.channel_id = $2 and m.id = $3 and not ${blockedBetween('$1', 'm.author_id')}`, [me.id, channelId, messageId]);
      if (!rootRows.rows[0]) throw new HttpError(404, 'not_found', 'That message is not available.');
      const cur = decodeCursor(q.cursor);
      const { rows } = await pool.query<MsgRow>(
        `${msgSelect('$1')} where m.channel_id = $2 and m.reply_to_id = $3 and not ${blockedBetween('$1', 'm.author_id')}
            and ($4::timestamptz is null or (m.created_at, m.id) > ($4::timestamptz, $5::uuid))
          order by m.created_at asc, m.id asc limit $6`, [me.id, channelId, messageId, cur?.t ?? null, cur?.id ?? null, q.limit + 1]);
      const page = toPage(rows, q.limit);
      return {
        root: (await presentMessages(ctx, rootRows.rows, me.id))[0],
        replies: await presentMessages(ctx, page.items as unknown as MsgRow[], me.id),
        nextCursor: page.nextCursor,
      };
    });

    // ================= reactions

    async function react(req: FastifyRequest, on: boolean) {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const channelId = channelParam(req);
      const messageId = messageParam(req);
      const reaction = z.enum(REACTIONS).parse((req.params as { reaction: string }).reaction);
      ctx.limit(`channel-react:${me.id}`, 120, 60);
      const { ch, counts } = await withTx(pool, async (client) => {
        const { ch, perms } = await loadChannel(me, communityId, channelId, client);
        if (!perms.react) deny(perms);
        const m = await loadOwn(client, channelId, messageId);
        if (m.status !== 'active') throw new HttpError(404, 'not_found', 'That message is not available.');
        if (on) {
          // The primary key makes a repeat harmless: the same reaction from the same person is stored once.
          await client.query('insert into community_channel_reactions (message_id, user_id, reaction) values ($1, $2, $3) on conflict do nothing', [messageId, me.id, reaction]);
        } else {
          await client.query('delete from community_channel_reactions where message_id = $1 and user_id = $2 and reaction = $3', [messageId, me.id, reaction]);
        }
        const rows = (await client.query<{ reaction: string; n: number }>('select reaction, count(*)::int as n from community_channel_reactions where message_id = $1 group by reaction', [messageId])).rows;
        return { ch, counts: rows.reduce<Record<string, number>>((acc, r) => { acc[r.reaction] = r.n; return acc; }, {}) };
      });
      hub.sendMany(await audienceOf(ch, me.id), { type: 'channel:reaction', communityId, channelId, messageId, userId: me.id, reaction, active: on, reactions: counts });
      const mine = (await pool.query<{ reaction: string }>('select reaction from community_channel_reactions where message_id = $1 and user_id = $2', [messageId, me.id])).rows.map((r) => r.reaction);
      return { reactions: counts, myReactions: mine };
    }
    app.put('/communities/:id/channels/:channelId/messages/:messageId/reactions/:reaction', (req) => react(req, true));
    app.delete('/communities/:id/channels/:channelId/messages/:messageId/reactions/:reaction', (req) => react(req, false));

    // ================= pins

    async function pin(req: FastifyRequest, on: boolean) {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const channelId = channelParam(req);
      const messageId = messageParam(req);
      ctx.limit(`channel-pin:${me.id}`, 60, 60);
      const ch = await withTx(pool, async (client) => {
        const { ch, perms } = await loadChannel(me, communityId, channelId, client, true);
        // Checked on the server from the caller's live role, so a direct API call gets the same answer as the app.
        if (!perms.pin) deny(perms);
        const m = await loadOwn(client, channelId, messageId, true);
        if (m.status !== 'active') throw new HttpError(404, 'not_found', 'That message is not available.');
        if (on) {
          const n = await client.query<{ n: number }>("select count(*)::int as n from community_channel_messages where channel_id = $1 and pinned_at is not null and status = 'active' and id <> $2", [channelId, messageId]);
          if (n.rows[0].n >= MAX_PINS) throw new HttpError(429, 'quota_exceeded', `A channel can have up to ${MAX_PINS} pinned messages.`);
          await client.query('update community_channel_messages set pinned_at = coalesce(pinned_at, now()), pinned_by = coalesce(pinned_by, $2) where id = $1', [messageId, me.id]);
        } else {
          await client.query('update community_channel_messages set pinned_at = null, pinned_by = null where id = $1', [messageId]);
        }
        await audit(client, me.id, on ? 'channel_message.pin' : 'channel_message.unpin', 'channel_message', messageId, { channelId, communityId });
        return ch;
      });
      await emitUpdated(ch, [messageId], me.id);
      return (await loadMessages(me, [messageId], channelId))[0];
    }
    app.put('/communities/:id/channels/:channelId/messages/:messageId/pin', (req) => pin(req, true));
    app.delete('/communities/:id/channels/:channelId/messages/:messageId/pin', (req) => pin(req, false));

    app.get('/communities/:id/channels/:channelId/pins', async (req) => {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const channelId = channelParam(req);
      await loadChannel(me, communityId, channelId);
      const { rows } = await pool.query<MsgRow>(
        `${msgSelect('$1')} where m.channel_id = $2 and m.pinned_at is not null and m.status = 'active' and not ${blockedBetween('$1', 'm.author_id')}
          order by m.pinned_at desc, m.id desc limit ${MAX_PINS}`, [me.id, channelId]);
      return { items: await presentMessages(ctx, rows, me.id) };
    });

    // ================= question and answer

    app.put('/communities/:id/channels/:channelId/messages/:messageId/accepted', async (req) => {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const channelId = channelParam(req);
      const questionId = messageParam(req);
      const b = z.object({ answerId: z.string().uuid() }).strict().parse(req.body ?? {});
      const { ch, previous } = await withTx(pool, async (client) => {
        const { ch, perms } = await loadChannel(me, communityId, channelId, client);
        if (ch.type !== 'qa') throw new HttpError(422, 'validation', 'Only question channels have accepted answers.');
        const q = await loadOwn(client, channelId, questionId, true);
        if (q.status !== 'active' || q.reply_to_id !== null) throw new HttpError(404, 'not_found', 'That question is not available.');
        if (!canAcceptAnswer(perms, ch, q.author_id === me.id)) deny(perms);
        const a = await client.query<{ status: string; reply_to_id: string | null }>('select status, reply_to_id from community_channel_messages where id = $1 and channel_id = $2', [b.answerId, channelId]);
        if (!a.rows[0] || a.rows[0].status !== 'active' || a.rows[0].reply_to_id !== questionId) throw new HttpError(422, 'validation', 'That is not an answer to this question.');
        await client.query('update community_channel_messages set accepted_answer_id = $2 where id = $1', [questionId, b.answerId]);
        await audit(client, me.id, 'channel_message.accept_answer', 'channel_message', questionId, { channelId, communityId, answerId: b.answerId });
        return { ch, previous: q.accepted_answer_id };
      });
      await emitUpdated(ch, [questionId, b.answerId, ...(previous && previous !== b.answerId ? [previous] : [])], me.id);
      return (await loadMessages(me, [questionId], channelId))[0];
    });

    app.delete('/communities/:id/channels/:channelId/messages/:messageId/accepted', async (req) => {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const channelId = channelParam(req);
      const questionId = messageParam(req);
      const { ch, previous } = await withTx(pool, async (client) => {
        const { ch, perms } = await loadChannel(me, communityId, channelId, client);
        if (ch.type !== 'qa') throw new HttpError(422, 'validation', 'Only question channels have accepted answers.');
        const q = await loadOwn(client, channelId, questionId, true);
        if (q.status !== 'active' || q.reply_to_id !== null) throw new HttpError(404, 'not_found', 'That question is not available.');
        if (!canAcceptAnswer(perms, ch, q.author_id === me.id)) deny(perms);
        await client.query('update community_channel_messages set accepted_answer_id = null where id = $1', [questionId]);
        return { ch, previous: q.accepted_answer_id };
      });
      await emitUpdated(ch, [questionId, ...(previous ? [previous] : [])], me.id);
      return (await loadMessages(me, [questionId], channelId))[0];
    });

    // ================= read state

    app.post('/communities/:id/channels/:channelId/read', async (req) => {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const channelId = channelParam(req);
      const b = z.object({ upToSeq: z.number().int().min(0).optional() }).strict().parse(req.body ?? {});
      const { ch } = await loadChannel(me, communityId, channelId);
      const target = Math.min(b.upToSeq ?? Number(ch.message_seq), Number(ch.message_seq));
      const res = await pool.query<{ last_read_seq: string }>(
        `insert into community_channel_reads (channel_id, user_id, last_read_seq) values ($1, $2, $3)
         on conflict (channel_id, user_id) do update set last_read_seq = greatest(community_channel_reads.last_read_seq, excluded.last_read_seq), updated_at = now()
         returning last_read_seq`, [channelId, me.id, target]);
      return { lastReadSeq: Number(res.rows[0].last_read_seq) };
    });

    // ================= search

    app.get('/communities/:id/channels/:channelId/search', async (req) => {
      const me = await ctx.auth(req);
      const communityId = communityParam(req);
      const channelId = channelParam(req);
      const q = searchSchema.parse(req.query);
      ctx.limit(`channel-search:${me.id}`, 30, 60);
      if (!q.q && !q.authorId && !q.from && !q.to && !q.hasAttachments && !q.pinned) throw new HttpError(400, 'validation', 'Type something to search for.');
      // Membership and channel visibility are checked first, so nothing from a channel the viewer cannot open is ever searched.
      await loadChannel(me, communityId, channelId);
      const cur = decodeCursor(q.cursor);
      const { rows } = await pool.query<MsgRow>(
        `${msgSelect('$1')} where m.channel_id = $2 and m.status = 'active' and not ${blockedBetween('$1', 'm.author_id')}
            and ($3::text is null or m.content ilike $3 or m.title ilike $3)
            and ($4::uuid is null or m.author_id = $4)
            and ($5::timestamptz is null or m.created_at >= $5)
            and ($6::timestamptz is null or m.created_at <= $6)
            and (not $7::boolean or exists (select 1 from media x where x.channel_message_id = m.id and x.status = 'ready'))
            and (not $8::boolean or m.pinned_at is not null)
            and ($9::timestamptz is null or (m.created_at, m.id) < ($9::timestamptz, $10::uuid))
          order by m.created_at desc, m.id desc limit $11`,
        [me.id, channelId, q.q ? likePattern(q.q) : null, q.authorId ?? null, q.from ?? null, q.to ?? null, q.hasAttachments === 'true', q.pinned === 'true', cur?.t ?? null, cur?.id ?? null, q.limit + 1]);
      const page = toPage(rows, q.limit);
      return { items: await presentMessages(ctx, page.items as unknown as MsgRow[], me.id), nextCursor: page.nextCursor };
    });
  };
}
