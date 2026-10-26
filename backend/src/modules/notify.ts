import type { PoolClient } from 'pg';
import { blockedBetween } from '../access';
import { enqueue } from '../jobs';

export type NotificationType =
  | 'story_reaction'
  | 'follow' | 'like' | 'comment' | 'reply'
  | 'message' | 'group_invite' | 'verification' | 'event_update'
  | 'community_join_request' | 'community_join_approved' | 'community_join_declined' | 'community_invitation'
  | 'community_role_changed' | 'community_member_removed' | 'community_announcement' | 'community_channel_announcement'
  | 'promotion';

/** Which user preference switch controls each type. `verification` is account-critical and cannot be switched off. */
const PREFERENCE_COLUMN: Record<NotificationType, string | null> = {
  story_reaction: 'story_reactions',
  follow: 'follows',
  like: 'likes', comment: 'comments', reply: 'comments',
  message: 'messages', group_invite: 'groups', event_update: 'events', verification: null,
  // Optional community social events follow the `communities` switch. Role changes and removals are administrative
  // notices the person needs to know about, so they cannot be switched off.
  community_join_request: 'communities', community_join_approved: 'communities', community_join_declined: 'communities',
  community_invitation: 'communities', community_role_changed: null, community_member_removed: null,
  community_announcement: 'communities',
  community_channel_announcement: 'communities',
  // Payment, review and refund notices about the person's own promotions are account notices: they cannot be switched off.
  promotion: null,
};

/** Finer switches for event notices. They apply on top of the `events` switch, never instead of it. */
export const EVENT_PREFERENCES = ['event_confirmations', 'event_reminders', 'event_changes', 'event_cancellations', 'event_community', 'event_organizer_updates'] as const;
export type EventPreference = (typeof EVENT_PREFERENCES)[number];

export interface NewNotification {
  recipientId: string;
  actorId: string | null;
  type: NotificationType;
  entityType?: string;
  entityId?: string;
  /** Ids and other non-sensitive references only. Never message text or student records. */
  metadata?: Record<string, string | number | boolean | null>;
  /** Which finer event switch also has to be on. Only the fixed names above are ever put into SQL. */
  preference?: EventPreference;
}

/**
 * Persists a notification inside the caller's transaction, so it exists if and only if the event it describes does.
 * Skips: notifying yourself, either side having blocked the other, and recipients who switched that type off.
 * Recipients are always chosen by server code, never by a client.
 */
export async function notify(client: Pick<PoolClient, 'query'>, n: NewNotification): Promise<boolean> {
  const column = PREFERENCE_COLUMN[n.type];
  const finer = n.preference && (EVENT_PREFERENCES as readonly string[]).includes(n.preference) ? n.preference : null;
  const prefCheck = (column
    ? `and coalesce((select np.${column} from notification_preferences np where np.user_id = $1), true)`
    : '') + (finer ? ` and coalesce((select np.${finer} from notification_preferences np where np.user_id = $1), true)` : '');
  // Community posts: the recipient's per-community level decides whether reactions and comments reach them.
  // all and highlights: everything. mentions (the default): comments and replies, but not reactions.
  // announcements and muted: neither. Administrative notices (role, removal) never go through this check.
  const postId = n.type === 'like' ? n.entityId : n.type === 'comment' || n.type === 'reply' ? (n.metadata?.postId as string | undefined) : undefined;
  const silencedLevels = n.type === 'like' ? ['mentions', 'announcements', 'muted'] : ['announcements', 'muted'];
  const extra: unknown[] = [];
  let communityCheck = '';
  if (postId && /^[0-9a-f-]{36}$/i.test(postId)) {
    extra.push(postId, silencedLevels);
    communityCheck = `and not exists (select 1 from posts cp join community_members cm on cm.community_id = cp.community_id and cm.user_id = $1
                        where cp.id = $7::uuid and cm.notification_level = any($8::text[]))`;
  }
  const blockCheck = n.actorId ? `and $1::uuid <> $2::uuid and not ${blockedBetween('$1::uuid', '$2::uuid')}` : '';
  const res = await client.query(
    `insert into notifications (recipient_id, actor_id, type, entity_type, entity_id, metadata)
     select $1, $2, $3, $4, $5, $6::jsonb
     where true ${blockCheck} ${prefCheck} ${communityCheck}
     on conflict do nothing
     returning id`,
    [n.recipientId, n.actorId, n.type, n.entityType ?? null, n.entityId ?? null, JSON.stringify(n.metadata ?? {}), ...extra],
  );
  if (!res.rowCount) return false;
  // Same transaction as the notification: the live update and the push are queued if and only if it was stored.
  await enqueue(client, 'notification.deliver', { notificationId: res.rows[0].id as string }, { dedupeKey: res.rows[0].id as string });
  return true;
}

/** Removes the notification a like/follow created when it is undone, so a re-like starts clean. */
export async function retract(
  client: Pick<PoolClient, 'query'>,
  recipientId: string, actorId: string, type: NotificationType, entityId?: string,
): Promise<void> {
  await client.query(
    'delete from notifications where recipient_id = $1 and actor_id = $2 and type = $3 and entity_id is not distinct from $4::uuid',
    [recipientId, actorId, type, entityId ?? null],
  );
}
