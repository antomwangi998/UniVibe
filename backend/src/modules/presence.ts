import type { Ctx } from '../context';
import type { Hub } from '../realtime';

/** `online` is live (a device is connected right now); `lastActiveAt` is when they were last connected (now, when online). */
export interface Presence { online: boolean; lastActiveAt: string | null }

/**
 * Presence of other students as `viewerId` may see it. Returns null for a person who switched "Show online status" off:
 * neither "online" nor "last active" is revealed, so a hidden person cannot be told apart from one who never connected.
 * A student always sees their own. Callers have already applied the block and university rules to who they ask about.
 */
export async function presenceOf(ctx: Ctx, hub: Hub, viewerId: string, userIds: string[]): Promise<Map<string, Presence | null>> {
  const out = new Map<string, Presence | null>();
  const ids = [...new Set(userIds)];
  if (ids.length === 0) return out;
  const { rows } = await ctx.pool.query<{ id: string; last_seen_at: Date | null; show_online: boolean }>(
    `select u.id, u.last_seen_at, coalesce(ps.show_online, true) as show_online
       from users u left join privacy_settings ps on ps.user_id = u.id where u.id = any($1::uuid[])`, [ids]);
  for (const r of rows) {
    if (r.id !== viewerId && !r.show_online) { out.set(r.id, null); continue; }
    const online = hub.isOnline(r.id);
    out.set(r.id, { online, lastActiveAt: online ? new Date().toISOString() : r.last_seen_at?.toISOString() ?? null });
  }
  return out;
}

/** Remembers when a student last connected or disconnected. Best effort: presence must never break a connection. */
export async function touchLastSeen(ctx: Ctx, userId: string): Promise<void> {
  await ctx.pool.query('update users set last_seen_at = now() where id = $1', [userId]).catch(() => undefined);
}
