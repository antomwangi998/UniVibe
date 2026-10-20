import type { WebSocket } from 'ws';
import type { JoinState } from './modules/community_state';

export type ServerEvent =
  | { type: 'message:new'; conversationId: string; message: unknown }
  | { type: 'message:updated'; conversationId: string; message: unknown }
  | { type: 'message:deleted'; conversationId: string; messageId: string }
  | { type: 'message:delivered'; conversationId: string; userId: string; upToSeq: number }
  | { type: 'message:read'; conversationId: string; userId: string; upToSeq: number }
  | { type: 'typing:update'; conversationId: string; userId: string; typing: boolean }
  | { type: 'presence:update'; userId: string; online: boolean }
  | { type: 'conversation:updated'; conversationId: string }
  | { type: 'message:reaction'; conversationId: string; messageId: string; userId: string; reaction: string | null; reactions: Record<string, number> }
  | { type: 'notification:created'; notification: { id: string; type: string; actorId: string | null; entityType: string | null; entityId: string | null; createdAt: string }; unreadCount: number }
  | { type: 'notification:unread'; unreadCount: number }
  /** Someone's Note changed or went away. Carries no text: the app re-reads the authoritative list. */
  | { type: 'note:changed'; userId: string; kind: 'updated' | 'deleted' }
  | { type: 'community:membership'; communityId: string; membership: JoinState; memberCount: number };

/**
 * Tracks live WebSocket connections per user (several devices per user are fine).
 * LIMITATION: this is process memory. With more than one server instance, events only reach sockets connected
 * to the instance that produced them; add a shared bus (for example Postgres LISTEN/NOTIFY or Redis) first.
 */
export class Hub {
  private sockets = new Map<string, Set<WebSocket>>();
  private typingTimers = new Map<string, NodeJS.Timeout>();
  private lastTyping = new Map<string, number>();

  add(userId: string, ws: WebSocket): boolean {
    const set = this.sockets.get(userId) ?? new Set();
    const first = set.size === 0;
    set.add(ws);
    this.sockets.set(userId, set);
    return first; // true when this is the user's first live connection (they just came online)
  }

  /** Returns true when that was the user's last connection (they just went offline). */
  remove(userId: string, ws: WebSocket): boolean {
    const set = this.sockets.get(userId);
    if (!set) return false;
    set.delete(ws);
    if (set.size === 0) { this.sockets.delete(userId); return true; }
    return false;
  }

  onlineUserIds = (): string[] => [...this.sockets.keys()];
  isOnline = (userId: string) => (this.sockets.get(userId)?.size ?? 0) > 0;
  connectionCount = () => [...this.sockets.values()].reduce((n, s) => n + s.size, 0);

  send(userId: string, event: ServerEvent): void {
    const payload = JSON.stringify(event);
    for (const ws of this.sockets.get(userId) ?? []) {
      if (ws.readyState === ws.OPEN) ws.send(payload);
    }
  }

  sendMany(userIds: Iterable<string>, event: ServerEvent): void {
    for (const id of userIds) this.send(id, event);
  }

  /** Typing is held only in memory and expires by itself; it is never stored. Returns false when rate limited. */
  typing(conversationId: string, userId: string, on: boolean, announce: (typing: boolean) => void): boolean {
    const key = `${conversationId}:${userId}`;
    const now = Date.now();
    if (on) {
      const last = this.lastTyping.get(key) ?? 0;
      if (now - last < 2000) return false;
      this.lastTyping.set(key, now);
      clearTimeout(this.typingTimers.get(key));
      announce(true);
      this.typingTimers.set(key, setTimeout(() => this.stopTyping(key, announce), 6000).unref());
    } else {
      this.stopTyping(key, announce);
    }
    return true;
  }

  private stopTyping(key: string, announce: (typing: boolean) => void): void {
    const timer = this.typingTimers.get(key);
    if (!timer) return;
    clearTimeout(timer);
    this.typingTimers.delete(key);
    announce(false);
  }

  clearTypingFor(userId: string, announceFor: (conversationId: string) => void): void {
    for (const key of [...this.typingTimers.keys()]) {
      if (!key.endsWith(`:${userId}`)) continue;
      clearTimeout(this.typingTimers.get(key));
      this.typingTimers.delete(key);
      announceFor(key.split(':')[0]);
    }
    for (const key of [...this.lastTyping.keys()]) if (key.endsWith(`:${userId}`)) this.lastTyping.delete(key);
  }

  closeAll(): void {
    for (const set of this.sockets.values()) for (const ws of set) ws.close(1001, 'server shutting down');
    for (const t of this.typingTimers.values()) clearTimeout(t);
    this.typingTimers.clear();
  }
}
