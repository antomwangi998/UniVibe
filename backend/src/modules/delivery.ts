import { blockedBetween } from '../access';
import type { Ctx } from '../context';
import { type Handler, PermanentJobError } from '../jobs';
import type { PushSender } from '../push';
import type { Hub } from '../realtime';
import { withTx } from '../db';
import { notify } from './notify';

/** Which push switch controls each notification type. `null` = account-critical, always allowed. */
const PUSH_SWITCH: Record<string, 'push_messages' | 'push_social' | 'push_events' | null> = {
  follow: 'push_social', like: 'push_social', comment: 'push_social', reply: 'push_social',
  message: 'push_messages', group_invite: 'push_messages', event_update: 'push_events', verification: null,
  community_join_request: 'push_social', community_join_approved: 'push_social', community_join_declined: 'push_social',
  community_invitation: 'push_social', community_role_changed: null, community_member_removed: null,
  community_announcement: 'push_social',
  story_reaction: 'push_social',
};

export async function unreadCount(ctx: Ctx, userId: string): Promise<number> {
  const { rows } = await ctx.pool.query<{ count: string }>(
    `select count(*) from notifications n where n.recipient_id = $1 and n.read_at is null
        and (n.actor_id is null or not ${blockedBetween('$1', 'n.actor_id')})`, [userId]);
  return Number(rows[0].count);
}

export interface PushOutcome { sent: number; invalidated: number; skipped: boolean }

/**
 * Sends one data message to every live device of a person. Dead tokens are revoked on the spot. If any device hit a
 * transient failure the whole job is retried; the app collapses repeats by id, so a retry cannot show it twice.
 */
export async function pushToUser(
  ctx: Ctx, push: PushSender, userId: string, data: Record<string, string>, collapseKey?: string,
): Promise<PushOutcome> {
  if (!push.enabled) return { sent: 0, invalidated: 0, skipped: true };
  const { rows } = await ctx.pool.query<{ id: string; fcm_token: string }>(
    'select id, fcm_token from device_tokens where user_id = $1 and revoked_at is null', [userId]);
  let sent = 0, invalidated = 0, transient = 0, rejected = '';
  for (const device of rows) {
    const result = await push.send(device.fcm_token, { data, collapseKey });
    if (result.ok) {
      sent += 1;
      await ctx.pool.query('update device_tokens set last_success_at = now() where id = $1', [device.id]);
    } else if (result.invalidToken) {
      invalidated += 1;
      await ctx.pool.query("update device_tokens set revoked_at = now(), revoked_reason = $2 where id = $1 and revoked_at is null", [device.id, `fcm:${result.reason}`.slice(0, 60)]);
    } else if (result.retryable) {
      transient += 1;
    } else {
      rejected = result.reason;
    }
  }
  if (transient > 0) throw new Error(`push provider temporarily failed for ${transient} device(s)`);
  if (rejected && sent === 0 && invalidated === 0) throw new PermanentJobError(`push provider rejected the message: ${rejected}`);
  return { sent, invalidated, skipped: false };
}

interface Prefs { push_enabled: boolean; push_messages: boolean; push_social: boolean; push_events: boolean }
const loadPrefs = async (ctx: Ctx, userId: string): Promise<Prefs> => {
  const row = (await ctx.pool.query<Prefs>(
    'select push_enabled, push_messages, push_social, push_events from notification_preferences where user_id = $1', [userId])).rows[0];
  return row ?? { push_enabled: true, push_messages: true, push_social: true, push_events: true };
};

/**
 * Handlers for the queue. Each one re-reads what it needs from the database (jobs carry only ids) and re-checks
 * every rule at delivery time, so a mute, a block or a read that happened after the job was queued is honoured.
 */
export function deliveryHandlers(ctx: Ctx, hub: Hub, push: PushSender): Record<string, Handler> {
  return {
    // Fan-out of one announcement. Idempotent: notify() skips anyone already notified, so a retry never doubles up.
    // Muted members, blocked pairs and people who switched community notifications off are skipped inside notify().
    'community.announce': async (job) => {
      const { postId, communityId } = job.payload;
      if (typeof postId !== 'string' || typeof communityId !== 'string') throw new PermanentJobError('payload incomplete');
      const post = (await ctx.pool.query<{ author_id: string; status: string }>(
        "select author_id, status from posts where id = $1 and community_id = $2 and is_announcement", [postId, communityId])).rows[0];
      if (!post || post.status !== 'active') return; // removed before delivery: nothing to announce
      const live = (await ctx.pool.query("select 1 from communities where id = $1 and status = 'active'", [communityId])).rowCount;
      if (!live) return;
      let after = '00000000-0000-0000-0000-000000000000';
      let sent = 0;
      for (let page = 0; page < 25; page++) { // hard cap: 25 x 200 recipients per announcement
        const { rows } = await ctx.pool.query<{ user_id: string }>(
          `select m.user_id from community_members m
            where m.community_id = $1 and m.status = 'active' and m.user_id <> $2 and m.user_id > $3
              and m.notification_level <> 'muted'
              and not exists (select 1 from community_mutes mu where mu.user_id = m.user_id and mu.community_id = m.community_id)
            order by m.user_id limit 200`, [communityId, post.author_id, after]);
        if (!rows.length) break;
        for (const r of rows) {
          // One transaction per recipient: the notification and its delivery job exist together or not at all.
          const ok = await withTx(ctx.pool, (c) => notify(c, { recipientId: r.user_id, actorId: post.author_id, type: 'community_announcement', entityType: 'post', entityId: postId, metadata: { communityId, postId } }));
          if (ok) sent += 1;
        }
        after = rows[rows.length - 1].user_id;
      }
      void sent;
    },
    // A stored notification: tell the person's open apps now, then push if they allow it.
    'notification.deliver': async (job) => {
      const id = job.payload.notificationId;
      if (typeof id !== 'string') throw new PermanentJobError('notificationId missing');
      const n = (await ctx.pool.query(
        `select id, recipient_id, actor_id, type, entity_type, entity_id, metadata, created_at, read_at
           from notifications where id = $1`, [id])).rows[0];
      if (!n) return; // retracted or deleted since it was queued: nothing to deliver
      const count = await unreadCount(ctx, n.recipient_id);
      hub.send(n.recipient_id, {
        type: 'notification:created', unreadCount: count,
        notification: { id: n.id, type: n.type, actorId: n.actor_id, entityType: n.entity_type, entityId: n.entity_id, createdAt: new Date(n.created_at).toISOString() },
      });
      // Message pushes are queued one per message ('push.message'), not per feed entry (the feed keeps one per conversation).
      if (n.type === 'message' || n.read_at) return;
      const sw = PUSH_SWITCH[n.type];
      const prefs = await loadPrefs(ctx, n.recipient_id);
      const allowed = sw === null ? true : prefs.push_enabled && prefs[sw ?? 'push_social'];
      if (!allowed) {
        await ctx.pool.query("update notifications set push_status = 'skipped' where id = $1", [id]);
        return;
      }
      const meta = (n.metadata ?? {}) as Record<string, unknown>;
      const outcome = await pushToUser(ctx, push, n.recipient_id, {
        kind: 'notification', notificationId: n.id, type: n.type,
        entityType: n.entity_type ?? '', entityId: n.entity_id ?? '',
        conversationId: typeof meta.conversationId === 'string' ? meta.conversationId : '',
        postId: typeof meta.postId === 'string' ? meta.postId : '',
      }, `n:${n.id}`);
      await ctx.pool.query(
        'update notifications set push_status = $2, push_attempted_at = now() where id = $1',
        [id, outcome.skipped || outcome.sent === 0 ? 'skipped' : 'accepted']);
    },

    // One chat message for one recipient.
    'push.message': async (job) => {
      const { messageId, recipientId } = job.payload;
      if (typeof messageId !== 'string' || typeof recipientId !== 'string') throw new PermanentJobError('payload incomplete');
      const row = (await ctx.pool.query(
        `select m.id, m.seq, m.conversation_id, m.sender_id, m.status, c.kind,
                cm.muted_until is not null and cm.muted_until > now() as muted, cm.last_read_seq
           from messages m join conversations c on c.id = m.conversation_id
           join conversation_members cm on cm.conversation_id = m.conversation_id and cm.user_id = $2
          where m.id = $1`, [messageId, recipientId])).rows[0];
      // Gone, unsent, left the conversation, muted, or already read on another device: nothing to push.
      if (!row || row.status !== 'active' || row.muted || Number(row.last_read_seq) >= Number(row.seq)) return;
      if (row.sender_id && (await ctx.pool.query(`select ${blockedBetween('$1::uuid', '$2::uuid')} as b`, [recipientId, row.sender_id])).rows[0].b) return;
      const prefs = await loadPrefs(ctx, recipientId);
      if (!prefs.push_enabled || !prefs.push_messages) return;
      await pushToUser(ctx, push, recipientId, {
        kind: 'message', messageId: row.id, conversationId: row.conversation_id, group: row.kind === 'group' ? '1' : '0',
      }, `c:${row.conversation_id}`);
    },
  };
}
